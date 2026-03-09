package com.bba.service.logic;

import com.bba.entity.RateCurve;
import com.bba.model.CalculationContext;
import com.bba.model.CohortState;
import com.bba.model.PolicyState;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * CSM（合同服务边际）和 LC（亏损组成部分）计量服务。
 * 负责执行 IFRS 17 准则下 CSM 和 LC 的后续计量，包括计息、IFIE 分摊、合同组状态判定、LC 计量和 CSM 计量。
 * 对应 Python 代码中的 part 3, 7, 8.5.5, LC measurement, 8.2 部分。
 */
@Service
@RequiredArgsConstructor
public class CsmLcMeasurementService {

    // 注入利率管理服务，用于获取利率曲线
    private final RatesManagerService ratesManagerService;
    // 注入覆盖单元服务，用于计算 CSM 摊销比例
    private final CoverageUnitsService coverageUnitsService;

    // 常量：0
    private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO;
    private static final BigDecimal COEXIST_EPS = new BigDecimal("0.01");
    // 日期格式化器：yyyyMM
    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 执行 CSM/LC 计量流程。
     *
     * @param context 计算上下文，包含中间结果和 PV 数据
     * @param logger 计算日志记录器，用于输出详细计算步骤
     * @param cohortState 合同组状态，包含期初余额等信息
     * @param policyState 保单状态（单单层面）
     * @param policies 保单列表（合同组层面）
     */
    public void run(
            CalculationContext context,
            CalculationLogger logger,
            CohortState cohortState,
            PolicyState policyState,
            List<PolicyState> policies
    ) {
        // 记录章节标题：CSM/LC 计量
        logger.logSection("Part 3-8.5.5: CSM/LC计量 (CSM/LC Measurement)");

        // 步骤1：CSM计息（Interest Accretion）
        calculateCsmInterest(context, logger, cohortState, policyState);

        // 步骤2：LC分摊IFIE（保险财务收益或费用）
        calculateLcIfieAllocation(context, logger, cohortState);

        // 步骤3：合同组状态判定（盈利或亏损）
        determineCohortStatus(cohortState, context, logger, policies);

        // 步骤4：LC计量（先于CSM计量，因为CSM计量需要LC的结果）
        calculateLcMeasurement(context, logger);

        // 步骤5：CSM计量（含摊销）
        calculateCsmMeasurement(context, logger);

        BigDecimal endLc = context.getEndLcFinal();
        BigDecimal endCsm = context.getEndCsmFinal();
        boolean isReversal = context.isReversalPolicy();

        boolean lcPresent = endLc != null && (
                (!isReversal && endLc.compareTo(BigDecimal.ZERO) < 0) ||
                (isReversal && endLc.compareTo(BigDecimal.ZERO) > 0)
        ) && endLc.abs().compareTo(COEXIST_EPS) > 0;
        boolean csmPresent = endCsm != null && (
                (!isReversal && endCsm.compareTo(BigDecimal.ZERO) > 0) ||
                (isReversal && endCsm.compareTo(BigDecimal.ZERO) < 0)
        ) && endCsm.abs().compareTo(COEXIST_EPS) > 0;

        if (lcPresent && csmPresent) {
            if (endLc.abs().compareTo(endCsm.abs()) >= 0) {
                logger.logText("⚠️ Warning: CSM (" + endCsm + ") cleared because LC (" + endLc + ") exists.");
                context.setEndCsmFinal(BigDecimal.ZERO);
            } else {
                logger.logText("⚠️ Warning: LC (" + endLc + ") cleared because CSM (" + endCsm + ") exists.");
                context.setEndLcFinal(BigDecimal.ZERO);
            }
        } else if (lcPresent) {
            if (endCsm != null && endCsm.abs().compareTo(COEXIST_EPS) > 0) {
                logger.logText("⚠️ Warning: CSM (" + endCsm + ") cleared because LC (" + endLc + ") exists.");
                context.setEndCsmFinal(BigDecimal.ZERO);
            }
        } else if (csmPresent) {
            if (endLc != null && endLc.abs().compareTo(COEXIST_EPS) > 0) {
                logger.logText("⚠️ Warning: LC (" + endLc + ") cleared because CSM (" + endCsm + ") exists.");
                context.setEndLcFinal(BigDecimal.ZERO);
            }
        }
    }

    // --- Part 3: CSM Interest Accretion ---

    /**
     * 计算 CSM 计息。
     *
     * @param context 计算上下文
     * @param logger 日志记录器
     * @param cohortState 合同组状态
     * @param policyState 保单状态
     */
    public void calculateCsmInterest(
            CalculationContext context,
            CalculationLogger logger,
            CohortState cohortState,
            PolicyState policyState
    ) {
        // 记录章节标题：CSM 计息
        logger.logSection("Part 3: CSM计息 (Interest Accretion) [Sec 6]");

        // 检查 PV 原材料数据是否存在
        if (context.getPvSourceData() == null) {
            throw new IllegalArgumentException("❌ 错误: PV原材料数据不可用！");
        }

        // 确保期末日期已设置
        if (context.getEopDate() == null) {
            context.setEopDate(LocalDate.of(context.getYear(), 12, 31));
        }

        // 获取承保日期
        LocalDate uwDate = context.getUnderWriteDate();
        if (uwDate == null) {
            throw new IllegalArgumentException("❌ 错误: context.underWriteDate 未设置");
        }
        // 格式化承保月份字符串
        String uwMonthStr = uwDate.format(YYYYMM);

        // 获取评估月份字符串
        String valMonthStr = context.getValMonthStr();
        if (valMonthStr == null) {
            valMonthStr = context.getEopDate().format(YYYYMM);
        }

        // 从 PV 数据中获取锁定利率曲线（Wlk Curve）
        // 这里的逻辑假设 Wlk 曲线与承保月份相关
        List<RateCurve> wlkCurve = getWlkCurveFromPvData(context, uwMonthStr);
        // 如果找不到利率曲线，记录日志并返回
        if (wlkCurve == null || wlkCurve.isEmpty()) {
            logger.logItem(
                    "锁定利率曲线缺失",
                    "[Sec 6.1] 无法从PV原材料数据获取签单年月的Wlk利率曲线",
                    "UW Month: " + uwMonthStr,
                    null,
                    BigDecimal.ZERO,
                    "请确保PV原材料数据包含签单月份的利率曲线信息"
            );
            return;
        }

        // 确定计息截止日期（保单结束日期或计算期末日期）
        LocalDate stopDate = null;
        if (policyState != null && policyState.getEndDate() != null) {
            stopDate = policyState.getEndDate();
        } else if (context.getEndDate() != null) {
            stopDate = context.getEndDate();
        }

        // 获取期初 CSM/LC 余额（从上下文或合同组状态）
        BigDecimal bopCsmLc = getBopCsmLc(context, cohortState);

        // 获取当期新增 CSM
        BigDecimal nbInitialCsm = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;

        // [修复] 如果不是新业务年度，强制 nbInitialCsm 为 0，避免重复计算新业务利息
        boolean isNewBusiness = context.getYear() == uwDate.getYear();
        if (!isNewBusiness) {
            nbInitialCsm = BigDecimal.ZERO;
            context.setNbInitialCsm(BigDecimal.ZERO);
        }

        // 分离 CSM 和 LC：如果余额 >= 0，则全部视为 CSM；否则 CSM 为 0
        BigDecimal bopCsm = bopCsmLc.compareTo(BigDecimal.ZERO) >= 0 ? bopCsmLc : BigDecimal.ZERO;

        // 期初月份字符串（通常为当年1月）
        String bopMonthStr = LocalDate.of(context.getYear(), 1, 1).format(YYYYMM);

        // 计算期初有效合同（IF）的 CSM 利息
        InterestResult ifResult = calculateIfCsmInterest(bopCsm, wlkCurve, uwDate, bopMonthStr, valMonthStr, stopDate);
        BigDecimal ifInterestCsm = ifResult.interest;

        // 计算新增业务（NB）的 CSM 利息
        InterestResult nbResult = calculateNbCsmInterest(nbInitialCsm, wlkCurve, uwDate, valMonthStr, stopDate);
        BigDecimal nbInterestCsm = nbResult.interest;

        // 将利息结果存入上下文
        context.setIfInterestCsm(ifInterestCsm);
        context.setNbInterestCsm(nbInterestCsm);

        // 计算计息后的 CSM 余额
        BigDecimal ifCsmPostInterest = bopCsm.add(ifInterestCsm);
        BigDecimal nbCsmPostInterest = nbInitialCsm.add(nbInterestCsm);

        // 更新合同组状态中的 CSM 利息
        if (cohortState != null) {
            cohortState.setCsmInterest(ifInterestCsm.add(nbInterestCsm));
        }

        // 构建日志元数据
        Map<String, Object> meta = new HashMap<>();
        meta.put("IF_年初CSM余额", bopCsm);
        meta.put("当年新增合同CSM", nbInitialCsm);
        meta.put("期初有效合同CSM计息", ifInterestCsm);
        meta.put("新增合同CSM计息", nbInterestCsm);
        meta.put("IF_计息后CSM", ifCsmPostInterest);
        meta.put("NB_计息后CSM", nbCsmPostInterest);

        // 记录 CSM 计息日志
        logger.logItem(
                "CSM计息明细",
                "[Sec 6] CSM计息明细（文档对照）",
                "IF_计息后CSM = IF_年初CSM余额 + IF_CSM计息\nNB_计息后CSM = NB_新增CSM + NB_CSM计息",
                meta,
                ifInterestCsm.add(nbInterestCsm),
                "CSM计息结果，用于后续净余额试算"
        );
    }

    // --- Part 7: LC IFIE Allocation ---

    /**
     * 计算 LC 的 IFIE（保险财务收益或费用）分摊。
     *
     * @param context 计算上下文
     * @param logger 日志记录器
     * @param cohortState 合同组状态
     */
    public void calculateLcIfieAllocation(CalculationContext context, CalculationLogger logger, CohortState cohortState) {
        // 记录章节标题：LC 分摊 IFIE
        logger.logSection("Part 7: LC分摊IFIE (LC IFIE Allocation) [Sec 7]");

        // 获取评估月份的 PV 数据
        String eopMonthStr = context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);
        if (pvData == null) {
            throw new IllegalArgumentException(context.getPolicyNo()+"_"+context.getCertiNo()+"❌ 错误: 找不到评估月 " + eopMonthStr + " 的PV原材料数据！");
        }

        // 获取期初 CSM/LC 余额
        BigDecimal bopCsmLc = getBopCsmLc(context, cohortState);

        // 获取新增业务初始 CSM/LC
        BigDecimal nbInitialCsmLc = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;
        // 如果初始 CSM 为 0 且存在初始 LC，则使用初始 LC
        if (nbInitialCsmLc.compareTo(BigDecimal.ZERO) == 0 && context.getNbInitialLc() != null) {
            BigDecimal nbLcVal = context.getNbInitialLc();
            boolean isReversal = context.isReversalPolicy();
            boolean isNbLc = (!isReversal && nbLcVal.compareTo(BigDecimal.ZERO) < 0) || (isReversal && nbLcVal.compareTo(BigDecimal.ZERO) > 0);
            if (isNbLc) nbInitialCsmLc = nbLcVal;
        }

        // --- IF（期初有效业务）LC 分摊 ---
        // 确定 IF 年初 LC：如果 CSM/LC < 0，则为 LC (正常保单)
        // 批减单：如果 CSM/LC > 0，则为 LC
        boolean isReversal = context.isReversalPolicy();
        BigDecimal ifBopLc;
        if (!isReversal) {
            ifBopLc = bopCsmLc.compareTo(BigDecimal.ZERO) < 0 ? bopCsmLc : BigDecimal.ZERO;
        } else {
            ifBopLc = bopCsmLc.compareTo(BigDecimal.ZERO) > 0 ? bopCsmLc : BigDecimal.ZERO;
        }

        // 记录 IF 年初 LC 日志
        logger.logItem("IF_年初LC", "[LC IFIE分摊] 期初有效合同年初LC（直接取数）", "IF_年初LC = IF_年初CSM/LC（如果<0，则为LC）",
                mapOf("IF_年初CSM/LC", bopCsmLc, "IF_年初LC", ifBopLc), ifBopLc, "使用统一字段逻辑");

        // 获取或计算 IF LC IFIE 分摊比例
        BigDecimal ifLcIfieRatio = context.getIfLcIfieRatio() != null ? context.getIfLcIfieRatio() : BigDecimal.ZERO;
        BigDecimal pvIfInitClaims = BigDecimal.ZERO;
        BigDecimal pvIfInitMaint = BigDecimal.ZERO;
        BigDecimal pvIfInitRa = BigDecimal.ZERO;
        BigDecimal denomIf = BigDecimal.ZERO;

        // 如果存在期初 LC，计算分摊比例
        boolean isIfLc = (!isReversal && ifBopLc.compareTo(BigDecimal.ZERO) < 0) || (isReversal && ifBopLc.compareTo(BigDecimal.ZERO) > 0);
        if (isIfLc) {
            // 获取期初现值（赔付、维费、RA）作为分母
            pvIfInitClaims = getPvAmount(pvData.getPvIfBopCfaBegLcuClaAmt());
            pvIfInitMaint = getPvAmount(pvData.getPvIfBopCfaBegLcuMtnAmt());
            pvIfInitRa = getPvAmount(pvData.getPvIfBopCfaBegLcuRadAmt());
            denomIf = pvIfInitClaims.add(pvIfInitMaint).add(pvIfInitRa);

            // 如果尚未计算比例且分母不为 0，则计算比例 (取绝对值计算)
            if (ifLcIfieRatio.compareTo(BigDecimal.ZERO) == 0 && denomIf.abs().compareTo(BigDecimal.ZERO) != 0) {
                // 比例 = |年初 LC| / |分母|
                ifLcIfieRatio = ifBopLc.abs().divide(denomIf.abs(), 16, RoundingMode.HALF_UP);
                context.setIfLcIfieRatio(ifLcIfieRatio);
            }
        }

        // 记录 IF LC IFIE 分摊比例日志
        logger.logItem("IF_LC IFIE分摊比例", "[LC IFIE分摊] 期初有效合同LC IFIE分摊比例",
                "IF_LC IFIE分摊比例 = IF_年初LC / (IF_预期赔付现金流_年初现值 + IF_预期维持费用现金流_年初现值 + IF_预期非金融风险调整_年初现值)",
                mapOf("IF_年初LC", ifBopLc, "分母合计", denomIf, "IF_LC IFIE分摊比例", ifLcIfieRatio), ifLcIfieRatio, null);

        // --- IF Accretion（计息部分）---
        // 获取各项现值用于计算计息
        BigDecimal pvIfBopCfaRepWlkClaims = getPvAmount(pvData.getPvIfBopCfaRepWlkClaAmt());
        BigDecimal pvIfBopCfaRepWlkMaint = getPvAmount(pvData.getPvIfBopCfaRepWlkMtnAmt());
        BigDecimal pvIfBopCcaRepWlkClaims = getPvAmount(pvData.getPvIfBopCcaRepWlkClaAmt());
        BigDecimal pvIfBopCcaRepWlkMaint = getPvAmount(pvData.getPvIfBopCcaRepWlkMtnAmt());
        BigDecimal pvIfBopCfaBegWlkClaims = getPvAmount(pvData.getPvIfBopCfaBegWlkClaAmt());
        BigDecimal pvIfBopCfaBegWlkMaint = getPvAmount(pvData.getPvIfBopCfaBegWlkMtnAmt());

        // 计算 IF 待分摊 IFIE（计息部分）：赔付与费用
        BigDecimal ifIfieAccretionClaims = pvIfBopCfaRepWlkClaims.add(pvIfBopCfaRepWlkMaint)
                .add(pvIfBopCcaRepWlkClaims).add(pvIfBopCcaRepWlkMaint)
                .subtract(pvIfBopCfaBegWlkClaims).subtract(pvIfBopCfaBegWlkMaint);

        logger.logItem("IF_待分摊IFIE_计息_赔付与费用", "[LC IFIE分摊] IF_待分摊IFIE_计息_赔付与费用", "公式：[Bop_Cfa_Rep_Wlk] + [Bop_Cca_Rep_Wlk] - [Bop_Cfa_Beg_Lkd]",
                null, ifIfieAccretionClaims, null);

        // 计算 IF 待分摊 IFIE（计息部分）：RA
        BigDecimal pvIfBopCfaRepWlkRa = getPvAmount(pvData.getPvIfBopCfaRepWlkRadAmt());
        BigDecimal pvIfBopCfaBegLkdRa = getPvAmount(pvData.getPvIfBopCfaBegWlkRadAmt());
        BigDecimal pvIfBopCcaRepWlkRa = getPvAmount(pvData.getPvIfBopCcaRepWlkRadAmt());

        BigDecimal ifIfieAccretionRa = pvIfBopCfaRepWlkRa.subtract(pvIfBopCfaBegLkdRa).add(pvIfBopCcaRepWlkRa);
        logger.logItem("IF_待分摊IFIE_计息_非金融风险调整", "[LC IFIE分摊] IF_待分摊IFIE_计息_非金融风险调整", "公式：[Bop_Cfa_Rep_Wlk] - [Bop_Cfa_Beg_Lkd] + [Bop_Cca_Rep_Wlk]",
                null, ifIfieAccretionRa, null);

        // --- IF Rate Change（利率变化影响）---
        // 获取各项现值用于计算利率变化影响
        BigDecimal pvIfEopCfaRepCurClaims = getPvAmount(pvData.getPvIfEopCfaRepCurClaAmt());
        BigDecimal pvIfEopCfaRepWlkClaims = getPvAmount(pvData.getPvIfEopCfaRepWlkClaAmt());
        BigDecimal pvIfEopCfaRepCurMaint = getPvAmount(pvData.getPvIfEopCfaRepCurMtnAmt());
        BigDecimal pvIfEopCfaRepWlkMaint = getPvAmount(pvData.getPvIfEopCfaRepWlkMtnAmt());
        BigDecimal pvIfBopCfaBegLcuClaims = getPvAmount(pvData.getPvIfBopCfaBegLcuClaAmt());
        // pvIfBopCfaBegWlkClaims 已定义
        BigDecimal pvIfBopCfaBegLcuMaint = getPvAmount(pvData.getPvIfBopCfaBegLcuMtnAmt());
        // pvIfBopCfaBegWlkMaint 已定义

        // 计算期末差异和期初差异
        BigDecimal termEndDiff = pvIfEopCfaRepCurClaims.subtract(pvIfEopCfaRepWlkClaims)
                .add(pvIfEopCfaRepCurMaint).subtract(pvIfEopCfaRepWlkMaint);
        BigDecimal termBegDiff = pvIfBopCfaBegLcuClaims.subtract(pvIfBopCfaBegWlkClaims)
                .add(pvIfBopCfaBegLcuMaint).subtract(pvIfBopCfaBegWlkMaint);

        // 计算 IF 待分摊 IFIE（利率变化部分）：赔付与费用
        BigDecimal ifIfieRateChangeClaims = termEndDiff.subtract(termBegDiff);
        logger.logItem("IF_待分摊IFIE_利率变化的影响_赔付与费用", "[LC IFIE分摊] IF_待分摊IFIE_利率变化的影响_赔付与费用", "公式：([Eop_Cfa_Rep_Cur] - [Eop_Cfa_Rep_Wlk]) - ([Bop_Cfa_Beg_Lcu] - [Bop_Cfa_Beg_Lkd])",
                null, ifIfieRateChangeClaims, null);

        // 计算 IF 待分摊 IFIE（利率变化部分）：RA
        BigDecimal pvIfEopCfaRepCurRa = getPvAmount(pvData.getPvIfEopCfaRepCurRadAmt());
        BigDecimal pvIfEopCfaRepWlkRa = getPvAmount(pvData.getPvIfEopCfaRepWlkRadAmt());
        BigDecimal pvIfBopCfaBegLcuRa = getPvAmount(pvData.getPvIfBopCfaBegLcuRadAmt());
        // pvIfBopCfaBegLkdRa 已定义

        BigDecimal termEndDiffRa = pvIfEopCfaRepCurRa.subtract(pvIfEopCfaRepWlkRa);
        BigDecimal termBegDiffRa = pvIfBopCfaBegLcuRa.subtract(pvIfBopCfaBegLkdRa);

        BigDecimal ifIfieRateChangeRa = termEndDiffRa.subtract(termBegDiffRa);
        logger.logItem("IF_待分摊IFIE_利率变化的影响_非金融风险调整", "[LC IFIE分摊] IF_待分摊IFIE_利率变化的影响_非金融风险调整", "公式：([Eop_Cfa_Rep_Cur] - [Eop_Cfa_Rep_Wlk]) - ([Bop_Cfa_Beg_Lcu] - [Bop_Cfa_Beg_Lkd])",
                null, ifIfieRateChangeRa, null);

        // --- IF Results（计算 IF 最终分摊结果）---
        // 应用分摊比例
        BigDecimal ifLcIfieClaimsBeforeSign = ifIfieAccretionClaims.add(ifIfieRateChangeClaims).multiply(ifLcIfieRatio);
        BigDecimal ifLcIfieRaBeforeSign = ifIfieAccretionRa.add(ifIfieRateChangeRa).multiply(ifLcIfieRatio);
        BigDecimal ifLcIfieTotalBeforeSign = ifLcIfieClaimsBeforeSign.add(ifLcIfieRaBeforeSign);

        BigDecimal ifLcIfieClaims, ifLcIfieRa, ifLcIfieTotal;
        if (isIfLc && ifBopLc.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal lcSign = ifBopLc.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            ifLcIfieClaims = ifLcIfieClaimsBeforeSign.abs().multiply(lcSign);
            ifLcIfieRa = ifLcIfieRaBeforeSign.abs().multiply(lcSign);
            ifLcIfieTotal = ifLcIfieTotalBeforeSign.abs().multiply(lcSign);
        } else {
            ifLcIfieClaims = ifLcIfieClaimsBeforeSign;
            ifLcIfieRa = ifLcIfieRaBeforeSign;
            ifLcIfieTotal = ifLcIfieTotalBeforeSign;
        }

        // 计算分摊后的 IF LC 余额
        BigDecimal ifLcAfterIfie = ifBopLc.add(ifLcIfieTotal);

        // 存储结果到上下文
        context.setIfLcAfterIfie(ifLcAfterIfie);
        context.setIfLcIfieTotal(ifLcIfieTotal);
        context.setIfLcIfieCf(ifLcIfieClaims);
        context.setIfLcIfieRa(ifLcIfieRa);

        // --- NB LC Allocation（新增业务 LC 分摊）---
        // 确定 NB 初始 LC
        BigDecimal nbInitialLc;
        if (!isReversal) {
            nbInitialLc = nbInitialCsmLc.compareTo(BigDecimal.ZERO) < 0 ? nbInitialCsmLc : BigDecimal.ZERO;
        } else {
            nbInitialLc = nbInitialCsmLc.compareTo(BigDecimal.ZERO) > 0 ? nbInitialCsmLc : BigDecimal.ZERO;
        }

        // 获取或计算 NB LC 分摊比例
        BigDecimal nbLcIfieRatio = context.getNbLcRatio() != null ? context.getNbLcRatio() : BigDecimal.ZERO;
        BigDecimal initFutClaim = context.getInitFutClaim() != null ? context.getInitFutClaim() : BigDecimal.ZERO;
        BigDecimal initFutMaint = context.getInitFutMaint() != null ? context.getInitFutMaint() : BigDecimal.ZERO;
        BigDecimal initRa = context.getInitRa() != null ? context.getInitRa() : BigDecimal.ZERO;
        BigDecimal denomNb = initFutClaim.add(initFutMaint).add(initRa);

        boolean isNbLc = (!isReversal && nbInitialLc.compareTo(BigDecimal.ZERO) < 0) || (isReversal && nbInitialLc.compareTo(BigDecimal.ZERO) > 0);
        if (isNbLc && nbLcIfieRatio.compareTo(BigDecimal.ZERO) == 0 && denomNb.abs().compareTo(BigDecimal.ZERO) != 0) {
            nbLcIfieRatio = nbInitialLc.abs().divide(denomNb.abs(), 16, RoundingMode.HALF_UP);
            context.setNbLcRatio(nbLcIfieRatio);
        }

        // 记录 NB LC IFIE 分摊比例日志
        logger.logItem("NB_LC IFIE分摊比例", "[LC IFIE分摊] 新增合同LC IFIE分摊比例", "NB_LC IFIE分摊比例 = |NB_年初LC| / (汇总当年各新增年月_预期赔付+维费+RA_初始确认现值)",
                mapOf("NB_年初LC", nbInitialLc, "分母合计", denomNb, "NB_LC IFIE分摊比例", nbLcIfieRatio), nbLcIfieRatio, null);

        // --- NB Accretion（NB 计息部分）---
        BigDecimal pvNbIniFutClaimsWlk = getPvAmount(pvData.getPvNbIniCfaRepWlkClaAmt());
        BigDecimal pvNbIniFutMaintWlk = getPvAmount(pvData.getPvNbIniCfaRepWlkMtnAmt());
        BigDecimal pvNbIniCurClaimsWlk = getPvAmount(pvData.getPvNbIniCcaRepWlkClaAmt());
        BigDecimal pvNbIniCurMaintWlk = getPvAmount(pvData.getPvNbIniCcaRepWlkMtnAmt());
        BigDecimal pvNbIniFutClaimsLkd = getPvAmount(pvData.getPvNbIniCfaRecLkdClaAmt());
        BigDecimal pvNbIniFutMaintLkd = getPvAmount(pvData.getPvNbIniCfaRecLkdMtnAmt());

        BigDecimal nbIfieAccretionClaims = pvNbIniFutClaimsWlk.add(pvNbIniFutMaintWlk)
                .add(pvNbIniCurClaimsWlk).add(pvNbIniCurMaintWlk)
                .subtract(pvNbIniFutClaimsLkd).subtract(pvNbIniFutMaintLkd);
        logger.logItem("NB_待分摊IFIE_计息_赔付与费用", "[LC IFIE分摊] NB_待分摊IFIE_计息_赔付与费用", "公式：[Ini_Cfa_Rep_Wlk] - [Ini_Cfa_Rec_Lkd] + [Ini_Cca_Rep_Wlk]",
                null, nbIfieAccretionClaims, null);

        BigDecimal pvNbIniFutRaWlk = getPvAmount(pvData.getPvNbIniCfaRepWlkRadAmt());
        BigDecimal pvNbIniCurRaWlk = getPvAmount(pvData.getPvNbIniCcaRepWlkRadAmt());
        BigDecimal pvNbIniFutRaLkd = getPvAmount(pvData.getPvNbIniCfaRecLkdRadAmt());

        BigDecimal nbIfieAccretionRa = pvNbIniFutRaWlk.subtract(pvNbIniFutRaLkd).add(pvNbIniCurRaWlk);
        logger.logItem("NB_待分摊IFIE_计息_非金融风险调整", "[LC IFIE分摊] NB_待分摊IFIE_计息_非金融风险调整", "公式：[Ini_Cfa_Rep_Wlk] - [Ini_Cfa_Rec_Lkd] + Ini_Cca_Rep_Wlk]",
                null, nbIfieAccretionRa, null);

        // --- NB Rate Change（NB 利率变化影响）---
        BigDecimal pvNbEopCfaRepCurClaims = getPvAmount(pvData.getPvNbEopCfaRepCurClaAmt());
        BigDecimal pvNbEopCfaRepWlkClaims = getPvAmount(pvData.getPvNbEopCfaRepWlkClaAmt());
        BigDecimal pvNbEopCfaRepCurMaint = getPvAmount(pvData.getPvNbEopCfaRepCurMtnAmt());
        BigDecimal pvNbEopCfaRepWlkMaint = getPvAmount(pvData.getPvNbEopCfaRepWlkMtnAmt());

        BigDecimal nbIfieRateChangeClaims = pvNbEopCfaRepCurClaims.subtract(pvNbEopCfaRepWlkClaims)
                .add(pvNbEopCfaRepCurMaint).subtract(pvNbEopCfaRepWlkMaint);
        logger.logItem("NB_待分摊IFIE_利率变化的影响_赔付与费用", "[LC IFIE分摊] NB_待分摊IFIE_利率变化的影响_赔付与费用", "公式：[Eop_Cfa_Rep_Cur] - [Eop_Cfa_Rep_Wlk]",
                null, nbIfieRateChangeClaims, null);

        BigDecimal pvNbEopCfaRepCurRa = getPvAmount(pvData.getPvNbEopCfaRepCurRadAmt());
        BigDecimal pvNbEopCfaRepWlkRa = getPvAmount(pvData.getPvNbEopCfaRepWlkRadAmt());

        BigDecimal nbIfieRateChangeRa = pvNbEopCfaRepCurRa.subtract(pvNbEopCfaRepWlkRa);
        logger.logItem("NB_待分摊IFIE_利率变化的影响_非金融风险调整", "[LC IFIE分摊] NB_待分摊IFIE_利率变化的影响_非金融风险调整", "公式：[Eop_Cfa_Rep_Cur] - [Eop_Cfa_Rep_Wlk]",
                null, nbIfieRateChangeRa, null);

        // --- NB Results（NB 最终分摊结果）---
        BigDecimal nbLcIfieClaimsBeforeSign = nbIfieAccretionClaims.add(nbIfieRateChangeClaims).multiply(nbLcIfieRatio);
        BigDecimal nbLcIfieRaBeforeSign = nbIfieAccretionRa.add(nbIfieRateChangeRa).multiply(nbLcIfieRatio);
        BigDecimal nbLcIfieTotalBeforeSign = nbLcIfieClaimsBeforeSign.add(nbLcIfieRaBeforeSign);

        BigDecimal nbLcIfieClaims, nbLcIfieRa, nbLcIfieTotal;
        if (isNbLc && nbInitialLc.compareTo(BigDecimal.ZERO) != 0) {
            BigDecimal lcSign = nbInitialLc.signum() < 0 ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            nbLcIfieClaims = nbLcIfieClaimsBeforeSign.abs().multiply(lcSign);
            nbLcIfieRa = nbLcIfieRaBeforeSign.abs().multiply(lcSign);
            nbLcIfieTotal = nbLcIfieTotalBeforeSign.abs().multiply(lcSign);
        } else {
            nbLcIfieClaims = nbLcIfieClaimsBeforeSign;
            nbLcIfieRa = nbLcIfieRaBeforeSign;
            nbLcIfieTotal = nbLcIfieTotalBeforeSign;
        }

        BigDecimal nbLcAfterIfie = nbInitialLc.add(nbLcIfieTotal);

        context.setNbLcAfterIfie(nbLcAfterIfie);
        context.setNbLcIfieTotal(nbLcIfieTotal);
        context.setNbLcIfieCf(nbLcIfieClaims);
        context.setNbLcIfieRa(nbLcIfieRa);

        // 存储中间结果以供后续使用
        context.setIfIfieAccretionClaims(ifIfieAccretionClaims);
        context.setIfIfieAccretionRa(ifIfieAccretionRa);
        context.setIfIfieRateChangeClaims(ifIfieRateChangeClaims);
        context.setIfIfieRateChangeRa(ifIfieRateChangeRa);
        context.setNbIfieAccretionClaims(nbIfieAccretionClaims);
        context.setNbIfieAccretionRa(nbIfieAccretionRa);
        context.setNbIfieRateChangeClaims(nbIfieRateChangeClaims);
        context.setNbIfieRateChangeRa(nbIfieRateChangeRa);

        // 记录合计日志
        logger.logItem("LC分摊IFIE明细", "[Sec 7] LC分摊IFIE明细", "LC分摊IFIE = IF_LC分摊IFIE + NB_LC分摊IFIE",
                mapOf("IF_LC分摊IFIE", ifLcIfieTotal, "NB_LC分摊IFIE", nbLcIfieTotal), ifLcIfieTotal.add(nbLcIfieTotal), null);
    }

    // --- Part 8.5.5: Cohort Status Determination ---

    /**
     * 确定合同组状态（盈利或亏损）。
     *
     * @param cohortState 合同组状态
     * @param context 计算上下文
     * @param logger 日志记录器
     * @param policies 保单列表
     */
    private void determineCohortStatus(
            CohortState cohortState,
            CalculationContext context,
            CalculationLogger logger,
            List<PolicyState> policies
    ) {
        // 记录章节标题：合同组状态判定
        logger.logSection("Part 8.5.5: 合同组状态判定 (Cohort Status Determination) [Sec 8.5.5]");

        // 获取期初余额
        BigDecimal bopCsmLc = getBopCsmLc(context, cohortState);
        BigDecimal ifBopCsm = bopCsmLc.compareTo(BigDecimal.ZERO) >= 0 ? bopCsmLc : BigDecimal.ZERO;
        BigDecimal ifBopLc = bopCsmLc.compareTo(BigDecimal.ZERO) < 0 ? bopCsmLc : BigDecimal.ZERO;

        // 计算 IF 计息后余额
        BigDecimal ifCsmPost = ifBopCsm.add(context.getIfInterestCsm() != null ? context.getIfInterestCsm() : BigDecimal.ZERO);

        // 获取 NB 初始值
        BigDecimal nbInitialCsm = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;
        BigDecimal nbInitialLc = context.getNbInitialLc() != null ? context.getNbInitialLc() : BigDecimal.ZERO;

        // 计算 NB 计息后余额
        BigDecimal nbCsmPost = nbInitialCsm.add(context.getNbInterestCsm() != null ? context.getNbInterestCsm() : BigDecimal.ZERO);
        BigDecimal nbLcBase = nbInitialLc;

        // 获取 LC 分摊的 IFIE
        BigDecimal ifLcIfieTotal = context.getIfLcIfieTotal() != null ? context.getIfLcIfieTotal() : BigDecimal.ZERO;
        BigDecimal nbLcIfieTotal = context.getNbLcIfieTotal() != null ? context.getNbLcIfieTotal() : BigDecimal.ZERO;

        // 计算 IFIE 分摊后的 LC 余额
        BigDecimal ifLcPost = ifBopLc.add(ifLcIfieTotal);
        BigDecimal nbLcPost = nbLcBase.add(nbLcIfieTotal);

        // 计算净余额试算值（Net Trial）
        BigDecimal netTrial = ifCsmPost.add(nbCsmPost).add(ifLcPost).add(nbLcPost);

        // 记录净余额试算日志
        logger.logItem("合同组净余额试算值", "[Sec 8.5.5] 步骤1：计算合同组净余额试算值", "Net_trial = IF_计息后CSM + NB_计息后CSM + IF_分摊后IFIE后LC + NB_分摊后IFIE后LC",
                mapOf("IF_计息后CSM", ifCsmPost, "NB_计息后CSM", nbCsmPost, "IF_分摊后IFIE后LC", ifLcPost, "NB_分摊后IFIE后LC", nbLcPost, "Net_trial", netTrial),
                netTrial, "不包含当期履约现金流变化");

        BigDecimal cohortCsm, cohortLc;
        String status;
        boolean isProfitable;

        // 判定状态
        // [FIX] 优先使用组级别已经判定好的盈利性状态
        if (context.getProfitable() != null) {
            isProfitable = context.getProfitable();
            System.out.println("[DEBUG-BBA] " + context.getYear() + " 年状态对齐: " + (isProfitable ? "盈利" : "亏损"));
            status = isProfitable ? "盈利 (Profitable - 遵循组级判定)" : "亏损 (Onerous - 遵循组级判定)";
            if (isProfitable) {
                cohortCsm = netTrial;
                cohortLc = BigDecimal.ZERO;
            } else {
                cohortCsm = BigDecimal.ZERO;
                cohortLc = netTrial;
            }
        } else {
            // 正常保单：net_trial >= 0 为盈利(CSM)，<0 为亏损(LC)
            // 批减单：符号逻辑相反（net_trial <= 0 为CSM，>0 为LC），且取值保持原符号（CSM为负，LC为正）
            boolean isReversal = context.isReversalPolicy();

            if ((!isReversal && netTrial.compareTo(BigDecimal.ZERO) >= 0) ||
                    (isReversal && netTrial.compareTo(BigDecimal.ZERO) <= 0)) {
                cohortCsm = netTrial;
                cohortLc = BigDecimal.ZERO;
                isProfitable = true;
                status = "盈利 (Profitable)";
            } else {
                cohortCsm = BigDecimal.ZERO;
                cohortLc = netTrial;
                isProfitable = false;
                status = "亏损 (Onerous)";
            }
        }

        // 更新合同组状态
        if (cohortState != null) {
            cohortState.setProfitable(isProfitable);
            cohortState.setNetTrial(netTrial);
        }

        // 记录最终状态日志
        logger.logItem("合同组最终状态", "[Sec 8.5.5] 步骤2：确定合同组最终状态", "IF(Net_trial >= 0, 盈利, 亏损)",
                mapOf("Net_trial", netTrial, "合同组 CSM", cohortCsm, "合同组 LC", cohortLc), netTrial, "判定结果: " + status);

        // 更新保单层面的初始 CSM/LC 标记（如果适用）
        if (policies != null && cohortState != null) {
            for (PolicyState policy : policies) {
                if (isProfitable) {
                    policy.setInitialLc(BigDecimal.ZERO);
                } else {
                    policy.setInitialCsm(BigDecimal.ZERO);
                }
            }
        }

        // 设置摊销前的 CSM/LC
        BigDecimal cohortCsmLc = cohortCsm.add(cohortLc);
        boolean isRev = context.isReversalPolicy();
        boolean isCsmBucket = (!isRev) ? (cohortCsmLc.compareTo(BigDecimal.ZERO) >= 0) : (cohortCsmLc.compareTo(BigDecimal.ZERO) <= 0);

        if (isCsmBucket) {
            context.setEndCsmBeforeAmort(cohortCsmLc);
            context.setEndLcBeforeAmort(BigDecimal.ZERO);
        } else {
            context.setEndCsmBeforeAmort(BigDecimal.ZERO);
            context.setEndLcBeforeAmort(cohortCsmLc);
        }
    }

    // --- Part LC: LC Measurement ---

    /**
     * 计算 LC 计量。
     *
     * @param context 计算上下文
     * @param logger 日志记录器
     */
    public void calculateLcMeasurement(CalculationContext context, CalculationLogger logger) {
        // 记录章节标题：LC 计量
        logger.logSection("Part LC: LC计量 (LC Measurement)");

        boolean isReversal = context.isReversalPolicy();
        String eopMonthStr = context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);
        if (pvData == null) {
            throw new IllegalArgumentException(context.getPolicyNo()+"_"+context.getCertiNo()+"❌ 错误: 找不到评估月 " + eopMonthStr + " 的PV原材料数据！");
        }

        // 1. 确定 CSM 摊销比例（用于 LC 调整）
        BigDecimal csmAmortRatio = context.getCsmAmortRatio();
        if (csmAmortRatio == null) {
            BigDecimal csmAmortAmount = context.getCsmAmortAmount();
            BigDecimal endCsmBeforeAmort = context.getEndCsmBeforeAmort();
            // 如果已计算摊销金额和摊销前 CSM，则反算比例
            if (csmAmortAmount != null && endCsmBeforeAmort != null && endCsmBeforeAmort.compareTo(BigDecimal.ZERO) != 0) {
                csmAmortRatio = csmAmortAmount.divide(endCsmBeforeAmort, 16, RoundingMode.HALF_UP).abs();
            } else {
                // 否则使用 IACF 摊销比例作为替代
                csmAmortRatio = context.getIacfAmortRatio() != null ? context.getIacfAmortRatio() : BigDecimal.ZERO;
            }
        }

        // 获取各项余额
        BigDecimal bopLc = context.getBopLc() != null ? context.getBopLc() : BigDecimal.ZERO;
        BigDecimal nbInitialLcTotal = context.getNbInitialLc() != null ? context.getNbInitialLc() : BigDecimal.ZERO;
        BigDecimal ifLcIfieTotal = context.getIfLcIfieTotal() != null ? context.getIfLcIfieTotal() : BigDecimal.ZERO;
        BigDecimal nbLcIfieTotal = context.getNbLcIfieTotal() != null ? context.getNbLcIfieTotal() : BigDecimal.ZERO;

        BigDecimal deltaCsmLc = context.getExpAdjCsmImpact() != null ? context.getExpAdjCsmImpact() : BigDecimal.ZERO;
        BigDecimal deltaCfTotal = context.getDeltaCfTotal() != null ? context.getDeltaCfTotal() : BigDecimal.ZERO;

        // --- Total Portion（合计部分）---
        logger.logSection("LC计量_合计部分（先计算）");

        BigDecimal bopLcTotal = bopLc;
        BigDecimal lcIfieTotal = ifLcIfieTotal.add(nbLcIfieTotal);

        BigDecimal cohortLcForRatio = context.getEndLcBeforeAmort() != null ? context.getEndLcBeforeAmort() : BigDecimal.ZERO;
        BigDecimal lcAllocationRatioTotal = BigDecimal.ZERO;
        // isReversal already defined above

        // 计算 LC 分摊比例的分母
        BigDecimal pvIfBegClaims = getPvAmount(pvData.getPvIfBopCfaBegLcuClaAmt());
        BigDecimal pvIfBegMaint = getPvAmount(pvData.getPvIfBopCfaBegLcuMtnAmt());
        BigDecimal pvIfBegRa = getPvAmount(pvData.getPvIfBopCfaBegLcuRadAmt());

        BigDecimal pvNbInitClaims = getPvAmount(pvData.getPvNbIniCfaRecLkdClaAmt());
        BigDecimal pvNbInitMaint = getPvAmount(pvData.getPvNbIniCfaRecLkdMtnAmt());
        BigDecimal pvNbInitRa = getPvAmount(pvData.getPvNbIniCfaRecLkdRadAmt());

        BigDecimal denomTotal = pvIfBegClaims.add(pvNbInitClaims)
                .add(pvIfBegMaint).add(pvNbInitMaint)
                .add(pvIfBegRa).add(pvNbInitRa)
                .add(context.getIfIfieAccretionClaims()).add(context.getNbIfieAccretionClaims())
                .add(context.getIfIfieAccretionRa()).add(context.getNbIfieAccretionRa())
                .add(context.getIfIfieRateChangeClaims()).add(context.getNbIfieRateChangeClaims())
                .add(context.getIfIfieRateChangeRa()).add(context.getNbIfieRateChangeRa());

        // 计算 LC 分摊比例
        boolean isLcBucketForRatio = (!isReversal && cohortLcForRatio.compareTo(BigDecimal.ZERO) < 0) ||
                (isReversal && cohortLcForRatio.compareTo(BigDecimal.ZERO) > 0);
        if (isLcBucketForRatio && denomTotal.abs().compareTo(BigDecimal.ZERO) != 0) {
            lcAllocationRatioTotal = cohortLcForRatio.abs().divide(denomTotal.abs(), 16, RoundingMode.HALF_UP);
        }

        logger.logItem("LC分摊比例_合计", "[LC计量] LC分摊比例_合计", "LC分摊比例_合计 = |合同组LC| / SUM(...)",
                mapOf("合同组LC", cohortLcForRatio, "分母合计", denomTotal, "LC分摊比例_合计", lcAllocationRatioTotal), lcAllocationRatioTotal, null);

        // 计算分摊到 LC 的金额
        BigDecimal pvIfCurClaims = getPvAmount(pvData.getPvIfBopCcaRepWlkClaAmt());
        BigDecimal pvIfCurMaint = getPvAmount(pvData.getPvIfBopCcaRepWlkMtnAmt());
        BigDecimal pvIfCurRa = getPvAmount(pvData.getPvIfBopCcaRepWlkRadAmt());
        BigDecimal pvNbCurClaims = getPvAmount(pvData.getPvNbIniCcaRepWlkClaAmt());
        BigDecimal pvNbCurMaint = getPvAmount(pvData.getPvNbIniCcaRepWlkMtnAmt());
        BigDecimal pvNbCurRa = getPvAmount(pvData.getPvNbIniCcaRepWlkRadAmt());

        BigDecimal allocatedLcTotal = pvIfCurClaims.add(pvIfCurMaint).add(pvIfCurRa)
                .add(pvNbCurClaims).add(pvNbCurMaint).add(pvNbCurRa)
                .multiply(lcAllocationRatioTotal);

        // 验证和调整
        BigDecimal cohortLc = context.getEndLcBeforeAmort() != null ? context.getEndLcBeforeAmort() : BigDecimal.ZERO;
        BigDecimal cohortCsm = context.getEndCsmBeforeAmort() != null ? context.getEndCsmBeforeAmort() : BigDecimal.ZERO;
        BigDecimal bopCsm = context.getBopCsm() != null ? context.getBopCsm() : BigDecimal.ZERO;
        BigDecimal nbInitialCsm = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;
        BigDecimal csmInterest = (context.getIfInterestCsm() != null ? context.getIfInterestCsm() : BigDecimal.ZERO)
                .add(context.getNbInterestCsm() != null ? context.getNbInterestCsm() : BigDecimal.ZERO);

        BigDecimal sumLcTest = cohortLc.add(allocatedLcTotal).add(deltaCsmLc);
        BigDecimal sumCsmTest = cohortCsm.add(deltaCsmLc);

        BigDecimal allocatedLcExpAdjTotal;

        // 判定是否需要确认/增加亏损部分 (LC)
        // 正常保单：LC < 0 或 CSM < 0 表示亏损
        // 批减单：LC > 0 或 CSM > 0 表示亏损
        boolean conditionNormal = !isReversal && (
            (cohortLc.compareTo(BigDecimal.ZERO) < 0 && sumLcTest.compareTo(BigDecimal.ZERO) < 0) ||
            (cohortLc.compareTo(BigDecimal.ZERO) == 0 && sumCsmTest.compareTo(BigDecimal.ZERO) < 0)
        );
        boolean conditionReversal = isReversal && (
            (cohortLc.compareTo(BigDecimal.ZERO) > 0 && sumLcTest.compareTo(BigDecimal.ZERO) > 0) ||
            (cohortLc.compareTo(BigDecimal.ZERO) == 0 && sumCsmTest.compareTo(BigDecimal.ZERO) > 0)
        );

        if (context.getLcAbsorbedTotal() != null) {
            // [根源修复] 直接使用组级别分摊下来的 lcAbsorbedTotal，之前错误地读取了 lcChange
            allocatedLcExpAdjTotal = context.getLcAbsorbedTotal();
        } else {
            // 如果没有组级分摊（例如单保单模式），则执行原有逻辑
            if (conditionNormal || conditionReversal) {
                allocatedLcExpAdjTotal = deltaCsmLc.add(bopCsm).add(nbInitialCsm).add(csmInterest);
            } else {
                allocatedLcExpAdjTotal = bopLcTotal.add(nbInitialLcTotal).add(lcIfieTotal).add(allocatedLcTotal).negate();
            }
        }

        BigDecimal lcBalanceToAdjustTotal = bopLcTotal.add(nbInitialLcTotal).add(lcIfieTotal).add(allocatedLcTotal).add(allocatedLcExpAdjTotal);

        // --- CF Portion（现金流部分）---
        logger.logSection("LC计量_预期现金流部分");

        BigDecimal bopLcCf = bopLc; // 假设期初 LC 全部为现金流部分（简化）

        BigDecimal nbInitialLcCf;
        BigDecimal denomNbInit = pvNbInitClaims.add(pvNbInitMaint).add(pvNbInitRa);
        if (denomNbInit.abs().compareTo(BigDecimal.ZERO) > 0) {
            nbInitialLcCf = nbInitialLcTotal.multiply(pvNbInitClaims.add(pvNbInitMaint)).divide(denomNbInit, 16, RoundingMode.HALF_UP);
        } else {
            nbInitialLcCf = BigDecimal.ZERO;
        }

        BigDecimal lcIfieCf = (context.getIfLcIfieCf() != null ? context.getIfLcIfieCf() : BigDecimal.ZERO)
                .add(context.getNbLcIfieCf() != null ? context.getNbLcIfieCf() : BigDecimal.ZERO);

        BigDecimal allocatedLcCf = pvIfCurClaims.add(pvIfCurMaint).add(pvNbCurClaims).add(pvNbCurMaint).multiply(lcAllocationRatioTotal);

        BigDecimal allocatedLcExpAdjCf;
        if (lcBalanceToAdjustTotal.compareTo(BigDecimal.ZERO) == 0) {
            allocatedLcExpAdjCf = bopLcTotal.add(nbInitialLcTotal).add(lcIfieTotal).add(allocatedLcTotal).negate();
        } else {
            if (deltaCsmLc.compareTo(BigDecimal.ZERO) != 0) {
                allocatedLcExpAdjCf = allocatedLcExpAdjTotal.multiply(deltaCfTotal).divide(deltaCsmLc, 16, RoundingMode.HALF_UP);
            } else {
                allocatedLcExpAdjCf = BigDecimal.ZERO;
            }
        }

        BigDecimal lcBalanceToAdjustCf = bopLcCf.add(nbInitialLcCf).add(lcIfieCf).add(allocatedLcCf).add(allocatedLcExpAdjCf);

        // 计算 LC 调整（摊销）
        BigDecimal lcAdjustCf = csmAmortRatio.compareTo(BigDecimal.ONE) >= 0 ? lcBalanceToAdjustCf.negate() : BigDecimal.ZERO;
        BigDecimal endLcCf = lcBalanceToAdjustCf.add(lcAdjustCf);

        context.setLcAdjustCf(lcAdjustCf);
        context.setAllocatedLcCf(allocatedLcCf);
        context.setAllocatedLcExpAdjCf(allocatedLcExpAdjCf);
        context.setEndLcCf(endLcCf);
        context.setNbInitialLcCf(nbInitialLcCf);

        logger.logItem("LC计量_预期现金流", "[LC计量] 预期现金流部分的LC计量", "...", mapOf("期末LC余额_预期现金流", endLcCf), endLcCf, null);

        // --- RA Portion（风险调整部分）---
        logger.logSection("LC计量_非金融风险调整部分");

        BigDecimal bopLcRa = BigDecimal.ZERO;
        BigDecimal nbInitialLcRa = nbInitialLcTotal.subtract(nbInitialLcCf);

        BigDecimal lcIfieRa = (context.getIfLcIfieRa() != null ? context.getIfLcIfieRa() : BigDecimal.ZERO)
                .add(context.getNbLcIfieRa() != null ? context.getNbLcIfieRa() : BigDecimal.ZERO);

        BigDecimal allocatedLcRa = pvIfCurRa.add(pvNbCurRa).multiply(lcAllocationRatioTotal);

        BigDecimal allocatedLcExpAdjRa = allocatedLcExpAdjTotal.subtract(allocatedLcExpAdjCf);

        BigDecimal lcBalanceToAdjustRa = bopLcRa.add(nbInitialLcRa).add(lcIfieRa).add(allocatedLcRa).add(allocatedLcExpAdjRa);

        BigDecimal lcAdjustRa = csmAmortRatio.compareTo(BigDecimal.ONE) >= 0 ? lcBalanceToAdjustRa.negate() : BigDecimal.ZERO;
        BigDecimal endLcRa = lcBalanceToAdjustRa.add(lcAdjustRa);

        context.setLcAdjustRa(lcAdjustRa);
        context.setAllocatedLcRa(allocatedLcRa);
        context.setAllocatedLcExpAdjRa(allocatedLcExpAdjRa);
        context.setEndLcRa(endLcRa);
        context.setNbInitialLcRa(nbInitialLcRa);

        logger.logItem("LC计量_非金融风险调整", "[LC计量] 非金融风险调整部分的LC计量", "...", mapOf("期末LC余额_非金融风险调整", endLcRa), endLcRa, null);

        // --- Total Final（最终合计）---
        logger.logSection("LC计量_合计部分（最终汇总）");

        BigDecimal lcAdjustTotal = csmAmortRatio.compareTo(BigDecimal.ONE) >= 0 ? lcBalanceToAdjustTotal.negate() : BigDecimal.ZERO;
        BigDecimal endLcTotal = lcBalanceToAdjustTotal.add(lcAdjustTotal);

        context.setLcChange(allocatedLcExpAdjTotal);
        context.setEndLcFinal(endLcTotal);
        context.setAllocatedLcTotal(allocatedLcTotal);

        logger.logItem("LC计量_合计", "[LC计量] 合计部分的LC计量", "...", mapOf("期末LC余额_合计", endLcTotal), endLcTotal, null);
    }

    // --- Part 8.2: CSM Measurement ---

    /**
     * 计算 CSM 计量。
     *
     * @param context 计算上下文
     * @param logger 日志记录器
     */
    public void calculateCsmMeasurement(CalculationContext context, CalculationLogger logger) {
        // 记录章节标题：CSM 计量
        logger.logSection("Part 8.2: CSM计量 (CSM Measurement) [Sec 8.2]");

        BigDecimal cohortCsm = context.getEndCsmBeforeAmort() != null ? context.getEndCsmBeforeAmort() : BigDecimal.ZERO;

        // 获取变化量
        BigDecimal deltaCsmLc = context.getExpAdjCsmImpact() != null ? context.getExpAdjCsmImpact() : BigDecimal.ZERO;
        BigDecimal deltaCfTotal = context.getDeltaCfTotal() != null ? context.getDeltaCfTotal() : BigDecimal.ZERO;

        BigDecimal allocatedLcExpAdjTotal = context.getLcChange() != null ? context.getLcChange() : BigDecimal.ZERO;
        BigDecimal allocatedLcExpAdjCf = context.getAllocatedLcExpAdjCf() != null ? context.getAllocatedLcExpAdjCf() : BigDecimal.ZERO;

        // 计算被 CSM 吸收的金额
        BigDecimal csmAbsorbedTotal;
        BigDecimal csmAbsorbedCf;
        BigDecimal csmAbsorbedRa;

        if (context.getCsmAbsorbed() != null) {
             // 组级别已经计算并分摊了吸收金额，直接使用
             csmAbsorbedTotal = context.getCsmAbsorbed();
             csmAbsorbedCf = context.getCsmAbsorbedCf() != null ? context.getCsmAbsorbedCf() : BigDecimal.ZERO;
             csmAbsorbedRa = context.getCsmAbsorbedRa() != null ? context.getCsmAbsorbedRa() : BigDecimal.ZERO;
        } else {
             // 单单模式或未预置，进行计算
             csmAbsorbedTotal = deltaCsmLc.subtract(allocatedLcExpAdjTotal);
             csmAbsorbedCf = deltaCfTotal.subtract(allocatedLcExpAdjCf);
             csmAbsorbedRa = csmAbsorbedTotal.subtract(csmAbsorbedCf);

             context.setCsmAbsorbed(csmAbsorbedTotal);
             context.setCsmAbsorbedCf(csmAbsorbedCf);
             context.setCsmAbsorbedRa(csmAbsorbedRa);
        }

        context.setCsmAbsorbed(csmAbsorbedTotal);

        logger.logItem("被CSM吸收的变化", "[Sec 8.2] 被CSM吸收的变化", "被CSM吸收的变化 = 被CSM/LC吸收的变化合计 - 被LC吸收的变化",
                mapOf("被CSM/LC吸收的变化合计", deltaCsmLc, "被LC吸收的变化", allocatedLcExpAdjTotal, "被CSM吸收的变化", csmAbsorbedTotal),
                csmAbsorbedTotal, "通过总变化减去LC吸收部分得到CSM吸收部分");

        // 计算覆盖单元（CSM 摊销比例）
        LocalDate startOfYear = LocalDate.of(context.getYear(), 1, 1);
        boolean isInitialYear = context.isInitialYear();

        BigDecimal csmAmortRatio;
        if (context.getPolicies() != null && !context.getPolicies().isEmpty()) {
            csmAmortRatio = coverageUnitsService.calculateCoverageUnitsReleased(
                    context.getPolicies(),
                    context.getEopDate(),
                    startOfYear,
                    logger,
                    isInitialYear
            ).divide(coverageUnitsService.calculateCoverageUnitsRemaining(
                    context.getPolicies(),
                    context.getEopDate(),
                    logger
            ).add(coverageUnitsService.calculateCoverageUnitsReleased(
                    context.getPolicies(),
                    context.getEopDate(),
                    startOfYear,
                    logger,
                    isInitialYear
            )), 16, RoundingMode.HALF_UP);
        } else {
            csmAmortRatio = BigDecimal.ZERO;
        }

        // 计算摊销前 CSM
        // [FIX] 使用 BOP + NB + Interest + Absorbed 的通用公式，避免使用 allocatedGroupCsm (Net Trial) 导致的双重计算或漏算 Delta
        BigDecimal bopCsmVal = context.getBopCsm() != null ? context.getBopCsm() : BigDecimal.ZERO;
        BigDecimal nbInitialCsmVal = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;
        BigDecimal csmInterestVal = (context.getIfInterestCsm() != null ? context.getIfInterestCsm() : BigDecimal.ZERO)
                .add(context.getNbInterestCsm() != null ? context.getNbInterestCsm() : BigDecimal.ZERO);
        
        BigDecimal csmBase = bopCsmVal.add(nbInitialCsmVal).add(csmInterestVal);
        BigDecimal csmBeforeAmortAdjusted = csmBase.add(csmAbsorbedTotal);

        // 计算摊销金额和期末 CSM
        BigDecimal csmAmortAmount;
        BigDecimal csmFinal;
        if (csmBeforeAmortAdjusted.compareTo(BigDecimal.ZERO) <= 0) {
            csmAmortAmount = BigDecimal.ZERO;
            csmFinal = csmBeforeAmortAdjusted;
        } else {
            csmAmortAmount = csmBeforeAmortAdjusted.multiply(csmAmortRatio).negate();
            csmFinal = csmBeforeAmortAdjusted.add(csmAmortAmount);
        }

        context.setCsmAmortAmount(csmAmortAmount);
        context.setEndCsmFinal(csmFinal);
        context.setCsmAmortRatio(csmAmortRatio);

        logger.logItem("CSM摊销与期末余额", "[Sec 8.2] CSM摊销与期末余额计算", "期末CSM = 摊销前CSM + CSM摊销",
                mapOf("摊销前CSM", csmBeforeAmortAdjusted, "CSM摊销", csmAmortAmount, "期末CSM", csmFinal), csmFinal, null);
    }

    // --- Helpers ---

    /**
     * 获取期初 CSM + LC 的合计值。
     */
    public BigDecimal getBopCsmLc(CalculationContext context, CohortState cohortState) {
        BigDecimal bopCsm = context.getBopCsm();
        BigDecimal bopLc = context.getBopLc();

        if (bopCsm == null && cohortState != null){
            bopCsm = cohortState.getBopCsm();
        }
        if (bopLc == null && cohortState != null){
            bopLc = cohortState.getBopLc();
        }

        BigDecimal bopCsmVal = bopCsm != null ? bopCsm : BigDecimal.ZERO;
        BigDecimal bopLcVal = bopLc != null ? bopLc : BigDecimal.ZERO;

        return bopCsmVal.add(bopLcVal);
    }

    private BigDecimal getPvAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    /**
     * 从 PV 数据中获取锁定利率曲线。
     */
    private List<RateCurve> getWlkCurveFromPvData(CalculationContext context, String uwMonthStr) {
        PVSourceData pvData = context.getPvSourceData().getData(uwMonthStr);
        if (pvData != null) {
             // 实际实现中，这里应从 PV 数据解析曲线
             // 目前假设 ratesManagerService 可以处理，或者回退到 ratesManager
        }
        // 回退：使用 ratesManager
        return ratesManagerService.getRates(uwMonthStr, "Yield Curve"); // Assumption
    }

    // --- Helper Methods for Interest Calculation ---

    /**
     * 计算从承保月到目标月的月数差。
     */
    private int monthsFromUwToTarget(LocalDate uwDate, String targetMonthStr) {
        if (uwDate == null || targetMonthStr == null) {
            return 0;
        }
        try {
            LocalDate firstDayOfMonth = LocalDate.parse(targetMonthStr + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
            LocalDate targetDate = firstDayOfMonth.with(java.time.temporal.TemporalAdjusters.lastDayOfMonth());

            java.time.Period period = java.time.Period.between(uwDate, targetDate);
            int months = period.getYears() * 12 + period.getMonths();

            if (targetDate.isAfter(uwDate) && months == 0) {
                months = 1;
            }
            return Math.max(months, 0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 计算新增业务（NB）CSM 利息。
     */
    private InterestResult calculateNbCsmInterest(
            BigDecimal principal,
            List<RateCurve> wlkCurve,
            LocalDate uwDate,
            String valMonthStr,
            LocalDate stopDate
    ) {
        if (wlkCurve == null || wlkCurve.isEmpty() || principal == null || principal.compareTo(BigDecimal.ZERO) == 0) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int monthsDiff = monthsFromUwToTarget(uwDate, valMonthStr);
        if (monthsDiff <= 0) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int actualMonthsDiff = monthsDiff;
        if (stopDate != null) {
            try {
                LocalDate valDate = LocalDate.parse(valMonthStr + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
                if (stopDate.getYear() == valDate.getYear()) {
                    if (stopDate.getMonthValue() < valDate.getMonthValue()) {
                        String stopMonthStr = stopDate.format(YYYYMM);
                        actualMonthsDiff = monthsFromUwToTarget(uwDate, stopMonthStr);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        if (actualMonthsDiff <= 0) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Map<Integer, BigDecimal> ratesMap = wlkCurve.stream()
                .collect(Collectors.toMap(RateCurve::getTermMonth, RateCurve::getForwardDisrateValue, (k1, k2) -> k1));

        int maxTerm = wlkCurve.stream().mapToInt(RateCurve::getTermMonth).max().orElse(0);

        BigDecimal factor = BigDecimal.ONE;

        // 第1个月：wlk[1] / 2
        BigDecimal r1 = ratesMap.getOrDefault(1, BigDecimal.ZERO);
        factor = factor.multiply(BigDecimal.ONE.add(r1.divide(new BigDecimal("2"), 10, RoundingMode.HALF_UP)));

        // 第2个月到实际月份
        for (int term = 2; term <= actualMonthsDiff; term++) {
            BigDecimal r = ratesMap.get(term);
            if (r == null && maxTerm > 0) {
                r = ratesMap.getOrDefault(maxTerm, BigDecimal.ZERO);
            } else if (r == null) {
                r = BigDecimal.ZERO;
            }
            factor = factor.multiply(BigDecimal.ONE.add(r));
        }

        BigDecimal interest = principal.multiply(factor.subtract(BigDecimal.ONE));
        return new InterestResult(interest, factor.subtract(BigDecimal.ONE));
    }

    /**
     * 计算期初有效业务（IF）CSM 利息。
     */
    private InterestResult calculateIfCsmInterest(
            BigDecimal principal,
            List<RateCurve> wlkCurve,
            LocalDate uwDate,
            String bopMonthStr,
            String valMonthStr,
            LocalDate stopDate
    ) {
        if (wlkCurve == null || wlkCurve.isEmpty() || principal == null || principal.compareTo(BigDecimal.ZERO) == 0) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int bopMonthsDiff = monthsFromUwToTarget(uwDate, bopMonthStr);
        int valMonthsDiff = monthsFromUwToTarget(uwDate, valMonthStr);

        if (valMonthsDiff <= bopMonthsDiff) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        int actualValMonthsDiff = valMonthsDiff;
        if (stopDate != null) {
            try {
                LocalDate valDate = LocalDate.parse(valMonthStr + "01", DateTimeFormatter.ofPattern("yyyyMMdd"));
                if (stopDate.getYear() == valDate.getYear()) {
                    if (stopDate.getMonthValue() < valDate.getMonthValue()) {
                        String stopMonthStr = stopDate.format(YYYYMM);
                        actualValMonthsDiff = monthsFromUwToTarget(uwDate, stopMonthStr);
                    }
                }
            } catch (Exception e) {
                // Ignore
            }
        }

        if (actualValMonthsDiff <= bopMonthsDiff) {
            return new InterestResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }

        Map<Integer, BigDecimal> ratesMap = wlkCurve.stream()
                .collect(Collectors.toMap(RateCurve::getTermMonth, RateCurve::getForwardDisrateValue, (k1, k2) -> k1));

        int maxTerm = wlkCurve.stream().mapToInt(RateCurve::getTermMonth).max().orElse(0);

        BigDecimal factor = BigDecimal.ONE;

        // 从期初月份+1开始累乘
        for (int term = bopMonthsDiff + 1; term <= actualValMonthsDiff; term++) {
            BigDecimal r = ratesMap.get(term);
            if (r == null && maxTerm > 0) {
                r = ratesMap.getOrDefault(maxTerm, BigDecimal.ZERO);
            } else if (r == null) {
                r = BigDecimal.ZERO;
            }
            factor = factor.multiply(BigDecimal.ONE.add(r));
        }

        BigDecimal interest = principal.multiply(factor.subtract(BigDecimal.ONE));
        return new InterestResult(interest, factor.subtract(BigDecimal.ONE));
    }

    /**
     * 利息计算结果内部类。
     */
    @Data
    @AllArgsConstructor
    private static class InterestResult {
        BigDecimal interest;
        BigDecimal factor;
    }

    /**
     * 构建 Map 的辅助方法。
     */
    private Map<String, Object> mapOf(Object... args) {
        Map<String, Object> map = new HashMap<>();
        if (args != null) {
            for (int i = 0; i < args.length; i += 2) {
                if (i + 1 < args.length && args[i] != null) {
                    map.put(args[i].toString(), args[i + 1]);
                }
            }
        }
        return map;
    }

}
