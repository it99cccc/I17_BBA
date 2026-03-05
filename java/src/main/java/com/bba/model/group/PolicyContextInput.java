package com.bba.model.group;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class PolicyContextInput {
    private String unitId;
    private boolean isIf;
    private BigDecimal bopCsm;
    private BigDecimal bopLc;
    private BigDecimal bopLcCf;
    private BigDecimal bopLcRa;
    private BigDecimal nbInitialCsm;
    private BigDecimal nbInitialLc;
    private BigDecimal ifInterestCsm;
    private BigDecimal nbInterestCsm;
    private BigDecimal ifLcIfieTotal;
    private BigDecimal nbLcIfieTotal;
    private BigDecimal csmAfterInterest;
    private BigDecimal lcAfterIfie;
    private BigDecimal deltaTotal;
    private BigDecimal deltaCf;
    private BigDecimal deltaRa;
    private BigDecimal allocatedLcTotal;
    private BigDecimal allocatedLcCf;
    private BigDecimal allocatedLcRa;
    private BigDecimal ifLcIfieCf;
    private BigDecimal nbLcIfieCf;
    private BigDecimal ifLcIfieRa;
    private BigDecimal nbLcIfieRa;
    private BigDecimal nbInitialLcCf;
    private BigDecimal nbInitialLcRa;
}
