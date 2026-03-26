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
        String eopMonthStr = context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);

        if (context.getPvSourceData() != null) {

            if (pvData != null) {
                // 1.1 初始确认预期当年 IACF
                // 公式：-[新增合同-初始确认-预期当期-预期IACF-期末现值(Wlk)]
                BigDecimal rawInitExpectedCurIacf = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
                initExpectedCurIacf = rawInitExpectedCurIacf.negate();
            }
        }

        logger.logItem(
                "初始确认预期当年IACF",
                "[Step 1.1] 初始确认时预期的当年IACF流出（期末现值）",
                "-[新增合同-初始确认-预期当期-预期IACF-期末现值(Wlk)]",
                new HashMap<String, Object>(),
                initExpectedCurIacf
        );


        // 1.4 当年新增总 IACF 期末现值
        BigDecimal totalNbIacfEndPv = initExpectedCurIacf;
        Map<String, Object> meta14 = new HashMap<>();
        meta14.put("初始确认预期当年IACF", initExpectedCurIacf);

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
            if (denominatorIacf.compareTo(BigDecimal.ZERO) == 0) {
                // 如果当期释放和期末剩余都为 0，说明保单已经完全过保或覆盖单元为 0，摊销比例设为 0 或者 1（看具体业务，通常为 0，或者如果还有余额应该一次性摊销）
                // 稳妥起见，设为 0，避免除零异常
                iacfAmortRatio = BigDecimal.ZERO;
                if (logger != null) {
                    logger.logText("⚠️ 警告: 计算获取费用摊销比例时分母为 0 (cuReleasedIacf=0, cuRemainingIacf=0)，摊销比例默认设置为 0");
                }
            } else {
                iacfAmortRatio = cuReleasedIacf.divide(denominatorIacf, 10, BigDecimal.ROUND_HALF_UP);
            }

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

        // 2.4 当年新增 IACF (使用名义值)
        BigDecimal expectedIacfNominal = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
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

        // 2.8 摊销的 IACF
        BigDecimal iacfBalanceBase = bopIacf
                .add(initExpectedCurIacf);

        // [User Request] 摊销的IACF应该是个负数
        BigDecimal iacfAmortAmount = iacfBalanceBase.multiply(iacfAmortRatio).negate();
        context.setIacfAmortAmount(iacfAmortAmount);

        Map<String, Object> metaAmort = new HashMap<>();
        metaAmort.put("Base Sum", iacfBalanceBase);
        metaAmort.put("Ratio", iacfAmortRatio);
        logger.logItem(
                "摊销的IACF",
                "[Step 2.8] 本期摊销计入费用的金额（负数表示减少资产）",
                "-(Sum(Balance+Additions+Var) * Ratio + ExpAdj)",
                metaAmort,
                iacfAmortAmount
        );

        // 2.9 期末待摊 IACF 余额
        // Balance = Base + ExpAdj + Amort (Amort is negative)
        BigDecimal eopIacfBalance = iacfBalanceBase.add(iacfAmortAmount);


        context.setEopIacfBalance(eopIacfBalance);

        Map<String, Object> metaEop = new HashMap<>();
        metaEop.put("Base", iacfBalanceBase);
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
