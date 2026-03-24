package com.bba.model.group;

import java.math.BigDecimal;

/**
 * Interface for policy data required for group-level calculation.
 * Implemented by CalculationContext to avoid data copying.
 */
public interface IPolicyGroupCalculationInput {
    String getUnitId();
    boolean isIf(); // is In-Force (calculated based on BOP values)
    
    BigDecimal getBopCsm();
    BigDecimal getBopLc();
    BigDecimal getBopLcCf();
    BigDecimal getBopLcRa();
    
    BigDecimal getNbInitialCsm();
    BigDecimal getNbInitialLc();
    BigDecimal getNbInitialLcCf();
    BigDecimal getNbInitialLcRa();
    
    BigDecimal getIfInterestCsm();
    BigDecimal getNbInterestCsm();
    
    BigDecimal getIfLcIfieTotal();
    BigDecimal getNbLcIfieTotal();
    
    BigDecimal getIfLcIfieCf();
    BigDecimal getNbLcIfieCf();
    BigDecimal getIfLcIfieRa();
    BigDecimal getNbLcIfieRa();
    
    // Derived or specific fields
    BigDecimal getCsmAfterInterest();
    BigDecimal getLcAfterIfie();
    
    BigDecimal getDeltaTotal();
    BigDecimal getDeltaCf();
    BigDecimal getDeltaRa();
    
    BigDecimal getAllocatedLcTotal();
    BigDecimal getAllocatedLcCf();
    BigDecimal getAllocatedLcRa();
    
    boolean isReversalPolicy();
}
