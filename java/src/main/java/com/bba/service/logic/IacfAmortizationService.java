package com.bba.service.logic;

import com.bba.model.CalculationContext;
import com.bba.model.PolicyState;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * IACF 摊销服务类。
 * 负责计算保险获取现金流（IACF）的摊销。
 */
@Service
@RequiredArgsConstructor
public class IacfAmortizationService {

    private final CoverageUnitsService coverageUnitsService;

    /**
     * 执行 IACF 摊销计算。
     *
     * @param context 计算上下文
     * @param logger  日志记录器
     */
    public void run(CalculationContext context, CalculationLogger logger) {
        logger.logSection("Part 6: IACF 摊销 (IACF Amortization)");

        // ==========================================================================================
        // 1. 当年新增合同总 IACF 期末现值
        // ==========================================================================================

        BigDecimal initExpectedCurIacf = BigDecimal.ZERO;
        BigDecimal endExpectedFutIacf = BigDecimal.ZERO;

        if (context.getPvSourceData() != null) {
            String eopMonthStr = context.getValMonthStr();
            PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);

            if (pvData != null) {
                // 1.1 初始确认预期当年 IACF
                // 公式：-[新增合同-初始确认-预期当期-预期IACF-期末现值(Wlk)]
                BigDecimal rawInitExpectedCurIacf = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
                initExpectedCurIacf = rawInitExpectedCurIacf.negate();

                // 1.3 期末预期未来 IACF 现值
                // 公式：-[新增合同-初始确认-预期未来-IACF-期末现值(Wlk)]
                BigDecimal rawEndExpectedFutIacf = pvData.getField("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
                endExpectedFutIacf = rawEndExpectedFutIacf.negate();
            }
        }

        logger.logItem(
                "初始确认预期当年IACF",
                "[Step 1.1] 初始确认时预期的当年IACF流出（期末现值）",
                "-[新增合同-初始确认-预期当期-预期IACF-期末现值(Wlk)]",
                new HashMap<String, Object>(),
                initExpectedCurIacf
        );

        // 1.2 当年 IACF 计息
        BigDecimal iacfInterestCurrent = BigDecimal.ZERO;
        logger.logItem(
                "当年IACF计息",
                "[Step 1.2] 当年新增IACF产生的利息",
                "0 (不考虑时间价值)",
                new HashMap<String, Object>(),
                iacfInterestCurrent
        );

        logger.logItem(
                "期末预期未来IACF现值",
                "[Step 1.3] 期末时预期的未来IACF流出（期末现值）",
                "-[新增合同-初始确认-预期未来-IACF-期末现值(Wlk)]",
                new HashMap<String, Object>(),
                endExpectedFutIacf
        );

        // 1.4 当年新增总 IACF 期末现值
        BigDecimal totalNbIacfEndPv = initExpectedCurIacf.add(iacfInterestCurrent).add(endExpectedFutIacf);
        Map<String, Object> meta14 = new HashMap<>();
        meta14.put("初始确认预期当年IACF", initExpectedCurIacf);
        meta14.put("当年IACF计息", iacfInterestCurrent);
        meta14.put("期末预期未来IACF现值", endExpectedFutIacf);
        logger.logItem(
                "当年新增总IACF期末现值",
                "[Step 1.4] 当年新增合同相关的IACF总额（期末现值）",
                "Sum(初始确认预期当年IACF, 当年IACF计息, 期末预期未来IACF现值)",
                meta14,
                totalNbIacfEndPv
        );

        // ==========================================================================================
        // 2. IACF 摊销
        // ==========================================================================================

        // 2.1 IACF 摊销比例
        BigDecimal iacfAmortRatio = BigDecimal.ZERO;
        if (context.getPolicies() != null && !context.getPolicies().isEmpty()) {
            LocalDate valuationDate = context.getEopDate() != null ? context.getEopDate() : LocalDate.of(context.getYear(), 12, 31);
            if (valuationDate == null) {
                valuationDate = LocalDate.of(context.getYear(), 12, 31);
            }
            LocalDate startOfYear = LocalDate.of(valuationDate.getYear(), 1, 1);
            boolean isInitialYear = context.isInitialYear();

            BigDecimal cuReleasedIacf = coverageUnitsService.calculateCoverageUnitsReleased(
                    context.getPolicies(), valuationDate, startOfYear, null, isInitialYear
            );
            BigDecimal cuRemainingIacf = coverageUnitsService.calculateCoverageUnitsRemaining(
                    context.getPolicies(), valuationDate, null
            );
            BigDecimal denominatorIacf = cuReleasedIacf.add(cuRemainingIacf);
            //摊销比例
            iacfAmortRatio = cuReleasedIacf.divide(denominatorIacf, 10, BigDecimal.ROUND_HALF_UP);

            //TODO IACF摊销比例可以复用CSM的
            Map<String, Object> metaRatio = new HashMap<>();
            metaRatio.put("CU_released", cuReleasedIacf);
            metaRatio.put("CU_remaining", cuRemainingIacf);
            metaRatio.put("Denominator", denominatorIacf);
            logger.logItem(
                    "IACF摊销比例",
                    "[Step 2.1] 本期摊销的比例",
                    "CU_released / (CU_released + CU_remaining)",
                    metaRatio,
                    iacfAmortRatio,
                    "独立计算，不直接复用CSM摊销比例"
            );
        }
        context.setIacfAmortRatio(iacfAmortRatio); // Assuming setter exists or need to add map support

        // 2.2 年初待摊 IACF 余额
        BigDecimal bopIacf = context.getBopIacf() != null ? context.getBopIacf() : BigDecimal.ZERO;
        if (context.getBopIacf() == null) {
            context.setBopIacf(bopIacf);
        }
        logger.logItem(
                "年初待摊IACF余额",
                "[Step 2.2] 期初尚未摊销的获取费用余额",
                "BOP Balance",
                new HashMap<>(),
                bopIacf
        );

        // 2.3 年初待摊 IACF 计息
        BigDecimal iacfInterestBop = BigDecimal.ZERO;
        logger.logItem(
                "年初待摊IACF计息",
                "[Step 2.3] 期初余额产生的利息",
                "0 (不考虑时间价值)",
                new HashMap<>(),
                iacfInterestBop
        );

        // 2.4 当年新增 IACF (使用名义值)
        BigDecimal expectedIacfNominal = context.getExpectedIacfNominal() != null ? context.getExpectedIacfNominal() : BigDecimal.ZERO;
        context.setNbIacfAddition(expectedIacfNominal);
        Map<String, Object> metaNb = new HashMap<>();
        metaNb.put("Expected IACF", expectedIacfNominal);
        logger.logItem(
                "当年新增IACF",
                "[Step 2.4] 本期新增业务带来的获取费用 (名义值)",
                "Expected IACF Nominal (不考虑时间价值)",
                metaNb,
                context.getNbIacfAddition()
        );

        // 2.5 当年新增 IACF 计息
        context.setIacfInterestNb(BigDecimal.ZERO);
        logger.logItem(
                "当年新增IACF计息",
                "[Step 2.5] 新增IACF产生的利息",
                "0 (不考虑时间价值)",
                new HashMap<>(),
                context.getIacfInterestNb()
        );

        // 2.6 IACF 变化
        BigDecimal iacfChange = context.getIacfVar() != null ? context.getIacfVar() : BigDecimal.ZERO;
        context.setIacfChange(iacfChange);
        Map<String, Object> metaChange = new HashMap<>();
        metaChange.put("Actual", context.getActualIacfIncurred());
        metaChange.put("Expected", context.getExpectedIacfNominal());
        logger.logItem(
                "IACF变化",
                "[Step 2.6] 实际与预期获取费用的差异",
                "Actual IACF - Expected IACF",
                metaChange,
                iacfChange
        );

        // 2.7 IACF 经验调整
        BigDecimal iacfExpAdj = BigDecimal.ZERO;
        logger.logItem(
                "IACF经验调整",
                "[Step 2.7] 其他经验调整项",
                "Manual Input",
                new HashMap<>(),
                iacfExpAdj
        );

        // 2.8 摊销的 IACF
        BigDecimal iacfBalanceBase = bopIacf.add(iacfInterestBop)
                .add(context.getNbIacfAddition())
                .add(context.getIacfInterestNb())
                .add(iacfChange);

        // [User Request] 摊销的IACF应该是个负数
        BigDecimal iacfAmortAmount = iacfBalanceBase.multiply(iacfAmortRatio).add(iacfExpAdj).negate();
        context.setIacfAmortAmount(iacfAmortAmount);

        Map<String, Object> metaAmort = new HashMap<>();
        metaAmort.put("Base Sum", iacfBalanceBase);
        metaAmort.put("Ratio", iacfAmortRatio);
        metaAmort.put("ExpAdj", iacfExpAdj);
        logger.logItem(
                "摊销的IACF",
                "[Step 2.8] 本期摊销计入费用的金额（负数表示减少资产）",
                "-(Sum(Balance+Additions+Var) * Ratio + ExpAdj)",
                metaAmort,
                iacfAmortAmount
        );

        // 2.9 期末待摊 IACF 余额
        // Balance = Base + ExpAdj + Amort (Amort is negative)
        BigDecimal eopIacfBalance = iacfBalanceBase.add(iacfExpAdj).add(iacfAmortAmount);


        context.setEopIacfBalance(eopIacfBalance);

        Map<String, Object> metaEop = new HashMap<>();
        metaEop.put("Base", iacfBalanceBase);
        metaEop.put("ExpAdj", iacfExpAdj);
        metaEop.put("Amortization", iacfAmortAmount);
        logger.logItem(
                "期末待摊IACF余额",
                "[Step 2.9] 期末剩余的待摊获取费用",
                "Sum(Base + ExpAdj + Amortization)",
                metaEop,
                eopIacfBalance
        );
    }
}
