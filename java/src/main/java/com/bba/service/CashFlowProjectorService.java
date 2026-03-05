package com.bba.service;

import com.bba.entity.PolicyContract;
import com.bba.model.Assumptions;
import com.bba.model.CashFlow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

@Service // 声明该类为Spring的业务逻辑层组件
@Slf4j // Lombok注解，自动生成一个名为log的日志记录器实例
public class CashFlowProjectorService {

    /**
     * 为单个保单构建月度现金流序列。
     * 复制了Python版本中 CashFlowProjector.project_policy_flows 的逻辑
     */
    public List<CashFlow> projectPolicyFlows(PolicyContract policy, Assumptions assumptions) {
        // 从保单对象中获取不含税的总保费，如果为null则默认为0
        BigDecimal premium = policy.getSumPremiumNoTax() != null ? policy.getSumPremiumNoTax() : BigDecimal.ZERO;
        // 从保单对象中获取费用，如果没有则使用精算假设计算
        BigDecimal iacfAmount = BigDecimal.ZERO;
        if(policy.getIacfAmount() == null ){
            iacfAmount = premium.multiply(assumptions.getAcquisitionExpenseRatio());
        }else {
            iacfAmount = policy.getIacfAmount() != null ? policy.getIacfAmount() : BigDecimal.ZERO;
        }

        // 从保单对象中获取关键日期：保险起期
        LocalDate startDate = policy.getStartDate();
        // 从保单对象中获取关键日期：保险止期
        LocalDate endDate = policy.getEndDate();
        // 从保单对象中获取关键日期：签单日期
        LocalDate uwDate = policy.getUnderWriteDate();
        // 从保单对象中获取关键日期：保修期结束日期
        LocalDate warrantyEnd = policy.getWarrantyEndDate();

        // 检查必要的日期是否存在，如果缺少任何一个则抛出非法参数异常
        if (startDate == null || endDate == null || uwDate == null) {
            throw new IllegalArgumentException("Missing required dates: Start Date, End Date, or Underwrite Date.");
        }

        // 如果保修结束日期为null，则将其默认设置为保险起期（尽管逻辑说可以是warrantyEnd或startDate）
        if (warrantyEnd == null) {
            warrantyEnd = startDate;
        }

        // 确定时间线的起始日期，确保我们能捕捉到风险期前的月份（例如，制造商保修期）
        // 取保险起期和签单日期中较早的那个
        LocalDate timelineStart = startDate.isBefore(uwDate) ? startDate : uwDate;

        // 确定风险期间：仅在保修期结束之后
        LocalDate riskStart = warrantyEnd;
        // 风险结束日期即为保险止期
        LocalDate riskEnd = endDate;

        // 将风险开始日期规范化为当月的第一天
        LocalDate riskStartMonth = riskStart.withDayOfMonth(1);
        // 将风险结束日期规范化为当月的第一天
        LocalDate riskEndMonth = riskEnd.withDayOfMonth(1);

        // 计算风险开始年月到风险结束年月经过的月份数
        long coverageMonths = ChronoUnit.MONTHS.between(riskStartMonth, riskEndMonth) +1;
        log.info("Projecting flows for Policy: {}, RiskStart: {}, RiskEnd: {}, CoverageMonths: {}",
                policy.getPolicyNo(), riskStartMonth, riskEndMonth, coverageMonths);
        // 初始化理赔和费用为0
        BigDecimal claims = BigDecimal.ZERO;
        BigDecimal expenses = BigDecimal.ZERO;

        // 从假设对象中获取损失率，如果为null则默认为0
        BigDecimal lossRatio = assumptions.getLossRatio() != null ? assumptions.getLossRatio() : BigDecimal.ZERO;
        // 从假设对象中获取间接理赔费用比率，如果为null则默认为0
        BigDecimal claimExpRatio = assumptions.getIndirectClaimsExpenseRatio() != null ? assumptions.getIndirectClaimsExpenseRatio() : BigDecimal.ZERO;
        // 从假设对象中获取维护费用比率，如果为null则默认为0
        BigDecimal maintRatio = assumptions.getMaintenanceExpenseRatio() != null ? assumptions.getMaintenanceExpenseRatio() : BigDecimal.ZERO;

        // 计算总理赔费用：签单保费 * 损失率 * (1 + 间接理赔费用比率)
        BigDecimal totalClaims = premium.multiply(lossRatio).multiply(BigDecimal.ONE.add(claimExpRatio));
        // 计算总维持费用：签单保费 * 维护费用比率
        BigDecimal totalExpenses = premium.multiply(maintRatio);

        // 如果覆盖月数大于0，则计算每月的赔付费用和维持费用
        if (coverageMonths > 0) {
            // 计算理赔支出：每月赚得的保费 * 损失率 * (1 + 间接理赔费用比率)
            claims = totalClaims.divide(new BigDecimal(coverageMonths), MathContext.DECIMAL128);
            // 计算费用支出：每月赚得的保费 * 维护费用比率
            expenses = totalExpenses.divide(new BigDecimal(coverageMonths), MathContext.DECIMAL128);
        }
        // 初始化一个用于存储现金流对象的列表
        List<CashFlow> cashFlows = new ArrayList<>();

        // 生成从时间线起始日期到保险结束日期的月份范围
        // 取保险起期和签单日期中较早的那个为时间线起始月份的第一天
        LocalDate currentMonth = timelineStart.withDayOfMonth(1);
        // 将结束月份设置为保险结束月份的第一天
        LocalDate endMonth = endDate.withDayOfMonth(1);

        // 循环遍历从当前月份到结束月份的每一个月
        while (!currentMonth.isAfter(endMonth)) {
            // 格式化年月为 "YYYYMM" 字符串
            String yyyymm = String.format("%04d%02d", currentMonth.getYear(), currentMonth.getMonthValue());

            // 初始化保费流入和IACF流出为0
            BigDecimal premiumInflow = BigDecimal.ZERO;
            BigDecimal iacfOutflow = BigDecimal.ZERO;
            BigDecimal claimsflow = BigDecimal.ZERO;
            BigDecimal expensesflow = BigDecimal.ZERO;


            // 保费和IACF发生在承保月份
            if (currentMonth.getYear() == uwDate.getYear() && currentMonth.getMonthValue() == uwDate.getMonthValue()) {
                // 如果当前月是承保月，则记录保费流入
                premiumInflow = premium;
                // 如果当前月是承保月，则记录IACF流出
                iacfOutflow = iacfAmount;
            }
            //如果当前月大于等于保障起期，则进入风险期，记录赔付和维持费用
            if(currentMonth.isEqual(riskStartMonth) || currentMonth.isAfter(riskStartMonth)){
                claimsflow = claims;
                expensesflow = expenses;
            }


            // 创建一个新的现金流对象，包含当前月的所有计算结果
            LocalDate cfDate = currentMonth.withDayOfMonth(currentMonth.lengthOfMonth());

            CashFlow cf = new CashFlow(
                    currentMonth.getYear(),    // 年份
                    currentMonth.getMonthValue(), // 月份
                    yyyymm,                    // "YYYYMM"格式的字符串
                    cfDate,                    // 当前月份的日期对象（月末）
                    premiumInflow,             // 保费流入
                    iacfOutflow,               // IACF流出
                    claimsflow,                    // 理赔支出
                    expensesflow                   // 费用支出
            );
            // 将创建的现金流对象添加到列表中
            cashFlows.add(cf);

            // 将当前月份向后推一个月，准备下一次循环
            currentMonth = currentMonth.plusMonths(1);
        }

        // 返回包含所有月份现金流的列表
        return cashFlows;
    }
}
