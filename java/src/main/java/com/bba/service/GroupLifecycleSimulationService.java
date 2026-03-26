package com.bba.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.bba.entity.PolicyContract;
import com.bba.entity.PvSourceDataEntity;
import com.bba.entity.RateCurve;
import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.group.*;
import com.bba.model.group.IPolicyGroupCalculationInput;
import com.bba.model.pv.PVSourceDataCollection;
import com.bba.model.pv.PVSourceData;
import com.bba.service.logic.*;
import com.bba.util.CalculationLogger;
import com.bba.util.BbaConstants;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import com.bba.util.ReportGenerator;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.stream.Collectors;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Collections;

@Service
@Slf4j
@RequiredArgsConstructor
public class GroupLifecycleSimulationService {

    private final DataLoaderService dataLoaderService;
    private final RatesManagerService ratesManagerService;
    private final InitialRecognitionService initialRecognitionService;
    private final CsmLcMeasurementService csmLcMeasurementService;
    private final GroupCsmLcMeasurementService groupCsmLcMeasurementService;
    private final CoverageUnitsService coverageUnitsService;
    private final IacfAmortizationService iacfAmortizationService;
    private final IfieService ifieService;
    private final LrcClosingService lrcClosingService;
    private final PVSourceLoaderService pvSourceLoaderService;
    private final FulfillmentCashflowChangesService fulfillmentCashflowChangesService;
    private final RevenueService revenueService;
    private final com.bba.mapper.CalculationResultMapper calculationResultMapper;
    private final com.bba.mapper.PvSourceDataMapper pvSourceDataMapper;

    private static final DateTimeFormatter YYYYMM = DateTimeFormatter.ofPattern("yyyyMM");
    private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO;

    /**
     * 运行组级别生命周期仿真
     *
     * @param groupId   合同组ID
     * @param runDate   运行日期 (yyyyMM)
     * @param valMethod 评估方法 (例如: BBA)
     */
    public List<Map<String, Object>> runSimulation(String groupId, String runDate, String valMethod) {
        String logFilePath = "logs/simulation_group_" + groupId + ".md";
        // 暂时注释掉详细日志输出以提升批量处理性能
        // try (CalculationLogger logger = new CalculationLogger(logFilePath)) {
        try (CalculationLogger logger = new CalculationLogger(null)) { // 传入 null 则内部不写文件
            logger.logSection("IFRS 17 BBA 组级别生命周期仿真 - 组ID: " + groupId);

            // 1. 初始化组数据
            GroupCohortState groupCohortState = initializeGroup(groupId, runDate, valMethod, logger);
            if (groupCohortState == null) {
                return new ArrayList<>();
            }

            // 2. 初始确认（计算PV现值）
            runInitialRecognition(groupCohortState, runDate, valMethod, logger);

            // 3. 仿真循环
            List<Map<String, Object>> groupYearlyResults = simulateLifecycle(groupCohortState, runDate, valMethod, logger);

            logger.logText("\n仿真成功完成。");
            return groupYearlyResults;
        } catch (Exception e) {
            log.error("仿真失败", e);
            return new ArrayList<>();
        }
    }

    /**
     * 初始化合同组，加载该组下的所有保单
     *
     * @param groupId   合同组ID
     * @param runDate   运行日期
     * @param valMethod 评估方法
     * @param logger    计算日志记录器
     * @return 初始化的合同组状态对象，如果未找到保单则返回 null
     */
    private GroupCohortState initializeGroup(String groupId, String runDate, String valMethod, CalculationLogger logger) {
        logger.logText("### [步骤 0] 加载组保单数据");
        List<PolicyContract> policies = dataLoaderService.getPoliciesByGroup(groupId, runDate, valMethod);

        if (policies.isEmpty()) {
            logger.logText("❌ 错误: 未找到组 " + groupId + " 的保单");
            return null;
        }

        //删除PV现值历史数据
        pvSourceDataMapper.delete(new LambdaQueryWrapper<PvSourceDataEntity>().eq(PvSourceDataEntity::getRunDate,
                runDate));

        logger.logText("- 找到保单数量: " + policies.size());
        for (PolicyContract p : policies) {
            logger.logText("  - " + p.getPolicyNo()+"-"+p.getCertiNo());
        }

        GroupCohortState groupCohortState = new GroupCohortState();
        groupCohortState.setGroupId(groupId);

        for (PolicyContract p : policies) {
            GroupPolicyState ps = new GroupPolicyState();
            ps.setPolicyNo(p.getPolicyNo());
            ps.setCertiNo(p.getCertiNo());
            ps.setUnitId(p.getPolicyNo()+p.getCertiNo());
            ps.setStartDate(p.getStartDate());
            ps.setEndDate(p.getEndDate());
            ps.setWarrantyEndDate(p.getWarrantyEndDate() != null ? p.getWarrantyEndDate() : p.getStartDate());
            ps.setWrittenPremium(p.getSumPremiumNoTax());
            ps.setValuationDate(p.getUnderWriteDate());
            ps.setGroupId(groupId);
            ps.setUwMonthStr(p.getUnderWriteDate().format(YYYYMM));
            ps.setClassCode(p.getClassCode());
            ps.setIacfAmount(p.getIacfAmount());

            ps.calculateMonths();

            // 初始化期初 (BOP) 值为 0
            ps.setBopCsm(DECIMAL_ZERO);
            ps.setBopLc(DECIMAL_ZERO);
            ps.setBopLcCf(DECIMAL_ZERO);
            ps.setBopLcRa(DECIMAL_ZERO);

            // [优化] 预加载 PV 数据，避免后续重复 IO
            try {
                PVSourceDataCollection pvData = pvSourceLoaderService.generatePvSourceData(ps.getPolicyNo(), ps.getCertiNo(), runDate);
                ps.setPvSourceData(pvData);
                savePvSourceData(pvData,runDate); // 保存 PV 数据到数据库
            } catch (Exception e) {
                logger.logText("  - ⚠️ 加载 PV 数据失败: " + e.getMessage());
            }

            groupCohortState.getGroupPolicies().add(ps);
        }

        return groupCohortState;
    }

    /**
     * 运行初始确认流程
     * 计算初始 CSM/LC 并设置组的初始状态
     *
     * @param groupState 合同组状态对象
     * @param runDate    运行日期
     * @param valMethod  评估方法
     * @param logger     日志记录器
     */
    private void runInitialRecognition(GroupCohortState groupState, String runDate, String valMethod, CalculationLogger logger) {
        logger.logSection("第一部分: 初始确认");
        //组级别的CSM/LC
        BigDecimal groupInitialCsm = DECIMAL_ZERO;
        BigDecimal groupInitialLc = DECIMAL_ZERO;

        for (GroupPolicyState ps : groupState.getGroupPolicies()) {
            try {
                logger.logText("### 处理保单: " + ps.getPolicyNo()+ps.getCertiNo());
                log.info("处理保单: {}", ps.getPolicyNo());

                CalculationContext context = new CalculationContext();
                context.setPolicyNo(ps.getPolicyNo());
                context.setCertiNo(ps.getCertiNo());
                context.setUnderWriteDate(ps.getValuationDate());
                context.setStartDate(ps.getStartDate());
                context.setEndDate(ps.getEndDate());
                //保修责任止期
                context.setWarrantyEndDate(ps.getWarrantyEndDate());
                context.setYear(ps.getValuationDate().getYear());
                //签单日期
                context.setValMonthStr(ps.getValuationDate().format(YYYYMM));

                // 设置实际签单保费，用于加权锁定利率更新
                context.setActualPremium(ps.getWrittenPremium() != null ? ps.getWrittenPremium() : BigDecimal.ZERO);

                // 加载 PV 数据 (优先使用预加载)
                PVSourceDataCollection pvData = ps.getPvSourceData();
                if (pvData == null) {
                    pvData = pvSourceLoaderService.generatePvSourceData(ps.getPolicyNo(), ps.getCertiNo(), runDate);
                    ps.setPvSourceData(pvData);
                }
                context.setPvSourceData(pvData);

                // 加载签单时点利率曲线
                List<RateCurve> rates = ratesManagerService.getRates(context.getValMonthStr());
                context.setRatesDf(rates);
                context.setRatesDfLocked(rates); // 初始确认时锁定

                // 获取签单时点精算假设
                Assumptions assumptions = dataLoaderService.getAssumptions(ps.getClassCode(), context.getValMonthStr(), valMethod);
                context.setAssumptions(assumptions);

                //计算保单初始CSM/LC
                initialRecognitionService.run(context, logger, runDate);
                ps.setInitialCsm(context.getNbInitialCsm());
                ps.setInitialLc(context.getNbInitialLc());
                ps.setReversalPolicy(context.isReversalPolicy());

                if (context.getNbInitialLcCf() != null) {
                    ps.setInitialLcCf(context.getNbInitialLcCf());
                    ps.setInitialLcRa(context.getNbInitialLcRa());
                } else {
                    // 简化处理: 如果未拆分，假设全部为 CF (或在此处实现拆分逻辑)
                    // 目前暂设为 null 并在服务中处理
                    ps.setInitialLcCf(context.getNbInitialLc());
                    ps.setInitialLcRa(DECIMAL_ZERO);
                }

                groupInitialCsm = groupInitialCsm.add(ps.getInitialCsm());
                groupInitialLc = groupInitialLc.add(ps.getInitialLc());
                logger.logText("完成保单处理: " + ps.getPolicyNo());
            } catch (Throwable e) {
                logger.logText("❌ 处理保单 " + ps.getPolicyNo() + " 时发生错误: " + e.getMessage());
                log.error("处理保单 {} 时发生错误", ps.getPolicyNo(), e);
                throw new RuntimeException("处理保单 " + ps.getPolicyNo() + " 时发生严重错误", e);
            }
        }

        groupState.setNewCsm(groupInitialCsm);
        groupState.setNewLc(groupInitialLc);

        logger.logText("组初始 CSM: " + groupInitialCsm);
        logger.logText("组初始 LC: " + groupInitialLc);
    }

    /**
     * 执行生命周期仿真循环
     * 逐年计算 CSM/LC 变动、利息、释放等
     *
     * @param groupState 合同组状态对象
     * @param runDate    运行日期
     * @param valMethod  评估方法
     * @param logger     日志记录器
     */
    private List<Map<String, Object>> simulateLifecycle(GroupCohortState groupState, String runDate, String valMethod, CalculationLogger logger) {
        logger.logSection("开始组维度生命周期仿真");

        int runYear = Integer.parseInt(runDate.substring(0, 4));
        //取最小的签单日期作为起始年度
        int startYear = groupState.getGroupPolicies().stream()
                .map(p -> p.getValuationDate().getYear())
                .min(Integer::compareTo)
                .orElse(runYear);

        int endYear = groupState.getGroupPolicies().stream()
                .map(p -> p.getEndDate().getYear())
                .max(Integer::compareTo)
                .orElse(runYear);

        if (endYear > runYear) {
            logger.logText("⚠️ **警告**: 保单终止日期最晚为 " + endYear + "年，但数据库仅配置到 " + runYear + "年");
            logger.logText("   将仅计算到 " + runYear + "年底");
            endYear = runYear;
        }

        logger.logText("- **起始年度**: " + startYear);
        logger.logText("- **终止年度**: " + endYear);

         // 2. 逐年仿真
        List<Map<String, Object>> groupYearlyResults = new ArrayList<>();

        for (int year = startYear; year <= endYear; year++) {
            String valMonthStr = year + "12";
            LocalDate currentValDate = LocalDate.of(year, 12, 31);
            boolean isInitialYear = (year == startYear);
            //如果评估期只有一年，或者是最后一年，评估期取运行日期
            if((year == startYear && year == endYear) || year == endYear){
                valMonthStr = runDate;
                currentValDate = YearMonth.parse(runDate, DateTimeFormatter.ofPattern("yyyyMM")).atEndOfMonth();

            }

            logger.logSection("Year " + year + " 年度计量");
            logger.logText("### [Step 1] 确定评估时点");
            logger.logText("- **评估日期**: " + currentValDate);
            logger.logText("- **评估月份**: " + valMonthStr);

            // 1. 逐单计算: 利息 & 投资收益 (IFIE) 分配
            logger.logText("### [Step 2] 读取最新数据");

            List<RateCurve> ratesDfCurrent = ratesManagerService.getRates(valMonthStr);
            logger.logText("✅ 成功获取 " + valMonthStr + " 利率曲线 (" + ratesDfCurrent.size() + " 条记录)");

            if (isInitialYear) {
                groupState.setBopCsm(DECIMAL_ZERO);
                groupState.setBopLc(DECIMAL_ZERO);
            } else {
                // [修复] 非初始年度，从上一年期末值汇总更新期初值
                BigDecimal bopCsmGroup = BigDecimal.ZERO;
                BigDecimal bopLcGroup = BigDecimal.ZERO;

                for (GroupPolicyState ps : groupState.getGroupPolicies()) {
                     bopCsmGroup = bopCsmGroup.add(ps.getBopCsm() != null ? ps.getBopCsm() : BigDecimal.ZERO);
                     bopLcGroup = bopLcGroup.add(ps.getBopLc() != null ? ps.getBopLc() : BigDecimal.ZERO);
                }
                groupState.setBopCsm(bopCsmGroup);
                groupState.setBopLc(bopLcGroup);
            }

            logger.logText("### [Step 3] 逐单计算明细");

            List<CalculationContext> policyContexts = new ArrayList<>();

            for (GroupPolicyState ps : groupState.getGroupPolicies()) {
                logger.logText("DEBUG: Loop Year=" + year + ", Policy=" + ps.getPolicyNo() + ", StartDate=" + ps.getStartDate() + ", ValDate=" + ps.getValuationDate());
                // [修复] 使用 StartDate 和 ValuationDate 的较小值来判断是否开始处理
                 // 只要当前年份达到了 ValuationDate 或 WarrantyEndDate 其中之一，就开始处理（例如处理初始费用）
                //TODO 待审查
                int policyStartYear =  ps.getValuationDate().getYear();
                if (year < policyStartYear) {
                     logger.logText("DEBUG: Skipping policy " + ps.getPolicyNo() + " because Year " + year + " < PolicyStartYear " + policyStartYear + " (Min of WarrantyEnd/Val)");
                     continue;
                }

                logger.logText("#### 处理保单: " + ps.getPolicyNo()+ps.getCertiNo());

                CalculationContext context = new CalculationContext();
                context.setPolicyNo(ps.getPolicyNo());
                context.setCertiNo(ps.getCertiNo());
                // 评估日期为本仿真年度末
                context.setUnderWriteDate(ps.getValuationDate()); // 保持原始核保日期
                context.setValuationDate(currentValDate);
                context.setStartDate(ps.getStartDate());
                context.setEndDate(ps.getEndDate());
                context.setWarrantyEndDate(ps.getWarrantyEndDate());
                context.setYear(year);
                context.setValMonthStr(valMonthStr);
                context.setEopDate(currentValDate);
                context.setInitialYear(isInitialYear);
                context.setReversalPolicy(ps.isReversalPolicy());

                // 从 GroupPolicyState 设置期初 (BOP) 值
                context.setBopCsm(ps.getBopCsm());
                context.setBopLc(ps.getBopLc());
                context.setBopLcCf(ps.getBopLcCf());
                context.setBopLcRa(ps.getBopLcRa());
                // 设置 IACF 期初余额
                context.setBopIacf(ps.getBopIacf() != null ? ps.getBopIacf() : BigDecimal.ZERO);

                // 加载该评估日期的 PV 数据
                PVSourceDataCollection pvDataCollection = ps.getPvSourceData();
                if (pvDataCollection == null) {
                    pvDataCollection = pvSourceLoaderService.generatePvSourceData(ps.getPolicyNo(), ps.getCertiNo(), runDate);
                    ps.setPvSourceData(pvDataCollection);
                }
                context.setPvSourceData(pvDataCollection);

                // 获取当期 PV 数据
                PVSourceData pvData = pvDataCollection.getData(valMonthStr);

                // 判断是否为新业务年度:签单年是否等于当前评估年
                boolean isNewBusiness = (policyStartYear == year);
                logger.logText("DEBUG: Policy=" + ps.getPolicyNo() + ", ValDate=" + ps.getValuationDate() + ", Year=" + year + ", isNB=" + isNewBusiness);
                context.setNewBusiness(isNewBusiness);

                // 设置新业务 (NB) 值
                if (isNewBusiness) {
                    context.setNbInitialCsm(ps.getInitialCsm());
                    context.setNbInitialLc(ps.getInitialLc());
                    context.setNbInitialLcCf(ps.getInitialLcCf());
                    context.setNbInitialLcRa(ps.getInitialLcRa());

                    // 从 PV 数据设置初始确认时的预期流出 (用于 FulfillmentCashflowChangesService 检查)
                    if (pvData != null) {
                        context.setInitFutClaim(pvData.getPvNbIniCfaRecLkdClaAmt() != null ? pvData.getPvNbIniCfaRecLkdClaAmt() : BigDecimal.ZERO);
                        context.setInitFutMaint(pvData.getPvNbIniCfaRecLkdMtnAmt() != null ? pvData.getPvNbIniCfaRecLkdMtnAmt() : BigDecimal.ZERO);
                        context.setInitRa(pvData.getPvNbIniCfaRecLkdRadAmt() != null ? pvData.getPvNbIniCfaRecLkdRadAmt() : BigDecimal.ZERO);
                    } else {
                        throw new IllegalArgumentException("评估月份:" + valMonthStr + ",读取的PV数据为空");
                    }
                } else {
                    context.setNbInitialCsm(DECIMAL_ZERO);
                    context.setNbInitialLc(DECIMAL_ZERO);
                    context.setNbInitialLcCf(DECIMAL_ZERO);
                    context.setNbInitialLcRa(DECIMAL_ZERO);

                    // 非新业务年度，初始流出设为 0
                    context.setInitFutClaim(BigDecimal.ZERO);
                    context.setInitFutMaint(BigDecimal.ZERO);
                    context.setInitRa(BigDecimal.ZERO);
                }

                // 加载评估时点精算假设
                Assumptions assumptions = dataLoaderService.getAssumptions(ps.getClassCode(), valMonthStr, valMethod);
                context.setAssumptions(assumptions);

                // 加载利率
                context.setRatesDf(ratesDfCurrent);
                context.setRatesDfEop(ratesDfCurrent);
                // 锁定利率
                String uwMonthStr = ps.getUwMonthStr();
                List<RateCurve> lockedRates = ratesManagerService.getRates(uwMonthStr);
                context.setRatesDfLocked(lockedRates);
                context.setPolicies(Collections.singletonList(ps));

                // 设置实际现金流 (Cash Flows)
                if (isNewBusiness) {
                    context.setActualPremium(ps.getWrittenPremium());
                    // 计算实际 IACF
                    BigDecimal pvIacf = (pvData != null) ? pvData.getPvNbIniCfaRecLkdAcqAmt() : null;
                    logger.logText("DEBUG: Policy=" + ps.getPolicyNo() + ", Year=" + year + ", isNewBusiness=true, pvIacf=" + pvIacf + ", dbIacf=" + ps.getIacfAmount());
                    if (pvIacf != null) {
                        context.setInitPvIacf(pvIacf);
                    }
                    if (ps.getIacfAmount() != null) {
                        context.setActualIacfIncurred(ps.getIacfAmount());
                        logger.logText("DEBUG: Set ActualIacfIncurred from DB: " + ps.getIacfAmount());
                    } else if (pvIacf != null) {
                        // [修复] 使用PV数据中的预期获取费用现值作为实际发生额
                        context.setActualIacfIncurred(pvIacf);
                        logger.logText("DEBUG: Set ActualIacfIncurred from PV: " + pvIacf);
                    }
                } else {
                    context.setActualPremium(BigDecimal.ZERO);
                    context.setActualIacfIncurred(BigDecimal.ZERO);
                    logger.logText("DEBUG: Policy=" + ps.getPolicyNo() + ", Year=" + year + ", isNewBusiness=false, ActualIacf=0");
                }

                // --- 核心计算逻辑 ---
                String pUnitId = ps.getUnitId();
                if (pUnitId == null) {
                    pUnitId = ps.getPolicyNo() + ps.getCertiNo();
                }
                context.setUnitId(pUnitId);
                logger.logText("#### [年度初始计算] 处理保单: " + pUnitId);

                // 1. 履约现金流变化(精算假设变动对未来现金流的影响)
                fulfillmentCashflowChangesService.run(
                    context,
                    logger,
                    assumptions,
                    groupState,
                    Collections.singletonList(ps),
                    isNewBusiness
                );

                // 2. CSM 计息 (Part 3)
                csmLcMeasurementService.calculateCsmInterest(context, logger, groupState, ps);

                // 3. LC 分摊 IFIE (Part 7 - 前置部分)
                csmLcMeasurementService.calculateLcIfieAllocation(context, logger, groupState);

                //  4. IACF 摊销 (Part 6)
                //TODO 逻辑太啰嗦重复
                iacfAmortizationService.run(context, logger);

                //  5. IFIE 计算 (Part 8)
                ifieService.run(context, logger, assumptions, groupState);

                //  6. 期末未到期责任负债 (LRC Closing) - 仅计算部分，后续还会更新
                lrcClosingService.runClosing(context, logger);

                policyContexts.add(context);
            }

            if (policyContexts.isEmpty()){
                continue;
            }

            // 组级别汇总 & 分配
            logger.logSection("第二部分: 合同组状态判定");

            // 逐单计算中已正确处理了批减单的CSM/LC符号（正常单CSM>0/LC<0，批减单CSM<0/LC>0）。
            // 组级别只需将各保单结果汇总（代数相加），然后按统一标准（Net Trial >= 0 为 CSM）判断即可。
            //同一个合同组内的保单险类是相同的，所以可以直接取第一个保单的假设
            Assumptions currentAssumptions = policyContexts.get(0).getAssumptions();

            List<IPolicyGroupCalculationInput> inputs = new ArrayList<>(policyContexts);
            GroupStatusResult groupStatus = groupCsmLcMeasurementService.calculateGroupStatus(inputs, logger);

            groupCsmLcMeasurementService.allocateGroupCsmLcToPolicies(inputs, groupStatus, policyContexts, logger);

            // 更新组级状态
            groupState.setNetTrial(groupStatus.getNetTrial());
            groupState.setProfitable(groupStatus.getCohortCsm().compareTo(BigDecimal.ZERO) > 0);

            // [修复] 将分摊的组 CSM/LC 设置为摊销前余额，供后续计算使用
            for (CalculationContext ctx : policyContexts) {
                if (ctx.getAllocatedGroupCsm() != null) {
                    ctx.setEndCsmBeforeAmort(ctx.getAllocatedGroupCsm());
                } else {
                    ctx.setEndCsmBeforeAmort(BigDecimal.ZERO);
                }

                if (ctx.getAllocatedGroupLc() != null) {
                    ctx.setEndLcBeforeAmort(ctx.getAllocatedGroupLc());
                } else {
                    ctx.setEndLcBeforeAmort(BigDecimal.ZERO);
                }
            }

            logger.logSection("第三部分: CSM、LC计量");

            // [第三部分-步骤1&2] 逐单计算LC分摊比例和分摊的LC
            logger.logText("### [第三部分-步骤1&2] 逐单计算LC分摊比例和分摊的LC");
            for (CalculationContext ctx : policyContexts) {
                logger.logText("#### [LC分摊计算] 处理保单: " + ctx.getUnitId());
                // 使用合同组LC作为判断条件
                csmLcMeasurementService.calculateLcMeasurement(ctx, logger);
            }

            // [第三部分-步骤3&4] 组级别计算被LC/CSM吸收的变化并分摊到各保单
            logger.logText("### [第三部分-步骤3&4] 组级别计算被LC/CSM吸收的变化并分摊到各保单");
            GroupAbsorptionResult absorptionResult = groupCsmLcMeasurementService.runGroupAbsorptionAllocation(
                    policyContexts, groupStatus, logger, currentAssumptions,year
            );

            // [FIX] 更新真实的组级盈利状态，防止后续步骤使用错误的状态
            groupState.setProfitable(groupStatus.isProfitable());

            System.out.println("[DEBUG-BBA] " + year + " 组吸收计算结果: csmAbsorbed=" + absorptionResult.getGroupCsmAbsorbedTotal() + ", lcAbsorbed=" + absorptionResult.getGroupLcAbsorbedTotal());

            logger.logText(String.format("- 被CSM吸收的变化_合计: %,.2f", absorptionResult.getGroupCsmAbsorbedTotal()));
            logger.logText(String.format("- 被LC吸收的变化_合计: %,.2f", absorptionResult.getGroupLcAbsorbedTotal()));

            // 3. 逐单计算: CSM 计量 & 最终结账
            logger.logText("### [第三部分-步骤5] 逐单计算CSM计量");
            for (CalculationContext ctx : policyContexts) {
                logger.logText("#### [CSM计量] 处理保单: " + ctx.getUnitId());
                // [FIX] 强制同步组级别的状态到 Context，防止 calculateCsmMeasurement 内部判定错误
                ctx.setProfitable(groupStatus.isProfitable());

                // 查找 PolicyState
                GroupPolicyState ps = groupState.getGroupPolicies().stream()
                        .filter(p -> p.getPolicyNo().equals(ctx.getPolicyNo())
                                && (ctx.getCertiNo() == null || ctx.getCertiNo().equals(p.getCertiNo())))
                        .findFirst()
                        .orElse(null);

                if (ps == null) {
                    continue;
                }

                // 设置覆盖单元所需的保单列表 - 按照Python逻辑，应仅包含当前保单以计算单单级别比例
                // ctx.setPolicies(new ArrayList<>(groupState.getGroupPolicies())); // 原组级别逻辑
                ctx.setPolicies(Collections.singletonList(ps));

                // 计算该保单自己的 CSM 摊销比例（使用覆盖单元动态比例法）
                BigDecimal policyCsmAmortRatio;
                if (ctx.getPolicies() != null && !ctx.getPolicies().isEmpty()) {
                    LocalDate startOfYear = LocalDate.of(year, 1, 1);
                    boolean initialYear = ctx.isInitialYear();
                    BigDecimal cuReleased = coverageUnitsService.calculateCoverageUnitsReleased(
                            ctx.getPolicies(),
                            ctx.getEopDate(),
                            startOfYear,
                            logger,
                            initialYear
                    );
                    BigDecimal cuRemaining = coverageUnitsService.calculateCoverageUnitsRemaining(
                            ctx.getPolicies(),
                            ctx.getEopDate(),
                            logger
                    );

                    BigDecimal cuTotal = cuReleased.add(cuRemaining);
                    if (cuTotal.compareTo(BigDecimal.ZERO) > 0) {
                        policyCsmAmortRatio = cuReleased.divide(cuTotal, 16, RoundingMode.HALF_UP);
                    } else {
                        policyCsmAmortRatio = BigDecimal.ZERO;
                        // [FIX] 针对最后一年（满期），强制设比例为1.0以将摊销前余额全部抹平，确保期末负债归0。
                        if (cuReleased.compareTo(BigDecimal.ZERO) == 0 && cuRemaining.compareTo(BigDecimal.ZERO) == 0) {
                             policyCsmAmortRatio = BigDecimal.ONE;
                        }
                    }
                } else {
                    policyCsmAmortRatio = ctx.getIacfAmortRatio() != null ? ctx.getIacfAmortRatio() : BigDecimal.ZERO;
                }

                ctx.setCsmAmortRatio(policyCsmAmortRatio);

                // [修复] 计算该保单自己的CSM摊销比例
                // 这里应该在 calculateCsmMeasurement 内部计算，或者在这里计算并设置
                // CsmLcMeasurementService.calculateCsmMeasurement会调用 coverageUnitsService
                // 所以直接调用即可
                csmLcMeasurementService.calculateCsmMeasurement(ctx, logger);
            }

            logger.logText("### [第三部分-步骤6] 逐单计算LC计量的后续部分");
            for (CalculationContext ctx : policyContexts) {
                logger.logText("#### [LC后续/收入/结账] 处理保单: " + ctx.getUnitId());
                // [FIX] 再次调用前，确保状态已对齐
                ctx.setProfitable(groupStatus.isProfitable());

                // [修复] 再次调用 LC 计量以应用吸收变化 (Two-Pass Calculation)
                // 第一遍 (步骤1&2) 是为了计算 allocatedLc (无吸收)，供组级判断状态
                // 组级计算吸收后，回写到 context
                // 第二遍 (此处) 使用 context 中的吸收值计算最终的 endLc
                csmLcMeasurementService.calculateLcMeasurement(ctx, logger);

                System.out.println("[DEBUG-BBA] " + year + " unitId " + ctx.getUnitId() + " 最终计量结果: endCsm=" + ctx.getEndCsmFinal() + ", endLc=" + ctx.getEndLcFinal());

                // [修复] 7. 保单收入 (Revenue) - Part 7
                revenueService.run(ctx, logger);

                // [修复] 8. 最终结账 (LRC Closing) - Part 9
                lrcClosingService.runClosing(ctx, logger);

                // 查找 PolicyState 并更新期末值
                // [FIX] 使用 unitId 作为唯一标识进行匹配，确保主单和批单严格区分
                // 此时 ctx.getUnitId() 已经在 collectPolicyData 时构建并设置好了
                String targetUnitId = ctx.getUnitId();
                if (targetUnitId == null) {
                     // 如果 ctx 中没有 unitId（理论上不应该，因为之前已经设置了），尝试构建
                     targetUnitId = ctx.getCertiNo()+ ctx.getCertiNo();
                }

                String finalTargetUnitId = targetUnitId; // effective final for lambda

                GroupPolicyState ps = groupState.getGroupPolicies().stream()
                        .filter(p -> {
                            String pUnitId = p.getUnitId();
                            return pUnitId.equals(finalTargetUnitId);
                        })
                        .findFirst()
                        .orElse(null);

                if (ps != null) {
                    System.out.println("[DEBUG-BBA] 更新年度状态: UnitId=" + finalTargetUnitId +
                                       ", Year=" + year +
                                       ", EndCsm=" + ctx.getEndCsmFinal() +
                                       ", EndLc=" + ctx.getEndLcFinal());
                    // 更新 GroupPolicyState 的期末值，供下次迭代使用
                    ps.setBopCsm(ctx.getEndCsmFinal());
                    ps.setBopLc(ctx.getEndLcFinal());
                    ps.setBopLcCf(ctx.getEndLcCf());
                    ps.setBopLcRa(ctx.getEndLcRa());
                    ps.setBopIacf(ctx.getEopIacfBalance()); // 更新 IACF 余额
                }
            }

            // 4. 生成该年度的报告/日志 (可选)，并在MD日志中输出年度组级别汇总结果
            collectGroupYearlyResults(year, groupState.getGroupId(), policyContexts, groupYearlyResults, logger);

            // 5. 将计量结果映射到CalculationResult对象中保存到数据库
            saveCalculationResults(policyContexts, runDate);
        }

        // 生成组级别报表 (103 & 104)
        generateGroupReports(groupState.getGroupId(), groupYearlyResults, logger);
        return groupYearlyResults;
    }

    /**
     * 将计量结果映射到 CalculationResult 对象并保存到数据库
     *
     * @param policyContexts 保单计算上下文列表
     * @param runDateVal     运行日期
     */
    private void saveCalculationResults(List<CalculationContext> policyContexts, String runDateVal) {
        if (policyContexts == null || policyContexts.isEmpty()) {
            return;
        }

        List<com.bba.entity.CalculationResult> resultsToSave = new ArrayList<>();

        for (CalculationContext ctx : policyContexts) {
            com.bba.entity.CalculationResult result = new com.bba.entity.CalculationResult();
            result.setPolicyNo(ctx.getPolicyNo());
            result.setCertiNo(ctx.getCertiNo());
            result.setYear(ctx.getYear());
            result.setRunDate(runDateVal);

            // --- 新增合同初始确认相关 ---
            boolean isInitYear = (ctx.getUnderWriteDate().getYear() == ctx.getYear());
            if (isInitYear || (ctx.getNbInitialCsm() != null && (ctx.getNbInitialCsm().compareTo(BigDecimal.ZERO) != 0 || (ctx.getNbInitialLc() != null && ctx.getNbInitialLc().compareTo(BigDecimal.ZERO) != 0)))) {
                result.setNbInitialLc(ctx.getNbInitialLc());
                result.setNbInitPrem(ctx.getActualPremium());

                BigDecimal claims = (ctx.getInitFutClaim() != null ? ctx.getInitFutClaim() : BigDecimal.ZERO)
                                    .add(ctx.getInitFutMaint() != null ? ctx.getInitFutMaint() : BigDecimal.ZERO);
                result.setNbInitClaims(claims);
                result.setNbInitMaint(BigDecimal.ZERO);
                result.setNbInitIacf(ctx.getActualIacfIncurred());
                result.setNbInitRa(ctx.getInitRa());
                result.setNbInitCsm(ctx.getNbInitialCsm());

                BigDecimal nbLc = ctx.getNbInitialLc() != null ? ctx.getNbInitialLc() : BigDecimal.ZERO;
                boolean isProfitable = nbLc.compareTo(BigDecimal.ZERO) >= 0;

                if (isProfitable) {
                    result.setNbCfPremProfit(ctx.getActualPremium());
                    result.setNbCfIacfProfit(ctx.getActualIacfIncurred());
                    result.setNbCfClaimsProfit(claims);
                    result.setNbRaProfit(ctx.getInitRa());
                    result.setNbCsmProfit(ctx.getNbInitialCsm());
                } else {
                    result.setNbCfPremLoss(ctx.getActualPremium());
                    result.setNbCfIacfLoss(ctx.getActualIacfIncurred());
                    result.setNbCfClaimsLossNonLc(claims);
                    result.setNbRaLossNonLc(ctx.getInitRa());
                }

                result.setLcPlNbCfLc(ctx.getNbInitialLcCf());
                result.setLcPlNbRaLc(ctx.getNbInitialLcRa());
            }

            // --- 保险合同收入 ---
            result.setInsuranceRevenueClaimsExpensesGross(ctx.getRevenueClaimsExpensesGross());
            result.setInsuranceRevenueClaimsExpensesLcAlloc(ctx.getRevenueClaimsExpensesLcAlloc());
            result.setInsuranceRevenueRaReleaseGross(ctx.getRaReleaseGross());
            result.setInsuranceRevenueRaReleaseLcAlloc(ctx.getRaReleaseLcAlloc());
            result.setInsuranceRevenueCsmAmort(ctx.getCsmAmortAmount());
            result.setInsuranceRevenueIacfAmort(ctx.getIacfAmortAmount());

            BigDecimal expAdj = BigDecimal.ZERO;
            if (ctx.getExpAdjPrem() != null) expAdj = expAdj.add(ctx.getExpAdjPrem());
            if (ctx.getExpAdjIacf() != null) expAdj = expAdj.add(ctx.getExpAdjIacf());
            result.setInsuranceRevenueExpAdj(expAdj);

            // --- 赔付与费用 ---
            result.setClaimsExpensesLcAllocCf(ctx.getRevenueClaimsExpensesLcAlloc());
            result.setClaimsExpensesLcAllocRa(ctx.getRaReleaseLcAlloc());
            result.setClaimsExpensesIacfAmort(ctx.getIacfAmortAmount() != null ? ctx.getIacfAmortAmount().negate() : null);

            // --- 亏损合同损益 ---
            BigDecimal allocatedLcExpAdjTotal = ctx.getLcAbsorbedTotal() != null ? ctx.getLcAbsorbedTotal() : BigDecimal.ZERO;
            BigDecimal allocatedLcExpAdjCf = ctx.getLcAbsorbedCf() != null ? ctx.getLcAbsorbedCf() : BigDecimal.ZERO;
            BigDecimal allocatedLcExpAdjRa = allocatedLcExpAdjTotal.subtract(allocatedLcExpAdjCf);
            result.setLcPlCfChangeNoCsm(allocatedLcExpAdjCf);
            result.setLcPlRaChangeNoCsm(allocatedLcExpAdjRa);

            // --- IFIE P&L ---
            result.setIfiePlCfNonLc(ctx.getIfiePlCfNonLc());
            result.setIfiePlCfLc(ctx.getIfiePlCfLc());
            result.setIfiePlRaNonLc(ctx.getIfiePlRaNonLc());
            result.setIfiePlRaLc(ctx.getIfiePlRaLc());
            result.setIfiePlCsm(ctx.getIfiePlCsm());

            // --- IFIE OCI ---
            result.setIfieOciCfNonLc(ctx.getIfieOciCfNonLc());
            result.setIfieOciCfLc(ctx.getIfieOciCfLc());
            result.setIfieOciRaNonLc(ctx.getIfieOciRaNonLc());
            result.setIfieOciRaLc(ctx.getIfieOciRaLc());

            // --- 未到期_调整CSM ---
            BigDecimal csmAbsorbedTotal = ctx.getCsmAbsorbed() != null ? ctx.getCsmAbsorbed() : BigDecimal.ZERO;
            BigDecimal csmAbsorbedCf = ctx.getCsmAbsorbedCf() != null ? ctx.getCsmAbsorbedCf() : BigDecimal.ZERO;
            result.setLrcAdjCsmCfChange(csmAbsorbedCf);
            result.setLrcAdjCsmRaChange(csmAbsorbedTotal.subtract(csmAbsorbedCf));
            result.setLrcAdjCsmEstChange(csmAbsorbedTotal);

            // --- 现金流 ---
            result.setCashflowPremReceived(ctx.getActualPremium());
            result.setCashflowIacfPaid(ctx.getActualIacfIncurred());

            // --- 期末余额 ---
            result.setClosingBel(ctx.getEndBel());
            result.setClosingRa(ctx.getEndRa());
            result.setClosingCsm(ctx.getEndCsmFinal());
            result.setClosingLc(ctx.getEndLcFinal());
            result.setClosingLic(ctx.getEndLic());

            // --- 期初余额 ---
            result.setOpeningCsm(ctx.getBopCsm());
            result.setOpeningLc(ctx.getBopLc());

            // 添加到批量保存列表
            resultsToSave.add(result);
        }

        // 批量保存到数据库
        // 注意：mybatis-plus 默认提供 saveBatch，如果没有可以手动实现或分批 insert
        if (!resultsToSave.isEmpty()) {
            for (com.bba.entity.CalculationResult r : resultsToSave) {
                calculationResultMapper.insert(r);
            }
        }
    }

    /**
     * 将 PV 数据保存到数据库
     *
     * @param pvDataCollection PV 数据集合
     */
    private void savePvSourceData(PVSourceDataCollection pvDataCollection,String runDate) {
        if (pvDataCollection == null || pvDataCollection.getDataByMonth() == null || pvDataCollection.getDataByMonth().isEmpty()) {
            return;
        }

        List<PvSourceDataEntity> entitiesToSave = new ArrayList<>();

        for (PVSourceData pvData : pvDataCollection.getDataByMonth().values()) {
            try {
                com.bba.entity.PvSourceDataEntity entity = new com.bba.entity.PvSourceDataEntity();
                entity.setUnitId(pvData.getUnitId());
                entity.setRunDate(runDate);
                entity.setValuationMonth(pvData.getValuationMonth());
                entity.setValuationDate(pvData.getValuationDate());
                entity.setUnderWriteDate(pvData.getUnderWriteDate());

                // Nb Ini
                entity.setPvflNbIniCfaRecLkdPreAmt(pvData.getPvNbIniCfaRecLkdPreAmt());
                entity.setPvflNbIniCfaRecLkdAcqAmt(pvData.getPvNbIniCfaRecLkdAcqAmt());
                entity.setPvflNbIniCfaRecLkdClaAmt(pvData.getPvNbIniCfaRecLkdClaAmt());
                entity.setPvflNbIniCfaRecLkdMtnAmt(pvData.getPvNbIniCfaRecLkdMtnAmt());
                entity.setPvflNbIniCfaRecLkdRadAmt(pvData.getPvNbIniCfaRecLkdRadAmt());

                // Nb Eop
                entity.setPvflNbEopCfaRepWlkPreAmt(pvData.getPvNbEopCfaRepWlkPreAmt());
                entity.setPvflNbEopCfaRepWlkClaAmt(pvData.getPvNbEopCfaRepWlkClaAmt());
                entity.setPvflNbEopCfaRepWlkMtnAmt(pvData.getPvNbEopCfaRepWlkMtnAmt());
                entity.setPvflNbEopCfaRepWlkRadAmt(pvData.getPvNbEopCfaRepWlkRadAmt());

                entity.setPvflNbEopCfaRepCurPreAmt(pvData.getPvNbEopCfaRepCurPreAmt());
                entity.setPvflNbEopCfaRepCurClaAmt(pvData.getPvNbEopCfaRepCurClaAmt());
                entity.setPvflNbEopCfaRepCurMtnAmt(pvData.getPvNbEopCfaRepCurMtnAmt());
                entity.setPvflNbEopCfaRepCurRadAmt(pvData.getPvNbEopCfaRepCurRadAmt());
                entity.setPvflNbEopCfaRepCurAcqAmt(pvData.getPvNbEopCfaRepCurAcqAmt());

                entity.setPvflNbEopCcaRepWlkClaAmt(pvData.getPvNbEopCcaRepWlkClaAmt());
                entity.setPvflNbEopCcaRepWlkMtnAmt(pvData.getPvNbEopCcaRepWlkMtnAmt());
                entity.setPvflNbEopCcaRepWlkRadAmt(pvData.getPvNbEopCcaRepWlkRadAmt());
                entity.setPvflNbEopCcaRepWlkPreAmt(pvData.getPvNbEopCcaRepWlkPreAmt());
                entity.setPvflNbEopCcaRepWlkAcqAmt(pvData.getPvNbEopCcaRepWlkAcqAmt());

                // Nb Ini at Eop
                entity.setPvflNbIniCfaRepWlkPreAmt(pvData.getPvNbIniCfaRepWlkPreAmt());
                entity.setPvflNbIniCcaRepWlkPreAmt(pvData.getPvNbIniCcaRepWlkPreAmt());
                entity.setPvflNbIniCfaRepWlkAcqAmt(pvData.getPvNbIniCfaRepWlkAcqAmt());
                entity.setPvflNbIniCcaRepWlkAcqAmt(pvData.getPvNbIniCcaRepWlkAcqAmt());
                entity.setPvflNbIniCcaRepWlkClaAmt(pvData.getPvNbIniCcaRepWlkClaAmt());
                entity.setPvflNbIniCcaRepWlkMtnAmt(pvData.getPvNbIniCcaRepWlkMtnAmt());
                entity.setPvflNbIniCcaRepWlkRadAmt(pvData.getPvNbIniCcaRepWlkRadAmt());
                entity.setPvflNbEopCfaRepWlkAcqAmt(pvData.getPvNbEopCfaRepWlkAcqAmt());
                entity.setPvflNbIniCfaRepWlkClaAmt(pvData.getPvNbIniCfaRepWlkClaAmt());
                entity.setPvflNbIniCfaRepWlkMtnAmt(pvData.getPvNbIniCfaRepWlkMtnAmt());
                entity.setPvflNbIniCfaRepWlkRadAmt(pvData.getPvNbIniCfaRepWlkRadAmt());

                // If Bop
                entity.setPvflIfBopCfaBegLcuClaAmt(pvData.getPvIfBopCfaBegLcuClaAmt());
                entity.setPvflIfBopCfaBegLcuMtnAmt(pvData.getPvIfBopCfaBegLcuMtnAmt());
                entity.setPvflIfBopCfaBegLcuRadAmt(pvData.getPvIfBopCfaBegLcuRadAmt());

                entity.setPvflIfBopCfaRepWlkPreAmt(pvData.getPvIfBopCfaRepWlkPreAmt());
                entity.setPvflIfBopCfaRepWlkAcqAmt(pvData.getPvIfBopCfaRepWlkAcqAmt());
                entity.setPvflIfBopCfaRepWlkClaAmt(pvData.getPvIfBopCfaRepWlkClaAmt());
                entity.setPvflIfBopCfaRepWlkMtnAmt(pvData.getPvIfBopCfaRepWlkMtnAmt());
                entity.setPvflIfBopCfaRepWlkRadAmt(pvData.getPvIfBopCfaRepWlkRadAmt());

                entity.setPvflIfBopCcaRepWlkPreAmt(pvData.getPvIfBopCcaRepWlkPreAmt());
                entity.setPvflIfBopCcaRepWlkAcqAmt(pvData.getPvIfBopCcaRepWlkAcqAmt());
                entity.setPvflIfBopCcaRepWlkClaAmt(pvData.getPvIfBopCcaRepWlkClaAmt());
                entity.setPvflIfBopCcaRepWlkMtnAmt(pvData.getPvIfBopCcaRepWlkMtnAmt());
                entity.setPvflIfBopCcaRepWlkRadAmt(pvData.getPvIfBopCcaRepWlkRadAmt());

                entity.setPvflIfBopCfaBegWlkClaAmt(pvData.getPvIfBopCfaBegWlkClaAmt());
                entity.setPvflIfBopCfaBegWlkMtnAmt(pvData.getPvIfBopCfaBegWlkMtnAmt());
                entity.setPvflIfBopCfaBegWlkRadAmt(pvData.getPvIfBopCfaBegWlkRadAmt());

                // If Eop
                entity.setPvflIfEopCfaRepWlkPreAmt(pvData.getPvIfEopCfaRepWlkPreAmt());
                entity.setPvflIfEopCfaRepWlkAcqAmt(pvData.getPvIfEopCfaRepWlkAcqAmt());
                entity.setPvflIfEopCfaRepWlkClaAmt(pvData.getPvIfEopCfaRepWlkClaAmt());
                entity.setPvflIfEopCfaRepWlkMtnAmt(pvData.getPvIfEopCfaRepWlkMtnAmt());
                entity.setPvflIfEopCfaRepWlkRadAmt(pvData.getPvIfEopCfaRepWlkRadAmt());

                entity.setPvflIfEopCfaRepCurClaAmt(pvData.getPvIfEopCfaRepCurClaAmt());
                entity.setPvflIfEopCfaRepCurMtnAmt(pvData.getPvIfEopCfaRepCurMtnAmt());
                entity.setPvflIfEopCfaRepCurRadAmt(pvData.getPvIfEopCfaRepCurRadAmt());
                entity.setPvflIfEopCfaRepCurPreAmt(pvData.getPvIfEopCfaRepCurPreAmt());
                entity.setPvflIfEopCfaRepCurAcqAmt(pvData.getPvIfEopCfaRepCurAcqAmt());

                entity.setPvflIfEopCcaRepWlkPreAmt(pvData.getPvIfEopCcaRepWlkPreAmt());
                entity.setPvflIfEopCcaRepWlkAcqAmt(pvData.getPvIfEopCcaRepWlkAcqAmt());
                entity.setPvflIfEopCcaRepWlkClaAmt(pvData.getPvIfEopCcaRepWlkClaAmt());
                entity.setPvflIfEopCcaRepWlkMtnAmt(pvData.getPvIfEopCcaRepWlkMtnAmt());
                entity.setPvflIfEopCcaRepWlkRadAmt(pvData.getPvIfEopCcaRepWlkRadAmt());

                entity.setCreatedAt(java.time.LocalDateTime.now());

                entitiesToSave.add(entity);
            } catch (Exception e) {
                log.error("保存 PV 数据失败，unitId: {}, month: {}", pvData.getUnitId(), pvData.getValuationMonth(), e);
            }
        }

        // 批量插入
        if (!entitiesToSave.isEmpty()) {
            for (PvSourceDataEntity entity : entitiesToSave) {
                pvSourceDataMapper.insert(entity);
            }
        }
    }

    private void collectGroupYearlyResults(
            int year,
            String groupId,
            List<CalculationContext> policyContexts,
            List<Map<String, Object>> resultsList,
            CalculationLogger logger
    ) {
        // 导出收入明细以供校验
        exportRevenueDetails(year, groupId, policyContexts, logger);

        Map<String, Object> res = new HashMap<>();
        res.put("year", year);
        res.put("policy_no", groupId);
        res.put("certi_no", ""); // 组级别无批单号

        // 1. Opening Balances (For first year, assume 0 as we start from inception)
        if (resultsList.isEmpty()) {
            res.put("opening_bel", BigDecimal.ZERO);
            res.put("opening_ra", BigDecimal.ZERO);
            res.put("opening_csm", BigDecimal.ZERO);
            res.put("opening_lc", BigDecimal.ZERO);
            res.put("opening_lic", BigDecimal.ZERO);
        }

        // 2. Aggregate Flow Variables
        BigDecimal nbInitialLc = BigDecimal.ZERO;
        BigDecimal nbInitialCsm = BigDecimal.ZERO; // Needed for 104
        BigDecimal nbLcIfieTotal = BigDecimal.ZERO; // Added NB_LC IFIE aggregation
        BigDecimal nbInitPrem = BigDecimal.ZERO;
        BigDecimal nbInitClaims = BigDecimal.ZERO;
        BigDecimal nbInitMaint = BigDecimal.ZERO;
        BigDecimal nbInitIacf = BigDecimal.ZERO;
        BigDecimal nbInitRa = BigDecimal.ZERO;

        // New Business P&L Breakdown
        BigDecimal nbClaimsProfit = BigDecimal.ZERO;
        BigDecimal nbIacfProfit = BigDecimal.ZERO;
        BigDecimal nbPremProfit = BigDecimal.ZERO;
        BigDecimal nbClaimsLossNonLc = BigDecimal.ZERO;
        BigDecimal lossNbCf = BigDecimal.ZERO;
        BigDecimal nbIacfLoss = BigDecimal.ZERO;
        BigDecimal nbPremLoss = BigDecimal.ZERO;
        BigDecimal nbRaProfit = BigDecimal.ZERO;
        BigDecimal nbRaLossNonLc = BigDecimal.ZERO;
        BigDecimal nbRaLossLc = BigDecimal.ZERO;
        BigDecimal nbCsmProfit = BigDecimal.ZERO;

        BigDecimal csmAmort = BigDecimal.ZERO;
        BigDecimal iacfAmort = BigDecimal.ZERO;
        BigDecimal expAdj = BigDecimal.ZERO;
        BigDecimal releaseClaimsGross = BigDecimal.ZERO;
        BigDecimal releaseRaGross = BigDecimal.ZERO;
        BigDecimal releaseClaimsLcAlloc = BigDecimal.ZERO;
        BigDecimal releaseRaLcAlloc = BigDecimal.ZERO;

        // IFIE P&L
        BigDecimal ifiePlCfNonLc = BigDecimal.ZERO;
        BigDecimal ifiePlRaNonLc = BigDecimal.ZERO;
        BigDecimal ifiePlCsm = BigDecimal.ZERO;
        BigDecimal ifiePlCfLc = BigDecimal.ZERO;
        BigDecimal ifiePlRaLc = BigDecimal.ZERO;

        // IFIE OCI
        BigDecimal ifieOciCfNonLc = BigDecimal.ZERO;
        BigDecimal ifieOciRaNonLc = BigDecimal.ZERO;
        BigDecimal ifieOciCfLc = BigDecimal.ZERO;
        BigDecimal ifieOciRaLc = BigDecimal.ZERO;

        // Cash Flows
        BigDecimal cfPrem = BigDecimal.ZERO;
        BigDecimal cfIacf = BigDecimal.ZERO;

        // Closing Balances (for verification)
        BigDecimal closingBel = BigDecimal.ZERO;
        BigDecimal closingRa = BigDecimal.ZERO;
        BigDecimal closingCsm = BigDecimal.ZERO;
        BigDecimal closingLc = BigDecimal.ZERO;
        BigDecimal closingLic = BigDecimal.ZERO;

        BigDecimal csmAbsorbedCfTotal = BigDecimal.ZERO;
        BigDecimal csmAbsorbedRaTotal = BigDecimal.ZERO;
        BigDecimal csmAbsorbedEstTotal = BigDecimal.ZERO;

        // 新增: LC吸收变动累加
        BigDecimal lcAbsorbedCfTotal = BigDecimal.ZERO;
        BigDecimal lcAbsorbedRaTotal = BigDecimal.ZERO;

        for (CalculationContext ctx : policyContexts) {
            // Check if policy is active in this year or if it is the initial recognition year
            boolean isInitYear = (ctx.getUnderWriteDate().getYear() == year);
            if (year < ctx.getStartDate().getYear() && !isInitYear) {
                if (year == 2021) {
                    System.out.println("DEBUG_SIM_2021: Skipping policy " + ctx.getPolicyNo() + " (Issue Year: " + ctx.getStartDate().getYear() + ")");
                }
                continue;
            }

            // DEBUG: Log for mock5
            if (ctx.getPolicyNo().contains("mock5")) {
                System.out.println("DEBUG_MOCK5: Processing mock5 in year " + year +
                    ". InitCsm: " + ctx.getNbInitialCsm() +
                    ", InitLc: " + ctx.getNbInitialLc() +
                    ", UWDate: " + ctx.getUnderWriteDate() +
                    ", StartDate: " + ctx.getStartDate() +
                    ", isInitYear: " + isInitYear);
            }

            // New Business
            if (ctx.getNbInitialLc() != null){
                nbInitialLc = nbInitialLc.add(ctx.getNbInitialLc());
            }
            if (ctx.getNbInitialCsm() != null){
                nbInitialCsm = nbInitialCsm.add(ctx.getNbInitialCsm());
            }
            if (ctx.getNbLcIfieTotal() != null){
                nbLcIfieTotal = nbLcIfieTotal.add(ctx.getNbLcIfieTotal());
            }

            // Retrieve NB flows from PV Data if is new business
            // Use isInitYear as primary check, or check if NB CSM/LC is set
            if (isInitYear || (ctx.getNbInitialCsm() != null && (ctx.getNbInitialCsm().compareTo(BigDecimal.ZERO) != 0 || (ctx.getNbInitialLc() != null && ctx.getNbInitialLc().compareTo(BigDecimal.ZERO) != 0)))) {
                // Determine if Profitable or Onerous based on nbInitialLc
                // Python logic: if nb_initial_lc >= 0 (Profitable), else (Onerous)
                // Note: nbInitialLc is 0 for profitable contracts in our logic (margin goes to CSM)
                // So >= 0 covers 0.

                BigDecimal nbLc = ctx.getNbInitialLc() != null ? ctx.getNbInitialLc() : BigDecimal.ZERO;
                boolean isProfitable = nbLc.compareTo(BigDecimal.ZERO) >= 0;

                BigDecimal prem = ctx.getActualPremium() != null ? ctx.getActualPremium() : BigDecimal.ZERO;
                BigDecimal iacf = ctx.getActualIacfIncurred() != null ? ctx.getActualIacfIncurred() : BigDecimal.ZERO;
                BigDecimal claims = (ctx.getInitFutClaim() != null ? ctx.getInitFutClaim() : BigDecimal.ZERO)
                                    .add(ctx.getInitFutMaint() != null ? ctx.getInitFutMaint() : BigDecimal.ZERO);
                BigDecimal ra = ctx.getInitRa() != null ? ctx.getInitRa() : BigDecimal.ZERO;
                BigDecimal nbCsm = ctx.getNbInitialCsm() != null ? ctx.getNbInitialCsm() : BigDecimal.ZERO;
                BigDecimal nbLcCf = ctx.getNbInitialLcCf() != null ? ctx.getNbInitialLcCf() : BigDecimal.ZERO;
                BigDecimal nbLcRa = ctx.getNbInitialLcRa() != null ? ctx.getNbInitialLcRa() : BigDecimal.ZERO;

                if (isProfitable) {
                    // 盈利合同
                    nbPremProfit = nbPremProfit.add(prem);
                    nbIacfProfit = nbIacfProfit.add(iacf);
                    nbClaimsProfit = nbClaimsProfit.add(claims);
                    nbRaProfit = nbRaProfit.add(ra);
                    nbCsmProfit = nbCsmProfit.add(nbCsm);
                } else {
                    // 亏损合同
                    nbPremLoss = nbPremLoss.add(prem);
                    nbIacfLoss = nbIacfLoss.add(iacf);

                    // Python puts FULL claims into "nbClaimsLossNonLc" (despite the name)
                    nbClaimsLossNonLc = nbClaimsLossNonLc.add(claims);

                    // Python puts FULL RA into "nbRaLossNonLc"
                    nbRaLossNonLc = nbRaLossNonLc.add(ra);

                    // LC Components (tracked separately)
                    lossNbCf = lossNbCf.add(nbLcCf);
                    // nbRaLossLc = nbRaLossLc.add(nbLcRa); // Not explicitly used in 104 BEL/RA calc in Python, but good to have
                }

                // Accumulate totals for verification/other reports if needed
                nbInitPrem = nbInitPrem.add(prem);
                nbInitClaims = nbInitClaims.add(claims); // This duplicates logic but keeps original variables populated
                nbInitMaint = nbInitMaint.add(BigDecimal.ZERO); // Included in claims above
                nbInitIacf = nbInitIacf.add(iacf);
                nbInitRa = nbInitRa.add(ra);
            }

            // Revenue
            if (ctx.getCsmAmortAmount() != null){
                csmAmort = csmAmort.add(ctx.getCsmAmortAmount());
            }
            if (ctx.getIacfAmortAmount() != null){
                iacfAmort = iacfAmort.add(ctx.getIacfAmortAmount());
            }

            // Exp Adj
            BigDecimal polExpAdj = BigDecimal.ZERO;
            if (ctx.getExpAdjPrem() != null){
                polExpAdj = polExpAdj.add(ctx.getExpAdjPrem());
            }
            if (ctx.getExpAdjIacf() != null){
                polExpAdj = polExpAdj.add(ctx.getExpAdjIacf());
            }
            expAdj = expAdj.add(polExpAdj);

            // 累加 CSM 吸收的变动 (Report 104 Row 9)
            BigDecimal deltaCfTotal = ctx.getDeltaCfTotal() != null ? ctx.getDeltaCfTotal() : BigDecimal.ZERO;
            BigDecimal deltaCsmLc = ctx.getExpAdjCsmImpact() != null ? ctx.getExpAdjCsmImpact() : BigDecimal.ZERO;

            // [修复] 直接使用 GroupCsmLcMeasurementService 回写到 Context 中的 CSM 吸收值
            // 之前的逻辑是通过 deltaCsmLc - allocatedLcExpAdjTotal 反算，这在组级分摊（如扭亏为盈）场景下不准确
            BigDecimal csmAbsorbedTotal = ctx.getCsmAbsorbed() != null ? ctx.getCsmAbsorbed() : BigDecimal.ZERO;
            BigDecimal csmAbsorbedCf = ctx.getCsmAbsorbedCf() != null ? ctx.getCsmAbsorbedCf() : BigDecimal.ZERO;

            csmAbsorbedCfTotal = csmAbsorbedCfTotal.add(csmAbsorbedCf);
            csmAbsorbedRaTotal = csmAbsorbedRaTotal.add(csmAbsorbedTotal.subtract(csmAbsorbedCf));
            csmAbsorbedEstTotal = csmAbsorbedEstTotal.add(csmAbsorbedTotal);

            // [修复] 恢复变量定义，供后续使用
            BigDecimal allocatedLcExpAdjTotal = ctx.getLcAbsorbedTotal() != null ? ctx.getLcAbsorbedTotal() : BigDecimal.ZERO;
            BigDecimal allocatedLcExpAdjCf = ctx.getLcAbsorbedCf() != null ? ctx.getLcAbsorbedCf() : BigDecimal.ZERO;
            BigDecimal allocatedLcExpAdjRa = allocatedLcExpAdjTotal.subtract(allocatedLcExpAdjCf);

            // [修复] 累加 LC 吸收的变动 (Report 104 Row 10)
            lcAbsorbedCfTotal = lcAbsorbedCfTotal.add(allocatedLcExpAdjCf);
            lcAbsorbedRaTotal = lcAbsorbedRaTotal.add(allocatedLcExpAdjRa);

            // Release (Gross)
            BigDecimal netClaims = ctx.getRevenueClaimsExpensesNet() != null ? ctx.getRevenueClaimsExpensesNet() : BigDecimal.ZERO;
            BigDecimal allocClaims = ctx.getRevenueClaimsExpensesLcAlloc() != null ? ctx.getRevenueClaimsExpensesLcAlloc() : BigDecimal.ZERO;
            BigDecimal grossClaims = ctx.getRevenueClaimsExpensesGross();
            if (grossClaims == null){
                grossClaims = netClaims.add(allocClaims);
            }

            releaseClaimsGross = releaseClaimsGross.add(grossClaims);
            releaseClaimsLcAlloc = releaseClaimsLcAlloc.add(allocClaims);

            // RA Release
            BigDecimal netRa = ctx.getRaReleaseNet() != null ? ctx.getRaReleaseNet() : BigDecimal.ZERO;
            BigDecimal allocRa = ctx.getRaReleaseLcAlloc() != null ? ctx.getRaReleaseLcAlloc() : BigDecimal.ZERO;
            BigDecimal grossRa = ctx.getRaReleaseGross();
            if (grossRa == null){
                grossRa = netRa.add(allocRa);
            }

            releaseRaGross = releaseRaGross.add(grossRa);
            releaseRaLcAlloc = releaseRaLcAlloc.add(allocRa);

            // IFIE
            if (ctx.getIfiePlCfNonLc() != null) {
                ifiePlCfNonLc = ifiePlCfNonLc.add(ctx.getIfiePlCfNonLc());
            }
            if (ctx.getIfiePlRaNonLc() != null) {
                ifiePlRaNonLc = ifiePlRaNonLc.add(ctx.getIfiePlRaNonLc());
            }
            if (ctx.getIfiePlCsm() != null) {
                ifiePlCsm = ifiePlCsm.add(ctx.getIfiePlCsm());
                System.out.println("DEBUG_AGG: Year=" + year + ", Policy=" + ctx.getPolicyNo() + ", IFIE_Pl_Csm=" + ctx.getIfiePlCsm());
            }
            if (ctx.getIfiePlCfLc() != null) {
                ifiePlCfLc = ifiePlCfLc.add(ctx.getIfiePlCfLc());
            }
            if (ctx.getIfiePlRaLc() != null) {
                ifiePlRaLc = ifiePlRaLc.add(ctx.getIfiePlRaLc());
            }

            if (ctx.getIfieOciCfNonLc() != null) {
                ifieOciCfNonLc = ifieOciCfNonLc.add(ctx.getIfieOciCfNonLc());
            }
            if (ctx.getIfieOciRaNonLc() != null) {
                ifieOciRaNonLc = ifieOciRaNonLc.add(ctx.getIfieOciRaNonLc());
            }
            if (ctx.getIfieOciCfLc() != null) {
                ifieOciCfLc = ifieOciCfLc.add(ctx.getIfieOciCfLc());
            }
            if (ctx.getIfieOciRaLc() != null) {
                ifieOciRaLc = ifieOciRaLc.add(ctx.getIfieOciRaLc());
            }

            // Cash Flows (From PV Data - Actuals? Or Expected?)
            // ReportGenerator uses "现金流_收到的保费". Usually actuals.
            // In Context, we have actualPremium, actualIacfIncurred etc.
            if (ctx.getActualPremium() != null){
                cfPrem = cfPrem.add(ctx.getActualPremium());
            }
            if (ctx.getActualIacfIncurred() != null) {
                cfIacf = cfIacf.add(ctx.getActualIacfIncurred());
                System.out.println("DEBUG: Aggregating IACF: " + ctx.getActualIacfIncurred() + " Total: " + cfIacf);
            }

            // Closing
            if (ctx.getEndBel() != null){
                closingBel = closingBel.add(ctx.getEndBel());
            }
            if (ctx.getEndRa() != null){
                closingRa = closingRa.add(ctx.getEndRa());
            }
            if (ctx.getEndCsmFinal() != null){
                closingCsm = closingCsm.add(ctx.getEndCsmFinal());
            }
            if (ctx.getEndLcFinal() != null){
                closingLc = closingLc.add(ctx.getEndLcFinal());
            }
            if (ctx.getEndLic() != null){
                closingLic = closingLic.add(ctx.getEndLic());
            }
        }

        res.put("nb_initial_lc", nbInitialLc);
        res.put("nb_lc_ifie", nbLcIfieTotal); // Added for Report 103 Row 7
        res.put("nb_init_csm", nbInitialCsm); // For 104
        res.put("nb_init_prem", nbInitPrem);
        res.put("nb_init_claims", nbInitClaims);
        res.put("nb_init_maint", nbInitMaint);
        res.put("nb_init_iacf", nbInitIacf);
        res.put("nb_init_ra", nbInitRa);

        res.put("新增合同预期现金流_赔付与费用现金流_盈利合同", nbClaimsProfit);
        res.put("新增合同预期现金流_IACF_盈利合同", nbIacfProfit);
        res.put("新增合同预期现金流_保费现金流_盈利合同", nbPremProfit);
        res.put("新增合同预期现金流_赔付与费用现金流_亏损合同_非亏损", nbClaimsLossNonLc);
        res.put("亏损合同损益_新增合同预期现金流_赔付与费用现金流_亏损", lossNbCf);
        res.put("新增合同预期现金流_IACF_亏损合同", nbIacfLoss);
        res.put("新增合同预期现金流_保费现金流_亏损合同", nbPremLoss);

        res.put("新增合同非金融风险调整_盈利合同", nbRaProfit);
        res.put("新增合同非金融风险调整_亏损合同_非亏损", nbRaLossNonLc);
        res.put("亏损合同损益_新增合同非金融风险调整_亏损", nbRaLossLc);

        res.put("新增合同CSM_盈利合同", nbCsmProfit);

        // [修复] CSM和IACF摊销在Context中已为负数（收入），此处直接存储，无需取反
        // ReportGenerator将直接读取这些负数值
        res.put("保险合同收入_摊销的CSM", csmAmort);
        res.put("保险合同收入_摊销的IACF", iacfAmort);

        // [修复] 费用侧应为正数
        res.put("赔付与费用_摊销的IACF", iacfAmort.negate());

        res.put("保险合同收入_经验调整", expAdj);

        res.put("保险合同收入_预期赔付与费用_含亏损", releaseClaimsGross);
        res.put("保险合同收入_预期释放的非金融风险调整_含亏损", releaseRaGross);
        res.put("保险合同收入_预期赔付与费用_亏损分摊", releaseClaimsLcAlloc);
        res.put("保险合同收入_预期释放的非金融风险调整_亏损分摊", releaseRaLcAlloc);

        // LC Change Est (Loss Component P&L - Changes in estimates)
        // This is hard to get directly aggregated if not explicitly stored.
        // ReportGenerator uses "亏损合同损益_不调整CSM的预期现金流变动" and "RA...".
        // This corresponds to changes in FCF that exceed CSM or are allocated to LC.
        // We can approximate or use 0 if not tracked.
        // In GroupCsmLcMeasurementService, `group_absorption` calculates `lc_absorbed_changes`.
        // This `lc_absorbed_changes` is what goes into LC.
        // But ReportGenerator expects breakdown?
        // Let's put 0 for now or try to get from context if possible.
        // Context has `allocatedLcTotal`?
        res.put("亏损合同损益_不调整CSM的预期现金流变动", lcAbsorbedCfTotal);
        res.put("亏损合同损益_不调整CSM的非金融风险调整变动", lcAbsorbedRaTotal);

        res.put("未到期_调整CSM的预期现金流变动", csmAbsorbedCfTotal);
        res.put("未到期_调整CSM的非金融风险调整变动", csmAbsorbedRaTotal);
        res.put("未到期_调整CSM的估计变更", csmAbsorbedEstTotal);

        res.put("IFIE_P&L_未到期_预期现金流_非亏损", ifiePlCfNonLc);
        res.put("IFIE_P&L_未到期_非金融风险调整_非亏损", ifiePlRaNonLc);
        System.out.println("DEBUG_RES: Year=" + year + ", IFIE_Pl_Csm_Total=" + ifiePlCsm);
        res.put("IFIE_P&L_未到期_CSM", ifiePlCsm);
        res.put("IFIE_P&L_未到期_预期现金流_亏损", ifiePlCfLc);
        res.put("IFIE_P&L_未到期_非金融风险调整_亏损", ifiePlRaLc);

        res.put("IFIE_OCI_未到期_预期现金流_非亏损", ifieOciCfNonLc);
        res.put("IFIE_OCI_未到期_非金融风险调整_非亏损", ifieOciRaNonLc);
        res.put("IFIE_OCI_未到期_预期现金流_亏损", ifieOciCfLc);
        res.put("IFIE_OCI_未到期_非金融风险调整_亏损", ifieOciRaLc);

        res.put("现金流_收到的保费", cfPrem);
        res.put("现金流_支付的获取费用", cfIacf);

        res.put("closing_bel", closingBel);
        res.put("closing_ra", closingRa);
        res.put("closing_csm", closingCsm);
        res.put("closing_lc", closingLc);
        res.put("closing_lic", closingLic);

        resultsList.add(res);

        // 在MD日志中输出“Part 6: 年度结果汇总（组级关键指标）”板块，模拟python版本的组级年度汇总日志
        // 这里直接输出本年度组级别的关键字段汇总，便于与组级103/104报表对账
        if (logger != null) {
            logger.logSection("Part 6: 年度结果汇总（组级关键指标）");
            logger.logText("### 年度组级汇总 - Year: " + year + ", Group: " + groupId);
            for (Map.Entry<String, Object> entry : res.entrySet()) {
                String fieldName = entry.getKey();
                Object value = entry.getValue();
                logger.logText("- " + fieldName + ": " + value);
            }
        }
    }

    private void generateGroupReports(String groupId, List<Map<String, Object>> results, CalculationLogger logger) {
        try {
            Files.createDirectories(Paths.get("logs"));
            String reportPath103 = "logs/report_103_group_" + groupId + ".html";
            String reportPath104 = "logs/report_104_group_" + groupId + ".html";

            ReportGenerator.generate103Report(results, reportPath103);
            logger.logText("已生成组级别 103 报表: " + reportPath103);

            ReportGenerator.generate104Report(results, reportPath104);
            logger.logText("已生成组级别 104 报表: " + reportPath104);
        } catch (Exception e) {
            logger.logText("❌ 生成组级别报表失败: " + e.getMessage());
            log.error("生成组级别报表失败", e);
        }
    }

    private void exportRevenueDetails(int year, String groupId, List<CalculationContext> contexts, CalculationLogger logger) {
        String csvPath = "logs/revenue_check_details_group_" + groupId + ".csv";
        try {
            boolean isNewFile = !Files.exists(Paths.get(csvPath));
            StringBuilder sb = new StringBuilder();
            if (isNewFile) {
                // 添加BOM以支持Excel正确显示中文
                sb.append('\ufeff');
                sb.append("Year,PolicyNo,Rev_CsmAmort,Rev_IacfAmort_Negated,Rev_ExpAdj,Rev_Claims_Net,Rev_Ra_Net,Revenue_NonLc_Total\n");
            }

            for (CalculationContext ctx : contexts) {
                // 1. CSM Amort
                BigDecimal csm = ctx.getCsmAmortAmount() != null ? ctx.getCsmAmortAmount() : BigDecimal.ZERO;

                // 2. IACF Amort (Negated as per ReportGenerator logic)
                BigDecimal iacfRaw = ctx.getIacfAmortAmount() != null ? ctx.getIacfAmortAmount() : BigDecimal.ZERO;
                BigDecimal revIacfVal = iacfRaw.negate();

                // 3. Exp Adj
                BigDecimal exp = BigDecimal.ZERO;
                if (ctx.getExpAdjPrem() != null){
                    exp = exp.add(ctx.getExpAdjPrem());
                }
                if (ctx.getExpAdjIacf() != null){
                    exp = exp.add(ctx.getExpAdjIacf());
                }

                // 4. Claims Net
                BigDecimal claimsGross = ctx.getRevenueClaimsExpensesGross();
                if (claimsGross == null) {
                    BigDecimal net = ctx.getRevenueClaimsExpensesNet() != null ? ctx.getRevenueClaimsExpensesNet() : BigDecimal.ZERO;
                    BigDecimal alloc = ctx.getRevenueClaimsExpensesLcAlloc() != null ? ctx.getRevenueClaimsExpensesLcAlloc() : BigDecimal.ZERO;
                    claimsGross = net.add(alloc);
                }
                BigDecimal claimsLc = ctx.getRevenueClaimsExpensesLcAlloc() != null ? ctx.getRevenueClaimsExpensesLcAlloc() : BigDecimal.ZERO;
                BigDecimal claimsNet = claimsGross.subtract(claimsLc);

                // 5. RA Net
                BigDecimal raGross = ctx.getRaReleaseGross();
                if (raGross == null) {
                    BigDecimal net = ctx.getRaReleaseNet() != null ? ctx.getRaReleaseNet() : BigDecimal.ZERO;
                    BigDecimal alloc = ctx.getRaReleaseLcAlloc() != null ? ctx.getRaReleaseLcAlloc() : BigDecimal.ZERO;
                    raGross = net.add(alloc);
                }
                BigDecimal raLc = ctx.getRaReleaseLcAlloc() != null ? ctx.getRaReleaseLcAlloc() : BigDecimal.ZERO;
                BigDecimal raNet = raGross.subtract(raLc);

                // Total Calculation: (Sum).negate()
                BigDecimal total = (csm.add(revIacfVal).add(exp).add(claimsNet).add(raNet)).negate();

                sb.append(year).append(",")
                        .append(ctx.getPolicyNo()).append(",")
                        .append(csm).append(",")
                        .append(revIacfVal).append(",")
                        .append(exp).append(",")
                        .append(claimsNet).append(",")
                        .append(raNet).append(",")
                        .append(total).append("\n");
            }

            Files.write(Paths.get(csvPath), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);

        } catch (Exception e) {
            logger.logText("❌ 导出收入明细失败: " + e.getMessage());
        }
    }
}
