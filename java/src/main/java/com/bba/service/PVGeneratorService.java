package com.bba.service;

import com.bba.entity.PolicyContract;
import com.bba.entity.RateCurve;
import com.bba.entity.SummaryIacfCost;
import com.bba.mapper.SummaryIacfCostMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.CashFlow;
import com.bba.model.pv.PVSourceData;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * PV生成服务类。
 * 负责协调 CashFlowProjectorService 和 PVCalculatorService，
 * 为特定的评估日期生成 PVSourceData 对象。
 * 它是 Python 中 pv_calculator.py 逻辑的主要入口点。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PVGeneratorService {

    // 默认精算假设（已移除，不再使用默认值，如果数据库缺失则报错）
    // private static final BigDecimal DEFAULT_LOSS_RATIO = new BigDecimal("0.60");
    // private static final BigDecimal DEFAULT_INDIRECT_CLAIM_EXP_RATIO = BigDecimal.ZERO;
    // private static final BigDecimal DEFAULT_MAINT_RATIO = new BigDecimal("0.05");
    // private static final BigDecimal DEFAULT_RA_RATIO = new BigDecimal("0.06");

    // 注入数据加载服务，用于获取利率和假设
    private final DataLoaderService dataLoaderService;
    // 注入现金流预测服务，用于生成现金流
    private final CashFlowProjectorService cashFlowProjectorService;
    // 注入 PV 计算服务，用于执行具体的折现计算
    private final PVCalculatorService pvCalculatorService;
    // 注入实际费用查询 Mapper
    private final SummaryIacfCostMapper summaryIacfCostMapper;

    private BigDecimal getRaRatioSafe(Assumptions assumptions) {
        if (assumptions == null || assumptions.getRaRatio() == null) {
            return BigDecimal.ZERO;
        }
        return assumptions.getRaRatio();
    }

    /**
     * 为特定的评估日期（通常是期末 EOP）生成 PV 原材料数据。
     *
     * @param policy 保单数据
     * @param valuationDate 评估日期
     * @return PVSourceData 包含计算出的所有 PV 字段
     */
    public PVSourceData generatePVSourceData(PolicyContract policy, LocalDate valuationDate) {
        CalculationContext context = new CalculationContext();
        context.setPolicyData(policy);
        context.setPolicyNo(policy.getPolicyNo());
        context.setValuationDate(valuationDate);
        context.setYear(valuationDate.getYear());

        calculate(context);

        return context.getCurrentPvData();
    }

    public void calculate(CalculationContext context) {
        PolicyContract policy = context.getPolicyData();
        //评估日期
        LocalDate valuationDate = context.getValuationDate();

        // 记录日志：开始为指定保单和评估日期生成 PV 原材料数据
        log.info("正在为保单: {}, 评估日期: {} 生成 PV 原材料数据", policy.getPolicyNo(), valuationDate);

        // 创建新的 PVSourceData 对象，用于存储结果
        PVSourceData pvData = new PVSourceData();
        // 设置保单号
        pvData.setPolicyNo(policy.getPolicyNo());
        // 设置评估日期
        pvData.setValuationDate(valuationDate);
        // 设置评估月份（格式为 yyyyMM）
        pvData.setValuationMonth(valuationDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
        // 设置签单日期
        pvData.setUnderWriteDate(policy.getUnderWriteDate());

        // 检查是否为冲销保单 (批单) - 负保费
        if (policy.getSumPremiumNoTax() != null && policy.getSumPremiumNoTax().compareTo(BigDecimal.ZERO) < 0) {
            pvData.getMetadata().put("is_reversal_policy", "true");
            log.info("检测到保单 {} 为冲销保单 (负保费)", policy.getPolicyNo());
        }

        // 1. 准备日期
        // 获取保单的签单日期
        LocalDate uwDate = policy.getUnderWriteDate();
        // 判断是否为新业务年度（评估年份等于签单年份）
        boolean isNewBusiness = (valuationDate.getYear() == uwDate.getYear());
        // 获取当年年初日期（1月1日）
        LocalDate yearStart = LocalDate.of(valuationDate.getYear(), 1, 1);
        // 获取上一年年末日期（当年年初的前一天）
        LocalDate prevYearEnd = yearStart.minusDays(1);

        // 2. 获取利率曲线
        // 锁定曲线Lkd：基于签单日
        List<RateCurve> lockedRates = loadRates("locked", uwDate);

        // 当前曲线：基于评估日
        // 加载当前利率曲线Cur，用于期末计量
        List<RateCurve> currentRates = loadRates("current", valuationDate);

        // 上期当前利率 (LCU) (上年末) 用于 Beg_Lcu 字段
        List<RateCurve> lcuRates = null;
        if (!isNewBusiness) {
             lcuRates = loadRates("lcu", prevYearEnd);
        }

        // 3. 获取精算假设
        // 签单假设Ini（用于初始确认）
        String uwMonthStr = uwDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        Assumptions assumpUw = dataLoaderService.getAssumptions(policy.getClassCode(), uwMonthStr, policy.getValMethod());
        // 签单月精算假设缺失的兜底：
        if (assumpUw == null) {
            log.error("保单:{},未找到 {} 的签单假设", policy.getPolicyNo(), uwMonthStr);
            return;
        }

        // 当前评估日假设Eop（用于期末计量）
        String valMonthStr = valuationDate.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM"));
        Assumptions assumpVal = dataLoaderService.getAssumptions(policy.getClassCode(), valMonthStr, policy.getValMethod());
        if (assumpVal == null) {
            log.error("保单:{},未找到 {} 的评估时点假设", policy.getPolicyNo(), valMonthStr);
            return;
        }

        // 上年末假设（用于期初计量 - 仅非新单）
        // 初始化上年末假设为 null
        Assumptions assumpPrevYe = null;
        // 如果不是新业务年度（即存量保单）
        if (!isNewBusiness) {
            // 获取上年末的假设数据
            assumpPrevYe = dataLoaderService.getAssumptions(policy.getClassCode(), prevYearEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")), policy.getValMethod());
            // 如果找不到上年末假设
            if (assumpPrevYe == null) {
                log.error("保单:{},未找到 {} 的上年末评估时点假设", policy.getPolicyNo(), prevYearEnd.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
                return;
            }
        }

        // 初始确认现金流（基于签单假设）
        // 使用签单日假设预测现金流
        List<CashFlow> cfUw = null;
        if (assumpUw != null) {
            cfUw = cashFlowProjectorService.projectPolicyFlows(policy, assumpUw);
        } else {
            log.warn("由于假设缺失，跳过签单现金流预测。");
            cfUw = new ArrayList<>();
        }

        // 期末现金流（基于当前评估月假设）
        List<CashFlow> cfVal = null;
        if (assumpVal != null) {
            cfVal = cashFlowProjectorService.projectPolicyFlows(policy, assumpVal);
        } else {
            log.warn("由于假设缺失，跳过期末现金流预测。");
            cfVal = new ArrayList<>();
        }

        // 期初现金流（基于上年末假设）
        List<CashFlow> cfPrevYe = null;
        // 如果不是新业务年度
        if (!isNewBusiness) {
            // 使用上年末假设预测现金流，用于计算期初现值
            if (assumpPrevYe != null) {
                cfPrevYe = cashFlowProjectorService.projectPolicyFlows(policy, assumpPrevYe);
            } else {
                log.warn("由于假设缺失，跳过期初现金流预测。");
                cfPrevYe = new ArrayList<>();
            }
        }

        // 5. 计算现值
        // 获取 PVSourceData 对象中的字段映射 Map，用于直接填充计算结果
        Map<String, BigDecimal> fields = pvData.getPvFields();

        // --- A. 新业务初始确认 (Nb_Ini_Rec)、使用签单日所在的精算假设现金流 ---
        if (isNewBusiness) {
            calculateInitialRecognition(fields, cfUw, lockedRates, uwDate, valuationDate, policy, assumpUw);
        } else {
            zeroOutNbIni(fields);
        }

        // 新增业务使用评估时点精算假设现金流(Nb_Eop) ---
        if (isNewBusiness) {
            calculateEop(fields, "Nb", cfVal, lockedRates, currentRates, valuationDate, uwDate, policy, assumpVal);
        } else {
            zeroOutEop(fields, "Nb");
        }

        // --- B. 有效业务期初 (If_Bop) ---
        // 仅限非新业务年度+使用上年末精算假设现金流
        if (!isNewBusiness && cfPrevYe != null) {
            // 对于 BOP，通常使用锁定利率（签单日利率）。
            calculateBop(fields, cfPrevYe, lockedRates, lcuRates, yearStart, uwDate, assumpPrevYe);
        } else {
            zeroOutIfBop(fields);
        }

        // 组 4: If_Eop (仅限有效业务/存量)
        // 仅限非新业务年度+使用评估时点精算假设现金流
        if (!isNewBusiness) {
             calculateEop(fields, "If", cfVal, lockedRates, currentRates, valuationDate, uwDate, policy, assumpVal);
        } else {
             zeroOutEop(fields, "If");
        }

        // 调用 unpack 方法填充强类型字段
        // 将 Map 中的值解析并设置到具体的成员变量中
        pvData.unpack();

        context.setCurrentPvData(pvData);
        if (context.getPvSourceData() != null) {
            context.getPvSourceData().addData(pvData);
        }
    }

    private void zeroOutNbIni(Map<String, BigDecimal> fields) {
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt", BigDecimal.ZERO);

        fields.put("Pvfl_Nb_Ini_Cca_Rep_Wlk_Pre_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cca_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cca_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cca_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);

        fields.put("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Pre_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);
        fields.put("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
    }

    private void zeroOutEop(Map<String, BigDecimal> fields, String segment) {
        String[] suffixes = {"_Pre_Amt", "_Acq_Amt", "_Cla_Amt", "_Mtn_Amt", "_Rad_Amt"};
        String[] types = {"_Eop_Cca_Rep_Wlk", "_Eop_Cfa_Rep_Wlk", "_Eop_Cfa_Rep_Cur", "_Eop_Cca_Rep_Cur"};

        for (String type : types) {
            for (String suffix : suffixes) {
                fields.put("Pvfl_" + segment + type + suffix, BigDecimal.ZERO);
            }
        }
    }

    private void zeroOutIfBop(Map<String, BigDecimal> fields) {
        String[] suffixes = {"_Pre_Amt", "_Acq_Amt", "_Cla_Amt", "_Mtn_Amt", "_Rad_Amt"};
        String[] types = {"_If_Bop_Cca_Rep_Wlk", "_If_Bop_Cfa_Rep_Wlk"};

        for (String type : types) {
            for (String suffix : suffixes) {
                fields.put("Pvfl" + type + suffix, BigDecimal.ZERO);
            }
        }

        // Beg fields only have Cla/Mtn/Rad usually, but we zero all for safety
        String[] begSuffixes = {"_Cla_Amt", "_Mtn_Amt", "_Rad_Amt"};
        String[] begTypes = {"_If_Bop_Cfa_Beg_Lcu", "_If_Bop_Cfa_Beg_Wlk"};

        for (String type : begTypes) {
            for (String suffix : begSuffixes) {
                fields.put("Pvfl" + type + suffix, BigDecimal.ZERO);
            }
        }
    }

    /**
     * 加载利率曲线。
     *
     * @param type 曲线类型（未使用，仅作为标识）
     * @param date 曲线日期
     * @return 利率曲线列表
     */
    private List<RateCurve> loadRates(String type, LocalDate date) {
        // 调用数据加载服务，根据日期格式化字符串获取利率数据
        return dataLoaderService.getRates(date.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")));
    }

    /**
     * 新增业务，使用签单时点现金流现值计算
     * @param fields
     * @param cf 签单时点现金流
     * @param rates 锁定曲线Lkd：基于签单日
     * @param uwDate 签单日
     * @param valuationDate 评估日
     * @param policy 保单数据
     * @param assumptions 签单时点精算假设数据
     */
    private void calculateInitialRecognition(
            Map<String, BigDecimal> fields,
            List<CashFlow> cf,
            List<RateCurve> rates,
            LocalDate uwDate,
            LocalDate valuationDate,
            PolicyContract policy,
            Assumptions assumptions
    ) {
        // Premium 和 IACF 假设为期初一次性发生，直接取保单金额计算

        BigDecimal acq = pvCalculatorService.calculatePvInitialRecognition(cf, CashFlow::getIacf, true, rates, uwDate, uwDate);
        BigDecimal cla = pvCalculatorService.calculatePvInitialRecognition(cf, CashFlow::getClaims, false, rates, uwDate, uwDate);
        BigDecimal mtn = pvCalculatorService.calculatePvInitialRecognition(cf, CashFlow::getExpenses, false, rates, uwDate, uwDate);

        if (cla == null) cla = BigDecimal.ZERO;
        if (mtn == null) mtn = BigDecimal.ZERO;
        BigDecimal rad = (cla.add(mtn)).multiply(getRaRatioSafe(assumptions));

        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt", policy.getSumPremiumNoTax());
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt", acq);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt", cla);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt", mtn);
        fields.put("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt", rad);

        // 2 & 3. Nb_Ini_Cca_Rep_Wlk & Nb_Ini_Cfa_Rep_Wlk
        // Split at EOP (valuationDate)
        LocalDate startCalc = uwDate.isBefore(policy.getStartDate()) ? uwDate : policy.getStartDate();

        // Optimize: Single pass for both Cca and Cfa
        processSplitAndPut(fields, "Pvfl_Nb_Ini_Cca_Rep_Wlk", "Pvfl_Nb_Ini_Cfa_Rep_Wlk",
                cf, rates, startCalc, valuationDate, uwDate, assumptions);
    }

    /**
     * 计算期初（BOP）现值。
     */
    private void calculateBop(
            Map<String, BigDecimal> fields,
            List<CashFlow> cf,
            List<RateCurve> lockedRates,
            List<RateCurve> lcuRates,
            LocalDate bopDate,
            LocalDate uwDate,
            Assumptions assumptions
    ) {
        LocalDate startOfYear = LocalDate.of(bopDate.getYear(), 1, 1);
        LocalDate valDate = LocalDate.of(bopDate.getYear(), 12, 31);

        // 1 & 2. If_Bop_Cca_Rep_Wlk & If_Bop_Cfa_Rep_Wlk
        // Split at EOP (Dec 31)
        processSplitAndPut(fields, "Pvfl_If_Bop_Cca_Rep_Wlk", "Pvfl_If_Bop_Cfa_Rep_Wlk",
                cf, lockedRates, startOfYear, valDate, uwDate, assumptions);

        // 3. If_Bop_Cfa_Beg_Lcu
        // "Beg" fields include ALL flows from Jan 1 onwards (Cca + Cfa)
        // Treated as "Future" (Cfa) relative to Jan 1 (Start of Day)
        // So splitDate = Jan 1 minus 1 day
        LocalDate prevYe = startOfYear.minusDays(1);

        // Use lcuRates (Previous Year End Locked)
        // Only output Cfa part (which includes everything >= Jan 1)
        // Note: processSplitAndPut puts Cca to first prefix, Cfa to second prefix.
        // We only want the Cfa part here, mapped to "If_Bop_Cfa_Beg_Lcu".
        // And Cca part (before Jan 1) should be ignored or empty if startDate=Jan1.
        // Wait, startDate is Jan 1. SplitDate is Jan 1 minus 1.
        // So everything >= Jan 1 is > SplitDate -> Cfa.
        // Cca is empty.

        // Special helper or reuse?
        // We can reuse processSplitAndPut but pass null for Cca prefix?
        // Or just call pvCalculatorService directly.

        PVCalculatorService.PvSplitResult splitLcu = pvCalculatorService.calculatePvSplit(
                cf, CashFlow::getClaims, lcuRates, startOfYear, prevYe, prevYe); // valueExtractor placeholder

        // We need to do it for Pre, Acq, Cla, Mtn separately...
        // Let's use a helper that doesn't put to map immediately?
        // Or a helper that accepts target prefixes.

        processBegLcu(fields, "Pvfl_If_Bop_Cfa_Beg_Lcu", cf, lcuRates, startOfYear, prevYe, assumptions);

        // 4. If_Bop_Cfa_Beg_Wlk (Locked Rates)
        // Similar to Beg_Lcu but using Locked Rates (Wlk) and UW Date as base
        // Note: IfieService expects "Wlk" suffix for Beg fields (e.g. Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt)
        processBegLcu(fields, "Pvfl_If_Bop_Cfa_Beg_Wlk", cf, lockedRates, startOfYear, prevYe, assumptions, uwDate);
    }

    /**
     * 计算期末（EOP当前评估月精算假设）现值。
     */
    private void calculateEop(
            Map<String, BigDecimal> fields,
            String segment,
            List<CashFlow> cf,
            List<RateCurve> lockedRates,
            List<RateCurve> currentRates,
            LocalDate valuationDate,
            LocalDate uwDate,
            PolicyContract policy,
            Assumptions assumptions
    ) {
        LocalDate startCalc;
        if ("Nb".equals(segment)) {
            startCalc = uwDate.isBefore(policy.getStartDate()) ? uwDate : policy.getStartDate();
        } else {
            startCalc = LocalDate.of(valuationDate.getYear(), 1, 1);
        }

        // 签单日所在月份锁定利率曲线
        processSplitAndPut(fields, "Pvfl_" + segment + "_Eop_Cca_Rep_Wlk", "Pvfl_" + segment + "_Eop_Cfa_Rep_Wlk",
                cf, lockedRates, startCalc, valuationDate, uwDate, assumptions);
        //评估时点所在月份利率曲线
        processSplitAndPut(fields, null, "Pvfl_" + segment + "_Eop_Cfa_Rep_Cur",
                cf, currentRates, startCalc, valuationDate, valuationDate, assumptions);
    }

    /**
     * Helper to process split PV calculation and put to fields.
     */
    private void processSplitAndPut(
            Map<String, BigDecimal> fields,
            String ccaPrefix,
            String cfaPrefix,
            List<CashFlow> cf,
            List<RateCurve> rates,
            LocalDate startDate,
            LocalDate splitDate,
            LocalDate curveBaseDate,
            Assumptions assumptions
    ) {
        // Calculate splits for all components
        PVCalculatorService.PvSplitResult pre = pvCalculatorService.calculatePvSplit(cf, CashFlow::getPremium, rates, startDate, splitDate, curveBaseDate);
        PVCalculatorService.PvSplitResult acq = pvCalculatorService.calculatePvSplit(cf, CashFlow::getIacf, rates, startDate, splitDate, curveBaseDate);
        PVCalculatorService.PvSplitResult cla = pvCalculatorService.calculatePvSplit(cf, CashFlow::getClaims, rates, startDate, splitDate, curveBaseDate);
        PVCalculatorService.PvSplitResult mtn = pvCalculatorService.calculatePvSplit(cf, CashFlow::getExpenses, rates, startDate, splitDate, curveBaseDate);

        // Cca Part
        if (ccaPrefix != null) {
            putFields(fields, ccaPrefix, pre.getCcaAmount(), acq.getCcaAmount(), cla.getCcaAmount(), mtn.getCcaAmount(), assumptions);
        }

        // Cfa Part
        if (cfaPrefix != null) {
            putFields(fields, cfaPrefix, pre.getCfaAmount(), acq.getCfaAmount(), cla.getCfaAmount(), mtn.getCfaAmount(), assumptions);
        }
    }

    private void processBegLcu(
            Map<String, BigDecimal> fields,
            String prefix,
            List<CashFlow> cf,
            List<RateCurve> rates,
            LocalDate startDate,
            LocalDate splitDate, // Usually prevYe
            Assumptions assumptions
    ) {
        processBegLcu(fields, prefix, cf, rates, startDate, splitDate, assumptions, splitDate);
    }

    private void processBegLcu(
            Map<String, BigDecimal> fields,
            String prefix,
            List<CashFlow> cf,
            List<RateCurve> rates,
            LocalDate startDate,
            LocalDate splitDate,
            Assumptions assumptions,
            LocalDate curveBaseDate
    ) {
        // We only care about Cfa (Future relative to splitDate)
        PVCalculatorService.PvSplitResult cla = pvCalculatorService.calculatePvSplit(cf, CashFlow::getClaims, rates, startDate, splitDate, curveBaseDate);
        PVCalculatorService.PvSplitResult mtn = pvCalculatorService.calculatePvSplit(cf, CashFlow::getExpenses, rates, startDate, splitDate, curveBaseDate);

        // Beg fields only have Cla/Mtn/Rad usually in Python output logic?
        // Original code:
        // fields.put("Pvfl_If_Bop_Cfa_Beg_Lcu_Cla_Amt", claLcu);
        // ...
        // fields.put("Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt", claBegWlk);

        BigDecimal claAmt = cla.getCfaAmount();
        BigDecimal mtnAmt = mtn.getCfaAmount();
        BigDecimal radAmt = (claAmt.add(mtnAmt)).multiply(getRaRatioSafe(assumptions));

        fields.put(prefix + "_Cla_Amt", claAmt);
        fields.put(prefix + "_Mtn_Amt", mtnAmt);
        fields.put(prefix + "_Rad_Amt", radAmt);

        // Should we set Pre/Acq to zero?
        // Original code didn't set them for Beg fields explicitly or set them to 0 in zeroOutIfBop.
    }

    private void putFields(
            Map<String, BigDecimal> fields,
            String prefix,
            BigDecimal pre,
            BigDecimal acq,
            BigDecimal cla,
            BigDecimal mtn,
            Assumptions assumptions
    ) {
        if (cla == null) cla = BigDecimal.ZERO;
        if (mtn == null) mtn = BigDecimal.ZERO;
        BigDecimal rad = (cla.add(mtn)).multiply(getRaRatioSafe(assumptions));

        fields.put(prefix + "_Pre_Amt", pre != null ? pre : BigDecimal.ZERO);
        fields.put(prefix + "_Acq_Amt", acq != null ? acq : BigDecimal.ZERO);
        fields.put(prefix + "_Cla_Amt", cla);
        fields.put(prefix + "_Mtn_Amt", mtn);
        fields.put(prefix + "_Rad_Amt", rad);
    }
}
