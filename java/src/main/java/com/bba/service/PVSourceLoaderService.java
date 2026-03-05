package com.bba.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.bba.entity.PolicyContract;
import com.bba.model.pv.PVSourceData;
import com.bba.model.pv.PVSourceDataCollection;
import com.bba.util.BbaConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * PV 原材料数据加载服务。
 * 负责生成或加载整个保单生命周期的 PV 数据集合。
 * 支持实时生成（模拟 Python 逻辑）和从 JSON 文件加载（测试/调试）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PVSourceLoaderService {

    // 注入数据加载服务，用于获取保单数据
    private final DataLoaderService dataLoaderService;
    // 注入 PV 生成服务，用于实时生成单月 PV 数据
    private final PVGeneratorService pvGeneratorService;

    /**
     * 生成保单的 PV 原材料数据集合。
     * 根据文档逻辑，只需要生成关键时点的数据：
     * 1. 签单日 (用于初始确认)
     * 2. 每年末 12-31 (用于期末计量)
     * 3. 每年初 01-01 (用于期初计量)
     *
     * @param policy 保单数据对象
     * @return 包含关键月份 PV 数据的集合
     */
    public PVSourceDataCollection generatePvSourceData(PolicyContract policy) {
        if (policy == null) {
            log.error("❌ 保单数据为空 (Policy is null)");
            return null;
        }
        String policyNo = policy.getPolicyNo();

        // 记录日志：开始生成 PV 原材料数据集合
        log.info("正在生成 PV 原材料数据集合，保单号: {}", policyNo);

        // 创建 PVSourceDataCollection 对象，用于存储所有月份的数据
        PVSourceDataCollection collection = new PVSourceDataCollection(policyNo);
        // 保单签单日期
        LocalDate underWriteDate = policy.getUnderWriteDate();
        // 保单止期
        LocalDate endDate = policy.getEndDate();

        // TODO: 暂时处理：只测算到配置的最大年份，超过部分不生成结果
        int maxSimYear = BbaConstants.MAX_SIMULATION_YEAR;
        LocalDate simulationEndDate = LocalDate.of(maxSimYear, 12, 31);
        if (endDate.getYear() < maxSimYear) {
             simulationEndDate = LocalDate.of(endDate.getYear(), 12, 31);
        }


        // 收集需要计算的关键日期
        // 使用 TreeSet 自动排序并去重
        java.util.TreeSet<LocalDate> targetDates = new java.util.TreeSet<>();

        // 确定起始年份：取签单年
        int startYear = underWriteDate.getYear();


        int endYear = simulationEndDate.getYear();

        // 2. 循环添加每年末
        for (int year = startYear; year <= endYear; year++) {
            // 添加年末 (12-31) - 用于期末计量 (EOP)
            targetDates.add(LocalDate.of(year, 12, 31));
        }

        // 记录日志：模拟的关键时点数量
        log.info("模拟时点数量: {} (范围: {} 至 {})", targetDates.size(), underWriteDate, simulationEndDate);

        // 遍历每一个关键日期
        for (LocalDate valDate : targetDates) {
            // 调试日志：正在为指定日期生成 PV 数据
            log.debug("DEBUG: 正在生成 PV 数据，日期: {}", valDate);

            try {
                // 检查 pvGeneratorService 是否为空
                if (pvGeneratorService == null) {
                    log.error("FATAL: pvGeneratorService 未注入 (is null)");
                    throw new NullPointerException("pvGeneratorService is null");
                }

                // 调用 PV 生成服务为该日期生成数据
                PVSourceData pvData = pvGeneratorService.generatePVSourceData(policy, valDate);

                // 添加到集合
                collection.addData(pvData);
            } catch (Exception e) {
                // 捕获异常并记录错误日志
                log.error("❌ 生成 PV 数据失败，日期 {}: {}", valDate, e.getMessage());
            }
        }

        // 返回填充好的集合
        return collection;
    }

    /**
     * 通过模拟整个生命周期为保单生成 PV 原材料数据。
     * 复刻 Python 脚本生成每个月 PV 数据的逻辑。
     *
     * @param policyNo 保单号
     * @param runDate 运行日期（YYYY-MM-DD），用于获取保单数据
     * @return 每个月的 PV 数据集合
     */
    public PVSourceDataCollection generatePvSourceData(String policyNo,String certiNo, String runDate) {
        // 1. 加载保单数据
        // 调用数据加载服务获取保单信息
        PolicyContract policy = dataLoaderService.getPolicyData(policyNo, certiNo, "7", runDate);
        // 如果找不到保单，记录错误并返回 null
        if (policy == null) {
            log.error("❌ 未找到保单: {}", policyNo);
            return null;
        }
        return generatePvSourceData(policy);
    }
}
