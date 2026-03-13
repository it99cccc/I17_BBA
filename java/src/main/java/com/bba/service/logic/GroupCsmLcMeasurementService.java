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

@Service
public class GroupCsmLcMeasurementService {

    private static final BigDecimal DECIMAL_ZERO = BigDecimal.ZERO;
    private static final BigDecimal DECIMAL_ONE = BigDecimal.ONE;
    private static final BigDecimal DECIMAL_100 = new BigDecimal("100");

    /**
     * 收集保单数据以进行组级别计算
     * 从 CalculationContext 列表中提取必要字段构建 PolicyContextInput 列表
     *
     * @param contexts 计算上下文列表
     * @return 保单上下文输入列表
     */
    public List<PolicyContextInput> collectPolicyData(List<CalculationContext> contexts) {
        List<PolicyContextInput> policyInputs = new ArrayList<>();

        for (int i = 0; i < contexts.size(); i++) {
            CalculationContext ctx = contexts.get(i);

            // [FIX] 在数据收集阶段就立即构建并设置 unitId，确保全链路唯一性
            String unitId = ctx.getPolicyNo()+ctx.getCertiNo();
            ctx.setUnitId(unitId);

            BigDecimal bopCsm = ctx.getBopCsm() != null ? ctx.getBopCsm() : DECIMAL_ZERO;
            BigDecimal bopLc = ctx.getBopLc() != null ? ctx.getBopLc() : DECIMAL_ZERO;
            BigDecimal bopLcCf = ctx.getBopLcCf() != null ? ctx.getBopLcCf() : DECIMAL_ZERO;
            BigDecimal bopLcRa = ctx.getBopLcRa() != null ? ctx.getBopLcRa() : DECIMAL_ZERO;
            BigDecimal nbInitialCsm = ctx.getNbInitialCsm() != null ? ctx.getNbInitialCsm() : DECIMAL_ZERO;
            BigDecimal nbInitialLc = ctx.getNbInitialLc() != null ? ctx.getNbInitialLc() : DECIMAL_ZERO;

            boolean isIf = (bopCsm.compareTo(DECIMAL_ZERO) != 0 || bopLc.compareTo(DECIMAL_ZERO) != 0);

            BigDecimal ifInterestCsm = ctx.getIfInterestCsm() != null ? ctx.getIfInterestCsm() : DECIMAL_ZERO;
            BigDecimal nbInterestCsm = ctx.getNbInterestCsm() != null ? ctx.getNbInterestCsm() : DECIMAL_ZERO;
            BigDecimal ifLcIfieTotal = ctx.getIfLcIfieTotal() != null ? ctx.getIfLcIfieTotal() : DECIMAL_ZERO;
            BigDecimal nbLcIfieTotal = ctx.getNbLcIfieTotal() != null ? ctx.getNbLcIfieTotal() : DECIMAL_ZERO;

            BigDecimal csmAfterInterest;
            if (isIf) {
                csmAfterInterest = bopCsm.add(ifInterestCsm);
            } else {
                csmAfterInterest = nbInitialCsm.add(nbInterestCsm);
            }

            BigDecimal lcAfterIfie;
            if (isIf) {
                lcAfterIfie = bopLc.add(ifLcIfieTotal);
            } else {
                lcAfterIfie = nbInitialLc.add(nbLcIfieTotal);
            }

            BigDecimal deltaTotal = ctx.getExpAdjCsmImpact() != null ? ctx.getExpAdjCsmImpact() : DECIMAL_ZERO;
            BigDecimal deltaCf = ctx.getDeltaCfTotal() != null ? ctx.getDeltaCfTotal() : DECIMAL_ZERO;
            BigDecimal deltaRa = deltaTotal.subtract(deltaCf);

            BigDecimal allocatedLcTotal = ctx.getAllocatedLcTotal() != null ? ctx.getAllocatedLcTotal() : DECIMAL_ZERO;
            BigDecimal allocatedLcCf = ctx.getAllocatedLcCf() != null ? ctx.getAllocatedLcCf() : DECIMAL_ZERO;
            BigDecimal allocatedLcRa = ctx.getAllocatedLcRa() != null ? ctx.getAllocatedLcRa() : DECIMAL_ZERO;

            BigDecimal ifLcIfieCf = ctx.getIfLcIfieCf() != null ? ctx.getIfLcIfieCf() : DECIMAL_ZERO;
            BigDecimal nbLcIfieCf = ctx.getNbLcIfieCf() != null ? ctx.getNbLcIfieCf() : DECIMAL_ZERO;
            BigDecimal ifLcIfieRa = ctx.getIfLcIfieRa() != null ? ctx.getIfLcIfieRa() : DECIMAL_ZERO;
            BigDecimal nbLcIfieRa = ctx.getNbLcIfieRa() != null ? ctx.getNbLcIfieRa() : DECIMAL_ZERO;

            BigDecimal nbInitialLcCf = ctx.getNbInitialLcCf() != null ? ctx.getNbInitialLcCf() : DECIMAL_ZERO;
            BigDecimal nbInitialLcRa = ctx.getNbInitialLcRa() != null ? ctx.getNbInitialLcRa() : DECIMAL_ZERO;

            policyInputs.add(PolicyContextInput.builder()
                    .unitId(unitId)
                    .isIf(isIf)
                    .bopCsm(bopCsm)
                    .bopLc(bopLc)
                    .bopLcCf(bopLcCf)
                    .bopLcRa(bopLcRa)
                    .nbInitialCsm(nbInitialCsm)
                    .nbInitialLc(nbInitialLc)
                    .ifInterestCsm(ifInterestCsm)
                    .nbInterestCsm(nbInterestCsm)
                    .ifLcIfieTotal(ifLcIfieTotal)
                    .nbLcIfieTotal(nbLcIfieTotal)
                    .csmAfterInterest(csmAfterInterest)
                    .lcAfterIfie(lcAfterIfie)
                    .deltaTotal(deltaTotal)
                    .deltaCf(deltaCf)
                    .deltaRa(deltaRa)
                    .allocatedLcTotal(allocatedLcTotal)
                    .allocatedLcCf(allocatedLcCf)
                    .allocatedLcRa(allocatedLcRa)
                    .ifLcIfieCf(ifLcIfieCf)
                    .nbLcIfieCf(nbLcIfieCf)
                    .ifLcIfieRa(ifLcIfieRa)
                    .nbLcIfieRa(nbLcIfieRa)
                    .nbInitialLcCf(nbInitialLcCf)
                    .nbInitialLcRa(nbInitialLcRa)
                    .build());
        }
        return policyInputs;
    }

    /**
     * 计算合同组的状态 (CSM vs LC)
     *
     * @param policyInputs 保单输入数据列表
     * @param logger       日志记录器
     * @return 合同组状态结果对象
     */
    public GroupStatusResult calculateGroupStatus(List<PolicyContextInput> policyInputs, CalculationLogger logger) {
        if (logger != null) {
            logger.logSection("第二部分：合同组状态判定");
            logger.logText("#### 步骤1：汇总合同组CSM/LC（计息后/分摊后）");
        }

        BigDecimal ifCsmAfterInterest = DECIMAL_ZERO;
        BigDecimal nbCsmAfterInterest = DECIMAL_ZERO;
        BigDecimal ifLcAfterIfie = DECIMAL_ZERO;
        BigDecimal nbLcAfterIfie = DECIMAL_ZERO;

        for (PolicyContextInput p : policyInputs) {
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
    public void allocateGroupCsmLcToPolicies(List<PolicyContextInput> policyInputs, GroupStatusResult groupStatus, List<CalculationContext> contexts, CalculationLogger logger) {
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
                    .map(PolicyContextInput::getCsmAfterInterest)
                    .filter(csm -> csm.compareTo(DECIMAL_ZERO) > 0)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            if (logger != null) {
                logger.logText(String.format("  - CSM分摊因子合计（计息后CSM合计）: %,.2f", totalCsmAfterInterest));
            }

            if (totalCsmAfterInterest.compareTo(DECIMAL_ZERO) > 0) {
                for (PolicyContextInput p : policyInputs) {
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
                for (PolicyContextInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx != null) ctx.setAllocatedGroupCsm(DECIMAL_ZERO);
                }
            }
        } else {
            for (PolicyContextInput p : policyInputs) {
                CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                if (ctx != null) ctx.setAllocatedGroupCsm(DECIMAL_ZERO);
            }
        }

        // 2. LC 分摊
        if (cohortLc.compareTo(DECIMAL_ZERO) != 0) {
            BigDecimal totalLcAfterIfie = DECIMAL_ZERO;
            List<PolicyContextInput> validPolicies = new ArrayList<>();

            for (PolicyContextInput p : policyInputs) {
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
                for (PolicyContextInput p : policyInputs) {
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
                for (PolicyContextInput p : policyInputs) {
                    CalculationContext ctx = unitIdToContext.get(p.getUnitId());
                    if (ctx != null) ctx.setAllocatedGroupLc(DECIMAL_ZERO);
                }
            }
        } else {
            for (PolicyContextInput p : policyInputs) {
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
    public GroupAbsorptionResult calculateGroupAbsorption(List<PolicyContextInput> policyInputs, GroupStatusResult groupStatus, CalculationLogger logger, Assumptions assumptions,int year) {
        BigDecimal cohortCsm = groupStatus.getCohortCsm();
        BigDecimal cohortLc = groupStatus.getCohortLc();
        BigDecimal netTrial = groupStatus.getNetTrial();

        if (logger != null) {
            logger.logText("#### 步骤3：计算组级吸收变化");
        }

        BigDecimal groupDeltaTotal = policyInputs.stream().map(PolicyContextInput::getDeltaTotal).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal allocatedLcTotal = policyInputs.stream().map(PolicyContextInput::getAllocatedLcTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal bopLcTotal = policyInputs.stream().map(PolicyContextInput::getBopLc).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nbInitialLcTotal = policyInputs.stream().map(PolicyContextInput::getNbInitialLc).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal lcIfieTotal = policyInputs.stream().map(p -> p.getIfLcIfieTotal().add(p.getNbLcIfieTotal())).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal bopCsmTotal = policyInputs.stream().map(PolicyContextInput::getBopCsm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal nbInitialCsmTotal = policyInputs.stream().map(PolicyContextInput::getNbInitialCsm).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal csmInterestTotal = policyInputs.stream().map(p -> p.getIfInterestCsm().add(p.getNbInterestCsm())).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal deltaCsmLcTotal = groupDeltaTotal;

        // [FIX] 彻底重构吸收逻辑，移除所有基于中间变量的脆弱布尔判定
        BigDecimal totalLcToReverse = bopLcTotal.add(nbInitialLcTotal).add(lcIfieTotal).add(allocatedLcTotal);
        boolean hasOpeningLc = totalLcToReverse.compareTo(BigDecimal.ZERO) < 0;
        boolean isProfitableNow = netTrial.compareTo(BigDecimal.ZERO) >= 0;

        BigDecimal groupLcAbsorbedTotal = DECIMAL_ZERO;
        BigDecimal groupCsmAbsorbedTotal;

        if (isProfitableNow) {
            // [Case 1: 盈利组 (一直盈利 或 扭亏为盈)]
            // 目标：LC 必须清零
            if (hasOpeningLc) {
                groupCsmAbsorbedTotal = netTrial.add(deltaCsmLcTotal).subtract(bopCsmTotal.add(nbInitialCsmTotal).add(csmInterestTotal));
                groupLcAbsorbedTotal = deltaCsmLcTotal.subtract(groupCsmAbsorbedTotal);
            } else {
                // 一直盈利 (没有期初 LC)
                groupCsmAbsorbedTotal = deltaCsmLcTotal;
                groupLcAbsorbedTotal = DECIMAL_ZERO;
            }
        } else {
            // [Case 2: 亏损组]
            // CSM 必须清零 (如果有)
            // CSM_Absorbed = -(CSM_Start)
            groupCsmAbsorbedTotal = bopCsmTotal.add(nbInitialCsmTotal).add(csmInterestTotal).negate();
            groupLcAbsorbedTotal = deltaCsmLcTotal.subtract(groupCsmAbsorbedTotal);
        }

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
                .cohortCsm(cohortCsm)
                .cohortLc(cohortLc)
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
    public List<PolicyAllocationResult> allocateAbsorptionToPolicies(List<PolicyContextInput> policyInputs, GroupAbsorptionResult groupResult, Assumptions assumptions) {
        List<PolicyAllocationResult> allocationResults = new ArrayList<>();

        BigDecimal totalCsmAfterInterest = policyInputs.stream()
                .map(PolicyContextInput::getCsmAfterInterest)
                .filter(csm -> csm.compareTo(DECIMAL_ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal totalLcAfterIfieAbs = policyInputs.stream()
                .filter(p -> p.getLcAfterIfie().compareTo(DECIMAL_ZERO) < 0)
                .map(p -> p.getLcAfterIfie().abs())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        for (PolicyContextInput p : policyInputs) {
            BigDecimal csmAbsorbed = DECIMAL_ZERO;
            BigDecimal csmAbsorbedCf = DECIMAL_ZERO;
            BigDecimal csmAbsorbedRa = DECIMAL_ZERO;
            BigDecimal csmAllocationWeight = DECIMAL_ZERO;

            if (p.getCsmAfterInterest().compareTo(DECIMAL_ZERO) > 0 && totalCsmAfterInterest.compareTo(DECIMAL_ZERO) > 0) {
                BigDecimal csmWeight = p.getCsmAfterInterest().divide(totalCsmAfterInterest, MathContext.DECIMAL128);
                csmAbsorbed = groupResult.getGroupCsmAbsorbedTotal().multiply(csmWeight);
                csmAbsorbedCf = groupResult.getGroupCsmAbsorbedCf().multiply(csmWeight);
                csmAbsorbedRa = groupResult.getGroupCsmAbsorbedRa().multiply(csmWeight);
                csmAllocationWeight = csmWeight.multiply(DECIMAL_100);
            }

            BigDecimal lcAbsorbedTotal = DECIMAL_ZERO;
            BigDecimal lcAbsorbedCf = DECIMAL_ZERO;
            BigDecimal lcAbsorbedRa = DECIMAL_ZERO;
            BigDecimal lcAllocationWeight = DECIMAL_ZERO;

            boolean isLcPolicy = p.getLcAfterIfie().compareTo(DECIMAL_ZERO) < 0;

            if (isLcPolicy && totalLcAfterIfieAbs.compareTo(DECIMAL_ZERO) > 0) {
                BigDecimal lcWeight = p.getLcAfterIfie().abs().divide(totalLcAfterIfieAbs, MathContext.DECIMAL128);
                lcAbsorbedTotal = groupResult.getGroupLcAbsorbedTotal().multiply(lcWeight);

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
                     if (assumptions == null || assumptions.getRaRatio() == null) {
                        throw new IllegalArgumentException("保单 LC 拆分缺少精算假设/ra_ratio");
                    }
                    BigDecimal raRatio = assumptions.getRaRatio();
                     if (raRatio.compareTo(new BigDecimal("-1")) == 0) {
                        throw new IllegalArgumentException("ra_ratio 不能为 -1");
                    }
                    BigDecimal cfRatioFromRa = DECIMAL_ONE.divide(DECIMAL_ONE.add(raRatio), MathContext.DECIMAL128);
                    lcAbsorbedCf = lcAbsorbedTotal.multiply(cfRatioFromRa);
                    lcAbsorbedRa = lcAbsorbedTotal.subtract(lcAbsorbedCf);
                }
                lcAllocationWeight = lcWeight.multiply(DECIMAL_100);
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

        List<PolicyContextInput> policyInputs = collectPolicyData(contexts);
        GroupAbsorptionResult groupResult = calculateGroupAbsorption(policyInputs, groupStatus, logger, assumptions,year);
        List<PolicyAllocationResult> allocationResults = allocateAbsorptionToPolicies(policyInputs, groupResult, assumptions);
        writeBackToContexts(contexts, allocationResults, groupStatus);

        return groupResult;
    }
}
