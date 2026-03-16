package com.bba.model;

import com.bba.entity.PolicyContract;
import com.bba.entity.RateCurve;
import com.bba.model.group.IPolicyGroupCalculationInput;
import com.bba.model.pv.PVSourceDataCollection;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
public class CalculationContext implements IPolicyGroupCalculationInput {
    
    // --- Data ---
    private PolicyContract policyData;
    private LocalDate underWriteDate;
    private LocalDate startDate;
    private LocalDate endDate;
    private LocalDate warrantyEndDate;
    private LocalDate valuationDate;
    private Integer year;
    private String valMonthStr;
    private int totalMonths = 0;
    
    private List<RateCurve> ratesDf;
    private List<RateCurve> ratesDfLocked;
    private LocalDate eopDate;
    private List<RateCurve> ratesDfEop;
    
    // --- Intermediate Variables ---
    private boolean isNewBusiness;
    private boolean isReversalPolicy;
    private BigDecimal actualPremium;
    private BigDecimal actualPremiumNb = BigDecimal.ZERO;
    private BigDecimal actualPremiumEff = BigDecimal.ZERO;
    private BigDecimal initFutClaim;
    private BigDecimal initFutMaint;
    private BigDecimal initRa;
    private BigDecimal initPvIacf;
    
    private BigDecimal nbInitialCsm;
    private BigDecimal nbInitialLc;
    
    private int monthsPassed = 0;
    private int cumulativeMonthsStart = 0;
    private int cumulativeMonthsEnd = 0;
    
    private BigDecimal expectedClaimNominal;
    private BigDecimal expectedMaintNominal;
    private BigDecimal actualClaimIncurred;
    private BigDecimal actualMaintIncurred;
    private BigDecimal expectedIacfNominal;
    private BigDecimal actualIacfIncurred;
    private BigDecimal actualIacfNb = BigDecimal.ZERO;
    private BigDecimal actualIacfEff = BigDecimal.ZERO;
    
    private BigDecimal expAdjPrem;
    private BigDecimal expAdjIacf;
    private BigDecimal ifiePlCsm;
    private BigDecimal endBel;
    private BigDecimal endRa;
    private BigDecimal endLic;
    
    private BigDecimal expAdjRatio;
    private BigDecimal premVar;
    private BigDecimal iacfVar;
    private BigDecimal adjPrem;
    private BigDecimal adjIacf;
    
    private BigDecimal deltaPrem;
    private BigDecimal deltaIacf;
    private BigDecimal deltaClaims;
    private BigDecimal deltaMaint;
    private BigDecimal deltaCfTotal;
    
    private BigDecimal expAdjCsmImpact;
    
    private BigDecimal accretionFactor;
    private BigDecimal nbInterestCsm;
    private BigDecimal nbInterestLc;
    private BigDecimal ifInterestCsm;
    private BigDecimal ifInterestLc;
    private BigDecimal totalCsmInterest;
    
    private BigDecimal nbLcRatio;
    private BigDecimal ifLcIfieRatio;
    private BigDecimal allocatedLcExpAdj;
    
    // --- LC IFIE Allocation Fields ---
    private BigDecimal ifLcIfieTotal;
    private BigDecimal ifLcAfterIfie;
    private BigDecimal ifLcIfieCf;
    private BigDecimal ifLcIfieRa;
    
    private BigDecimal nbLcIfieTotal;
    private BigDecimal nbLcAfterIfie;
    private BigDecimal nbLcIfieCf;
    private BigDecimal nbLcIfieRa;
    
    private BigDecimal ifIfieAccretionClaims;
    private BigDecimal ifIfieAccretionRa;
    private BigDecimal ifIfieRateChangeClaims;
    private BigDecimal ifIfieRateChangeRa;
    
    private BigDecimal nbIfieAccretionClaims;
    private BigDecimal nbIfieAccretionRa;
    private BigDecimal nbIfieRateChangeClaims;
    private BigDecimal nbIfieRateChangeRa;
    
    // --- Pre-calculated IFIE Totals (PV only) ---
    private BigDecimal ifiePvCfTotal;
    private BigDecimal ifiePvRaTotal;
    private BigDecimal ifieOciPvCfTotal;
    private BigDecimal ifieOciPvRaTotal;
    
    // --- LC Measurement Fields ---
    private BigDecimal csmAmortRatio;
    
    private BigDecimal allocatedLcTotal;
    private BigDecimal allocatedLcCf;
    private BigDecimal allocatedLcRa;
    
    private BigDecimal allocatedLcExpAdjTotal;
    private BigDecimal allocatedLcExpAdjCf;
    private BigDecimal allocatedLcExpAdjRa;
    
    private BigDecimal lcAdjustTotal;
    private BigDecimal lcAdjustCf;
    private BigDecimal lcAdjustRa;
    
    private BigDecimal endLcCf;
    private BigDecimal endLcRa;
    
    private BigDecimal nbInitialLcCf;
    private BigDecimal nbInitialLcRa;

    private BigDecimal endCsmBeforeAmort;
    private BigDecimal endLcBeforeAmort;
    private BigDecimal csmAbsorbed;
    private BigDecimal lcChange;
    
    private boolean isInitialYear;
    private Boolean profitable; // [FIX] 用于同步合同组的盈利性状态到保单上下文
    
    private BigDecimal iacfAmortRatio;
    private BigDecimal iacfAmortAmount;
    private BigDecimal eopIacfBalance;
    private BigDecimal bopIacf;
    private BigDecimal bopCsm;
    private BigDecimal bopLc;
    private BigDecimal nbIacfAddition;
    private BigDecimal iacfInterestNb;
    private BigDecimal iacfChange;
    
    private BigDecimal revenueClaimsExpensesNet;
    private BigDecimal raReleaseNet;
    private BigDecimal csmAmortAmount;
    private BigDecimal endCsmFinal;
    private BigDecimal endLcFinal; // Added this field as it is referenced in Python logic (eop_lc update)
    private BigDecimal revenueIacfAmort;
    private BigDecimal revenueExpAdj;
    private BigDecimal totalRevenue;
    
    private BigDecimal pvEopClaimsCurrent;
    private BigDecimal pvEopMaintCurrent;
    
    // LRC
    private BigDecimal lrcBelTotal;
    private BigDecimal lrcRa;
    private BigDecimal lrcTotal;
    
    // IFIE
    private BigDecimal ifiePl;
    private BigDecimal ifieOci;
    private BigDecimal ifiePlNonLc;
    private BigDecimal ifiePlLc;
    private BigDecimal ifieOciNonLc;
    private BigDecimal ifieOciLc;

    // IFIE breakdown fields
    private BigDecimal ifiePlCfLc;
    private BigDecimal ifiePlRaLc;
    private BigDecimal ifiePlCfNonLc;
    private BigDecimal ifiePlRaNonLc;
    private BigDecimal ifieOciCfLc;
    private BigDecimal ifieOciRaLc;
    private BigDecimal ifieOciCfNonLc;
    private BigDecimal ifieOciRaNonLc;

    // Revenue breakdown fields
    private BigDecimal revenueClaimsExpensesLcAlloc;
    private BigDecimal raReleaseLcAlloc;
    private BigDecimal raReleaseGross;
    private BigDecimal revenueCsmAmort;

    
    // PV Source Data
    private PVSourceDataCollection pvSourceData;
    
    // Additional fields needed
    private String policyNo;
    private String certiNo;
    private List<PolicyState> policies; // For coverage units
    private Assumptions assumptions;
    private com.bba.model.pv.PVSourceData currentPvData;

    // Group Measurement Fields
    private String unitId;
    private BigDecimal bopLcCf;
    private BigDecimal bopLcRa;
    
    private BigDecimal nbLcIfieRatio;
    private BigDecimal nbLcIfieInterestCf;
    private BigDecimal nbLcIfieInterestRa;
    private BigDecimal nbLcIfieRateChangeCf;
    private BigDecimal nbLcIfieRateChangeRa;
    
    private BigDecimal ifLcIfieInterestCf;
    private BigDecimal ifLcIfieInterestRa;
    private BigDecimal ifLcIfieRateChangeCf;
    private BigDecimal ifLcIfieRateChangeRa;
    
    private BigDecimal allocatedGroupCsm;
    private BigDecimal allocatedGroupLc;
    
    private BigDecimal csmAbsorbedCf;
    private BigDecimal csmAbsorbedRa;
    
    private BigDecimal lcAbsorbedTotal;
    private BigDecimal lcAbsorbedCf;
    private BigDecimal lcAbsorbedRa;
    
    private BigDecimal revenueClaimsExpensesGross;
    private BigDecimal bopIacfInterest;
    private BigDecimal nbIacfInterest;
    private BigDecimal iacfExpAdj;

    // --- IPolicyGroupCalculationInput Implementation ---

    @Override
    public boolean isIf() {
        BigDecimal csm = getBopCsm() != null ? getBopCsm() : BigDecimal.ZERO;
        BigDecimal lc = getBopLc() != null ? getBopLc() : BigDecimal.ZERO;
        return csm.compareTo(BigDecimal.ZERO) != 0 || lc.compareTo(BigDecimal.ZERO) != 0;
    }

    @Override
    public BigDecimal getCsmAfterInterest() {
        BigDecimal interest = isIf() ? 
            (getIfInterestCsm() != null ? getIfInterestCsm() : BigDecimal.ZERO) : 
            (getNbInterestCsm() != null ? getNbInterestCsm() : BigDecimal.ZERO);
        
        BigDecimal initial = isIf() ? 
            (getBopCsm() != null ? getBopCsm() : BigDecimal.ZERO) : 
            (getNbInitialCsm() != null ? getNbInitialCsm() : BigDecimal.ZERO);
            
        return initial.add(interest);
    }

    @Override
    public BigDecimal getLcAfterIfie() {
        BigDecimal ifie = isIf() ? 
            (getIfLcIfieTotal() != null ? getIfLcIfieTotal() : BigDecimal.ZERO) : 
            (getNbLcIfieTotal() != null ? getNbLcIfieTotal() : BigDecimal.ZERO);
            
        BigDecimal initial = isIf() ? 
            (getBopLc() != null ? getBopLc() : BigDecimal.ZERO) : 
            (getNbInitialLc() != null ? getNbInitialLc() : BigDecimal.ZERO);
            
        return initial.add(ifie);
    }

    @Override
    public BigDecimal getDeltaTotal() {
        // Changes in fulfillment cash flows that adjust CSM (ExpAdjCsmImpact)
        return getExpAdjCsmImpact() != null ? getExpAdjCsmImpact() : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getDeltaCf() {
        return getDeltaCfTotal() != null ? getDeltaCfTotal() : BigDecimal.ZERO;
    }

    @Override
    public BigDecimal getDeltaRa() {
        // Delta Total = Delta CF + Delta RA
        // So Delta RA = Delta Total - Delta CF
        return getDeltaTotal().subtract(getDeltaCf());
    }
}
