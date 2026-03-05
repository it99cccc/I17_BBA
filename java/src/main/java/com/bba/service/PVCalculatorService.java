package com.bba.service;

import com.bba.entity.RateCurve;
import com.bba.model.CashFlow;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 现值（PV）计算服务类。
 * 负责执行各种场景下的现金流折现计算，包括精确日期的折现、
 * 初始确认时的折现以及期初/期末的折现逻辑。
 * 复刻了Python版本中 pv_calculator.py 的核心数学逻辑。
 */
@Service // 声明为Spring服务组件
@Slf4j // 启用日志记录
public class PVCalculatorService {

    // 定义高精度的数学计算上下文（128位十进制），用于确保金融计算的精度
    private static final MathContext MC = MathContext.DECIMAL128;

    /**
     * 内部类：折现因子缓存。
     * 预计算累积折现因子，优化 O(N^2) 为 O(N)。
     * factors[i] 存储 1 / ((1+r1)*(1+r2)*...*(1+ri))
     */
    private static class DiscountFactorCache {
        private final BigDecimal[] cumulativeFactors;
        private final BigDecimal maxRateFactor; // 1 / (1 + r_max)
        private final int maxTerm;

        public DiscountFactorCache(List<RateCurve> rates) {
            if (rates == null || rates.isEmpty()) {
                this.cumulativeFactors = new BigDecimal[0];
                this.maxRateFactor = BigDecimal.ONE;
                this.maxTerm = 0;
                return;
            }

            // 找出最大期限
            this.maxTerm = rates.stream().mapToInt(RateCurve::getTermMonth).max().orElse(0);

            // 构建期限->利率映射
            Map<Integer, BigDecimal> ratesMap = new HashMap<>();
            for (RateCurve rc : rates) {
                ratesMap.put(rc.getTermMonth(), rc.getForwardDisrateValue());
            }

            // 预计算累积因子
            // 索引 0 未使用，索引 t 存储期限 t 的累积因子
            this.cumulativeFactors = new BigDecimal[maxTerm + 1];
            this.cumulativeFactors[0] = BigDecimal.ONE;

            BigDecimal currentCumulative = BigDecimal.ONE;
            for (int t = 1; t <= maxTerm; t++) {
                BigDecimal r = ratesMap.getOrDefault(t, BigDecimal.ZERO);
                // 因子 = 1 / (1 + r)
                BigDecimal stepFactor = BigDecimal.ONE.divide(BigDecimal.ONE.add(r), MC);
                currentCumulative = currentCumulative.multiply(stepFactor, MC);
                this.cumulativeFactors[t] = currentCumulative;
            }

            // 计算最大期限的单步因子，用于外推
            BigDecimal rMax = ratesMap.getOrDefault(maxTerm, BigDecimal.ZERO);
            this.maxRateFactor = BigDecimal.ONE.divide(BigDecimal.ONE.add(rMax), MC);
        }

        /**
         * 获取从 startTerm 到 endTerm 的累积折现因子。
         * 计算公式：Product(1/(1+r_t)) for t from startTerm to endTerm.
         */
        public BigDecimal getDiscountFactorRange(int startTerm, int endTerm) {
            if (startTerm > endTerm) return BigDecimal.ONE;
            if (maxTerm == 0) return BigDecimal.ONE;

            // 处理 startTerm 超出范围的情况
            if (startTerm > maxTerm) {
                int extraMonths = endTerm - startTerm + 1;
                return maxRateFactor.pow(extraMonths, MC);
            }

            // 处理 endTerm 超出范围的情况：分段计算
            if (endTerm > maxTerm) {
                // 第一段：startTerm 到 maxTerm
                BigDecimal part1 = getDiscountFactorRange(startTerm, maxTerm);
                // 第二段：maxTerm+1 到 endTerm (外推)
                int extraMonths = endTerm - maxTerm;
                BigDecimal part2 = maxRateFactor.pow(extraMonths, MC);
                return part1.multiply(part2, MC);
            }

            // 正常范围：利用累积因子相除
            // 结果 = Cumulative[endTerm] / Cumulative[startTerm - 1]
            return cumulativeFactors[endTerm].divide(cumulativeFactors[startTerm - 1], MC);
        }

        /**
         * 获取单期利率 r_t (用于初始确认的半月折现等特殊逻辑)
         */
        public BigDecimal getRate(int term) {
             if (maxTerm == 0) return BigDecimal.ZERO;
             if (term > maxTerm) term = maxTerm;
             if (term <= 0) return BigDecimal.ZERO;

             // 从累积因子中反推利率：
             // factor[t] = factor[t-1] / (1+r) => 1+r = factor[t-1]/factor[t] => r = (factor[t-1]/factor[t]) - 1
             BigDecimal fPrev = cumulativeFactors[term-1];
             BigDecimal fCurr = cumulativeFactors[term];
             if (fCurr.compareTo(BigDecimal.ZERO) == 0) return BigDecimal.ZERO;
             return fPrev.divide(fCurr, MC).subtract(BigDecimal.ONE);
        }
    }

    /**
     * PV 分割计算结果容器
     */
    @Getter
    public static class PvSplitResult {
        private BigDecimal ccaAmount = BigDecimal.ZERO; // 当前/过去 (不计息)
        private BigDecimal cfaAmount = BigDecimal.ZERO; // 未来 (折现)

        public void addCca(BigDecimal val) {
            if (val != null) ccaAmount = ccaAmount.add(val);
        }
        public void addCfa(BigDecimal val) {
             if (val != null) cfaAmount = cfaAmount.add(val);
        }
    }

    /**
     * 计算两个日期之间的月数差。
     */
    private int getMonthDiff(LocalDate d1, LocalDate d2) {
        return (d1.getYear() - d2.getYear()) * 12 + (d1.getMonthValue() - d2.getMonthValue());
    }

    /**
     * 一次性计算 Cca (当期不计息) 和 Cfa (未来折现) 的 PV。
     * 优化了性能，避免多次遍历。
     *
     * @param startDate  统计起始日期（之前的现金流将被忽略）。如果为null，则不限制起始日期。
     * @param splitDate  分割日期（<= splitDate 为 Cca, > splitDate 为 Cfa）
     */
    public PvSplitResult calculatePvSplit(
            List<CashFlow> cashFlows,
            Function<CashFlow, BigDecimal> valueExtractor,
            List<RateCurve> rates,
            LocalDate startDate,
            LocalDate splitDate,
            LocalDate curveBaseDate
    ) {
        PvSplitResult result = new PvSplitResult();
        DiscountFactorCache cache = new DiscountFactorCache(rates);

        boolean isCurrentCurve = curveBaseDate.isEqual(splitDate);

        // 评估日期调整（用于月差计算）
        LocalDate valDateForCalc = splitDate.getDayOfMonth() == 1 ? splitDate.minusDays(1) : splitDate;

        int valYear = splitDate.getYear();
        int valMonth = splitDate.getMonthValue();

        // 预计算 Locked Curve 的 idxVal
        int idxVal = getMonthDiff(valDateForCalc, curveBaseDate);

        for (CashFlow cf : cashFlows) {
            BigDecimal amount = valueExtractor.apply(cf);
            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;

            LocalDate cfDate = cf.getDate();

            // 过滤掉起始日期之前的现金流
            if (startDate != null && cfDate.isBefore(startDate)) {
                continue;
            }

            // 1. 判断属于 Cca (当期/过去) 还是 Cfa (未来)
            boolean isCca;
            if (cf.getYear() < valYear || (cf.getYear() == valYear && cf.getMonth() <= valMonth)) {
                isCca = true;
            } else {
                isCca = !cfDate.isAfter(splitDate);
            }

            if (isCca) {
                // Cca 逻辑：直接累加金额（不计息）
                result.addCca(amount);
            } else {
                // Cfa 逻辑：折现
                BigDecimal factor;
                if (isCurrentCurve) {
                    // 当前曲线: Term = MonthDiff(CF, Val)
                    int monthsDiff = getMonthDiff(cfDate, valDateForCalc);
                    if (monthsDiff > 0) {
                        factor = cache.getDiscountFactorRange(1, monthsDiff);
                    } else {
                         factor = BigDecimal.ONE;
                    }
                } else {
                    // 锁定曲线
                    int idxCf = getMonthDiff(cfDate, curveBaseDate);
                    int startStep = Math.max(1, idxVal + 2);
                    int endStep = idxCf + 1;

                    if (idxCf == idxVal) {
                        factor = cache.getDiscountFactorRange(idxCf + 1, idxCf + 1);
                    } else {
                        factor = cache.getDiscountFactorRange(startStep, endStep);
                    }
                }
                result.addCfa(amount.multiply(factor, MC));
            }
        }
        return result;
    }

    /**
     * 基于日期差异的精确 PV 计算。
     * 使用缓存优化性能。
     */
    public BigDecimal calculatePvExact(
            List<CashFlow> cashFlows,
            Function<CashFlow, BigDecimal> valueExtractor,
            List<RateCurve> rates,
            LocalDate valuationDate,
            LocalDate curveBaseDate
    ) {
        BigDecimal totalPv = BigDecimal.ZERO;
        DiscountFactorCache cache = new DiscountFactorCache(rates);

        boolean isCurrentCurve = curveBaseDate.isEqual(valuationDate);
        LocalDate valDateForCalc = valuationDate.getDayOfMonth() == 1 ? valuationDate.minusDays(1) : valuationDate;
        int idxVal = getMonthDiff(valDateForCalc, curveBaseDate);

        for (CashFlow cf : cashFlows) {
            BigDecimal amount = valueExtractor.apply(cf);
            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;

            LocalDate cfDate = cf.getDate();
            if (cfDate.isEqual(valuationDate)) {
                totalPv = totalPv.add(amount);
                continue;
            }

            BigDecimal factor;
            if (isCurrentCurve) {
                int monthsDiff = getMonthDiff(cfDate, valDateForCalc);
                if (monthsDiff > 0) {
                    factor = cache.getDiscountFactorRange(1, monthsDiff);
                } else {
                    // 累积: 1 / Factor
                    BigDecimal discount = cache.getDiscountFactorRange(1, Math.abs(monthsDiff));
                    factor = BigDecimal.ONE.divide(discount, MC);
                }
            } else {
                int idxCf = getMonthDiff(cfDate, curveBaseDate);
                if (cfDate.isAfter(valuationDate)) {
                    // 未来 (折现)
                     if (idxCf == idxVal) {
                        factor = cache.getDiscountFactorRange(idxCf + 1, idxCf + 1);
                    } else {
                        int startStep = Math.max(1, idxVal + 2);
                        int endStep = idxCf + 1;
                        factor = cache.getDiscountFactorRange(startStep, endStep);
                    }
                } else {
                    // 过去 (累积)
                    // 原逻辑: startStep = idxCf + 1; endStep = idxVal; multiply(1+r)
                    // 此处: 除以折现因子
                    int startStep = Math.max(1, idxCf + 1);
                    int endStep = idxVal;
                    BigDecimal discount = cache.getDiscountFactorRange(startStep, endStep);
                    factor = BigDecimal.ONE.divide(discount, MC);
                }
            }
            totalPv = totalPv.add(amount.multiply(factor, MC));
        }
        return totalPv;
    }

    /**
     * 初始确认 PV 计算 (针对单点金额，如期初一次性保费/费用)。
     * 避免构建和遍历现金流列表，直接计算。
     */
    public BigDecimal calculatePvInitialRecognitionSingle(
            BigDecimal amount,
            List<RateCurve> rates
    ) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }

        // 初始确认通常假设发生在期初或当月中
        // 如果是期初一次性支付，折现因子通常为 1.0 (如果基于签单日曲线且在签单日)
        // 或者如果逻辑是“折现半个月”，则计算半个月因子

        // 根据 calculatePvInitialRecognition 的逻辑：
        // if (isUwMonth) { if (isPremiumOrIacf) factor = 1.0; ... }
        // 所以对于 Premium/IACF，如果发生在签单月，因子就是 1.0。

        return amount; // 乘以 1.0
    }

    /**
     * 初始确认 PV 计算 (针对现金流列表，如赔付/维持费用)。
     */
    public BigDecimal calculatePvInitialRecognition(
            List<CashFlow> cashFlows,
            Function<CashFlow, BigDecimal> valueExtractor,
            boolean isPremiumOrIacf,
            List<RateCurve> rates,
            LocalDate curveBaseDate,
            LocalDate uwDate
    ) {
        BigDecimal totalPv = BigDecimal.ZERO;
        DiscountFactorCache cache = new DiscountFactorCache(rates);

        int uwYear = uwDate.getYear();
        int uwMonth = uwDate.getMonthValue();

        //遍历保单保障期到止期内发生的现金流list
        for (CashFlow cf : cashFlows) {
            BigDecimal amount = valueExtractor.apply(cf);
            if (amount.compareTo(BigDecimal.ZERO) == 0) continue;
            //现金流日期
            LocalDate cfDate = cf.getDate();
            boolean isUwMonth = (cf.getYear() == uwYear && cf.getMonth() == uwMonth);
            BigDecimal factor;

            if (isUwMonth) {
                if (isPremiumOrIacf) {
                    factor = BigDecimal.ONE;
                } else {
                    // 折现半个月: 1 / (1 + r1/2)
                    BigDecimal r1 = cache.getRate(1);
                    factor = BigDecimal.ONE.divide(BigDecimal.ONE.add(r1.divide(new BigDecimal("2"), MC)), MC);
                }
            } else {
                int idxCf = getMonthDiff(cfDate, curveBaseDate);
                if (idxCf <= 0) {
                    factor = BigDecimal.ONE;
                } else {
                    // 未来: 先折半个月，再折满月
                    // 因子 = (1 / (1 + r1/2)) * Product(1/(1+rt)) for t=2..idxCf+1
                    BigDecimal r1 = cache.getRate(1);
                    BigDecimal halfMonthFactor = BigDecimal.ONE.divide(BigDecimal.ONE.add(r1.divide(new BigDecimal("2"), MC)), MC);

                    // 剩余月份: 2 到 idxCf+1
                    BigDecimal remainingFactor = cache.getDiscountFactorRange(2, idxCf + 1);
                    factor = halfMonthFactor.multiply(remainingFactor, MC);
                }
            }
            totalPv = totalPv.add(amount.multiply(factor, MC));
        }
        return totalPv;
    }
}
