package com.bba.dto;

import com.bba.entity.ActuarialAssumption;
import com.bba.model.Assumptions;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

@Data
@Builder
public class YearlyLogData {
    private int year;
    private PolicyInfoDTO policyInfo;
    private Assumptions assumptions;
    private List<CashFlowDetailDTO> cashFlows;
    private Map<String, List<RateCurvePointDTO>> rateCurves;
    private List<PvCalcDetailDTO> pvCalculations;

    @Data
    @Builder
    public static class PolicyInfoDTO {
        private String policyNo;
        private String certiNo;
        private LocalDate underWriteDate;
        private LocalDate startDate;
        private LocalDate endDate;
        private LocalDate warrantyEndDate;
        private BigDecimal writtenPremium;
    }

    @Data
    @Builder
    public static class CashFlowDetailDTO {
        private String yyyymm;
        private LocalDate dateObj;
        private BigDecimal premium;
        private BigDecimal iacf;
        private BigDecimal claims;
        private BigDecimal expenses;
        private boolean inRisk;
        private String riskDesc;
    }

    @Data
    @Builder
    public static class RateCurvePointDTO {
        private int termMonth;
        private BigDecimal forwardRate;
        private BigDecimal discountFactor;
        private String description;
    }

    @Data
    @Builder
    public static class PvCalcDetailDTO {
        private String fieldName;
        private String description;
        private BigDecimal value;
    }
}
