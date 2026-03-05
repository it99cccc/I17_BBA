package com.bba.service.logic;

import com.bba.model.PolicyState;
import com.bba.util.CalculationLogger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 覆盖单元服务类。
 * 负责计算保单的覆盖单元（Coverage Units）释放和剩余情况。
 * 覆盖单元用于 CSM 的摊销计算。
 */
@Service
public class CoverageUnitsService {

    /**
     * 计算当期释放的覆盖单元。
     *
     * @param policies 保单状态列表
     * @param valuationDate 评估日期
     * @param startOfYear 年初日期
     * @param logger 计算日志记录器
     * @param isInitialYear 是否为初始年份（影响服务起期的确定）
     * @return 当期释放的总覆盖单元
     */
    public BigDecimal calculateCoverageUnitsReleased(
            List<PolicyState> policies,
            LocalDate valuationDate,
            LocalDate startOfYear,
            CalculationLogger logger,
            boolean isInitialYear
    ) {
        // 初始化释放的覆盖单元总数为0
        BigDecimal cuReleased = BigDecimal.ZERO;

        // 遍历所有保单
        for (PolicyState policy : policies) {
            // 如果保单在年初之前已结束，或在评估日期之后才开始，则跳过
            if (policy.getEndDate().isBefore(startOfYear) || policy.getStartDate().isAfter(valuationDate)) {
                continue;
            }

            // 确定保修结束日期，如果未设置则默认为保单开始日期
            LocalDate warrantyEnd = policy.getWarrantyEndDate() != null ? policy.getWarrantyEndDate() : policy.getStartDate();
            // 判断评估日期是否在保修期内
            boolean isInWarranty = valuationDate.isBefore(warrantyEnd);

            // 确定服务起期
            LocalDate serviceStart;
            if (isInitialYear) {
                // 如果是初始年份，服务起期为保修结束日期（追溯调整逻辑）
                serviceStart = warrantyEnd;
            } else {
                // 否则，服务起期为保修结束日期和年初日期的较晚者
                serviceStart = warrantyEnd.isAfter(startOfYear) ? warrantyEnd : startOfYear;
            }

            // 确定服务止期：保单结束日期和评估日期的较早者
            LocalDate serviceEnd = policy.getEndDate().isBefore(valuationDate) ? policy.getEndDate() : valuationDate;

            long serviceDays = 0;
            String note = "";

            if (isInWarranty) {
                // 如果在保修期内，不释放覆盖单元
                serviceDays = 0;
                serviceStart = warrantyEnd;
                serviceEnd = valuationDate;
                note = String.format("评估日期（%s）在保修期内（保修结束日期：%s），服务天数为0", valuationDate, warrantyEnd);
            } else if (serviceEnd.isBefore(serviceStart)) {
                // 如果服务止期早于服务起期，天数为0
                serviceDays = 0;
                note = String.format("服务止期（%s）早于服务起期（%s），服务天数为0", serviceEnd, serviceStart);
            } else {
                // 计算服务天数（包含首尾）
                serviceDays = ChronoUnit.DAYS.between(serviceStart, serviceEnd) + 1; // Inclusive
                note = String.format("服务期间：%s 至 %s", serviceStart, serviceEnd);
            }

            // 获取覆盖基础（签单保费）
            BigDecimal coverageBase = policy.getWrittenPremium();
            // 计算单张保单的覆盖单元：覆盖基础 * 服务天数
            BigDecimal policyCu = coverageBase.multiply(new BigDecimal(serviceDays));
            // 累加到总数
            cuReleased = cuReleased.add(policyCu);

            // 记录日志
            if (logger != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("保单号", policy.getPolicyNo());
                meta.put("签单保费", coverageBase);
                meta.put("服务天数", serviceDays);
                meta.put("服务起期", serviceStart);
                meta.put("服务止期", serviceEnd);
                meta.put("保修结束日期", warrantyEnd);
                meta.put("评估日期", valuationDate);

                logger.logItem(
                        "保单 " + policy.getPolicyNo() + " 覆盖单元释放",
                        "[Sec 8.2] 本期释放的覆盖单元",
                        "保额（或签单保费）× 服务天数",
                        meta,
                        policyCu,
                        note
                );
            }
        }

        // 记录合计日志
        if (logger != null) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("保单数量", policies.size());
            logger.logItem(
                    "本期释放的覆盖单元合计",
                    "[Sec 8.2] CU_released = Σ(保额或签单保费 × 服务天数)",
                    "合同组内所有有效保单的覆盖单元之和",
                    meta,
                    cuReleased,
                    isInitialYear ? "首年包含起保日至评估日的累计服务（含追溯月份）" : "本期（当月）该合同组内所有有效保单释放的覆盖单元之和"
            );
        }

        return cuReleased;
    }

    /**
     * 计算期末剩余的覆盖单元。
     *
     * @param policies 保单状态列表
     * @param valuationDate 评估日期
     * @param logger 计算日志记录器
     * @return 期末剩余的总覆盖单元
     */
    public BigDecimal calculateCoverageUnitsRemaining(
            List<PolicyState> policies,
            LocalDate valuationDate,
            CalculationLogger logger
    ) {
        // 初始化剩余覆盖单元总数为0
        BigDecimal cuRemaining = BigDecimal.ZERO;

        // 遍历所有保单
        for (PolicyState policy : policies) {
            // 如果保单结束日期不在评估日期之后（即已结束），则跳过
            if (!policy.getEndDate().isAfter(valuationDate)) {
                continue;
            }

            // 确定保修结束日期
            LocalDate warrantyEnd = policy.getWarrantyEndDate() != null ? policy.getWarrantyEndDate() : policy.getStartDate();
            // 判断评估日期是否在保修期内
            boolean isInWarranty = valuationDate.isBefore(warrantyEnd);

            long remainingDays = 0;
            LocalDate serviceStartNote;
            String note;

            if (isInWarranty) {
                // 如果在保修期内，剩余天数从保修结束日期开始计算到保单结束日期
                remainingDays = ChronoUnit.DAYS.between(warrantyEnd, policy.getEndDate()); // Inclusive? Python: (end - warranty).days
                // Python logic: (policy.end_date - warranty_end).days
                // If warranty_end is 2023-01-01 and end_date is 2023-01-02, days is 1.
                // Java between is exclusive of end date?
                // ChronoUnit.DAYS.between(start, end) calculates days between.
                // e.g. 1st to 2nd is 1 day. Matches Python.
                serviceStartNote = warrantyEnd;
                note = String.format("评估日期（%s）在保修期内（保修结束日期：%s），剩余服务天数从保修结束日期开始计算", valuationDate, warrantyEnd);
            } else {
                // 如果不在保修期内，剩余天数从评估日期开始计算到保单结束日期
                remainingDays = ChronoUnit.DAYS.between(valuationDate, policy.getEndDate());
                serviceStartNote = valuationDate;
                note = String.format("评估日期（%s）在保修期后，剩余服务天数从评估日期开始计算", valuationDate);
            }

            // 如果剩余天数小于等于0，跳过
            if (remainingDays <= 0) {
                continue;
            }

            // 获取覆盖基础（签单保费）
            BigDecimal coverageBase = policy.getWrittenPremium();
            // 计算单张保单的剩余覆盖单元
            BigDecimal policyCu = coverageBase.multiply(new BigDecimal(remainingDays));
            // 累加到总数
            cuRemaining = cuRemaining.add(policyCu);

            // 记录日志
            if (logger != null) {
                Map<String, Object> meta = new HashMap<>();
                meta.put("保单号", policy.getPolicyNo());
                meta.put("签单保费", coverageBase);
                meta.put("剩余服务天数", remainingDays);
                meta.put("保单止期", policy.getEndDate());
                meta.put("评估日期", valuationDate);
                meta.put("保修结束日期", warrantyEnd);
                meta.put("服务起算日期", serviceStartNote);

                logger.logItem(
                        "保单 " + policy.getPolicyNo() + " 剩余覆盖单元",
                        "[Sec 8.2] 期末剩余服务期的覆盖单元",
                        "保额（或签单保费）× 剩余服务天数",
                        meta,
                        policyCu,
                        note
                );
            }
        }

        // 记录合计日志
        if (logger != null) {
            Map<String, Object> meta = new HashMap<>();
            meta.put("保单数量", policies.size());
            logger.logItem(
                    "期末剩余服务期的覆盖单元合计",
                    "[Sec 8.2] CU_remaining = Σ(保额或签单保费 × 剩余服务天数)",
                    "合同组内所有有效保单的剩余覆盖单元之和",
                    meta,
                    cuRemaining,
                    "期末时点，该合同组内所有有效保单剩余服务期的覆盖单元之和"
            );
        }

        return cuRemaining;
    }
}
