package com.bba.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bba.entity.ActuarialAssumption;
import com.bba.entity.PolicyContract;
import com.bba.entity.RateCurve;
import com.bba.mapper.ActuarialAssumptionMapper;
import com.bba.mapper.PolicyContractMapper;
import com.bba.mapper.RateCurveMapper;
import com.bba.model.Assumptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据加载服务类，负责从数据库获取保单、利率曲线和精算假设等基础数据。
 * 该服务使用了MyBatis-Plus进行数据库操作，并提供了简单的缓存机制。
 */
@Service // 标记为Spring的服务组件，交由Spring容器管理
@Slf4j // 使用Lombok自动生成日志对象log
@RequiredArgsConstructor // 使用Lombok自动生成包含final字段的构造函数，实现依赖注入
//@DS("pg_measure_platform") // Removed class-level DS to support multi-datasource
public class DataLoaderService {

    // 注入保单数据访问接口，用于查询保单表
    private final PolicyContractMapper policyContractMapper;
    // 注入利率曲线数据访问接口，用于查询利率表
    private final RateCurveMapper rateCurveMapper;
    // 注入精算假设数据访问接口，用于查询假设表
    private final ActuarialAssumptionMapper actuarialAssumptionMapper;

    // 缓存容器：用于缓存利率曲线数据，Key为评估月份字符串，Value为该月的利率曲线列表
    // 使用ConcurrentHashMap保证线程安全
    private final Map<String, List<RateCurve>> ratesCache = new ConcurrentHashMap<>();
    // 缓存容器：用于缓存精算假设数据，Key为组合键（险类_月份_方法），Value为假设对象
    // 使用ConcurrentHashMap保证线程安全
    private final Map<String, Assumptions> assumptionsCache = new ConcurrentHashMap<>();

    private static final DateTimeFormatter YM_FMT = DateTimeFormatter.ofPattern("yyyyMM");

    /**
     * 根据保单号、凭证号、评估方法和运行日期查询保单详情。
     *
     * @param policyNo 保单号
     * @param certiNo  凭证号（可选）
     * @param valMethod 评估方法（如BBA）
     * @param runDate  运行日期（数据快照日期）
     * @return 查询到的保单实体对象，如果未找到则返回null
     */
    public PolicyContract getPolicyData(String policyNo, String certiNo, String valMethod, String runDate) {
        // 创建一个Lambda查询包装器，用于构建查询条件
        LambdaQueryWrapper<PolicyContract> query = new LambdaQueryWrapper<>();
        // 添加查询条件：保单号等于传入参数
        query.eq(PolicyContract::getPolicyNo, policyNo)
             // 添加查询条件：评估方法等于传入参数
             .eq(PolicyContract::getValMethod, valMethod)
             // 添加查询条件：运行日期等于传入参数
             .eq(PolicyContract::getRunDate, runDate);

        // 判断凭证号是否不为空且不为空字符串
        if (certiNo != null && !certiNo.isEmpty()) {
            // 如果有凭证号，添加查询条件：凭证号等于传入参数
            query.eq(PolicyContract::getCertiNo, certiNo);
        } else {
            // 如果没有凭证号，添加查询条件：凭证号为NULL 或者 凭证号为空字符串
            query.isNull(PolicyContract::getCertiNo);
        }

        // 限制查询结果只返回一条记录
        query.last("LIMIT 1");

        // 执行查询并返回结果
        PolicyContract policy = policyContractMapper.selectOne(query);
        if (policy == null) {
            return null;
        }
        //TODO 查询获取费用实际现金流表，测试版本待更换表
        BigDecimal iacfAmount = policyContractMapper.selectIacfAmount(policy.getPolicyNo(), policy.getCertiNo());
        policy.setIacfAmount(iacfAmount != null ? iacfAmount : BigDecimal.ZERO);

        return policy;
    }

    /**
     * 获取所有符合条件的保单条目（保单号, 凭证号）。
     *
     * @param runDate 运行日期
     * @param valMethod 评估方法
     * @return 保单列表
     */
    public List<PolicyContract> getAllPolicyEntries(String runDate, String valMethod) {
        LambdaQueryWrapper<PolicyContract> query = new LambdaQueryWrapper<>();
        query.select(PolicyContract::getPolicyNo, PolicyContract::getCertiNo)
             .eq(PolicyContract::getRunDate, runDate)
             .eq(PolicyContract::getValMethod, valMethod)
             .groupBy(PolicyContract::getPolicyNo, PolicyContract::getCertiNo) // Ensure distinct if needed, though usually unique per run/method
             .orderByAsc(PolicyContract::getPolicyNo);
        return policyContractMapper.selectList(query);
    }

    /**
     * Find the latest run date for a given group ID.
     */
    public String findLatestRunDateForGroup(String groupId) {
        LambdaQueryWrapper<PolicyContract> query = new LambdaQueryWrapper<>();
        query.select(PolicyContract::getRunDate)
             .eq(PolicyContract::getGroupId, groupId)
             .orderByDesc(PolicyContract::getRunDate)
             .last("LIMIT 1");
        PolicyContract p = policyContractMapper.selectOne(query);
        return p != null ? p.getRunDate() : null;
    }

    /**
     * 根据合同组ID获取保单列表 (Ignore ValMethod)
     */
    public List<PolicyContract> getPoliciesByGroup(String groupId, String runDate) {
        LambdaQueryWrapper<PolicyContract> query = new LambdaQueryWrapper<>();
        query.eq(PolicyContract::getGroupId, groupId)
             .eq(PolicyContract::getRunDate, runDate)
             .orderByAsc(PolicyContract::getPolicyNo);

        List<PolicyContract> policies = policyContractMapper.selectList(query);
        for (PolicyContract policy : policies) {
            BigDecimal iacfAmount = policyContractMapper.selectIacfAmount(policy.getPolicyNo(), policy.getCertiNo());
            policy.setIacfAmount(iacfAmount != null ? iacfAmount : BigDecimal.ZERO);
        }
        return policies;
    }

    /**
     * 根据合同组ID获取保单列表
     * @param groupId 合同组ID
     * @param runDate 运行日期
     * @param valMethod 评估方法
     * @return 保单列表
     */
    public List<PolicyContract> getPoliciesByGroup(String groupId, String runDate, String valMethod) {
        LambdaQueryWrapper<PolicyContract> query = new LambdaQueryWrapper<>();
        query.eq(PolicyContract::getGroupId, groupId)
             .eq(PolicyContract::getRunDate, runDate)
             .eq(PolicyContract::getValMethod, valMethod)
             .orderByAsc(PolicyContract::getPolicyNo);

        List<PolicyContract> policies = policyContractMapper.selectList(query);

        // Populate IACF amount for each policy (if needed)
        // Note: doing this in loop might be slow for large groups, but acceptable for now
        for (PolicyContract policy : policies) {
            BigDecimal iacfAmount = policyContractMapper.selectIacfAmount(policy.getPolicyNo(), policy.getCertiNo());
            policy.setIacfAmount(iacfAmount != null ? iacfAmount : BigDecimal.ZERO);
        }

        return policies;
    }

    /**
     * 根据评估月份获取利率曲线列表。
     * 优先从缓存读取，如果缓存不存在则查询数据库并更新缓存。
     *
     * @param valMonthStr 评估月份字符串，格式通常为 "yyyyMM"
     * @return 利率曲线列表
     */
    @DS("pg_measure_platform")
    public List<RateCurve> getRates(String valMonthStr) {
        if (ratesCache.containsKey(valMonthStr)) {
            return ratesCache.get(valMonthStr);
        }

        YearMonth ym = null;
        try {
            ym = YearMonth.parse(valMonthStr, YM_FMT);
        } catch (Exception ignored) {
        }

        String queryMonth = valMonthStr;
        List<RateCurve> rates;
        int fallbackSteps = 0;

        while (true) {
            LambdaQueryWrapper<RateCurve> query = new LambdaQueryWrapper<>();
            query.eq(RateCurve::getValMonth, queryMonth)
                    .orderByAsc(RateCurve::getTermMonth);
            rates = rateCurveMapper.selectList(query);

            if (rates != null && !rates.isEmpty()) {
                break;
            }

            if (ym == null || fallbackSteps >= 24) {
                log.error("❌ Error: No rate curve data found for {}", valMonthStr);
                throw new RuntimeException("Rate curve data missing for " + valMonthStr);
            }

            fallbackSteps++;
            ym = ym.minusMonths(1);
            queryMonth = ym.format(YM_FMT);
        }

        if (!queryMonth.equals(valMonthStr)) {
            log.warn("⚠️ Warning: No rate curve data found for {}, fallback to {}", valMonthStr, queryMonth);
        }

        ratesCache.put(valMonthStr, rates);
        return rates;
    }

    /**
     * 根据险类代码、评估月份和评估方法获取精算假设数据。
     * 优先从缓存读取，如果缓存不存在则查询数据库并更新缓存。
     *
     * @param classCode 险类代码
     * @param valMonthStr 评估月份字符串
     * @param valMethod 评估方法
     * @return 转换后的 Assumptions 模型对象，如果未找到则返回null
     */
    public Assumptions getAssumptions(String classCode, String valMonthStr, String valMethod) {
        // 构建缓存Key，组合险类、月份和方法，确保唯一性
        String cacheKey = classCode + "_" + valMonthStr + "_" + valMethod;
        // 检查缓存中是否存在该Key
        if (assumptionsCache.containsKey(cacheKey)) {
            // 如果存在，直接返回缓存中的假设对象
            return assumptionsCache.get(cacheKey);
        }

        // 如果缓存中没有，创建查询包装器准备查库
        LambdaQueryWrapper<ActuarialAssumption> query = new LambdaQueryWrapper<>();
        // 添加查询条件：险类代码
        query.eq(ActuarialAssumption::getClassCode, classCode)
             // 添加查询条件：评估月份
             .eq(ActuarialAssumption::getValMonth, valMonthStr)
             // 添加查询条件：评估方法
             .eq(ActuarialAssumption::getValMethod, valMethod)
             // 限制只返回一条记录
             .last("LIMIT 1");

        // 执行查询，获取数据库实体对象
        ActuarialAssumption entity = actuarialAssumptionMapper.selectOne(query);
        // 检查查询结果是否为空
        if (entity == null) {
            String errorMsg = String.format("❌ 错误: 找不到精算假设数据! 险类代码: %s, 评估月份: %s, 评估方法: %s", classCode, valMonthStr, valMethod);
            throw new RuntimeException(errorMsg);
        }

        // 创建业务模型对象 Assumptions，将数据库实体转换为业务对象
        Assumptions assumptions = new Assumptions();
        // 设置评估月份
        assumptions.setValMonth(valMonthStr);
        // 设置险类代码
        assumptions.setClassCode(classCode);
        // 设置赔付率
        assumptions.setLossRatio(entity.getLossRatio());
        // 设置间接理赔费用比率
        assumptions.setIndirectClaimsExpenseRatio(entity.getIndirectClaimsExpenseRatio());
        // 设置维护费用比率
        assumptions.setMaintenanceExpenseRatio(entity.getMaintenanceExpenseRatio());
        // 设置风险调整(RA)比率
        assumptions.setRaRatio(entity.getRaRatio());
        // 检查获取费用比率是否不为空
        if (entity.getAcquisitionExpenseRatio() != null) {
            // 如果不为空，设置获取费用比率
            assumptions.setAcquisitionExpenseRatio(entity.getAcquisitionExpenseRatio());
        }

        // 将构建好的业务对象放入缓存
        assumptionsCache.put(cacheKey, assumptions);
        // 返回业务对象
        return assumptions;
    }
}
