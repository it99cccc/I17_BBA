package com.bba;

import com.bba.service.GroupLifecycleSimulationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BbaRunner implements CommandLineRunner {

    private final GroupLifecycleSimulationService groupLifecycleSimulationService;

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

        // Handle potential comma-separated arguments from Maven
        String[] effectiveArgs = args;
        if (args.length == 1 && args[0].contains(",")) {
            effectiveArgs = args[0].split(",");
        }

        // Check if group simulation is requested
        // if (effectiveArgs.length > 0 && effectiveArgs[0].equals("group")) {
            String groupId = effectiveArgs.length > 1 ? effectiveArgs[1] : "QHPLIA2023ABBA305";
            String runDateVal = "202412"; // Renamed to avoid conflict with runDate below if needed, though scoping handles it
            String valMethod = "7"; // Default to BBA or 7 depending on logic, Python uses VAL_METHOD from config which is usually 7 or BBA
            // Let's use "7" as in the commented out code, or check what Python uses.
            // Python config usually says VAL_METHOD = 'BBA' or '7'.
            // The commented code used "7". Let's stick to "7" if that's what the system expects, or "BBA".
            // Python config: from BBA_group.config import VAL_METHOD

            log.info("Running Group Simulation for Group ID: {}", groupId);
            groupLifecycleSimulationService.runSimulation(groupId, runDateVal, valMethod);
        //     return;
        // }

    //    String policyNo = "mock1";
    //    String certiNo = "";
    //    String runDate = "202412";

    //    if (effectiveArgs.length > 0 && !effectiveArgs[0].startsWith("--")) {
    //        policyNo = effectiveArgs[0];
    //    }

    //    try {
    //        java.util.List<java.util.Map<String, Object>> results = lifecycleSimulationService.runSimulation(policyNo, certiNo, runDate);
    //        writeResultsToCsv(results, policyNo);
    //        log.info("Simulation completed successfully for policy: {}", policyNo);
    //    } catch (Exception e) {
    //        log.error("Simulation failed for policy: {}", policyNo, e);
    //    }
    }

    private void writeResultsToCsv(java.util.List<java.util.Map<String, Object>> results, String policyNo) {
        if (results == null || results.isEmpty()) return;

        String logsDirPath = resolveLogsDirPath();
        java.io.File logsDir = new java.io.File(logsDirPath);
        if (!logsDir.exists()) {
            logsDir.mkdirs();
        }

        String csvFilePath = logsDirPath + "/report_" + policyNo + ".csv";
        try (java.io.PrintWriter writer = new java.io.PrintWriter(
                new java.io.OutputStreamWriter(new java.io.FileOutputStream(csvFilePath), java.nio.charset.StandardCharsets.UTF_8)
        )) {
            // Write Header
            java.util.Map<String, Object> firstRow = results.get(0);
            java.util.List<String> headers = new java.util.ArrayList<>(firstRow.keySet());
            writer.println(String.join(",", headers));

            // Write Data
            for (java.util.Map<String, Object> row : results) {
                java.util.List<String> values = new java.util.ArrayList<>();
                for (String header : headers) {
                    Object val = row.get(header);
                    values.add(val != null ? val.toString() : "");
                }
                writer.println(String.join(",", values));
            }
            log.info("Report generated at: {}", new java.io.File(csvFilePath).getAbsolutePath());
        } catch (java.io.IOException e) {
            log.error("Failed to write CSV report", e);
        }
    }
}
