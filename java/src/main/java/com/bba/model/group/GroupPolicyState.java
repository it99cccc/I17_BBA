package com.bba.model.group;

import com.bba.model.PolicyState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GroupPolicyState extends PolicyState {
    private String groupId;
    private String portfolioId;
    private String certiNo;
    private String uwMonthStr;
    private String classCode;
    private java.math.BigDecimal iacfAmount;
    private java.math.BigDecimal initialCsmForWeight;

    private java.math.BigDecimal initialLcCf;
    private java.math.BigDecimal initialLcRa;

    /**
     * 期初CSM
     */
    private java.math.BigDecimal bopCsm;
    /**
     * 期初LC
     */
    private java.math.BigDecimal bopLc;
    /**
     * 期初赔付+维持
     */
    private java.math.BigDecimal bopLcCf;
    /**
     * 期初非金融风险调整
     */
    private java.math.BigDecimal bopLcRa;
    /**
     * 期初获取费用
     */
    private java.math.BigDecimal bopIacf;
}
