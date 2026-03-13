package com.bba.service.logic;

import com.bba.model.CalculationContext;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * LRC 期末负债服务类。
 * 负责计算未到期责任负债 (LRC)。
 */
@Service
public class LrcClosingService {

    public void runClosing(CalculationContext context, CalculationLogger logger) {
        logger.logSection("Part 9: 期末负债 (Liability for Remaining Coverage)");

        // 1. 预期现金流现值 (BEL)
        String eopMonthStr = context.getEopDate() != null ? context.getEopDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) : context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);

        logger.logText("LRC Calculation - EOP Month: " + eopMonthStr);
        if (pvData == null) {
            logger.logText("⚠️ PV Data not found for " + eopMonthStr + ", attempting fallback...");
             TreeMap<String, PVSourceData> dataMap = new TreeMap<>(context.getPvSourceData().getDataByMonth());
             String fallbackMonth = dataMap.floorKey(eopMonthStr);
             if (fallbackMonth == null && !dataMap.isEmpty()) {
                fallbackMonth = dataMap.lastKey();
            }
             if (fallbackMonth != null) {
                 pvData = context.getPvSourceData().getData(fallbackMonth);
                 logger.logText("✅ Fallback to PV Data from " + fallbackMonth);
             } else {
                 logger.logText("❌ No PV Data available for fallback!");
             }
        } else {
            logger.logText("✅ PV Data found for " + eopMonthStr);
        }

        BigDecimal pvEopClaimsCurrent = BigDecimal.ZERO;
        BigDecimal pvEopMaintCurrent = BigDecimal.ZERO;
        BigDecimal pvEopPremCurrent = BigDecimal.ZERO;
        BigDecimal pvEopIacfCurrent = BigDecimal.ZERO;

        if (pvData != null) {
            // 有效合同
            BigDecimal ifClaims = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Cla_Amt", BigDecimal.ZERO);
            BigDecimal ifMaint = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Mtn_Amt", BigDecimal.ZERO);
            BigDecimal ifPrem = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Pre_Amt", BigDecimal.ZERO);
            BigDecimal ifIacf = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Acq_Amt", BigDecimal.ZERO);

            // 新增合同 (如果存在)
            BigDecimal nbClaims = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Cla_Amt", BigDecimal.ZERO);
            BigDecimal nbMaint = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Mtn_Amt", BigDecimal.ZERO);
            BigDecimal nbPrem = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Pre_Amt", BigDecimal.ZERO);
            BigDecimal nbIacf = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Acq_Amt", BigDecimal.ZERO);

            logger.logText(String.format("BEL Components (IF): Claims=%s, Maint=%s, Prem=%s, IACF=%s", ifClaims, ifMaint, ifPrem, ifIacf));
            logger.logText(String.format("BEL Components (NB): Claims=%s, Maint=%s, Prem=%s, IACF=%s", nbClaims, nbMaint, nbPrem, nbIacf));

            pvEopClaimsCurrent = ifClaims.add(nbClaims);
            pvEopMaintCurrent = ifMaint.add(nbMaint);
            pvEopPremCurrent = ifPrem.add(nbPrem);
            pvEopIacfCurrent = ifIacf.add(nbIacf);
        }

        context.setPvEopClaimsCurrent(pvEopClaimsCurrent);
        context.setPvEopMaintCurrent(pvEopMaintCurrent);

        // LRC BEL Total = -Premium + IACF + Claims + Maint
        // Note: Premium is inflow (Asset), so it reduces Liability.
        BigDecimal lrcBelTotal = pvEopPremCurrent.negate()
                .add(pvEopIacfCurrent)
                .add(pvEopClaimsCurrent)
                .add(pvEopMaintCurrent);
        context.setLrcBelTotal(lrcBelTotal);

        Map<String, Object> metaBel = new HashMap<>();
        metaBel.put("Claims", pvEopClaimsCurrent);
        metaBel.put("Maint", pvEopMaintCurrent);
        metaBel.put("Premium", pvEopPremCurrent);
        metaBel.put("IACF", pvEopIacfCurrent);
        logger.logItem(
                "未到期责任负债_预期现金流",
                "期末预期现金流现值 (BEL)",
                "-Premium + IACF + Claims + Maint (Current Rate)",
                metaBel,
                lrcBelTotal
        );

        // 2. 非金融风险调整 (RA)
        BigDecimal lrcRa = BigDecimal.ZERO;
        if (pvData != null) {
            BigDecimal ifRa = pvData.getField("Pvfl_If_Eop_Cfa_Rep_Cur_Rad_Amt", BigDecimal.ZERO);
            BigDecimal nbRa = pvData.getField("Pvfl_Nb_Eop_Cfa_Rep_Cur_Rad_Amt", BigDecimal.ZERO);
            lrcRa = ifRa.add(nbRa);
        }
        context.setLrcRa(lrcRa);

        logger.logItem(
                "未到期责任负债_非金融风险调整",
                "期末RA现值",
                "RA (Current Rate)",
                new HashMap<String, Object>(),
                lrcRa
        );

        // 3. CSM
        BigDecimal endCsm = context.getEndCsmFinal() != null ? context.getEndCsmFinal() : (context.getEndCsmBeforeAmort() != null ? context.getEndCsmBeforeAmort() : BigDecimal.ZERO);

        logger.logItem(
                "未到期责任负债_CSM",
                "期末CSM余额",
                "End CSM Final",
                new HashMap<String, Object>(),
                endCsm
        );

        // 4. Total LRC
        BigDecimal lrcTotal = lrcBelTotal.add(lrcRa).add(endCsm);
        context.setLrcTotal(lrcTotal);

        logger.logItem(
                "未到期责任负债_合计",
                "Total LRC",
                "BEL + RA + CSM",
                new HashMap<String, Object>(),
                lrcTotal
        );
    }
}
