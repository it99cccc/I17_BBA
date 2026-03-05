package com.bba.service.logic;

import com.bba.model.Assumptions;
import com.bba.model.CalculationContext;
import com.bba.model.CohortState;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * IFIE 服务类。
 * 负责计算保险财务收益或费用 (Insurance Finance Income or Expenses)。
 * 包括 P&L 和 OCI 部分。
 */
@Service
@RequiredArgsConstructor
public class IfieService {

    private static final boolean USE_OCI_OPTION = true; // Configurable? Using default true from Python logic

    public void run(CalculationContext context, CalculationLogger logger, Assumptions assumptions, CohortState cohortState) {
        // 如果 PV Interest 尚未计算（即 preCalculatePvInterest 未被调用），则在此处计算
        // 通常 LifecycleSimulationService 会提前调用 preCalculatePvInterest
        if (context.getIfiePvCfTotal() == null) {
            calculatePvInterest(context, logger, cohortState);
        } else {
             logger.logSection("Part 8: IFIE（利率变化对预期现金流的影响）- 汇总 [Sec 13-14]");
             logger.logText("注: PV部分的IFIE已在之前的步骤中计算。此处仅计算CSM部分并汇总。");
        }

        BigDecimal ifieCf = getVal(context.getIfiePvCfTotal());
        BigDecimal ifieRa = getVal(context.getIfiePvRaTotal());
        BigDecimal ifieOciCf = getVal(context.getIfieOciPvCfTotal());
        BigDecimal ifieOciRa = getVal(context.getIfieOciPvRaTotal());

        // [Sec 13.8] IFIE_CSM
        BigDecimal csmInterest = context.isNewBusiness() ?
                (context.getNbInterestCsm() != null ? context.getNbInterestCsm() : BigDecimal.ZERO) :
                (context.getIfInterestCsm() != null ? context.getIfInterestCsm() : BigDecimal.ZERO);
        BigDecimal ifieCsm = csmInterest.negate();
        context.setIfiePlCsm(ifieCsm);

        Map<String, Object> metaCsm = new HashMap<>();
        metaCsm.put("CSM计息", csmInterest);
        logger.logItem(
                "IFIE_CSM",
                "[Sec 13.8] CSM IFIE（仅包含计息影响）",
                "-CSM计息",
                metaCsm,
                ifieCsm
        );

        BigDecimal ifiePlTotal = ifieCf.add(ifieRa).add(ifieCsm);

        Map<String, Object> metaPl = new HashMap<>();
        metaPl.put("IFIE_预期现金流", ifieCf);
        metaPl.put("IFIE_非金融风险调整", ifieRa);
        metaPl.put("IFIE_CSM", ifieCsm);
        logger.logItem(
                "IFIE",
                "[Sec 13.9] IFIE计入损益部分合计",
                "IFIE = IFIE_预期现金流 + IFIE_非金融风险调整 + IFIE_CSM",
                metaPl,
                ifiePlTotal
        );

        BigDecimal ifieOciTotal = ifieOciCf.add(ifieOciRa);
        // OCI Log was done in calculatePvInterest

        context.setIfiePl(ifiePlTotal);
        context.setIfieOci(ifieOciTotal);

        // [Sec 13.10-13.13] 亏损分摊 (Allocation to Loss Component)
        BigDecimal ifiePlCfLc;
        BigDecimal ifiePlRaLc;
        BigDecimal ifiePlCfNonLc;
        BigDecimal ifiePlRaNonLc;
        BigDecimal ifiePlLc;
        BigDecimal ifiePlNonLc;

        BigDecimal ifieOciCfLc;
        BigDecimal ifieOciRaLc;
        BigDecimal ifieOciCfNonLc;
        BigDecimal ifieOciRaNonLc;
        BigDecimal ifieOciLc;
        BigDecimal ifieOciNonLc;

        if (USE_OCI_OPTION) {
            BigDecimal ifLcIfieCf = context.getIfLcIfieCf() != null ? context.getIfLcIfieCf() : BigDecimal.ZERO;
            BigDecimal nbLcIfieCf = context.getNbLcIfieCf() != null ? context.getNbLcIfieCf() : BigDecimal.ZERO;
            BigDecimal lcIfieCf = ifLcIfieCf.add(nbLcIfieCf);

            BigDecimal ifLcIfieRa = context.getIfLcIfieRa() != null ? context.getIfLcIfieRa() : BigDecimal.ZERO;
            BigDecimal nbLcIfieRa = context.getNbLcIfieRa() != null ? context.getNbLcIfieRa() : BigDecimal.ZERO;
            BigDecimal lcIfieRa = ifLcIfieRa.add(nbLcIfieRa);

            BigDecimal ifieCfTotal = ifieCf.add(ifieOciCf);
            if (ifieCfTotal.compareTo(BigDecimal.ZERO) != 0) {
                ifiePlCfLc = ifieCf.multiply(lcIfieCf).divide(ifieCfTotal, 16, RoundingMode.HALF_UP);
            } else {
                ifiePlCfLc = BigDecimal.ZERO;
            }

            BigDecimal ifieRaTotal = ifieRa.add(ifieOciRa);
            if (ifieRaTotal.compareTo(BigDecimal.ZERO) != 0) {
                ifiePlRaLc = ifieRa.multiply(lcIfieRa).divide(ifieRaTotal, 16, RoundingMode.HALF_UP);
            } else {
                ifiePlRaLc = BigDecimal.ZERO;
            }

            ifieOciCfLc = lcIfieCf.subtract(ifiePlCfLc);
            ifieOciRaLc = lcIfieRa.subtract(ifiePlRaLc);

            ifiePlCfNonLc = ifieCf.subtract(ifiePlCfLc);
            ifiePlRaNonLc = ifieRa.subtract(ifiePlRaLc);
            ifieOciCfNonLc = ifieOciCf.subtract(ifieOciCfLc);
            ifieOciRaNonLc = ifieOciRa.subtract(ifieOciRaLc);

            ifiePlLc = ifiePlCfLc.add(ifiePlRaLc);
            ifieOciLc = ifieOciCfLc.add(ifieOciRaLc);

            ifiePlNonLc = ifiePlTotal.subtract(ifiePlLc);
            ifieOciNonLc = ifieOciTotal.subtract(ifieOciLc);
        } else {
            BigDecimal lcRatio = context.getNbLcRatio() != null ? context.getNbLcRatio() : BigDecimal.ZERO;
            if (lcRatio.compareTo(BigDecimal.ZERO) != 0) {
                ifiePlLc = ifiePlTotal.multiply(lcRatio);
                ifiePlNonLc = ifiePlTotal.subtract(ifiePlLc);

                BigDecimal ifiePlCfRatio;
                BigDecimal ifiePlRaRatio;
                if (ifiePlTotal.compareTo(BigDecimal.ZERO) != 0) {
                    ifiePlCfRatio = ifieCf.divide(ifiePlTotal, 16, RoundingMode.HALF_UP);
                    ifiePlRaRatio = ifieRa.divide(ifiePlTotal, 16, RoundingMode.HALF_UP);
                } else {
                    ifiePlCfRatio = BigDecimal.ZERO;
                    ifiePlRaRatio = BigDecimal.ZERO;
                }

                ifiePlCfLc = ifiePlLc.multiply(ifiePlCfRatio);
                ifiePlRaLc = ifiePlLc.multiply(ifiePlRaRatio);
                ifiePlCfNonLc = ifieCf.subtract(ifiePlCfLc);
                ifiePlRaNonLc = ifieRa.subtract(ifiePlRaLc);

                ifieOciCfLc = BigDecimal.ZERO;
                ifieOciRaLc = BigDecimal.ZERO;
                ifieOciCfNonLc = BigDecimal.ZERO;
                ifieOciRaNonLc = BigDecimal.ZERO;
                ifieOciLc = BigDecimal.ZERO;
                ifieOciNonLc = BigDecimal.ZERO;
            } else {
                ifiePlCfLc = BigDecimal.ZERO;
                ifiePlRaLc = BigDecimal.ZERO;
                ifiePlCfNonLc = ifieCf;
                ifiePlRaNonLc = ifieRa;
                ifiePlLc = BigDecimal.ZERO;
                ifiePlNonLc = ifiePlTotal;

                ifieOciCfLc = BigDecimal.ZERO;
                ifieOciRaLc = BigDecimal.ZERO;
                ifieOciCfNonLc = BigDecimal.ZERO;
                ifieOciRaNonLc = BigDecimal.ZERO;
                ifieOciLc = BigDecimal.ZERO;
                ifieOciNonLc = BigDecimal.ZERO;
            }
        }

        context.setIfiePlLc(ifiePlLc);
        context.setIfiePlNonLc(ifiePlNonLc);
        context.setIfieOciLc(ifieOciLc);
        context.setIfieOciNonLc(ifieOciNonLc);

        context.setIfiePlCfLc(ifiePlCfLc);
        context.setIfiePlRaLc(ifiePlRaLc);
        context.setIfiePlCfNonLc(ifiePlCfNonLc);
        context.setIfiePlRaNonLc(ifiePlRaNonLc);

        context.setIfieOciCfLc(ifieOciCfLc);
        context.setIfieOciRaLc(ifieOciRaLc);
        context.setIfieOciCfNonLc(ifieOciCfNonLc);
        context.setIfieOciRaNonLc(ifieOciRaNonLc);
    }

    /**
     * 预先计算 PV 相关的 IFIE (CF 和 RA)，用于后续步骤剔除利息影响。
     */
    public void calculatePvInterest(CalculationContext context, CalculationLogger logger, CohortState cohortState) {
        logger.logSection("Part 0: IFIE 预计算 (PV Interest Accretion) [Sec 13-14]");

        if (context.getPvSourceData() == null) {
            throw new RuntimeException("PV原材料数据不可用！");
        }

        // [Sec 13.1] 获取加权初始确认利率（锁定利率）
        BigDecimal lockedRate = cohortState != null ? cohortState.getWeightedLockedRate() : BigDecimal.ZERO;

        Map<String, Object> metaLocked = new HashMap<>();
        metaLocked.put("Locked Rate", lockedRate);
        logger.logItem(
                "加权初始确认利率（锁定利率）",
                "[Sec 13.1] 用于IFIE_P&C计算的锁定利率",
                "CohortState.weighted_locked_rate",
                metaLocked,
                lockedRate,
                "IFIE_P&C仅包含计息影响，使用加权初始确认利率（锁定利率）"
        );

        // 判断是否为新业务
        boolean isNewBusiness = context.isNewBusiness();
        if (!context.isNewBusiness() && context.getYear() != null && context.getUnderWriteDate() != null) {
            isNewBusiness = context.getYear().equals(context.getUnderWriteDate().getYear());
            context.setNewBusiness(isNewBusiness);
        }

        String eopMonthStr = context.getEopDate() != null ? context.getEopDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) : context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);
        if (pvData == null) {
             TreeMap<String, PVSourceData> dataMap = new TreeMap<>(context.getPvSourceData().getDataByMonth());
             String fallbackMonth = dataMap.floorKey(eopMonthStr);
             if (fallbackMonth == null && !dataMap.isEmpty()) fallbackMonth = dataMap.lastKey();
             if (fallbackMonth != null) {
                 pvData = context.getPvSourceData().getData(fallbackMonth);
                 logger.logText("⚠️  警告: 使用替代PV数据: " + fallbackMonth);
             } else {
                 throw new RuntimeException("PV data missing for " + eopMonthStr);
             }
        }

        // [Sec 13.2] 年初有效合同_预期现金流 IFIE_P&C
        BigDecimal ifieIfCf = BigDecimal.ZERO;

        if (!isNewBusiness) {
            BigDecimal pvBopFutClaims = pvData.getField("Pvfl_If_Bop_Cfa_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvBopFutMaint = pvData.getField("Pvfl_If_Bop_Cfa_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);
//            BigDecimal pvBopCurPrem = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Pre_Amt", BigDecimal.ZERO);
//            BigDecimal pvBopCurIacf = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
            BigDecimal pvBopCurClaims = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvBopCurMaint = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

            BigDecimal pvEndTotal = pvBopFutClaims.add(pvBopFutMaint).add(pvBopCurClaims).add(pvBopCurMaint);

            BigDecimal pvBopFutClaimsWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvBopFutMaintWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Mtn_Amt", BigDecimal.ZERO);

            BigDecimal pvBegFutWlk = pvBopFutClaimsWlk.add(pvBopFutMaintWlk);

            ifieIfCf = pvEndTotal.subtract(pvBegFutWlk);

            Map<String, Object> meta132 = new HashMap<>();
            meta132.put("期末现值合计（Wlk）", pvEndTotal);
            meta132.put("年初现值合计（Wlk）", pvBegFutWlk);
            logger.logItem(
                    "年初有效合同_预期现金流 IFIE_P&C",
                    "[Sec 13.2] 年初有效合同预期现金流 IFIE",
                    "期末现值（Wlk） - 期初现值（Wlk）",
                    meta132,
                    ifieIfCf
            );
        }

        // [Sec 13.3] 当年新增合同_预期现金流 IFIE_P&C
        BigDecimal ifieNbCf = BigDecimal.ZERO;
        if (isNewBusiness) {
            BigDecimal pvNbIniFutClaims = pvData.getField("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvNbIniFutMaint = pvData.getField("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

//            BigDecimal pvNbIniCurPrem = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Pre_Amt", BigDecimal.ZERO);
//            BigDecimal pvNbIniCurIacf = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt", BigDecimal.ZERO);
            BigDecimal pvNbIniCurClaims = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvNbIniCurMaint = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

            BigDecimal pvNbEndTotal = pvNbIniFutClaims.add(pvNbIniFutMaint).add(pvNbIniCurClaims).add(pvNbIniCurMaint);

            BigDecimal pvNbInitFutClaims = pvData.getField("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt", BigDecimal.ZERO);
            BigDecimal pvNbInitFutMaint = pvData.getField("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt", BigDecimal.ZERO);

            BigDecimal pvNbInitFutTotal = pvNbInitFutClaims.add(pvNbInitFutMaint);

            ifieNbCf = pvNbEndTotal.subtract(pvNbInitFutTotal);

            Map<String, Object> meta133 = new HashMap<>();
            meta133.put("期末现值合计（Wlk）", pvNbEndTotal);
            meta133.put("初始现值合计（Lkd）", pvNbInitFutTotal);
            logger.logItem(
                    "新增合同_预期现金流 IFIE_P&C",
                    "[Sec 13.3] 新增合同预期现金流 IFIE",
                    "期末现值（Wlk） - 初始现值（Lkd）",
                    meta133,
                    ifieNbCf
            );
        }

        BigDecimal ifieCf = ifieIfCf.add(ifieNbCf);

        // [Sec 13.5] 年初有效合同_非金融风险调整 IFIE_P&C
        BigDecimal ifieIfRa = BigDecimal.ZERO;
        if (!isNewBusiness) {
            BigDecimal raBopFut = pvData.getField("Pvfl_If_Bop_Cfa_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
            BigDecimal raBopCur = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
            BigDecimal raEndTotal = raBopFut.add(raBopCur);
            BigDecimal raBopFutWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Rad_Amt", BigDecimal.ZERO);

            ifieIfRa = raEndTotal.subtract(raBopFutWlk);

            Map<String, Object> meta135 = new HashMap<>();
            meta135.put("期末RA现值合计（Wlk）", raEndTotal);
            meta135.put("年初-预期未来-RA（Wlk）", raBopFutWlk);
            logger.logItem(
                    "年初有效合同_非金融风险调整 IFIE_P&C",
                    "[Sec 13.5] 年初有效合同非金融风险调整 IFIE",
                    "期末RA（Wlk） - 期初RA（Wlk）",
                    meta135,
                    ifieIfRa
            );
        }

        // [Sec 13.6] 当年新增合同_非金融风险调整 IFIE_P&C
        BigDecimal ifieNbRa = BigDecimal.ZERO;
        if (isNewBusiness) {
            BigDecimal raNbIniFut = pvData.getField("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
            BigDecimal raNbIniCur = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
            BigDecimal raNbEndTotal = raNbIniFut.add(raNbIniCur);
            BigDecimal raNbInitFut = pvData.getField("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt", BigDecimal.ZERO);

            ifieNbRa = raNbEndTotal.subtract(raNbInitFut);

            Map<String, Object> meta136 = new HashMap<>();
            meta136.put("期末RA现值合计（Wlk）", raNbEndTotal);
            meta136.put("初始RA现值合计（Lkd）", raNbInitFut);
            logger.logItem(
                    "新增合同_非金融风险调整 IFIE_P&C",
                    "[Sec 13.6] 新增合同非金融风险调整 IFIE",
                    "期末RA（Wlk） - 初始RA（Lkd）",
                    meta136,
                    ifieNbRa
            );
        }

        BigDecimal ifieRa = ifieIfRa.add(ifieNbRa);

        // Save totals to context
        context.setIfiePvCfTotal(ifieCf);
        context.setIfiePvRaTotal(ifieRa);

        // [Sec 14] IFIE_OCI
        BigDecimal ifieOciCf = BigDecimal.ZERO;
        BigDecimal ifieOciRa = BigDecimal.ZERO;

        if (USE_OCI_OPTION) {
            // 14.2 IFIE_OCI_IF_CF
            BigDecimal ifieOciIfCf = BigDecimal.ZERO;
            if (!isNewBusiness) {
                BigDecimal pvIfEndClaimsCur = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvIfEndMaintCur = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Mtn_Amt", BigDecimal.ZERO);
                BigDecimal pvIfEndClaimsLkd = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvIfEndMaintLkd = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

                BigDecimal pvIfBegClaimsPrevCur = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Lcu_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvIfBegMaintPrevCur = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Lcu_Mtn_Amt", BigDecimal.ZERO);
                BigDecimal pvIfBegClaimsPrevWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvIfBegMaintPrevWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Mtn_Amt", BigDecimal.ZERO);

                BigDecimal endRateDiff = pvIfEndClaimsCur.add(pvIfEndMaintCur).subtract(pvIfEndClaimsLkd.add(pvIfEndMaintLkd));
                BigDecimal begRateDiff = pvIfBegClaimsPrevCur.add(pvIfBegMaintPrevCur).subtract(pvIfBegClaimsPrevWlk.add(pvIfBegMaintPrevWlk));

                ifieOciIfCf = endRateDiff.subtract(begRateDiff);

                Map<String, Object> metaOciIfCf = new HashMap<>();
                metaOciIfCf.put("End Diff", endRateDiff);
                metaOciIfCf.put("Beg Diff", begRateDiff);
                logger.logItem(
                        "年初有效合同_预期现金流 IFIE_OCI",
                        "[Sec 14.2] 年初有效合同预期现金流 IFIE_OCI",
                        "利率变化差异 (期末 - 年初)",
                        metaOciIfCf,
                        ifieOciIfCf
                );
            }

            // 14.3 IFIE_OCI_NB_CF
            BigDecimal ifieOciNbCf = BigDecimal.ZERO;
            if (isNewBusiness) {
                BigDecimal pvNbEndClaimsCur = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvNbEndMaintCur = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Mtn_Amt", BigDecimal.ZERO);
                BigDecimal pvNbEndClaimsLkd = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
                BigDecimal pvNbEndMaintLkd = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

                ifieOciNbCf = pvNbEndClaimsCur.add(pvNbEndMaintCur).subtract(pvNbEndClaimsLkd.add(pvNbEndMaintLkd));

                logger.logItem(
                        "新增合同_预期现金流 IFIE_OCI",
                        "[Sec 14.3] 新增合同预期现金流 IFIE_OCI",
                        "利率变化差异 (期末)",
                        new HashMap<String, Object>(),
                        ifieOciNbCf
                );
            }
            ifieOciCf = ifieOciIfCf.add(ifieOciNbCf);

            // 14.5 IFIE_OCI_IF_RA
            BigDecimal ifieOciIfRa = BigDecimal.ZERO;
            if (!isNewBusiness) {
                BigDecimal raIfEndCur = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Rad_Amt", BigDecimal.ZERO);
                BigDecimal raIfEndLkd = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
                BigDecimal raIfBegPrevCur = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Lcu_Rad_Amt", BigDecimal.ZERO);
                BigDecimal raIfBegPrevWlk = pvData.getField("Pvfl_If_Bop_Cfa_Beg_Wlk_Rad_Amt", BigDecimal.ZERO);

                BigDecimal endRaRateDiff = raIfEndCur.subtract(raIfEndLkd);
                BigDecimal begRaRateDiff = raIfBegPrevCur.subtract(raIfBegPrevWlk);

                ifieOciIfRa = endRaRateDiff.subtract(begRaRateDiff);

                Map<String, Object> metaOciIfRa = new HashMap<>();
                metaOciIfRa.put("End Diff", endRaRateDiff);
                metaOciIfRa.put("Beg Diff", begRaRateDiff);
                logger.logItem(
                        "年初有效合同_非金融风险调整 IFIE_OCI",
                        "[Sec 14.5] 年初有效合同非金融风险调整 IFIE_OCI",
                        "利率变化差异 (期末 - 年初)",
                        metaOciIfRa,
                        ifieOciIfRa
                );
            }

            // 14.6 IFIE_OCI_NB_RA
            BigDecimal ifieOciNbRa = BigDecimal.ZERO;
            if (isNewBusiness) {
                BigDecimal raNbEndCur = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Rad_Amt", BigDecimal.ZERO);
                BigDecimal raNbEndLkd = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);

                ifieOciNbRa = raNbEndCur.subtract(raNbEndLkd);

                logger.logItem(
                        "新增合同_非金融风险调整 IFIE_OCI",
                        "[Sec 14.6] 新增合同非金融风险调整 IFIE_OCI",
                        "利率变化差异 (期末)",
                        new HashMap<String, Object>(),
                        ifieOciNbRa
                );
            }
            ifieOciRa = ifieOciIfRa.add(ifieOciNbRa);

            BigDecimal ifieOciTotal = ifieOciCf.add(ifieOciRa);
            logger.logItem("IFIE_OCI合计", "IFIE_OCI_CF + IFIE_OCI_RA", "", new HashMap<String, Object>(), ifieOciTotal);
        }

        context.setIfieOciPvCfTotal(ifieOciCf);
        context.setIfieOciPvRaTotal(ifieOciRa);
    }

    private BigDecimal getVal(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
