package com.bba.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import lombok.Data;

@Data
@TableName(value = "zh.summary_iacf_cost", autoResultMap = true)
public class SummaryIacfCost {
    
    @TableField("\"保单号\"")
    private String policyNo;

    @TableField("\"批单号\"")
    private String endorsementNo;

    @TableField("\"累计跟单获取费用\"")
    private Double accumulatedDirectAcquisitionCost;

    @TableField("\"累计非跟单获取费用\"")
    private Double accumulatedIndirectAcquisitionCost;

    @TableField("\"合计费用\"")
    private Double totalCost;
}
