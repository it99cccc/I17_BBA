package com.bba.service.logic;

import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.CohortState;
import com.bba.model.PolicyState;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 履约现金流变化服务类。
 * 负责处理 IFRS 17 准则下的履约现金流变化（Fulfillment Cashflow Changes）逻辑。
 * 包括经验调整（Experience Adjustment）、假设变更导致的现金流变化等。
 * 对应 Python 代码中的 Part 2 (Experience Adjustment) 和 Part 4 (Fulfillment Cashflow Changes)。
 */
@Service
@RequiredArgsConstructor
public class FulfillmentCashflowChangesService {

    // 注入覆盖单元服务，用于计算覆盖单元释放比例，进而计算费用分摊比例
    private final CoverageUnitsService coverageUnitsService;

    // 默认赔付率假设 (60%)
    private static final BigDecimal RATIO_CLAIM = new BigDecimal("0.6");
    // 默认维持费用率假设 (5%)
    private static final BigDecimal RATIO_MAINT_EXP = new BigDecimal("0.05");
    // 默认获取费用率假设 (20%)
    private static final BigDecimal RATIO_IACF = new BigDecimal("0.20");

    /**
     * 执行履约现金流变化逻辑。
     *
     * @param context            计算上下文，包含中间结果和 PV 数据
     * @param logger             计算日志记录器，用于输出详细计算步骤
     * @param assumptions        精算假设
     * @param cohortState        合同组状态
     * @param policies           保单列表
     * @param isNewBusinessInput 新业务标记（可选，若为 null 则从 context 获取）
     */
    public void run(
            CalculationContext context,
            CalculationLogger logger,
            Assumptions assumptions,
            CohortState cohortState,
            List<PolicyState> policies,
            Boolean isNewBusinessInput
    ) {
        // 记录章节标题：履约现金流变化
        logger.logSection("Part 2-4: 履约现金流变化 (Fulfillment Cashflow Changes) [Sec 4-5]");

        // 检查 PV 原材料数据是否可用
        if (context.getPvSourceData() == null) {
            // 获取保单号用于报错信息
            String policyNo = context.getPolicyData() != null ? context.getPolicyData().getPolicyNo() : "UNKNOWN";
            // 抛出异常，提示用户必须先生成 PV 数据
            throw new IllegalArgumentException(
                    "❌ 错误: PV原材料数据不可用！\n" +
                    "   保单号: " + policyNo + "\n" +
                    "   请先运行 pv_calculator.py 生成PV原材料数据文件: logs/pv_source_data_" + policyNo + ".json\n" +
                    "   系统要求必须使用PV原材料数据，不允许使用旧的计算方式。"
            );
        }

        // 确定是否为新业务
        boolean isNewBusiness;
        if (isNewBusinessInput != null) {
            // 如果输入参数指定了，则使用输入参数
            isNewBusiness = isNewBusinessInput;
        } else if (context.isNewBusiness()) {
            // 如果 context 中已标记，则使用 context 的标记
            isNewBusiness = context.isNewBusiness();
        } else if (context.getUnderWriteDate() != null && context.getYear() != null) {
            // 否则根据签单年份和计算年份判断
            isNewBusiness = context.getYear().equals(context.getUnderWriteDate().getYear());
        } else {
            // 默认不是新业务
            isNewBusiness = false;
        }
        // 更新 context 中的新业务标记
        context.setNewBusiness(isNewBusiness);
        // 设置初始年标记 (通常与新业务标记一致，或者根据签单年判断)
        boolean isInitialYear = isNewBusiness;
        context.setInitialYear(isInitialYear);

        // 步骤 1: 计算经验调整 (Experience Adjustment)
        calculateExperienceAdjustment(context, logger, assumptions, isNewBusiness);

        // 步骤 2: 计算被 CSM/LC 吸收的变化 (CSM/LC Absorption)
        calculateCsmLcAbsorption(context, logger, cohortState, policies);

        // 计算经验调整总额：保费差异 + IACF 差异
        BigDecimal totalExpAdj = context.getPremVar().add(context.getIacfVar());
        // 计算履约现金流变化总额：经验调整 + 被 CSM/LC 吸收的变化
        BigDecimal totalChange = totalExpAdj.add(context.getExpAdjCsmImpact());

        // 准备汇总日志的数据
        Map<String, Object> meta = new HashMap<>();
        meta.put("经验调整（保费）", context.getPremVar());
        meta.put("经验调整（IACF）", context.getIacfVar());
        meta.put("经验调整合计", totalExpAdj);
        meta.put("被CSM/LC吸收的变化", context.getExpAdjCsmImpact());
        meta.put("被CSM吸收", context.getCsmAbsorbed());
        meta.put("被LC吸收", context.getAllocatedLcExpAdj());

        // 记录履约现金流变化合计日志
        logger.logItem(
                "履约现金流变化合计",
                "[汇总] 经验调整和被CSM/LC吸收的变化合计",
                "履约现金流变化 = 经验调整 + 被CSM/LC吸收的变化",
                meta,
                totalChange,
                "整合经验调整和被CSM/LC吸收的变化，使用统一字段逻辑。注意：计息逻辑不在此模块，应在interest_accretion模块中处理"
        );
    }

    /**
     * 计算经验调整 (Experience Adjustment)。
     * 经验调整是指实际现金流与预期现金流之间的差异。
     *
     * @param context       计算上下文
     * @param logger        日志记录器
     * @param assumptions   精算假设
     * @param isNewBusiness 是否为新业务
     */
    private void calculateExperienceAdjustment(
            CalculationContext context,
            CalculationLogger logger,
            Assumptions assumptions,
            boolean isNewBusiness
    ) {
        // 记录章节标题：经验调整
        logger.logSection("Part 2: 经验调整 (Experience Adjustment) [Sec 4]");

        // 定义假设变量
        BigDecimal lossRatio;
        BigDecimal indirectClaimsExpenseRatio;
        BigDecimal maintenanceExpenseRatio;
        // BigDecimal acquisitionExpenseRatio; // 暂未使用

        // 获取假设值，如果 assumptions 为 null 则使用默认值
        if (assumptions != null) {
            lossRatio = assumptions.getLossRatio() != null ? assumptions.getLossRatio() : RATIO_CLAIM;
            indirectClaimsExpenseRatio = assumptions.getIndirectClaimsExpenseRatio() != null ? assumptions.getIndirectClaimsExpenseRatio() : BigDecimal.ZERO;
            maintenanceExpenseRatio = assumptions.getMaintenanceExpenseRatio() != null ? assumptions.getMaintenanceExpenseRatio() : RATIO_MAINT_EXP;
            // acquisitionExpenseRatio = assumptions.getAcquisitionExpenseRatio();
        } else {
            lossRatio = RATIO_CLAIM;
            indirectClaimsExpenseRatio = BigDecimal.ZERO;
            maintenanceExpenseRatio = RATIO_MAINT_EXP;
            // acquisitionExpenseRatio = RATIO_IACF;
        }

        // 计算已过月数 (Months Passed)
        int monthsPassed = 0;
        LocalDate startDate = context.getStartDate() != null ? context.getStartDate() : context.getUnderWriteDate();
        LocalDate valuationDate = context.getEopDate();
        if (valuationDate == null && context.getYear() != null) {
            valuationDate = LocalDate.of(context.getYear(), 12, 31);
        }

        if (startDate != null && valuationDate != null) {
            // 计算从起保月到评估月的总月数（包含首尾月）
            // 例如：2022-09 到 2023-12
            // 2022年: 9,10,11,12 (4个月)
            // 2023年: 12个月
            // 总计: 16个月
            long months = ChronoUnit.MONTHS.between(startDate.withDayOfMonth(1), valuationDate.withDayOfMonth(1)) + 1;
            monthsPassed = (int) Math.max(0, months);
        }
        // 设置到 context 中
        context.setMonthsPassed(monthsPassed);

        // 判断是否有追溯月份（StartDate 早于 UnderWriteDate）
        boolean catchUpFlag = context.getStartDate() != null && context.getUnderWriteDate() != null
                && context.getStartDate().isBefore(context.getUnderWriteDate());

        // 记录服务期间统计日志
        Map<String, Object> monthsMeta = new HashMap<>();
        monthsMeta.put("Months Passed", monthsPassed);
        monthsMeta.put("Total Months", context.getTotalMonths());

        logger.logItem(
                "服务期间统计",
                "[Sec 4] 追溯至保单起期的服务月数",
                "Months_Passed / Total_Months",
                monthsMeta,
                new BigDecimal(monthsPassed),
                "包含追溯月份: " + (catchUpFlag ? "是" : "否") + "（起算点: " + context.getStartDate() + "）"
        );

        // 检查质保期 (Warranty Period)
        LocalDate warrantyEndDate = null;
        if (context.getPolicies() != null && !context.getPolicies().isEmpty()) {
            warrantyEndDate = context.getPolicies().get(0).getWarrantyEndDate();
        }
        if (warrantyEndDate == null) warrantyEndDate = context.getWarrantyEndDate();
        if (warrantyEndDate == null) warrantyEndDate = context.getStartDate();

        // Use the valuationDate already defined above
        // LocalDate valuationDate = context.getEopDate();
        // if (valuationDate == null && context.getYear() != null) {
        //    valuationDate = LocalDate.of(context.getYear(), 12, 31);
        // }

        // 判断当前评估日是否在质保期内
        boolean isInWarrantyPeriod = warrantyEndDate != null && valuationDate != null && valuationDate.isBefore(warrantyEndDate);

        if (isInWarrantyPeriod) {
            // 质保期内，预期赔付和预期维费设为 0（由厂商承担）
            context.setExpectedClaimNominal(BigDecimal.ZERO);
            context.setExpectedMaintNominal(BigDecimal.ZERO);
        } else {
            // 质保期外逻辑
            // 简化逻辑：根据已过月数计算

            BigDecimal monthsAfterWarranty;
            BigDecimal riskPeriodMonths = new BigDecimal(context.getTotalMonths());

            if (warrantyEndDate != null && context.getStartDate() != null && warrantyEndDate.isAfter(context.getStartDate())) {
                 // 复杂逻辑省略，简化处理
                 monthsAfterWarranty = new BigDecimal(monthsPassed);
            } else {
                monthsAfterWarranty = new BigDecimal(monthsPassed);
            }

            // 获取实际保费
            BigDecimal actualPremium = context.getActualPremium() != null ? context.getActualPremium() : BigDecimal.ZERO;

            if (riskPeriodMonths.compareTo(BigDecimal.ZERO) > 0) {
                // 计算赔付基数：实际保费 * 赔付率 * (1 + 间接理赔费用率)
                BigDecimal claimBase = actualPremium.multiply(lossRatio).multiply(BigDecimal.ONE.add(indirectClaimsExpenseRatio));
                // 计算预期赔付名义值：基数 / 风险期间月数 * 质保期后月数
                context.setExpectedClaimNominal(claimBase.divide(riskPeriodMonths, 10, RoundingMode.HALF_UP).multiply(monthsAfterWarranty));

                // 计算维费基数：实际保费 * 维费率
                BigDecimal maintBase = actualPremium.multiply(maintenanceExpenseRatio);
                // 计算预期维费名义值
                context.setExpectedMaintNominal(maintBase.divide(riskPeriodMonths, 10, RoundingMode.HALF_UP).multiply(monthsAfterWarranty));
            } else {
                context.setExpectedClaimNominal(BigDecimal.ZERO);
                context.setExpectedMaintNominal(BigDecimal.ZERO);
            }
        }

        // 设置实际发生额（此处假设等于预期额，实际项目中应从数据源获取）
        context.setActualClaimIncurred(context.getExpectedClaimNominal());
        context.setActualMaintIncurred(context.getExpectedMaintNominal());

        // 获取 PV 数据
        String eopMonthStr = context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);
        if (pvData == null) {
            throw new IllegalArgumentException(context.getPolicyNo()+"_"+context.getCertiNo()+"❌ 错误: 找不到评估月 " + eopMonthStr + " 的PV原材料数据！");
        }

        // 计算费用分摊比例 (Expense Allocation Ratio)
        BigDecimal expAdjRatio = calculateExpenseAllocationRatio(context);
        context.setExpAdjRatio(expAdjRatio);

        // Sec 4.3 保费经验调整 (Premium Experience Adjustment)
        BigDecimal premVar;
        BigDecimal newCActualPrem;
        BigDecimal effCActualPrem;

        if (isNewBusiness) {
            // 新业务：获取新单相关的 PV 字段
            BigDecimal newFEndPrem = getPvAmount(pvData.getPvNbEopCfaRepWlkPreAmt());
            BigDecimal newFInitPrem = getPvAmount(pvData.getPvNbIniCfaRepWlkPreAmt());
            BigDecimal newCInitPrem = getPvAmount(pvData.getPvNbIniCcaRepWlkPreAmt());

            // 获取实际保费
            newCActualPrem = context.getActualPremium();
            // 如果年份不匹配，置为 0
            if (context.getYear() != null && context.getUnderWriteDate() != null && context.getYear() != context.getUnderWriteDate().getYear()) {
                newCActualPrem = BigDecimal.ZERO;
            }
            if (newCActualPrem == null) newCActualPrem = BigDecimal.ZERO;

            // 如果是批减单，取反
//            if (context.isReversalPolicy() && newCActualPrem.compareTo(BigDecimal.ZERO) != 0) {
//                newCActualPrem = newCActualPrem.negate();
//            }

            // 计算保费差异原始值：(期末预期 + 实际) - (期初预期 + 当期预期)
            BigDecimal premVarRaw = newFEndPrem.add(newCActualPrem).subtract(newFInitPrem.add(newCInitPrem));
            // 应用分摊比例
            premVar = premVarRaw.multiply(expAdjRatio);

            // 设置上下文
            context.setActualPremiumNb(newCActualPrem);
            context.setActualPremiumEff(BigDecimal.ZERO);

            // 记录日志
            Map<String, Object> premMeta = new HashMap<>();
            premMeta.put("New.F_end", newFEndPrem);
            premMeta.put("New.C_actual", newCActualPrem);
            premMeta.put("New.F_init", newFInitPrem);
            premMeta.put("New.C_init", newCInitPrem);
            premMeta.put("EA_ratio_prem", expAdjRatio);
            premMeta.put("Adj_Prem", premVar);

            logger.logItem(
                    "保费现金流经验调整",
                    "[Sec 4.3] 实际保费与预期保费的差异（经验调整）",
                    "Adj_Prem^New = [(New.F_end + New.C_actual) - (New.F_init + New.C_init)] × EA_ratio_prem",
                    premMeta,
                    premVar,
                    "从PV原材料数据读取。保费经验调整占比=100%"
            );
        } else {
            // 有效业务（存量）：获取有效业务相关的 PV 字段
            BigDecimal effFEndPrem = getPvAmount(pvData.getPvIfEopCfaRepWlkPreAmt());
            effCActualPrem = BigDecimal.ZERO; // 存量保单通常无新保费
            BigDecimal effFBegPrem = getPvAmount(pvData.getPvIfBopCfaRepWlkPreAmt());
            BigDecimal effCYearPrem = getPvAmount(pvData.getPvIfBopCcaRepWlkPreAmt());

            // 计算保费差异
            BigDecimal premVarRaw = effFEndPrem.add(effCActualPrem).subtract(effFBegPrem.add(effCYearPrem));
            premVar = premVarRaw.multiply(expAdjRatio);

            context.setActualPremiumEff(effCActualPrem);
            context.setActualPremiumNb(BigDecimal.ZERO);

            Map<String, Object> premMeta = new HashMap<>();
            premMeta.put("Eff.F_end", effFEndPrem);
            premMeta.put("Eff.C_actual", effCActualPrem);
            premMeta.put("Eff.F_beg", effFBegPrem);
            premMeta.put("Eff.C_year", effCYearPrem);
            premMeta.put("EA_ratio_prem", expAdjRatio);
            premMeta.put("Adj_Prem", premVar);

            logger.logItem(
                    "保费现金流经验调整",
                    "[Sec 4.3] 实际保费与预期保费的差异（经验调整）",
                    "Adj_Prem^Eff = [(Eff.F_end + Eff.C_actual) - (Eff.F_beg + Eff.C_year)] × EA_ratio_prem",
                    premMeta,
                    premVar,
                    "从PV原材料数据读取。保费经验调整占比=100%"
            );
        }
        context.setPremVar(premVar);
        context.setAdjPrem(premVar);

        // Sec 4.4 IACF 经验调整 (Acquisition Cost Experience Adjustment)
        BigDecimal iacfVar;
        BigDecimal newCActualIacf;
        BigDecimal effCActualIacf;

        if (isNewBusiness) {
            BigDecimal newFEndIacf = getPvAmount(pvData.getPvNbEopCfaRepWlkAcqAmt());
            BigDecimal newFInitIacf = getPvAmount(pvData.getPvNbIniCfaRepWlkAcqAmt());
            BigDecimal newCInitIacf = getPvAmount(pvData.getPvNbIniCcaRepWlkAcqAmt());

            newCActualIacf = context.getActualIacfIncurred();
            if (newCActualIacf == null) {
                 newCActualIacf = (context.getYear() != null && context.getUnderWriteDate() != null && context.getYear().equals(context.getUnderWriteDate().getYear()))
                        ? context.getActualIacfIncurred() : BigDecimal.ZERO;
            }
            if (newCActualIacf == null) newCActualIacf = BigDecimal.ZERO;

            BigDecimal iacfVarRaw = newFEndIacf.add(newCActualIacf).subtract(newFInitIacf.add(newCInitIacf));
            iacfVar = iacfVarRaw.multiply(expAdjRatio);

            context.setIacfVar(iacfVar);
            context.setAdjIacf(iacfVar);
            context.setExpectedIacfNominal(newFInitIacf.add(newCInitIacf));
            context.setActualIacfNb(newCActualIacf);
            context.setActualIacfEff(BigDecimal.ZERO);

             Map<String, Object> iacfMeta = new HashMap<>();
            iacfMeta.put("New.F_end^I", newFEndIacf);
            iacfMeta.put("New.C_actual^I", newCActualIacf);
            iacfMeta.put("New.F_init^I", newFInitIacf);
            iacfMeta.put("New.C_init^I", newCInitIacf);
            iacfMeta.put("EA_ratio_iacf", expAdjRatio);
            iacfMeta.put("Adj_IACF", iacfVar);

            logger.logItem(
                    "IACF 经验调整",
                    "[Sec 4.4] 实际获取费用与预期获取费用的差异（经验调整）",
                    "Adj_IACF^New = [(New.F_end^I + New.C_actual^I) - (New.F_init^I + New.C_init^I)] × EA_ratio_iacf",
                    iacfMeta,
                    iacfVar,
                    "实际IACF（New.C_actual^I）是名义值，不计息，直接从保单数据获取。预期IACF从PV原材料数据读取。IACF经验调整占比=0%"
            );
        } else {
            BigDecimal effFEndIacf = getPvAmount(pvData.getPvIfEopCfaRepWlkAcqAmt());
            effCActualIacf = BigDecimal.ZERO;
            BigDecimal effFBegIacf = getPvAmount(pvData.getPvIfBopCfaRepWlkAcqAmt());
            BigDecimal effCYearIacf = getPvAmount(pvData.getPvIfBopCcaRepWlkAcqAmt());

            BigDecimal iacfVarRaw = effFEndIacf.add(effCActualIacf).subtract(effFBegIacf.add(effCYearIacf));
            iacfVar = iacfVarRaw.multiply(expAdjRatio);

            context.setIacfVar(iacfVar);
            context.setAdjIacf(iacfVar);
            context.setExpectedIacfNominal(effFBegIacf.add(effCYearIacf));
            context.setActualIacfEff(effCActualIacf);
            context.setActualIacfNb(BigDecimal.ZERO);

            Map<String, Object> iacfMeta = new HashMap<>();
            iacfMeta.put("Eff.F_end^I", effFEndIacf);
            iacfMeta.put("Eff.C_actual^I", effCActualIacf);
            iacfMeta.put("Eff.F_beg^I", effFBegIacf);
            iacfMeta.put("Eff.C_year^I", effCYearIacf);
            iacfMeta.put("EA_ratio_iacf", expAdjRatio);
            iacfMeta.put("Adj_IACF", iacfVar);

            logger.logItem(
                    "IACF 经验调整",
                    "[Sec 4.4] 实际获取费用与预期获取费用的差异（经验调整）",
                    "Adj_IACF^Eff = [(Eff.F_end^I + Eff.C_actual^I) - (Eff.F_beg^I + Eff.C_year^I)] × EA_ratio_iacf",
                    iacfMeta,
                    iacfVar,
                    "从PV原材料数据读取。IACF经验调整占比=0%"
            );
        }

        Map<String, Object> totalMeta = new HashMap<>();
        totalMeta.put("Adj_Prem", premVar);
        totalMeta.put("Adj_IACF", iacfVar);

        logger.logItem(
                "经验调整合计",
                "[Sec 4] 保费和IACF经验调整合计",
                "Adj_Total = Adj_Prem + Adj_IACF",
                totalMeta,
                premVar.add(iacfVar),
                "所有 'F'/ 'C' 项需保持同一加权初始确认利率"
        );
    }

    /**
     * 计算被 CSM/LC 吸收的变化 (CSM/LC Absorption)。
     * 主要是指与未来服务相关的履约现金流变化。
     *
     * @param context       计算上下文
     * @param logger        日志记录器
     * @param cohortState   合同组状态
     * @param policies      保单列表
     */
    private void calculateCsmLcAbsorption(
            CalculationContext context,
            CalculationLogger logger,
            CohortState cohortState,
            List<PolicyState> policies
    ) {
        // 记录章节标题：被 CSM/LC 吸收的变化
        logger.logSection("Part 4: 被CSM/LC吸收的变化 (CSM/LC Absorption) [Sec 5]");

        // 检查必要字段是否已设置
        if (context.getInitFutClaim() == null || context.getInitFutMaint() == null || context.getInitRa() == null) {
            throw new IllegalArgumentException("❌ 错误: context.init_fut_claim/maint/ra 未设置！");
        }

        // 获取 PV 数据
        PVSourceData pvData = context.getPvSourceData().getData(context.getValMonthStr());
        if (pvData == null) throw new IllegalArgumentException("PV Data missing");

        boolean isNewBusiness = context.isNewBusiness();

        // Sec 5.2 保费现金流变化 (Delta Prem)
        BigDecimal effFEndPrem = getPvAmount(pvData.getPvIfEopCfaRepWlkPreAmt());
        BigDecimal effFBegPrem = getPvAmount(pvData.getPvIfBopCfaRepWlkPreAmt());
        BigDecimal effCYearPrem = getPvAmount(pvData.getPvIfBopCcaRepWlkPreAmt());

        BigDecimal newFEndPrem = BigDecimal.ZERO;
        BigDecimal newFInitPrem = BigDecimal.ZERO;
        BigDecimal newCInitPrem = BigDecimal.ZERO;

        if (isNewBusiness) {
            newFEndPrem = getPvAmount(pvData.getPvNbEopCfaRepWlkPreAmt());
            newFInitPrem = getPvAmount(pvData.getPvNbIniCfaRepWlkPreAmt());
            newCInitPrem = getPvAmount(pvData.getPvNbIniCcaRepWlkPreAmt());
        }

        // 计算保费变化量
        // 公式：(EffEnd + NBEnd) - (EffBeg + NBInit) + (EffActual + NBActual) - (EffExpCurrent + NBExpCurrent) - AdjPrem
        BigDecimal effActualPrem = context.getActualPremiumEff() != null ? context.getActualPremiumEff() : BigDecimal.ZERO;
        BigDecimal nbActualPrem = context.getActualPremiumNb() != null ? context.getActualPremiumNb() : BigDecimal.ZERO;

        BigDecimal effExpCurrentPrem = effCYearPrem;
        BigDecimal nbExpCurrentPrem = newCInitPrem;

        BigDecimal adjPrem = context.getAdjPrem() != null ? context.getAdjPrem() : BigDecimal.ZERO;

        BigDecimal deltaPrem = effFEndPrem.add(newFEndPrem)
                .subtract(effFBegPrem.add(newFInitPrem))
                .add(effActualPrem.add(nbActualPrem))
                .subtract(effExpCurrentPrem.add(nbExpCurrentPrem))
                .subtract(adjPrem);

        context.setDeltaPrem(deltaPrem);

        logger.logItem("保费现金流变化", "[Sec 5.2] 保费现金流变化（统一Wlk公式）",
                "Δ_Prem = (End - Init) + Actual - Expected_Current - Adj", null, deltaPrem, "修正公式对齐Python");

        // Sec 5.3 IACF 变化 (Delta IACF)
        BigDecimal effFEndIacf = getPvAmount(pvData.getPvIfEopCfaRepWlkAcqAmt());
        BigDecimal effFBegIacf = getPvAmount(pvData.getPvIfBopCfaRepWlkAcqAmt());
        BigDecimal effCYearIacf = getPvAmount(pvData.getPvIfBopCcaRepWlkAcqAmt());

        BigDecimal newFEndIacf = BigDecimal.ZERO;
        BigDecimal newFInitIacf = BigDecimal.ZERO;
        BigDecimal newCInitIacf = BigDecimal.ZERO;

        if (isNewBusiness) {
            newFEndIacf = getPvAmount(pvData.getPvNbEopCfaRepWlkAcqAmt());
            newFInitIacf = getPvAmount(pvData.getPvNbIniCfaRepWlkAcqAmt());
            newCInitIacf = getPvAmount(pvData.getPvNbIniCcaRepWlkAcqAmt());
        }

        // 计算 IACF 变化量
        // 公式：(EffEnd + NBEnd) - (EffBeg + NBInit) + (EffActual + NBActual) - (EffExpCurrent + NBExpCurrent) - AdjIacf
        BigDecimal effActualIacf = context.getActualIacfEff() != null ? context.getActualIacfEff() : BigDecimal.ZERO;
        BigDecimal nbActualIacf = context.getActualIacfNb() != null ? context.getActualIacfNb() : BigDecimal.ZERO;

        BigDecimal effExpCurrentIacf = effCYearIacf;
        BigDecimal nbExpCurrentIacf = newCInitIacf;

        BigDecimal adjIacf = context.getAdjIacf() != null ? context.getAdjIacf() : BigDecimal.ZERO;

        BigDecimal deltaIacf = effFEndIacf.add(newFEndIacf)
                .subtract(effFBegIacf.add(newFInitIacf))
                .add(effActualIacf.add(nbActualIacf))
                .subtract(effExpCurrentIacf.add(nbExpCurrentIacf))
                .subtract(adjIacf);

        context.setDeltaIacf(deltaIacf);
        logger.logItem("IACF变化", "[Sec 5.3] IACF变化（统一Wlk公式）", "Δ_IACF = (End - Init) + Actual - Expected_Current - Adj", null, deltaIacf, "修正公式对齐Python");

        // Sec 5.4 赔付变化 (Delta Claims)
        BigDecimal effFEndClaim = getPvAmount(pvData.getPvIfEopCfaRepWlkClaAmt());
        BigDecimal effFBegClaim = getPvAmount(pvData.getPvIfBopCfaRepWlkClaAmt());
        // BigDecimal effCYearClaim = getPvAmount(pvData.getPvIfBopCcaRepWlkClaAmt()); // Not used in user formula

        BigDecimal newFEndClaim = BigDecimal.ZERO;
        BigDecimal newFInitClaim = BigDecimal.ZERO;
        // BigDecimal newCInitClaim = BigDecimal.ZERO; // Not used in user formula

        if (isNewBusiness) {
            newFEndClaim = getPvAmount(pvData.getPvNbEopCfaRepWlkClaAmt());
            newFInitClaim = getPvAmount(pvData.getPvNbIniCfaRepWlkClaAmt());
            // newCInitClaim = getPvAmount(pvData.getPvNbIniCcaRepWlkClaAmt());
        }

        // Debug Logs
        Map<String, Object> claimsDebug = new HashMap<>();
        claimsDebug.put("effFEndClaim", effFEndClaim);
        claimsDebug.put("effFBegClaim", effFBegClaim);
        claimsDebug.put("newFEndClaim", newFEndClaim);
        claimsDebug.put("newFInitClaim", newFInitClaim);

        // 计算赔付变化量
        // 公式: (EffEnd + NBEnd) - (EffBeg + NBInit)
        BigDecimal deltaClaims = effFEndClaim.add(newFEndClaim)
                .subtract(effFBegClaim.add(newFInitClaim));

        context.setDeltaClaims(deltaClaims);
        logger.logItem("赔付与费用_预期赔付变化", "[Sec 5.4] 赔付现金流变化（统一Wlk公式）", "Δ_Claims = (End - Init)", claimsDebug, deltaClaims, "修正公式对齐Python");

        // Sec 5.5 维持费用变化 (Delta Maint)
        BigDecimal effFEndMaint = getPvAmount(pvData.getPvIfEopCfaRepWlkMtnAmt());
        BigDecimal effFBegMaint = getPvAmount(pvData.getPvIfBopCfaRepWlkMtnAmt());
        // BigDecimal effCYearMaint = getPvAmount(pvData.getPvIfBopCcaRepWlkMtnAmt()); // Not used

        BigDecimal newFEndMaint = BigDecimal.ZERO;
        BigDecimal newFInitMaint = BigDecimal.ZERO;
        // BigDecimal newCInitMaint = BigDecimal.ZERO; // Not used

        if (isNewBusiness) {
            newFEndMaint = getPvAmount(pvData.getPvNbEopCfaRepWlkMtnAmt());
            newFInitMaint = getPvAmount(pvData.getPvNbIniCfaRepWlkMtnAmt());
            // newCInitMaint = getPvAmount(pvData.getPvNbIniCcaRepWlkMtnAmt());
        }

        // Debug Logs
        Map<String, Object> maintDebug = new HashMap<>();
        maintDebug.put("effFEndMaint", effFEndMaint);
        maintDebug.put("effFBegMaint", effFBegMaint);
        maintDebug.put("newFEndMaint", newFEndMaint);
        maintDebug.put("newFInitMaint", newFInitMaint);

        // 计算维持费用变化量
        // 公式: (EffEnd + NBEnd) - (EffBeg + NBInit)
        BigDecimal deltaMaint = effFEndMaint.add(newFEndMaint)
                .subtract(effFBegMaint.add(newFInitMaint));

        context.setDeltaMaint(deltaMaint);
        logger.logItem("维持费用现金流变化", "[Sec 5.5] 维持费用现金流变化（统一Wlk公式）", "Δ_Maint = (End - Init)", maintDebug, deltaMaint, "修正公式对齐Python");

        // Sec 5.6 预期现金流变化合计 (Total Delta CF)
        // 合计 = 保费变化 - IACF变化 - 赔付变化 - 维费变化
        BigDecimal deltaCfTotal = deltaPrem.subtract(deltaIacf).subtract(deltaClaims).subtract(deltaMaint);

        // 修正: 剔除 IFIE PV Interest (Time Value of Money) - NO, Python uses raw Delta CF Total
        // BigDecimal ifieCf = context.getIfiePvCfTotal() != null ? context.getIfiePvCfTotal() : BigDecimal.ZERO;

        // [User Request] Match Python logic: Do NOT force to 0. Use calculated value.
        // Python: delta_cf_total = delta_prem - delta_iacf - delta_claims - delta_maint
        BigDecimal deltaCfTotalAdjusted = deltaCfTotal;

        context.setDeltaCfTotal(deltaCfTotalAdjusted);

        Map<String, Object> deltaCfMeta = new HashMap<>();
        deltaCfMeta.put("Raw Delta CF", deltaCfTotal);
        // deltaCfMeta.put("IFIE PV CF (Interest)", ifieCf);

        logger.logItem("预期现金流变化合计", "[Sec 5.6] 预期现金流变化合计",
                "Δ_CF_Total = Δ_Prem - Δ_IACF - Δ_Claims - Δ_Maint",
                deltaCfMeta, deltaCfTotalAdjusted, "不再强制剔除利息，与Python保持一致");

        // Sec 5.7 RA 变化 (Delta RA)
        BigDecimal effFEndRa = getPvAmount(pvData.getPvIfEopCfaRepWlkRadAmt());
        BigDecimal effFBegRa = getPvAmount(pvData.getPvIfBopCfaRepWlkRadAmt());

        BigDecimal newFEndRa = BigDecimal.ZERO;
        BigDecimal newFInitRa = BigDecimal.ZERO;

        if (isNewBusiness) {
            newFEndRa = getPvAmount(pvData.getPvNbEopCfaRepWlkRadAmt());
            newFInitRa = getPvAmount(pvData.getPvNbIniCfaRepWlkRadAmt());
        }

        BigDecimal deltaRa = effFEndRa.add(newFEndRa).subtract(effFBegRa.add(newFInitRa));

        // 修正: 剔除 IFIE PV RA Interest - NO, Match Python
        // BigDecimal ifieRa = context.getIfiePvRaTotal() != null ? context.getIfiePvRaTotal() : BigDecimal.ZERO;

        BigDecimal deltaRaAdjusted = deltaRa;

        Map<String, Object> deltaRaMeta = new HashMap<>();
        deltaRaMeta.put("Raw Delta RA", deltaRa);
        // deltaRaMeta.put("IFIE PV RA (Interest)", ifieRa);

        logger.logItem("非金融风险调整变化", "[Sec 5.7] RA变化",
                "Δ_RA = End - Beg",
                deltaRaMeta, deltaRaAdjusted, "不再强制剔除利息，与Python保持一致");

        // Sec 5.8 CSM/LC 吸收变化合计 (Delta CSM/LC)
        // 净变化 = 现金流变化 - RA变化
        BigDecimal deltaCsmLc = deltaCfTotalAdjusted.subtract(deltaRaAdjusted);
        context.setExpAdjCsmImpact(deltaCsmLc);
        logger.logItem("被CSM/LC吸收的变化合计", "[Sec 5.8]", "Δ_CSM/LC = Δ_CF_Total_Adj - Δ_RA_Adj", null, deltaCsmLc, null);

        // CSM/LC 分摊逻辑 (Allocation Logic)
        // ==========================================================================================
        // CSM/LC统一字段逻辑：使用一个字段，>=0走CSM逻辑，<0走LC逻辑
        // ==========================================================================================

        // 计算期初 CSM/LC 余额
        BigDecimal bopCsmLc = getBopCsmLc(context, cohortState);

        // 获取统一的CSM/LC字段（用于计算LC IFIE分摊比例）
        // 正常保单：CSM>=0，LC<0；批减单：CSM<=0，LC>0（符号逻辑相反）
        boolean isReversal = context.isReversalPolicy();

        BigDecimal nbInitialCsmLc = context.getNbInitialCsm() != null ? context.getNbInitialCsm() : BigDecimal.ZERO;
        if (nbInitialCsmLc.compareTo(BigDecimal.ZERO) == 0 && context.getNbInitialLc() != null) {
            BigDecimal nbLcVal = context.getNbInitialLc();
            // is_nb_lc = (nb_lc_val < 0) if (not is_reversal) else (nb_lc_val > 0)
            boolean isNbLc = (!isReversal && nbLcVal.compareTo(BigDecimal.ZERO) < 0) ||
                             (isReversal && nbLcVal.compareTo(BigDecimal.ZERO) > 0);
            if (isNbLc) {
                nbInitialCsmLc = nbLcVal;
            }
        }

        // [Sec 7.2.2] LC IFIE分摊比例（期初有效合同）
        BigDecimal ifLcIfieRatio = BigDecimal.ZERO;
        boolean isIfLc = (!isReversal && bopCsmLc.compareTo(BigDecimal.ZERO) < 0) ||
                         (isReversal && bopCsmLc.compareTo(BigDecimal.ZERO) > 0);

        if (isIfLc) {
            // 分母：预期赔付现金流年初现值 + 预期维持费用现金流年初现值 + 预期非金融风险调整年初现值
            // 注意：已删除 Cca_Beg_Lcu 字段，Cfa_Beg_Lcu 已经包含了1月现金流（折现到年初）
            BigDecimal pvIfInitClaims = getPvAmount(pvData.getPvIfBopCfaBegLcuClaAmt());
            BigDecimal pvIfInitMaint = getPvAmount(pvData.getPvIfBopCfaBegLcuMtnAmt());
            BigDecimal pvIfInitRa = getPvAmount(pvData.getPvIfBopCfaBegLcuRadAmt());
            BigDecimal denomIf = pvIfInitClaims.add(pvIfInitMaint).add(pvIfInitRa);

            if (denomIf.abs().compareTo(BigDecimal.ZERO) > 0) {
                ifLcIfieRatio = bopCsmLc.abs().divide(denomIf.abs(), 10, RoundingMode.HALF_UP);
            }
        }

        // [Sec 7.3.2] LC IFIE分摊比例（当年新增合同）
        BigDecimal nbLcIfieRatio = BigDecimal.ZERO;
        boolean isNbLc = (!isReversal && nbInitialCsmLc.compareTo(BigDecimal.ZERO) < 0) ||
                         (isReversal && nbInitialCsmLc.compareTo(BigDecimal.ZERO) > 0);

        if (isNbLc) {
            BigDecimal denomNb = context.getInitFutClaim().add(context.getInitFutMaint()).add(context.getInitRa());
            if (denomNb.abs().compareTo(BigDecimal.ZERO) > 0) {
                nbLcIfieRatio = nbInitialCsmLc.abs().divide(denomNb.abs(), 10, RoundingMode.HALF_UP);
            }
        }

        // 保存LC IFIE分摊比例到context（供IFIE模块使用）
        context.setNbLcRatio(nbLcIfieRatio);
        context.setIfLcIfieRatio(ifLcIfieRatio);

        // [Sec 5] 被CSM/LC吸收的变化分摊
        // 使用统一字段逻辑：如果变化被LC吸收（正常保单：<0；批减单：>0），则分摊到LC；否则被CSM吸收
        BigDecimal allocatedLcExpAdj = deltaCsmLc.multiply(nbLcIfieRatio);
        BigDecimal csmAbsorbed = deltaCsmLc.subtract(allocatedLcExpAdj);

        context.setAllocatedLcExpAdj(allocatedLcExpAdj);
        context.setCsmAbsorbed(csmAbsorbed);

        Map<String, Object> allocMeta = new HashMap<>();
        allocMeta.put("Δ_CSM/LC", deltaCsmLc);
        allocMeta.put("NB_LC_Ratio", nbLcIfieRatio);
        allocMeta.put("被CSM吸收", csmAbsorbed);
        allocMeta.put("被LC吸收", allocatedLcExpAdj);
        allocMeta.put("NB_初始CSM/LC", nbInitialCsmLc);
        allocMeta.put("说明", "如果NB_初始CSM/LC < 0，则为LC，部分变化被LC吸收；否则全部被CSM吸收");

        logger.logItem("被CSM/LC吸收的变化分摊", "[Sec 5] 被CSM/LC吸收的变化分摊（使用统一字段逻辑）",
                "被CSM吸收 = Δ_CSM/LC × (1 - LC_Ratio)\n被LC吸收 = Δ_CSM/LC × LC_Ratio", allocMeta, deltaCsmLc,
                "被CSM/LC吸收的变化使用同一个字段（delta_csm_lc），根据LC_Ratio分摊到CSM或LC。注意：计息逻辑不在此模块，应在interest_accretion模块中处理");
    }

    /**
     * 计算费用分摊比例 (Expense Allocation Ratio)。
     * 用于在时间上分摊费用或经验调整。
     *
     * @param context 计算上下文
     * @return 分摊比例
     */
    private BigDecimal calculateExpenseAllocationRatio(CalculationContext context) {
        BigDecimal ratio = BigDecimal.ZERO;

        if (context.getPolicies() != null && !context.getPolicies().isEmpty()) {
            LocalDate valuationDate = context.getEopDate();
            if (valuationDate == null) valuationDate = LocalDate.of(context.getYear() != null ? context.getYear() : 2022, 12, 31);
            LocalDate startOfYear = LocalDate.of(valuationDate.getYear(), 1, 1);
            boolean isInitialYear = context.isInitialYear();

            // 使用 CoverageUnitsService 计算释放和剩余的覆盖单元
            BigDecimal cuReleased = coverageUnitsService.calculateCoverageUnitsReleased(context.getPolicies(), valuationDate, startOfYear, null, isInitialYear);
            BigDecimal cuRemaining = coverageUnitsService.calculateCoverageUnitsRemaining(context.getPolicies(), valuationDate, null);

            BigDecimal denominator = cuReleased.add(cuRemaining);
            if (denominator.compareTo(BigDecimal.ZERO) > 0) {
                // 比例 = 释放CU / (释放CU + 剩余CU)
                ratio = cuReleased.divide(denominator, 10, RoundingMode.HALF_UP);
            }
        } else {
             // 基于时间的降级策略
             int totalMonths = context.getTotalMonths();
             int monthsPassed = context.getMonthsPassed();
             if (totalMonths > 0) {
                 ratio = new BigDecimal(monthsPassed).divide(new BigDecimal(totalMonths), 10, RoundingMode.HALF_UP);
             }
        }
        return ratio;
    }

    // -------------------------------------------------------------------------
    // Helper Methods for Unified CSM/LC Logic (Matches Python)
    // -------------------------------------------------------------------------

    private BigDecimal getBopCsmLc(CalculationContext context, CohortState cohortState) {
        // 优先从 context 获取
        BigDecimal bopCsm = context.getBopCsm();
        BigDecimal bopLc = context.getBopLc();

        // 如果 context 中没有，从 cohortState 获取
        if (bopCsm == null && cohortState != null) bopCsm = cohortState.getBopCsm();
        if (bopLc == null && cohortState != null) bopLc = cohortState.getBopLc();

        // 合并为统一字段：bop_csm_lc = bop_csm + bop_lc
        // 盈利时：bop_csm > 0, bop_lc = 0，所以 bop_csm_lc = bop_csm（正数）
        // 亏损时：bop_csm = 0, bop_lc < 0，所以 bop_csm_lc = bop_lc（负数）
        BigDecimal bopCsmVal = bopCsm != null ? bopCsm : BigDecimal.ZERO;
        BigDecimal bopLcVal = bopLc != null ? bopLc : BigDecimal.ZERO;

        return bopCsmVal.add(bopLcVal);
    }

    /**
     * 安全获取 PV 金额，处理 null 值。
     *
     * @param value BigDecimal 值
     * @return 非 null 的 BigDecimal
     */
    private BigDecimal getPvAmount(BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }
}
