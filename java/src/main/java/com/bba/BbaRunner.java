package com.bba;

import com.bba.service.GroupLifecycleSimulationService;
import com.bba.service.DataLoaderService;
import com.bba.util.ReportGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class BbaRunner implements CommandLineRunner {

    private final GroupLifecycleSimulationService groupLifecycleSimulationService;
    private final DataLoaderService dataLoaderService;

    private static String resolveLogsDirPath() {
        java.nio.file.Path cwd = java.nio.file.Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();

        java.nio.file.Path directModule = cwd;
        if (java.nio.file.Files.exists(directModule.resolve("pom.xml"))
                && java.nio.file.Files.exists(directModule.resolve("src").resolve("main").resolve("java"))) {
            return directModule.resolve("logs").toString();
        }
        java.nio.file.Path nestedModule = cwd.resolve("java");
        if (java.nio.file.Files.exists(nestedModule.resolve("pom.xml"))
                && java.nio.file.Files.exists(nestedModule.resolve("src").resolve("main").resolve("java"))) {
            return nestedModule.resolve("logs").toString();
        }

        java.nio.file.Path p = cwd;
        for (int i = 0; i < 8 && p != null; i++) {
            java.nio.file.Path candidateDirect = p;
            if (java.nio.file.Files.exists(candidateDirect.resolve("pom.xml"))
                    && java.nio.file.Files.exists(candidateDirect.resolve("src").resolve("main").resolve("java"))) {
                return candidateDirect.resolve("logs").toString();
            }
            java.nio.file.Path candidateNested = p.resolve("java");
            if (java.nio.file.Files.exists(candidateNested.resolve("pom.xml"))
                    && java.nio.file.Files.exists(candidateNested.resolve("src").resolve("main").resolve("java"))) {
                return candidateNested.resolve("logs").toString();
            }
            p = p.getParent();
        }

        return cwd.resolve("logs").toString();
    }

    @Override
    public void run(String... args) throws Exception {
        log.info("Starting BBA Lifecycle Simulation...");

        String runDateVal = "202412"; // 默认评估日期
        String valMethod = "7"; // 默认计量方法

        log.info("查询批次 {} 下的所有合同组...", runDateVal);
        List<String> groupIds = dataLoaderService.getAllGroupIds(runDateVal, valMethod);
        
        if (groupIds == null || groupIds.isEmpty()) {
            log.warn("⚠️ 未找到任何属于批次 {} 且方法为 {} 的合同组。", runDateVal, valMethod);
            return;
        }

        log.info("共找到 {} 个合同组，开始批量计量...", groupIds.size());

        int successCount = 0;
        int failCount = 0;
        List<List<Map<String, Object>>> allResults = new ArrayList<>();

        for (String groupId : groupIds) {
            try {
                log.info(">>> 开始计算合同组: {}", groupId);
                List<Map<String, Object>> results = groupLifecycleSimulationService.runSimulation(groupId, runDateVal, valMethod);
                if (results != null && !results.isEmpty()) {
                    allResults.add(results);
                }
                successCount++;
                log.info("<<< 合同组 {} 计算完成。", groupId);
            } catch (Exception e) {
                failCount++;
                log.error("❌ 合同组 {} 计算失败: {}", groupId, e.getMessage(), e);
            }
        }

        if (!allResults.isEmpty()) {
            log.info("开始生成批次汇总报表...");
            List<Map<String, Object>> batchResults = aggregateBatchResults(allResults, runDateVal);
            generateBatchReports(batchResults, runDateVal);
            log.info("批次汇总报表生成完成。");
        }

        log.info("🎉 批量计量任务结束。总计: {}, 成功: {}, 失败: {}", groupIds.size(), successCount, failCount);
    }

    private List<Map<String, Object>> aggregateBatchResults(List<List<Map<String, Object>>> allResults, String runDateVal) {
        Map<Integer, Map<String, Object>> aggregatedByYear = new TreeMap<>();

        for (List<Map<String, Object>> groupResult : allResults) {
            for (Map<String, Object> yearlyData : groupResult) {
                Integer year = (Integer) yearlyData.get("year");
                Map<String, Object> aggregatedYear = aggregatedByYear.computeIfAbsent(year, k -> {
                    Map<String, Object> newMap = new HashMap<>();
                    newMap.put("year", k);
                    newMap.put("policy_no", "BATCH_" + runDateVal);
                    newMap.put("certi_no", "ALL_GROUPS");
                    return newMap;
                });

                for (Map.Entry<String, Object> entry : yearlyData.entrySet()) {
                    String key = entry.getKey();
                    Object value = entry.getValue();
                    if (value instanceof BigDecimal) {
                        BigDecimal currentSum = (BigDecimal) aggregatedYear.getOrDefault(key, BigDecimal.ZERO);
                        aggregatedYear.put(key, currentSum.add((BigDecimal) value));
                    }
                }
            }
        }

        return new ArrayList<>(aggregatedByYear.values());
    }

    private void generateBatchReports(List<Map<String, Object>> batchResults, String runDateVal) {
        try {
            Files.createDirectories(Paths.get("logs"));
            String reportPath103 = "logs/report_103_batch_" + runDateVal + ".html";
            String reportPath104 = "logs/report_104_batch_" + runDateVal + ".html";

            ReportGenerator.generate103Report(batchResults, reportPath103);
            log.info("已生成批次级别 103 报表: {}", reportPath103);

            ReportGenerator.generate104Report(batchResults, reportPath104);
            log.info("已生成批次级别 104 报表: {}", reportPath104);
        } catch (Exception e) {
            log.error("生成批次级别报表失败", e);
        }
    }
}
