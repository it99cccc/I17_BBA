package com.bba.model;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class CohortState {

    private String cohortId;

    private BigDecimal weightedLockedRate = BigDecimal.ZERO;
    private BigDecimal totalWrittenPremium = BigDecimal.ZERO;

    //期初 (BOP)
    private BigDecimal bopCsm = BigDecimal.ZERO;
    private BigDecimal bopLc = BigDecimal.ZERO;
    private BigDecimal bopIacf = BigDecimal.ZERO;

    // New Business
    private BigDecimal newCsm = BigDecimal.ZERO;
    private BigDecimal newLc = BigDecimal.ZERO;
    private BigDecimal newIacf = BigDecimal.ZERO;

    // 增加的利息
    private BigDecimal csmInterest = BigDecimal.ZERO;
    private BigDecimal lcInterest = BigDecimal.ZERO;
    private BigDecimal iacfInterest = BigDecimal.ZERO;

    // 吸收的变化
    private BigDecimal csmAbsorbedChanges = BigDecimal.ZERO;
    private BigDecimal lcAbsorbedChanges = BigDecimal.ZERO;

    // 通宵
    private BigDecimal csmAmortization = BigDecimal.ZERO;
    private BigDecimal lcAmortization = BigDecimal.ZERO;
    private BigDecimal iacfAmortization = BigDecimal.ZERO;

    // 期末 (EOP)
    private BigDecimal eopCsm = BigDecimal.ZERO;
    private BigDecimal eopLc = BigDecimal.ZERO;
    private BigDecimal eopIacf = BigDecimal.ZERO;

    // 累计的IFIE
    private BigDecimal ifiePlTotal = BigDecimal.ZERO;
    private BigDecimal ifieOciTotal = BigDecimal.ZERO;

    // Status
    private boolean isProfitable = true;
    private BigDecimal netTrial = BigDecimal.ZERO;

    private int monthsSinceInitial = 0;

    public void calculateEopBalances() {
        BigDecimal bopCsmVal = bopCsm != null ? bopCsm : BigDecimal.ZERO;
        BigDecimal bopLcVal = bopLc != null ? bopLc : BigDecimal.ZERO;
        BigDecimal bopIacfVal = bopIacf != null ? bopIacf : BigDecimal.ZERO;
        BigDecimal newCsmVal = newCsm != null ? newCsm : BigDecimal.ZERO;
        BigDecimal newLcVal = newLc != null ? newLc : BigDecimal.ZERO;
        BigDecimal newIacfVal = newIacf != null ? newIacf : BigDecimal.ZERO;
        BigDecimal csmInterestVal = csmInterest != null ? csmInterest : BigDecimal.ZERO;
        BigDecimal lcInterestVal = lcInterest != null ? lcInterest : BigDecimal.ZERO;
        BigDecimal iacfInterestVal = iacfInterest != null ? iacfInterest : BigDecimal.ZERO;
        BigDecimal csmAbsorbedChangesVal = csmAbsorbedChanges != null ? csmAbsorbedChanges : BigDecimal.ZERO;
        BigDecimal lcAbsorbedChangesVal = lcAbsorbedChanges != null ? lcAbsorbedChanges : BigDecimal.ZERO;
        BigDecimal csmAmortizationVal = csmAmortization != null ? csmAmortization : BigDecimal.ZERO;
        BigDecimal lcAmortizationVal = lcAmortization != null ? lcAmortization : BigDecimal.ZERO;
        BigDecimal iacfAmortizationVal = iacfAmortization != null ? iacfAmortization : BigDecimal.ZERO;

        BigDecimal expectedEopCsm = bopCsmVal.add(newCsmVal).add(csmInterestVal)
                .add(csmAbsorbedChangesVal).add(csmAmortizationVal);

        // [User Request] Always recalculate EOP from components to ensure consistency with roll-forward
        // This avoids issues where updateStatesFromContext passes a null or partial value
        this.eopCsm = expectedEopCsm;

        this.eopLc = bopLcVal.add(newLcVal).add(lcInterestVal).add(lcAbsorbedChangesVal).add(lcAmortizationVal);

        this.eopIacf = bopIacfVal.add(newIacfVal).add(iacfInterestVal).add(iacfAmortizationVal);

        this.netTrial = this.eopCsm.add(this.eopLc);
        this.isProfitable = this.netTrial.compareTo(BigDecimal.ZERO) >= 0;
    }

    public void rollForward() {
        this.bopCsm = this.eopCsm;
        this.bopLc = this.eopLc;
        this.bopIacf = this.eopIacf;

        this.newCsm = BigDecimal.ZERO;
        this.newLc = BigDecimal.ZERO;
        this.newIacf = BigDecimal.ZERO;
        this.csmInterest = BigDecimal.ZERO;
        this.lcInterest = BigDecimal.ZERO;
        this.iacfInterest = BigDecimal.ZERO;
        this.csmAbsorbedChanges = BigDecimal.ZERO;
        this.lcAbsorbedChanges = BigDecimal.ZERO;
        this.csmAmortization = BigDecimal.ZERO;
        this.lcAmortization = BigDecimal.ZERO;
        this.iacfAmortization = BigDecimal.ZERO;
    }
}
