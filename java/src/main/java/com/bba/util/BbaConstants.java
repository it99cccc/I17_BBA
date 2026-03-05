package com.bba.util;

/**
 * BBA 项目全局常量定义
 */
public class BbaConstants {
    // 统一配置最晚仿真年份，避免不同服务间的不一致
    // 之前 GroupLifecycleSimulationService 使用 2025，PVSourceLoaderService 使用 2024
    // 现统一设定为 2025
    public static final int MAX_SIMULATION_YEAR = 2024;
}
