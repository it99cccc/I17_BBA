-- 创建计量结果表
CREATE TABLE IF NOT EXISTS calculation_result (
    id BIGSERIAL PRIMARY KEY,
    policy_no VARCHAR(50) NOT NULL,
    certi_no VARCHAR(50),
    year INTEGER NOT NULL,
    run_date TIMESTAMP,
    
    -- 新增合同初始确认相关
    nb_initial_lc NUMERIC(20, 6),
    nb_init_prem NUMERIC(20, 6),
    nb_init_claims NUMERIC(20, 6),
    nb_init_maint NUMERIC(20, 6),
    nb_init_iacf NUMERIC(20, 6),
    nb_init_ra NUMERIC(20, 6),
    nb_init_csm NUMERIC(20, 6),
    
    -- 保险合同收入
    insurance_revenue_claims_expenses_gross NUMERIC(20, 6),
    insurance_revenue_claims_expenses_lc_alloc NUMERIC(20, 6),
    insurance_revenue_ra_release_gross NUMERIC(20, 6),
    insurance_revenue_ra_release_lc_alloc NUMERIC(20, 6),
    insurance_revenue_csm_amort NUMERIC(20, 6),
    insurance_revenue_iacf_amort NUMERIC(20, 6),
    insurance_revenue_exp_adj NUMERIC(20, 6),
    insurance_revenue_inv_comp NUMERIC(20, 6),
    
    -- 赔付与费用
    claims_expenses_lc_alloc_cf NUMERIC(20, 6),
    claims_expenses_lc_alloc_ra NUMERIC(20, 6),
    claims_expenses_iacf_amort NUMERIC(20, 6),
    claims_expenses_inv_comp NUMERIC(20, 6),
    
    -- 亏损合同损益
    lc_pl_nb_cf_lc NUMERIC(20, 6),
    lc_pl_nb_ra_lc NUMERIC(20, 6),
    lc_pl_cf_change_no_csm NUMERIC(20, 6),
    lc_pl_ra_change_no_csm NUMERIC(20, 6),
    
    -- IFIE P&L
    ifie_pl_cf_non_lc NUMERIC(20, 6),
    ifie_pl_cf_lc NUMERIC(20, 6),
    ifie_pl_ra_non_lc NUMERIC(20, 6),
    ifie_pl_ra_lc NUMERIC(20, 6),
    ifie_pl_csm NUMERIC(20, 6),
    
    -- IFIE OCI
    ifie_oci_cf_non_lc NUMERIC(20, 6),
    ifie_oci_cf_lc NUMERIC(20, 6),
    ifie_oci_ra_non_lc NUMERIC(20, 6),
    ifie_oci_ra_lc NUMERIC(20, 6),
    
    -- 未到期责任负债 (LRC)
    lrc_cf_non_lc NUMERIC(20, 6),
    lrc_cf_lc NUMERIC(20, 6),
    lrc_ra_non_lc NUMERIC(20, 6),
    lrc_ra_lc NUMERIC(20, 6),
    lrc_csm NUMERIC(20, 6),
    
    -- 未到期_调整CSM
    lrc_adj_csm_cf_change NUMERIC(20, 6),
    lrc_adj_csm_ra_change NUMERIC(20, 6),
    lrc_adj_csm_est_change NUMERIC(20, 6),
    
    -- 新增合同明细
    nb_cf_prem_profit NUMERIC(20, 6),
    nb_cf_iacf_profit NUMERIC(20, 6),
    nb_cf_claims_profit NUMERIC(20, 6),
    nb_ra_profit NUMERIC(20, 6),
    nb_csm_profit NUMERIC(20, 6),
    nb_cf_prem_loss NUMERIC(20, 6),
    nb_cf_iacf_loss NUMERIC(20, 6),
    nb_cf_claims_loss_non_lc NUMERIC(20, 6),
    nb_ra_loss_non_lc NUMERIC(20, 6),
    
    -- 现金流
    cashflow_prem_received NUMERIC(20, 6),
    cashflow_iacf_paid NUMERIC(20, 6),
    
    -- 期末余额
    closing_bel NUMERIC(20, 6),
    closing_ra NUMERIC(20, 6),
    closing_csm NUMERIC(20, 6),
    closing_lc NUMERIC(20, 6),
    closing_lic NUMERIC(20, 6),
    
    -- 期初余额
    opening_bel NUMERIC(20, 6),
    opening_ra NUMERIC(20, 6),
    opening_csm NUMERIC(20, 6),
    opening_lc NUMERIC(20, 6),
    opening_lic NUMERIC(20, 6)
);

CREATE INDEX idx_calc_result_policy_year ON calculation_result (policy_no, year);
