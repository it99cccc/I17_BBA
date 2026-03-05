package com.bba.service.logic;

import com.bba.entity.RateCurve;
import com.bba.model.CohortState;
import com.bba.service.DataLoaderService;
import com.bba.util.CalculationLogger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 利率管理服务类。
 * 负责获取利率曲线、计算即期利率以及更新加权锁定利率。
 * 对应 IFRS 17 准则中关于折现率的处理，特别是锁定利率（Locked Rate）的计算和更新。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RatesManagerService {

    private static final MathContext MC = MathContext.DECIMAL128;

    // 注入数据加载服务，用于从数据源获取利率数据
    private final DataLoaderService dataLoaderService;

    /**
     * 获取指定月份的利率曲线列表。
     *
     * @param valMonthStr 评估月份字符串（格式：yyyyMM）
     * @return 利率曲线列表
     */
    public List<RateCurve> getRates(String valMonthStr) {
        // 调用数据加载服务获取指定月份的利率数据
        return dataLoaderService.getRates(valMonthStr);
    }

    /**
     * 获取指定月份和类型的利率曲线列表。
     * 目前简化处理，忽略曲线类型，直接返回该月的唯一曲线。
     *
     * @param valMonthStr 评估月份字符串（格式：yyyyMM）
     * @param curveType   曲线类型（如 "risk_free", "illiquidity_premium" 等）
     * @return 利率曲线列表
     */
    public List<RateCurve> getRates(String valMonthStr, String curveType) {
        // Currently ignoring curveType as we only have one curve per month in this simplified version
        // 目前忽略 curveType，因为在这个简化版本中我们每个月只有一条曲线
        // 直接调用单参数的 getRates 方法
        return getRates(valMonthStr);
    }

    /**
     * 计算即期利率（Spot Rate）。
     * 通常用于新业务初始确认时的折现率。
     *
     * @param ratesDf 利率曲线列表（折现因子）
     * @return 即期利率
     */
    public BigDecimal calculateSpotRate(List<RateCurve> ratesDf) {
        // 检查利率列表是否为空或 null
        if (ratesDf == null || ratesDf.isEmpty()) {
            // 如果为空，返回 0
            log.warn("calculateSpotRate: ratesDf is null or empty");
            return BigDecimal.ZERO;
        }
        // Simplify: use the first term's rate
        // 简化处理：使用第一期（通常是 Term=0 或 Term=1）的利率作为即期利率
        // 这里假设 ratesDf 已经按期限排序，取第一个元素的远期折现率值
        RateCurve firstCurve = ratesDf.get(0);
        log.info("calculateSpotRate: First curve point - Term: {}, Value: {}", firstCurve.getTermMonth(), firstCurve.getForwardDisrateValue());
        return firstCurve.getForwardDisrateValue();
    }

    /**
     * 更新合同组的加权锁定利率（Weighted Locked Rate）。
     * 当有新业务加入合同组时，需要根据保费权重重新计算锁定利率。
     * 公式：R_new = (R_old * W_old + R_spot * W_new) / (W_old + W_new)
     *
     * @param cohortState       合同组状态，包含当前的加权锁定利率和累计保费
     * @param newSpotRate       新业务的即期利率
     * @param newWrittenPremium 新业务的签单保费
     * @param logger            计算日志记录器（可选，可为 null）
     * @return 更新后的加权锁定利率
     */
    public BigDecimal updateWeightedLockedRate(CohortState cohortState, BigDecimal newSpotRate, BigDecimal newWrittenPremium, CalculationLogger logger) {
        // 获取当前的加权锁定利率 (R_old)
        BigDecimal rOld = cohortState.getWeightedLockedRate();
        // 获取当前的累计签单保费 (W_old)
        BigDecimal wOld = cohortState.getTotalWrittenPremium();
        
        // 新业务的即期利率 (R_spot)
        BigDecimal rSpot = newSpotRate;
        // 新业务的签单保费 (W_new)
        BigDecimal wNew = newWrittenPremium;
        
        // 定义新的加权锁定利率变量 (R_new)
        BigDecimal rNew;
        
        // 如果旧的累计保费为 0（说明是该合同组的第一张保单）
        if (wOld.compareTo(BigDecimal.ZERO) == 0) {
            // 直接使用新业务的即期利率作为加权锁定利率
            rNew = rSpot;
            // 如果提供了日志记录器，记录相关日志
            if (logger != null) {
                // 准备日志变量 Map
                Map<String, Object> vars = new HashMap<>();
                vars.put("R_spot", rSpot); // 记录即期利率
                vars.put("W_new", wNew);   // 记录新单保费
                // 记录日志项：首单更新
                logger.logItem(
                    "加权初始确认利率更新（首单）",
                    "[Sec 1.5.2] 第一张单，直接使用即期利率",
                    "R_new = R_spot",
                    vars,
                    rNew,
                    "期初权重为0，无需加权"
                );
            }
        } else {
            // 如果旧保费不为 0，则进行加权平均计算
            // 分子 = 旧利率 * 旧保费 + 新利率 * 新保费
            BigDecimal numerator = rOld.multiply(wOld).add(rSpot.multiply(wNew));
            // 分母 = 旧保费 + 新保费
            BigDecimal denominator = wOld.add(wNew);
            
            // 如果分母大于 0，进行除法运算
            if (denominator.compareTo(BigDecimal.ZERO) > 0) {
                // 计算新的加权利率，使用 MathContext.DECIMAL128 匹配 Python 精度
                rNew = numerator.divide(denominator, MC); 
            } else {
                // 如果分母为 0（理论上不应发生，除非保费为负且抵消），设置为 0
                rNew = BigDecimal.ZERO;
            }
            
            // 如果提供了日志记录器，记录相关日志
            if (logger != null) {
                // 准备日志变量 Map
                Map<String, Object> vars = new HashMap<>();
                vars.put("R_old", rOld);       // 旧利率
                vars.put("W_old", wOld);       // 旧累计保费
                vars.put("R_spot", rSpot);     // 新即期利率
                vars.put("W_new", wNew);       // 新单保费
                vars.put("Numerator", numerator); // 分子
                vars.put("Denominator", denominator); // 分母
                
                // 记录日志项：递归更新
                logger.logItem(
                    "加权初始确认利率更新",
                    "[Sec 1.5.2] 递归更新公式：R_new = (R_old * W_old + R_spot * W_new) / (W_old + W_new)",
                    "加权平均",
                    vars,
                    rNew,
                    String.format("期初存量保单权重: %,.2f, 新单权重: %,.2f", wOld, wNew)
                );
            }
        }
        
        // 更新合同组状态中的加权锁定利率
        cohortState.setWeightedLockedRate(rNew);
        // 更新合同组状态中的累计签单保费
        cohortState.setTotalWrittenPremium(wOld.add(wNew));
        
        // 返回新的加权锁定利率
        return rNew;
    }
}
