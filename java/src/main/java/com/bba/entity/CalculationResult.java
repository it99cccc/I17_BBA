package com.bba.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 计量结果与数据输出实体类
 */
@Data
@TableName("calculation_result")
public class CalculationResult {

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("policy_no")
    private String policyNo;

    @TableField("certi_no")
    private String certiNo;

    @TableField("year")
    private Integer year;
    
    @TableField("run_date")
    private LocalDateTime runDate;

    // --- 新增合同初始确认相关 ---
    @TableField("nb_initial_lc")
    private BigDecimal nbInitialLc;

    @TableField("nb_init_prem")
    private BigDecimal nbInitPrem;

    @TableField("nb_init_claims")
    private BigDecimal nbInitClaims;

    @TableField("nb_init_maint")
    private BigDecimal nbInitMaint;

    @TableField("nb_init_iacf")
    private BigDecimal nbInitIacf;

    @TableField("nb_init_ra")
    private BigDecimal nbInitRa;

    @TableField("nb_init_csm")
    private BigDecimal nbInitCsm;

    // --- 保险合同收入 ---
    @TableField("insurance_revenue_claims_expenses_gross")
    private BigDecimal insuranceRevenueClaimsExpensesGross; // 保险合同收入_预期赔付与费用_含亏损

    @TableField("insurance_revenue_claims_expenses_lc_alloc")
    private BigDecimal insuranceRevenueClaimsExpensesLcAlloc; // 保险合同收入_预期赔付与费用_亏损分摊

    @TableField("insurance_revenue_ra_release_gross")
    private BigDecimal insuranceRevenueRaReleaseGross; // 保险合同收入_预期释放的非金融风险调整_含亏损

    @TableField("insurance_revenue_ra_release_lc_alloc")
    private BigDecimal insuranceRevenueRaReleaseLcAlloc; // 保险合同收入_预期释放的非金融风险调整_亏损分摊

    @TableField("insurance_revenue_csm_amort")
    private BigDecimal insuranceRevenueCsmAmort; // 保险合同收入_摊销的CSM

    @TableField("insurance_revenue_iacf_amort")
    private BigDecimal insuranceRevenueIacfAmort; // 保险合同收入_摊销的IACF

    @TableField("insurance_revenue_exp_adj")
    private BigDecimal insuranceRevenueExpAdj; // 保险合同收入_经验调整

    @TableField("insurance_revenue_inv_comp")
    private BigDecimal insuranceRevenueInvComp; // 保险合同收入_分解的投资成分

    // --- 赔付与费用 ---
    @TableField("claims_expenses_lc_alloc_cf")
    private BigDecimal claimsExpensesLcAllocCf; // 赔付与费用_亏损分摊_预期现金流

    @TableField("claims_expenses_lc_alloc_ra")
    private BigDecimal claimsExpensesLcAllocRa; // 赔付与费用_亏损分摊_非金融风险调整

    @TableField("claims_expenses_iacf_amort")
    private BigDecimal claimsExpensesIacfAmort; // 赔付与费用_摊销的IACF

    @TableField("claims_expenses_inv_comp")
    private BigDecimal claimsExpensesInvComp; // 赔付与费用_分解的投资成分

    // --- 亏损合同损益 ---
    @TableField("lc_pl_nb_cf_lc")
    private BigDecimal lcPlNbCfLc; // 亏损合同损益_新增合同预期现金流_赔付与费用现金流_亏损

    @TableField("lc_pl_nb_ra_lc")
    private BigDecimal lcPlNbRaLc; // 亏损合同损益_新增合同非金融风险调整_亏损

    @TableField("lc_pl_cf_change_no_csm")
    private BigDecimal lcPlCfChangeNoCsm; // 亏损合同损益_不调整CSM的预期现金流变动

    @TableField("lc_pl_ra_change_no_csm")
    private BigDecimal lcPlRaChangeNoCsm; // 亏损合同损益_不调整CSM的非金融风险调整变动

    // --- IFIE P&L ---
    @TableField("ifie_pl_cf_non_lc")
    private BigDecimal ifiePlCfNonLc; // IFIE_P&L_未到期_预期现金流_非亏损

    @TableField("ifie_pl_cf_lc")
    private BigDecimal ifiePlCfLc; // IFIE_P&L_未到期_预期现金流_亏损

    @TableField("ifie_pl_ra_non_lc")
    private BigDecimal ifiePlRaNonLc; // IFIE_P&L_未到期_非金融风险调整_非亏损

    @TableField("ifie_pl_ra_lc")
    private BigDecimal ifiePlRaLc; // IFIE_P&L_未到期_非金融风险调整_亏损

    @TableField("ifie_pl_csm")
    private BigDecimal ifiePlCsm; // IFIE_P&L_未到期_CSM

    // --- IFIE OCI ---
    @TableField("ifie_oci_cf_non_lc")
    private BigDecimal ifieOciCfNonLc; // IFIE_OCI_未到期_预期现金流_非亏损

    @TableField("ifie_oci_cf_lc")
    private BigDecimal ifieOciCfLc; // IFIE_OCI_未到期_预期现金流_亏损

    @TableField("ifie_oci_ra_non_lc")
    private BigDecimal ifieOciRaNonLc; // IFIE_OCI_未到期_非金融风险调整_非亏损

    @TableField("ifie_oci_ra_lc")
    private BigDecimal ifieOciRaLc; // IFIE_OCI_未到期_非金融风险调整_亏损

    // --- 未到期责任负债 (LRC) ---
    @TableField("lrc_cf_non_lc")
    private BigDecimal lrcCfNonLc; // 未到期责任负债_预期现金流_非亏损

    @TableField("lrc_cf_lc")
    private BigDecimal lrcCfLc; // 未到期责任负债_预期现金流_亏损

    @TableField("lrc_ra_non_lc")
    private BigDecimal lrcRaNonLc; // 未到期责任负债_非金融风险调整_非亏损

    @TableField("lrc_ra_lc")
    private BigDecimal lrcRaLc; // 未到期责任负债_非金融风险调整_亏损

    @TableField("lrc_csm")
    private BigDecimal lrcCsm; // 未到期责任负债_CSM

    // --- 未到期_调整CSM ---
    @TableField("lrc_adj_csm_cf_change")
    private BigDecimal lrcAdjCsmCfChange; // 未到期_调整CSM的预期现金流变动

    @TableField("lrc_adj_csm_ra_change")
    private BigDecimal lrcAdjCsmRaChange; // 未到期_调整CSM的非金融风险调整变动

    @TableField("lrc_adj_csm_est_change")
    private BigDecimal lrcAdjCsmEstChange; // 未到期_调整CSM的估计变更

    // --- 新增合同明细 ---
    @TableField("nb_cf_prem_profit")
    private BigDecimal nbCfPremProfit; // 新增合同预期现金流_保费现金流_盈利合同

    @TableField("nb_cf_iacf_profit")
    private BigDecimal nbCfIacfProfit; // 新增合同预期现金流_IACF_盈利合同

    @TableField("nb_cf_claims_profit")
    private BigDecimal nbCfClaimsProfit; // 新增合同预期现金流_赔付与费用现金流_盈利合同

    @TableField("nb_ra_profit")
    private BigDecimal nbRaProfit; // 新增合同非金融风险调整_盈利合同

    @TableField("nb_csm_profit")
    private BigDecimal nbCsmProfit; // 新增合同CSM_盈利合同

    @TableField("nb_cf_prem_loss")
    private BigDecimal nbCfPremLoss; // 新增合同预期现金流_保费现金流_亏损合同

    @TableField("nb_cf_iacf_loss")
    private BigDecimal nbCfIacfLoss; // 新增合同预期现金流_IACF_亏损合同

    @TableField("nb_cf_claims_loss_non_lc")
    private BigDecimal nbCfClaimsLossNonLc; // 新增合同预期现金流_赔付与费用现金流_亏损合同_非亏损

    @TableField("nb_ra_loss_non_lc")
    private BigDecimal nbRaLossNonLc; // 新增合同非金融风险调整_亏损合同_非亏损

    // --- 现金流 ---
    @TableField("cashflow_prem_received")
    private BigDecimal cashflowPremReceived; // 现金流_收到的保费

    @TableField("cashflow_iacf_paid")
    private BigDecimal cashflowIacfPaid; // 现金流_支付的获取费用

    // --- 期末余额 ---
    @TableField("closing_bel")
    private BigDecimal closingBel;

    @TableField("closing_ra")
    private BigDecimal closingRa;

    @TableField("closing_csm")
    private BigDecimal closingCsm;

    @TableField("closing_lc")
    private BigDecimal closingLc;

    @TableField("closing_lic")
    private BigDecimal closingLic;

    // --- 期初余额 ---
    @TableField("opening_bel")
    private BigDecimal openingBel;

    @TableField("opening_ra")
    private BigDecimal openingRa;

    @TableField("opening_csm")
    private BigDecimal openingCsm;

    @TableField("opening_lc")
    private BigDecimal openingLc;

    @TableField("opening_lic")
    private BigDecimal openingLic;
}
