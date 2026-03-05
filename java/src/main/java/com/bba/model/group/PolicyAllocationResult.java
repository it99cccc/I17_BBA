package com.bba.model.group;

import lombok.Builder;
import lombok.Data;
import java.math.BigDecimal;

@Data
@Builder
public class PolicyAllocationResult {
    private String unitId;
    private BigDecimal csmAbsorbed;
    private BigDecimal csmAbsorbedCf;
    private BigDecimal csmAbsorbedRa;
    private BigDecimal lcAbsorbedTotal;
    private BigDecimal lcAbsorbedCf;
    private BigDecimal lcAbsorbedRa;
    private BigDecimal csmAllocationWeight;
    private BigDecimal lcAllocationWeight;
    
    // [FIX] 新增字段，用于在保单分摊结果中携带组级别的 CSM/LC 净额
    private BigDecimal cohortCsm;
    private BigDecimal cohortLc;
}
