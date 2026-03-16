package com.bba.model.group;

import com.bba.model.PolicyState;
import com.bba.model.pv.PVSourceDataCollection;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.ToString;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
public class GroupPolicyState extends PolicyState {
    private String groupId;
    private String portfolioId;
    private String uwMonthStr;
    private String classCode;
    private java.math.BigDecimal iacfAmount;
    private java.math.BigDecimal initialCsmForWeight;
    private PVSourceDataCollection pvSourceData;

    /**
     * 初始预期现金流_含亏损
     */
    private java.math.BigDecimal initialLcCf;

    /**
     * 初始ra_含亏损
     */
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
     * 期初ra
     */
    private java.math.BigDecimal bopLcRa;
    /**
     * 期初获取费用
     */
    private java.math.BigDecimal bopIacf;
}
