package com.bba.service.logic;

import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.group.*;
import com.bba.util.CalculationLogger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class GroupCsmLcMeasurementService {

    private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO;
    private static final BigDecimal DECIMAL_ONE = BigDecimal.ONE;
    private static final BigDecimal DECIMAL_100 = new BigDecimal("100");

    /**
     * 计算合同组的状态 (CSM vs LC)
     *
     * @param policyInputs 保单输入数据列表
     * @param logger       日志记录器
     * @return 合同组状态结果对象
     */
    public GroupStatusResult calculateGroupStatus(List<IPolicyGroupCalculationInput> policyInputs, CalculationLogger logger) {
        if (logger != null) {
            logger.logSection("第二部分：合同组状态判定");
            logger.logText("#### 步骤1：汇总合同组CSM/LC（计息后/分摊后）");
        }

        BigDecimal ifCsmAfterInterest = DECIMAL_ZERO;
        BigDecimal nbCsmAfterInterest = DECIMAL_ZERO;
        BigDecimal ifLcAfterIfie = DECIMAL_ZERO;
        BigDecimal nbLcAfterIfie = DECIMAL_ZERO;

        for (IPolicyGroupCalculationInput p : policyInputs) {
            if (p.isIf()) {
                ifCsmAfterInterest = ifCsmAfterInterest.add(p.getBopCsm()).add(p.getIfInterestCsm());
                ifLcAfterIfie = ifLcAfterIfie.add(p.getBopLc()).add(p.getIfLcIfieTotal());
            } else {
                nbCsmAfterInterest = nbCsmAfterInterest.add(p.getNbInitialCsm()).add(p.getNbInterestCsm());
                nbLcAfterIfie = nbLcAfterIfie.add(p.getNbInitialLc()).add(p.getNbLcIfieTotal());
            }
        }

        if (logger != null) {
            logger.logText(String.format("  - IF_计息后CSM合计: %,.2f", ifCsmAfterInterest));
            logger.logText(String.format("  - NB_计息后CSM合计: %,.2f", nbCsmAfterInterest));
            logger.logText(String.format("  - IF_分摊后IFIE后LC合计: %,.2f", ifLcAfterIfie));
            logger.logText(String.format("  - NB_分摊后IFIE后LC合计: %,.2f", nbLcAfterIfie));
        }

        BigDecimal netTrial = ifCsmAfterInterest.add(nbCsmAfterInterest).add(ifLcAfterIfie).add(nbLcAfterIfie);

        if (logger != null) {
            logger.logText("#### 步骤2：计算Net Trial并判断合同组状态");
            logger.logText(String.format("  - Net Trial = %,.2f", netTrial));
        }

        BigDecimal cohortCsm;
        BigDecimal cohortLc;
        boolean isProfitable;

        if (netTrial.compareTo(DECIMAL_ZERO) >= 0) {
            cohortCsm = netTrial;
            cohortLc = DECIMAL_ZERO;
            isProfitable = true;
        } else {
            cohortCsm = DECIMAL_ZERO;
            cohortLc = netTrial;
            isProfitable = false;
        }

        if (logger != null) {
            logger.logText(String.format("  - 合同组状态判定: 合同组CSM=%,.2f, 合同组LC=%,.2f, 是否盈利=%b", cohortCsm, cohortLc, isProfitable));
        }

        return GroupStatusResult.builder()
                .ifCsmAfterInterest(ifCsmAfterInterest)
                .nbCsmAfterInterest(nbCsmAfterInterest)
                .ifLcAfterIfie(ifLcAfterIfie)
                .nbLcAfterIfie(nbLcAfterIfie)
                .netTrial(netTrial)
                .cohortCsm(cohortCsm)
                .cohortLc(cohortLc)
                .isProfitable(isProfitable)
                .build();
    }

    /**
     * 将合同组的 CSM 或 LC 分摊回各保单
     *
     * @param policyInputs 保单输入数据列表
     * @param groupStatus  合同组状态结果
     * @param contexts     计算上下文列表 (用于回写分摊结果)
     * @param logger       日志记录器
     */
    public void allocateGroupCsmLcToPolicies(List<IPolicyGroupCalculationInput> policyInputs, GroupStatusResult groupStatus, List<CalculationContext> contexts, CalculationLogger logger) {
        BigDecimal cohortCsm = groupStatus.getCohortCsm();
        BigDecimal cohortLc = groupStatus.getCohortLc();

        if (logger != null) {
            logger.logSection("分摊合同组CSM和LC到逐单");
            logger.logText(String.format("  - 合同组CSM: %,.2f", cohortCsm));
            logger.logText(String.format("  - 合同组LC: %,.2f", cohortLc));
        }

        Map<String, CalculationContext> unitIdToContext = new HashMap<>();
        for (int i = 0; i < contexts.size(); i++) {
            CalculationContext ctx = contexts.get(i);
            String unitId = ctx.getUnitId();
            if (unitId == null) {
                unitId = ctx.getPolicyNo()+ctx.getCertiNo();
                ctx.setUnitId(unitId);
            }
            unitIdToContext.put(unitId, ctx);
        }

        // 1. CSM 分摊
        if (cohortCsm.compareTo(DECIMAL_ZERO) != 0) {
            BigDecimal totalCsmAfterInterest = policyInputs.stream()
                    .map(IPolicyGroupCalculationInput::getCsmAfterInterest)
                    .filter(csm -> csm.compareTo(DECIMAL_ZERO) > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (logger != null) {
                logger.logText(String.format("  - CSM分摊因子合计（计息后CSM合计）: %,.2f", totalCsmAfterInterest));
            }

            if (totalCsmAfterInterest.compareTo(DECIMAL_ZERO) > 0) {
                for (IPolicyGroupCalculationInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx == null) {
                        continue;
                    }

                    if (p.getCsmAfterInterest().compareTo(DECIMAL_ZERO) > 0) {
                        BigDecimal csmWeight = p.getCsmAfterInterest().divide(totalCsmAfterInterest, MathContext.DECIMAL128);
                        BigDecimal allocatedGroupCsm = cohortCsm.multiply(csmWeight);
                        ctx.setAllocatedGroupCsm(allocatedGroupCsm);

                        if (logger != null) {
                            logger.logText(String.format("  - 单元 %s: 计息后CSM=%,.2f, 占比=%.4f, 合同组分摊CSM=%,.2f",
                                    p.getUnitId(), p.getCsmAfterInterest(), csmWeight, allocatedGroupCsm));
                        }
                    } else {
                        ctx.setAllocatedGroupCsm(DECIMAL_ZERO);
                    }
                }
            } else {
                for (IPolicyGroupCalculationInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx != null) ctx.setAllocatedGroupCsm(DECIMAL_ZERO);
                }
            }
        } else {
            for (IPolicyGroupCalculationInput p : policyInputs) {
                CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                if (ctx != null) ctx.setAllocatedGroupCsm(DECIMAL_ZERO);
            }
        }

        // 2. LC 分摊
        if (cohortLc.compareTo(DECIMAL_ZERO) != 0) {
            BigDecimal totalLcAfterIfie = DECIMAL_ZERO;
            List<IPolicyGroupCalculationInput> validPolicies = new ArrayList<>();

            for (IPolicyGroupCalculationInput p : policyInputs) {
                boolean isLcPolicy = p.getLcAfterIfie().compareTo(DECIMAL_ZERO) < 0;
                if (isLcPolicy && p.getLcAfterIfie().compareTo(DECIMAL_ZERO) != 0) {
                    totalLcAfterIfie = totalLcAfterIfie.add(p.getLcAfterIfie());
                    validPolicies.add(p);
                }
            }

            if (logger != null) {
                logger.logText(String.format("  - LC分摊因子合计（分摊后IFIE后LC合计）: %,.2f", totalLcAfterIfie));
                logger.logText(String.format("  - 有效保单数: %d", validPolicies.size()));
            }

            if (totalLcAfterIfie.compareTo(DECIMAL_ZERO) != 0) {
                for (IPolicyGroupCalculationInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx == null) continue;

                    boolean isLcPolicy = p.getLcAfterIfie().compareTo(DECIMAL_ZERO) < 0;
                    if (isLcPolicy && p.getLcAfterIfie().compareTo(DECIMAL_ZERO) != 0) {
                        BigDecimal lcWeight = p.getLcAfterIfie().divide(totalLcAfterIfie, MathContext.DECIMAL128);
                        BigDecimal allocatedGroupLc = cohortLc.multiply(lcWeight);
                        ctx.setAllocatedGroupLc(allocatedGroupLc);

                        if (logger != null) {
                            logger.logText(String.format("  - 单元 %s: 分摊后IFIE后LC=%,.2f, 占比=%.4f, 合同组分摊LC=%,.2f",
                                    p.getUnitId(), p.getLcAfterIfie(), lcWeight, allocatedGroupLc));
                        }
                    } else {
                        ctx.setAllocatedGroupLc(DECIMAL_ZERO);
                    }
                }
            } else {
                for (IPolicyGroupCalculationInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx != null) ctx.setAllocatedGroupLc(DECIMAL_ZERO);
                }
            }
        } else {
            for (IPolicyGroupCalculationInput p : policyInputs) {
                CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                if (ctx != null) ctx.setAllocatedGroupLc(DECIMAL_ZERO);
            }
        }
    }

    /**
     * 计算组级别吸收变化 (Group Absorption)
     * 确定是 CSM 吸收亏损，还是 LC 吸收盈利
     *
     * @param policyInputs 保单输入数据列表
     * @param groupStatus  合同组状态结果
     * @param logger       日志记录器
     * @param assumptions  精算假设 (用于 CF/RA 拆分)
     * @return 组吸收计算结果
     */
    public GroupAbsorptionResult calculateGroupAbsorption(List<IPolicyGroupCalculationInput> policyInputs, GroupStatusResult groupStatus, CalculationLogger logger, Assumptions assumptions,int year) {
        BigDecimal cohortCsm = groupStatus.getCohortCsm();
        BigDecimal cohortLc = groupStatus.getCohortLc();
        BigDecimal netTrial = groupStatus.getNetTrial();

        if (logger != null) {
            logger.logText("#### 步骤3：计算组级吸收变化");
        }

        BigDecimal groupDeltaTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getDeltaTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal allocatedLcTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getAllocatedLcTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bopLcTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getBopLc).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nbInitialLcTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getNbInitialLc).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lcIfieTotal = policyInputs.stream().map(p -> p.getIfLcIfieTotal().add(p.getNbLcIfieTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal bopCsmTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getBopCsm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nbInitialCsmTotal = policyInputs.stream().map(IPolicyGroupCalculationInput::getNbInitialCsm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal csmInterestTotal = policyInputs.stream().map(p -> p.getIfInterestCsm().add(p.getNbInterestCsm())).reduce(BigDecimal.ZERO, BigDecimal::add);

        // [FIX] 总变化就等于所有保单的履约现金流变化之和
        BigDecimal deltaCsmLcTotal = groupDeltaTotal;

        // 存储每个保单的组别合计，用于后续判断抹平和分摊因子
        Map<String, BigDecimal> policyGroupTotals = new HashMap<>();
        BigDecimal groupTotalAfterDelta = BigDecimal.ZERO;

        for (IPolicyGroupCalculationInput p : policyInputs) {
            BigDecimal policyGroupTotal = p.getBopCsm().add(p.getBopLc())
                    .add(p.getNbInitialCsm()).add(p.getNbInitialLc())
                    .add(p.getIfInterestCsm()).add(p.getNbInterestCsm())
                    .add(p.getIfLcIfieTotal()).add(p.getNbLcIfieTotal())
                    .add(p.getAllocatedLcTotal())
                    .add(p.getDeltaTotal());
            policyGroupTotals.put(p.getUnitId(), policyGroupTotal);
            groupTotalAfterDelta = groupTotalAfterDelta.add(policyGroupTotal);
        }

        // [FIX] 吸收后的盈利状态必须由包含 deltaTotal 的最终组别合计决定！
        boolean isProfitableNow = groupTotalAfterDelta.compareTo(BigDecimal.ZERO) >= 0;

        // [FIX] 更新真实的吸收后组级别 CSM 和 LC
        BigDecimal finalCohortCsm = isProfitableNow ? groupTotalAfterDelta : BigDecimal.ZERO;
        BigDecimal finalCohortLc = isProfitableNow ? BigDecimal.ZERO : groupTotalAfterDelta;

        // [FIX] 使用倒推法完美计算组级吸收变化总额
        // 组级 CSM 吸收 = 目标期末 CSM - 吸收前 CSM
        BigDecimal groupCsmBeforeDelta = bopCsmTotal.add(nbInitialCsmTotal).add(csmInterestTotal);
        BigDecimal groupCsmAbsorbedTotal = finalCohortCsm.subtract(groupCsmBeforeDelta);

        // 组级 LC 吸收 = 目标期末 LC - 吸收前 LC
        BigDecimal groupLcBeforeDelta = bopLcTotal.add(nbInitialLcTotal).add(lcIfieTotal).add(allocatedLcTotal);
        BigDecimal groupLcAbsorbedTotal = finalCohortLc.subtract(groupLcBeforeDelta);

        if (logger != null) {
            logger.logText(String.format("  - 被CSM/LC吸收的变化合计: %,.2f", deltaCsmLcTotal));
            logger.logText(String.format("  - 被LC吸收的变化_合计: %,.2f", groupLcAbsorbedTotal));
            logger.logText(String.format("  - 被CSM吸收的变化_合计: %,.2f", groupCsmAbsorbedTotal));
        }

        if (assumptions == null) {
            throw new IllegalArgumentException("CF/RA 拆分需要精算假设数据，但当前为 null");
        }
        if (assumptions.getRaRatio() == null) {
             throw new IllegalArgumentException("精算假设中缺少 ra_ratio (风险调整比例)");
        }
        BigDecimal raRatioCsm = assumptions.getRaRatio();
        if (raRatioCsm.compareTo(new BigDecimal("-1")) == 0) {
            throw new IllegalArgumentException("ra_ratio 不能为 -1");
        }

        BigDecimal cfRatioFromRaCsm = DECIMAL_ONE.divide(DECIMAL_ONE.add(raRatioCsm), MathContext.DECIMAL128);
        BigDecimal groupCsmAbsorbedCf = groupCsmAbsorbedTotal.multiply(cfRatioFromRaCsm);
        BigDecimal groupCsmAbsorbedRa = groupCsmAbsorbedTotal.subtract(groupCsmAbsorbedCf);

        return GroupAbsorptionResult.builder()
                .cohortCsm(finalCohortCsm) // [FIX] 使用更新后的组级别 CSM
                .cohortLc(finalCohortLc)   // [FIX] 使用更新后的组级别 LC
                .netTrial(netTrial)
                .groupCsmAbsorbedTotal(groupCsmAbsorbedTotal)
                .groupCsmAbsorbedCf(groupCsmAbsorbedCf)
                .groupCsmAbsorbedRa(groupCsmAbsorbedRa)
                .groupLcAbsorbedTotal(groupLcAbsorbedTotal)
                .build();
    }

    /**
     * 将组级吸收结果分摊到各保单
     *
     * @param policyInputs 保单输入数据列表
     * @param groupResult  组吸收计算结果
     * @param assumptions  精算假设
     * @return 保单分摊结果列表
     */
    public List<PolicyAllocationResult> allocateAbsorptionToPolicies(List<IPolicyGroupCalculationInput> policyInputs, GroupAbsorptionResult groupResult, Assumptions assumptions) {
        List<PolicyAllocationResult> allocationResults = new ArrayList<>();

        // [FIX] 盈利状态必须由包含 deltaTotal 的最终组别合计决定！
        BigDecimal groupTotalAfterDelta = BigDecimal.ZERO;
        for (IPolicyGroupCalculationInput p : policyInputs) {
             groupTotalAfterDelta = groupTotalAfterDelta.add(p.getBopCsm().add(p.getBopLc())
                    .add(p.getNbInitialCsm()).add(p.getNbInitialLc())
                    .add(p.getIfInterestCsm()).add(p.getNbInterestCsm())
                    .add(p.getIfLcIfieTotal()).add(p.getNbLcIfieTotal())
                    .add(p.getAllocatedLcTotal())
                    .add(p.getDeltaTotal()));
        }
        boolean isProfitableNow = groupTotalAfterDelta.compareTo(BigDecimal.ZERO) >= 0;

        // 重新计算各单的“抹平后余额”作为分摊因子
        Map<String, BigDecimal> smoothedBalances = new HashMap<>();
        BigDecimal totalSmoothedBalance = BigDecimal.ZERO;

        for (IPolicyGroupCalculationInput p : policyInputs) {
            BigDecimal policyGroupTotal = p.getBopCsm().add(p.getBopLc())
                    .add(p.getNbInitialCsm()).add(p.getNbInitialLc())
                    .add(p.getIfInterestCsm()).add(p.getNbInterestCsm())
                    .add(p.getIfLcIfieTotal()).add(p.getNbLcIfieTotal())
                    .add(p.getAllocatedLcTotal())
                    .add(p.getDeltaTotal());

            BigDecimal smoothedBalance = policyGroupTotal;

            if (isProfitableNow) {
                // 盈利组：抹平所有亏损倾向的保单（非批减单<0，或批减单>0）
                // 简单处理：盈利组中，所有与盈利方向相反（即 < 0）的余额抹平
                if (policyGroupTotal.compareTo(BigDecimal.ZERO) < 0) {
                    smoothedBalance = BigDecimal.ZERO;
                }
            } else {
                // 亏损组：抹平所有与亏损方向相反（即 > 0）的余额，以及所有批减单
                if (policyGroupTotal.compareTo(BigDecimal.ZERO) > 0 || p.isReversalPolicy()) {
                    smoothedBalance = BigDecimal.ZERO;
                }
            }
            smoothedBalances.put(p.getUnitId(), smoothedBalance);
            totalSmoothedBalance = totalSmoothedBalance.add(smoothedBalance);
        }

        BigDecimal denominator = totalSmoothedBalance;

        for (IPolicyGroupCalculationInput p : policyInputs) {
            BigDecimal smoothedBalance = smoothedBalances.get(p.getUnitId());
            
            // 计算保单的目标期末余额
            BigDecimal targetFinalCsm = BigDecimal.ZERO;
            BigDecimal targetFinalLc = BigDecimal.ZERO;
            BigDecimal csmAllocationWeight = BigDecimal.ZERO;
            BigDecimal lcAllocationWeight = BigDecimal.ZERO;

            if (denominator.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal weight = smoothedBalance.divide(denominator, MathContext.DECIMAL128);
                if (isProfitableNow) {
                    targetFinalCsm = groupTotalAfterDelta.multiply(weight);
                    csmAllocationWeight = weight.multiply(DECIMAL_100);
                } else {
                    targetFinalLc = groupTotalAfterDelta.multiply(weight);
                    lcAllocationWeight = weight.multiply(DECIMAL_100);
                }
            } else if (policyInputs.size() == 1) {
                // 单保单防呆：如果分母为0但只有一张单，全部分给它
                if (isProfitableNow) {
                    targetFinalCsm = groupTotalAfterDelta;
                    csmAllocationWeight = DECIMAL_100;
                } else {
                    targetFinalLc = groupTotalAfterDelta;
                    lcAllocationWeight = DECIMAL_100;
                }
            }

            // 计算吸收前的余额
            BigDecimal csmBeforeDelta = p.getBopCsm().add(p.getNbInitialCsm()).add(p.getIfInterestCsm()).add(p.getNbInterestCsm());
            BigDecimal lcBeforeDelta = p.getBopLc().add(p.getNbInitialLc()).add(p.getIfLcIfieTotal()).add(p.getNbLcIfieTotal()).add(p.getAllocatedLcTotal());

            // [核心修复] 使用倒推法完美计算吸收金额：吸收额 = 目标期末 - 吸收前余额
            BigDecimal csmAbsorbed = targetFinalCsm.subtract(csmBeforeDelta);
            BigDecimal lcAbsorbedTotal = targetFinalLc.subtract(lcBeforeDelta);

            // 拆分 CF 和 RA
            BigDecimal csmAbsorbedCf = BigDecimal.ZERO;
            BigDecimal csmAbsorbedRa = BigDecimal.ZERO;
            if (csmAbsorbed.compareTo(BigDecimal.ZERO) != 0) {
                 if (assumptions != null && assumptions.getRaRatio() != null && assumptions.getRaRatio().compareTo(new BigDecimal("-1")) != 0) {
                     BigDecimal cfRatio = DECIMAL_ONE.divide(DECIMAL_ONE.add(assumptions.getRaRatio()), MathContext.DECIMAL128);
                     csmAbsorbedCf = csmAbsorbed.multiply(cfRatio);
                     csmAbsorbedRa = csmAbsorbed.subtract(csmAbsorbedCf);
                 } else {
                     csmAbsorbedCf = csmAbsorbed;
                 }
            }

            BigDecimal lcAbsorbedCf = BigDecimal.ZERO;
            BigDecimal lcAbsorbedRa = BigDecimal.ZERO;
            if (lcAbsorbedTotal.compareTo(BigDecimal.ZERO) != 0) {
                BigDecimal lcBalanceToAdjustBeforeAbsorption = p.getBopLc()
                        .add(p.getNbInitialLc())
                        .add(p.getIfLcIfieTotal().add(p.getNbLcIfieTotal()))
                        .add(p.getAllocatedLcTotal());

                if (lcBalanceToAdjustBeforeAbsorption.compareTo(DECIMAL_ZERO) == 0) {
                    lcAbsorbedCf = (p.getBopLcCf()
                            .add(p.getNbInitialLcCf())
                            .add(p.getIfLcIfieCf().add(p.getNbLcIfieCf()))
                            .add(p.getAllocatedLcCf())).negate();
                    lcAbsorbedRa = lcAbsorbedTotal.subtract(lcAbsorbedCf);
                } else {
                     if (assumptions != null && assumptions.getRaRatio() != null && assumptions.getRaRatio().compareTo(new BigDecimal("-1")) != 0) {
                        BigDecimal cfRatio = DECIMAL_ONE.divide(DECIMAL_ONE.add(assumptions.getRaRatio()), MathContext.DECIMAL128);
                        lcAbsorbedCf = lcAbsorbedTotal.multiply(cfRatio);
                        lcAbsorbedRa = lcAbsorbedTotal.subtract(lcAbsorbedCf);
                     } else {
                        lcAbsorbedCf = lcAbsorbedTotal;
                     }
                }
            }

            allocationResults.add(PolicyAllocationResult.builder()
                    .unitId(p.getUnitId())
                    .csmAbsorbed(csmAbsorbed)
                    .csmAbsorbedCf(csmAbsorbedCf)
                    .csmAbsorbedRa(csmAbsorbedRa)
                    .lcAbsorbedTotal(lcAbsorbedTotal)
                    .lcAbsorbedCf(lcAbsorbedCf)
                    .lcAbsorbedRa(lcAbsorbedRa)
                    .csmAllocationWeight(csmAllocationWeight)
                    .lcAllocationWeight(lcAllocationWeight)
                    .cohortCsm(groupResult.getCohortCsm()) // [FIX] 同步组级CSM
                    .cohortLc(groupResult.getCohortLc())   // [FIX] 同步组级LC
                    .build());
        }
        return allocationResults;
    }

    /**
     * 将分摊计算结果回写到计算上下文中
     *
     * @param contexts          计算上下文列表
     * @param allocationResults 分摊结果列表
     * @param groupStatus       合同组状态 (用于同步盈利性)
     */
    public void writeBackToContexts(List<CalculationContext> contexts, List<PolicyAllocationResult> allocationResults, GroupStatusResult groupStatus) {
        Map<String, PolicyAllocationResult> resultMap = new HashMap<>();
        for (PolicyAllocationResult r : allocationResults) {
            resultMap.put(r.getUnitId(), r);
        }

        for (int i = 0; i < contexts.size(); i++) {
            CalculationContext ctx = contexts.get(i);
            String unitId = ctx.getUnitId(); // unitId 已在 collectPolicyData 中构建

            PolicyAllocationResult result = resultMap.get(unitId);

            if (result == null) {
                System.out.println("[WARN-BBA] 无法通过 unitId [" + unitId + "] 匹配到分摊结果，请检查 ID 构建逻辑。");
                continue;
            }

            ctx.setCsmAbsorbed(result.getCsmAbsorbed());
            ctx.setCsmAbsorbedCf(result.getCsmAbsorbedCf());
            ctx.setCsmAbsorbedRa(result.getCsmAbsorbedRa());

            ctx.setLcAbsorbedTotal(result.getLcAbsorbedTotal());
            ctx.setLcAbsorbedCf(result.getLcAbsorbedCf());
            ctx.setLcAbsorbedRa(result.getLcAbsorbedRa());
            
            // [FIX] 被LC吸收的变化，对于单保单计量模块，名称叫 LcChange，对应 allocatedLcExpAdjTotal
            ctx.setLcChange(result.getLcAbsorbedTotal());
            ctx.setAllocatedLcExpAdjCf(result.getLcAbsorbedCf());
            ctx.setAllocatedLcExpAdjRa(result.getLcAbsorbedRa());

            // [FIX] 将分摊结果中携带的组级 CSM/LC 净额写回上下文
            ctx.setAllocatedGroupCsm(result.getCohortCsm());
            ctx.setAllocatedGroupLc(result.getCohortLc());

            // [FIX] 强制同步组级别的盈利性状态到保单上下文，防止保单级别计算逻辑出现偏差
            if (groupStatus != null) {
                ctx.setProfitable(groupStatus.isProfitable());
            }

            if (ctx.getAllocatedLcTotal() == null) {
                BigDecimal cf = ctx.getAllocatedLcCf() != null ? ctx.getAllocatedLcCf() : DECIMAL_ZERO;
                BigDecimal ra = ctx.getAllocatedLcRa() != null ? ctx.getAllocatedLcRa() : DECIMAL_ZERO;
                ctx.setAllocatedLcTotal(cf.add(ra));
            }
        }
    }

    /**
     * 执行完整的组级别吸收和分摊流程
     * 1. 收集保单数据
     * 2. 计算组吸收
     * 3. 分摊吸收额到保单
     * 4. 回写结果
     *
     * @param contexts    计算上下文列表
     * @param groupStatus 合同组状态
     * @param logger      日志记录器
     * @param assumptions 精算假设
     * @return 组吸收计算结果
     */
    public GroupAbsorptionResult runGroupAbsorptionAllocation(List<CalculationContext> contexts, GroupStatusResult groupStatus, CalculationLogger logger, Assumptions assumptions,int year) {
        if (logger != null) {
            logger.logSection("第三部分-步骤3&4: 组级吸收变化汇总与分摊");
        }

        List<IPolicyGroupCalculationInput> policyInputs = new ArrayList<>(contexts);
        GroupAbsorptionResult groupResult = calculateGroupAbsorption(policyInputs, groupStatus, logger, assumptions,year);
        
        // [FIX] 将吸收后的真实盈利状态和余额同步回 groupStatus
        groupStatus.setCohortCsm(groupResult.getCohortCsm());
        groupStatus.setCohortLc(groupResult.getCohortLc());
        groupStatus.setProfitable(groupResult.getCohortCsm().compareTo(BigDecimal.ZERO) > 0 || (groupResult.getCohortCsm().compareTo(BigDecimal.ZERO) == 0 && groupResult.getCohortLc().compareTo(BigDecimal.ZERO) == 0));

        List<PolicyAllocationResult> allocationResults = allocateAbsorptionToPolicies(policyInputs, groupResult, assumptions);
        writeBackToContexts(contexts, allocationResults, groupStatus);

        return groupResult;
    }
}
