CREATE TABLE pv_source_data (
    id SERIAL PRIMARY KEY,
    unit_id VARCHAR(50) NOT NULL,
    valuation_month VARCHAR(6) NOT NULL,
    valuation_date DATE,
    under_write_date DATE,

    -- Nb Ini
    pvfl_nb_ini_cfa_rec_lkd_pre_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rec_lkd_acq_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rec_lkd_cla_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rec_lkd_mtn_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rec_lkd_rad_amt NUMERIC(18, 2),

    -- Nb Eop
    pvfl_nb_eop_cfa_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_wlk_rad_amt NUMERIC(18, 2),

    pvfl_nb_eop_cfa_rep_cur_pre_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_cur_cla_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_cur_mtn_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_cur_rad_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_cur_acq_amt NUMERIC(18, 2),

    pvfl_nb_eop_cca_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_nb_eop_cca_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_nb_eop_cca_rep_wlk_rad_amt NUMERIC(18, 2),
    pvfl_nb_eop_cca_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_nb_eop_cca_rep_wlk_acq_amt NUMERIC(18, 2),

    -- Nb Ini at Eop
    pvfl_nb_ini_cfa_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_nb_ini_cca_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_nb_ini_cca_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_nb_ini_cca_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_nb_ini_cca_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_nb_ini_cca_rep_wlk_rad_amt NUMERIC(18, 2),
    pvfl_nb_eop_cfa_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_nb_ini_cfa_rep_wlk_rad_amt NUMERIC(18, 2),

    -- If Bop
    pvfl_if_bop_cfa_beg_lcu_cla_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_beg_lcu_mtn_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_beg_lcu_rad_amt NUMERIC(18, 2),

    pvfl_if_bop_cfa_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_rep_wlk_rad_amt NUMERIC(18, 2),

    pvfl_if_bop_cca_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_if_bop_cca_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_if_bop_cca_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_if_bop_cca_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_if_bop_cca_rep_wlk_rad_amt NUMERIC(18, 2),

    pvfl_if_bop_cfa_beg_wlk_cla_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_beg_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_if_bop_cfa_beg_wlk_rad_amt NUMERIC(18, 2),

    -- If Eop
    pvfl_if_eop_cfa_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_wlk_rad_amt NUMERIC(18, 2),

    pvfl_if_eop_cfa_rep_cur_cla_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_cur_mtn_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_cur_rad_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_cur_pre_amt NUMERIC(18, 2),
    pvfl_if_eop_cfa_rep_cur_acq_amt NUMERIC(18, 2),

    pvfl_if_eop_cca_rep_wlk_pre_amt NUMERIC(18, 2),
    pvfl_if_eop_cca_rep_wlk_acq_amt NUMERIC(18, 2),
    pvfl_if_eop_cca_rep_wlk_cla_amt NUMERIC(18, 2),
    pvfl_if_eop_cca_rep_wlk_mtn_amt NUMERIC(18, 2),
    pvfl_if_eop_cca_rep_wlk_rad_amt NUMERIC(18, 2),

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_pv_source_data_unit_month ON pv_source_data (unit_id, valuation_month);
