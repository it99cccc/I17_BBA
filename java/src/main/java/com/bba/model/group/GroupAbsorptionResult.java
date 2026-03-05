package com.bba.model.group;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class GroupAbsorptionResult {
    private BigDecimal cohortCsm;
    private BigDecimal cohortLc;
    private BigDecimal netTrial;
    private BigDecimal groupCsmAbsorbedTotal;
    private BigDecimal groupCsmAbsorbedCf;
    private BigDecimal groupCsmAbsorbedRa;
    private BigDecimal groupLcAbsorbedTotal;
}
