package com.bba.service.logic;

import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.CohortState;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import com.bba.mapper.SummaryIacfCostMapper;
import com.bba.entity.SummaryIacfCost;
import com.bba.entity.PolicyContract;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 初始确认服务类。
 * 负责处理新业务（New Business）的初始确认（Initial Recognition）逻辑。
 * 包括读取PV数据、计算初始CSM/LC、更新加权锁定利率等。
 * 对应 IFRS 17 准则中的初始确认环节。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InitialRecognitionService {

    // 注入利率管理服务，用于计算即期利率和更新加权锁定利率
    private final RatesManagerService ratesManagerService;
    private final SummaryIacfCostMapper summaryIacfCostMapper;

    /**
     * 执行初始确认逻辑。
     *
     * @param context       计算上下文，包含保单数据、PV数据等
     * @param logger        计算日志记录器，用于记录详细计算步骤
     * @param assumptions   精算假设（此处主要用于日志或潜在计算，当前逻辑主要依赖PV数据）
     * @param cohortState   合同组状态，用于更新加权锁定利率
     */
    public void run(CalculationContext context, CalculationLogger logger, Assumptions assumptions, CohortState cohortState) {
        // 记录章节标题：初始确认 - 新业务
        logger.logSection("Part 1: 初始确认 (Initial Recognition) - New Business [Sec 1-3]");

        // 检查PV原材料数据是否已加载
        if (context.getPvSourceData() == null) {
            // 如果PV数据为空，抛出异常
            throw new IllegalArgumentException("❌ Error: PV Source Data is missing!");
        }

        // 记录PV数据验证成功的日志
        logger.logItem(
            "PV原材料数据验证", // 标题
            "验证PV原材料数据已加载", // 描述
            "ensure_pv_source_data()", // 涉及方法/公式
            new HashMap<>(), // 额外变量
            "✅ 成功", // 结果值
            "所有现值计算将严格使用PV原材料数据，确保数据完整性和准确性" // 备注
        );

        // 获取实际签单保费
        // 在Java中，假设context.policyData已填充或context.actualPremium已设置
        if (context.getPolicyData() != null) {
            // 从保单数据中设置实际保费
            context.setActualPremium(context.getPolicyData().getSumPremiumNoTax());

            // 设置实际IACF
            // [Modified] Use actual IACF from database, fallback to assumption only if db lookup fails (which returns 0)
            BigDecimal actualIacf = getActualIacf(context.getPolicyData());
            context.setActualIacfIncurred(actualIacf);

            // 记录日志
            logger.logItem("初始确认_实际IACF", "实际发生的获取费用", "From DB: zh.summary_iacf_cost", null, context.getActualIacfIncurred());
        }

        // 使用精算假设
        // ... (变量用于日志或计算，如果逻辑是动态的，但此处我们读取PV)

        // 计算即期利率 (Spot Rate)
        // 这里的Spot Rate通常取自利率曲线的第一个值，用于新单的初始确认折现
        BigDecimal spotRate = ratesManagerService.calculateSpotRate(context.getRatesDf());
        // 记录即期利率日志
        logger.logItem(
            "即期利率（Spot Rate）",
            "[Sec 2.0] 新单初始确认时使用的即期利率",
            "利率曲线的第一个值",
            new HashMap<>(),
            spotRate,
            "新单初始确认时使用即期利率计算现值，计算完成后权重并入加权锁定利率"
        );

        // 获取保单签单日期的月份字符串 (yyyyMM)
        String uwMonthStr = context.getUnderWriteDate().withMonth(12).format(DateTimeFormatter.ofPattern("yyyyMM"));
        // 根据签单月获取对应的PV数据
        PVSourceData pvData = context.getPvSourceData().getData(uwMonthStr);
        // 如果找不到对应月份的PV数据
        if (pvData == null) {
            // 抛出异常，提示缺少数据
            throw new IllegalArgumentException("❌ Error: PV data not found for month " + uwMonthStr);
        }

        // 检查是否为批减单 (Reversal Policy)
        // 1. 从PV数据的元数据中获取标记
        boolean isReversalPolicy = Boolean.parseBoolean(pvData.getMetadata().getOrDefault("is_reversal_policy", "false").toString());
        
        // 2. 如果实际保费为负数，强制标记为批减单
        if (context.getActualPremium() != null && context.getActualPremium().compareTo(BigDecimal.ZERO) < 0) {
            isReversalPolicy = true;
            logger.logText("⚠️ 检测到实际保费为负值 (" + context.getActualPremium() + ")，强制标记为批减单。");
        }
        
        context.setReversalPolicy(isReversalPolicy);

        // 如果是批减单，记录警告日志
        if (isReversalPolicy) {
            logger.logText("⚠️  **批减单标记**: 检测到批减单（签单保费为负值）。本次口径：PV字段保持原始符号；仅在CSM/LC及所有LC触发条件上按批减单反号规则判定（<=0为CSM，>0为LC）。");
        }

        // 1.1 预期保费现值 (PV Premium)
        // 对应字段: Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt
        String pvFieldPrem = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt";
        // 从PV数据中获取保费现值，如果为null则设为0
        BigDecimal pvPremium = pvData.getPvNbIniCfaRecLkdPreAmt() != null ? pvData.getPvNbIniCfaRecLkdPreAmt() : BigDecimal.ZERO;
        // 记录保费现值日志
        logItem(logger, "当年新增合同_初始确认_预期保费现值", "[Sec 2.1] 初始确认时，预期未来收到的保费折现值（从PV原材料数据读取）",
                pvFieldPrem, pvPremium, uwMonthStr);

        // 1.2 预期获取费用现值 (PV IACF)
        // 对应字段: Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt
        String pvFieldIacf = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt";
        // 从PV数据中获取获取费用现值，如果为null则设为0
        BigDecimal valIacf = pvData.getPvNbIniCfaRecLkdAcqAmt() != null ? pvData.getPvNbIniCfaRecLkdAcqAmt() : BigDecimal.ZERO;
        // 记录获取费用现值日志
        logItem(logger, "当年新增合同_初始确认_IACF现值", "[Sec 2.1] 初始确认时，预期支付的获取费用折现值（从PV原材料数据读取）",
                pvFieldIacf, valIacf, uwMonthStr);

        // 1.3 预期赔付现值 (PV Claims)
        // 对应字段: Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt
        String pvFieldClaims = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt";
        // 从PV数据中获取赔付现值，并设置到context中
        context.setInitFutClaim(pvData.getPvNbIniCfaRecLkdClaAmt() != null ? pvData.getPvNbIniCfaRecLkdClaAmt() : BigDecimal.ZERO);
        // 记录赔付现值日志
        logItem(logger, "当年新增合同_初始确认_预期赔付现值", "[Sec 2.2] 初始确认时，预期赔付支出的折现值（从PV原材料数据读取）",
                pvFieldClaims, context.getInitFutClaim(), uwMonthStr);

        // 1.4 预期维持费用现值 (PV Maint)
        // 对应字段: Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt
        String pvFieldMaint = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt";
        // 从PV数据中获取维持费用现值，并设置到context中
        context.setInitFutMaint(pvData.getPvNbIniCfaRecLkdMtnAmt() != null ? pvData.getPvNbIniCfaRecLkdMtnAmt() : BigDecimal.ZERO);
        // 记录维持费用现值日志
        logItem(logger, "当年新增合同_初始确认_预期维费现值", "[Sec 2.3] 初始确认时，预期维持费用的折现值（从PV原材料数据读取）",
                pvFieldMaint, context.getInitFutMaint(), uwMonthStr);

        // 1.5 非金融风险调整 (RA)
        // 对应字段: Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt
        String pvFieldRa = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt";
        // 从PV数据中获取RA现值，并设置到context中
        context.setInitRa(pvData.getPvNbIniCfaRecLkdRadAmt() != null ? pvData.getPvNbIniCfaRecLkdRadAmt() : BigDecimal.ZERO);
        // 记录RA现值日志
        logItem(logger, "当年新增合同_初始确认_非金融风险调整(RA)", "[Sec 3.2] 初始确认时，对非金融风险的调整额（从PV原材料数据读取）",
                pvFieldRa, context.getInitRa(), uwMonthStr);

        // 批减单判定：使用之前确定的标记
        boolean isEndorsement = context.isReversalPolicy();

        // 1.6 计算 CSM 或 LC (Contractual Service Margin / Loss Component)
        // 净流入现值 = 保费现值 - (获取费用现值 + 赔付现值 + 维费现值)
        // 设置保费流入
        BigDecimal pvInflow = pvPremium;
        // 计算总流出：IACF + 赔付 + 维费
        BigDecimal pvOutflow = valIacf.add(context.getInitFutClaim()).add(context.getInitFutMaint());
        // 计算净流入
        BigDecimal netInflow = pvInflow.subtract(pvOutflow);

        BigDecimal margin = netInflow.subtract(context.getInitRa());

        // 初始化 CSM 为 0
        context.setNbInitialCsm(BigDecimal.ZERO);
        // 初始化 LC 为 0
        context.setNbInitialLc(BigDecimal.ZERO);

        // 定义状态字符串
        String csmStatus;

        if (isEndorsement) {
            // 批减单逻辑（反转）
            if (margin.compareTo(BigDecimal.ZERO) <= 0) {
                // Margin <= 0 -> CSM (Profitable)
                context.setNbInitialCsm(margin);
                csmStatus = "Profitable (CSM) [Endorsement]";
            } else {
                // Margin > 0 -> LC (Onerous)
                context.setNbInitialLc(margin);
                csmStatus = "Onerous (Loss Component) - 立即确认亏损 [Endorsement]";
            }
        } else {
            // 标准逻辑
            if (margin.compareTo(BigDecimal.ZERO) >= 0) {
                // Margin >= 0 -> CSM (Profitable)
                context.setNbInitialCsm(margin);
                csmStatus = "Profitable (CSM)";
            } else {
                // Margin < 0 -> LC (Onerous)
                context.setNbInitialLc(margin);
                csmStatus = "Onerous (Loss Component) - 立即确认亏损";
            }
        }

        // 准备日志变量 Map
        Map<String, Object> vars = new HashMap<>();
        vars.put("PV_Prem", pvInflow); // 保费现值
        vars.put("PV_IACF", valIacf);  // 获取费用现值
        vars.put("PV_Claims", context.getInitFutClaim()); // 赔付现值
        vars.put("PV_Maint", context.getInitFutMaint());  // 维持费用现值
        vars.put("Net_Inflow", netInflow); // 净流入
        vars.put("RA", context.getInitRa()); // 风险调整
        vars.put("Margin", margin); // 利润边缘

        // 记录 CSM/LC 计算日志
        logger.logItem(
            "当年新增合同_初始确认_CSM/LC",
            "[Sec 3.3] 初始确认时的合同服务边际或亏损（逐单判定）",
            "Net_Inflow = PV_Prem - (PV_Claims + PV_Maint + PV_IACF); Margin = Net_Inflow - RA",
            vars,
            margin,
            String.format("判定结果: %s. Initial CSM = %,.2f, Initial LC = %,.2f", csmStatus, context.getNbInitialCsm(), context.getNbInitialLc())
        );

        // 更新加权锁定利率 (Weighted Locked Rate)
        // 仅当 cohortState 不为空时执行（即有合同组上下文）
        if (cohortState != null) {
            // 调用利率管理服务更新加权锁定利率
            ratesManagerService.updateWeightedLockedRate(
                cohortState,
                spotRate,
                context.getActualPremium(),
                logger
            );
        }
    }

    /**
     * 辅助方法：记录PV数据项日志。
     *
     * @param logger   日志记录器
     * @param title    日志标题
     * @param desc     日志描述
     * @param pvField  PV字段名
     * @param value    PV值
     * @param month    评估月
     */
    private void logItem(CalculationLogger logger, String title, String desc, String pvField, BigDecimal value, String month) {
        // 准备日志变量 Map
        Map<String, Object> vars = new HashMap<>();
        vars.put("PV字段", pvField);
        vars.put(pvField, value);
        vars.put("评估月", month);
        vars.put("数据来源", "PV原材料数据");

        // 记录日志项
        logger.logItem(
            title,
            desc,
            pvField,
            vars,
            value,
            "从PV原材料数据读取：" + pvField
        );
    }

    private BigDecimal getActualIacf(PolicyContract policy) {
        if (policy == null) return BigDecimal.ZERO;

        try {
            // Use selectList by PolicyNo to be robust against SQL null/empty string handling nuances
            QueryWrapper<SummaryIacfCost> query = new QueryWrapper<>();
            query.eq("\"保单号\"", policy.getPolicyNo());
            List<SummaryIacfCost> costs = summaryIacfCostMapper.selectList(query);

            if (costs == null || costs.isEmpty()) {
                log.warn("No IACF records found for Policy: [{}]", policy.getPolicyNo());
                return BigDecimal.ZERO;
            }

            SummaryIacfCost bestMatch = null;
            String targetCerti = policy.getCertiNo();
            boolean targetIsEmpty = targetCerti == null || targetCerti.trim().isEmpty();

            for (SummaryIacfCost c : costs) {
                if (c == null) continue;

                String dbEndorsement = c.getEndorsementNo();
                boolean dbIsEmpty = dbEndorsement == null || dbEndorsement.trim().isEmpty();

                if (targetIsEmpty) {
                    // We want a record with empty/null endorsement
                    if (dbIsEmpty) {
                        bestMatch = c;
                        break; // Exact match found
                    }
                } else {
                    // We want a specific endorsement
                    if (targetCerti.equals(dbEndorsement)) {
                        bestMatch = c;
                        break; // Exact match found
                    }
                }
            }

            // Fallback: If no exact match, but target is base policy (empty certi) and there is exactly one record, use it.
            if (bestMatch == null && targetIsEmpty && costs.size() == 1) {
                bestMatch = costs.get(0);
                if (bestMatch != null) {
                    log.info("IACF Fuzzy Match: Using single available record for base policy request. Endorsement in DB: [{}]", bestMatch.getEndorsementNo());
                }
            }

            if (bestMatch != null && bestMatch.getTotalCost() != null) {
                log.info("Using IACF Cost: {} for Policy: [{}]", bestMatch.getTotalCost(), policy.getPolicyNo());
                return BigDecimal.valueOf(bestMatch.getTotalCost());
            } else {
                log.warn("No suitable IACF record matched for Policy: [{}], Certi: [{}]", policy.getPolicyNo(), targetCerti);
            }

        } catch (Exception e) {
            log.error("查询实际IACF费用失败", e);
        }

        return BigDecimal.ZERO;
    }
}
