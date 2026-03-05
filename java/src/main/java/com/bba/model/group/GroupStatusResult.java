package com.bba.model.group;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class GroupStatusResult {
    private BigDecimal ifCsmAfterInterest;
    private BigDecimal nbCsmAfterInterest;
    private BigDecimal ifLcAfterIfie;
    private BigDecimal nbLcAfterIfie;
    private BigDecimal netTrial;
    private BigDecimal cohortCsm;
    private BigDecimal cohortLc;
    private boolean isProfitable;
}
