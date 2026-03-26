package com.bba.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Data
@TableName("pv_source_data")
public class PvSourceDataEntity {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("unit_id")
    private String unitId;

    @TableField("valuation_month")
    private String valuationMonth;

    @TableField("run_date")
    private String runDate;

    @TableField("valuation_date")
    private LocalDate valuationDate;

    @TableField("under_write_date")
    private LocalDate underWriteDate;

    @TableField("pvfl_nb_ini_cfa_rec_lkd_pre_amt")
    private BigDecimal pvflNbIniCfaRecLkdPreAmt;

    @TableField("pvfl_nb_ini_cfa_rec_lkd_acq_amt")
    private BigDecimal pvflNbIniCfaRecLkdAcqAmt;

    @TableField("pvfl_nb_ini_cfa_rec_lkd_cla_amt")
    private BigDecimal pvflNbIniCfaRecLkdClaAmt;

    @TableField("pvfl_nb_ini_cfa_rec_lkd_mtn_amt")
    private BigDecimal pvflNbIniCfaRecLkdMtnAmt;

    @TableField("pvfl_nb_ini_cfa_rec_lkd_rad_amt")
    private BigDecimal pvflNbIniCfaRecLkdRadAmt;

    @TableField("pvfl_nb_eop_cfa_rep_wlk_pre_amt")
    private BigDecimal pvflNbEopCfaRepWlkPreAmt;

    @TableField("pvfl_nb_eop_cfa_rep_wlk_cla_amt")
    private BigDecimal pvflNbEopCfaRepWlkClaAmt;

    @TableField("pvfl_nb_eop_cfa_rep_wlk_mtn_amt")
    private BigDecimal pvflNbEopCfaRepWlkMtnAmt;

    @TableField("pvfl_nb_eop_cfa_rep_wlk_rad_amt")
    private BigDecimal pvflNbEopCfaRepWlkRadAmt;

    @TableField("pvfl_nb_eop_cfa_rep_cur_pre_amt")
    private BigDecimal pvflNbEopCfaRepCurPreAmt;

    @TableField("pvfl_nb_eop_cfa_rep_cur_cla_amt")
    private BigDecimal pvflNbEopCfaRepCurClaAmt;

    @TableField("pvfl_nb_eop_cfa_rep_cur_mtn_amt")
    private BigDecimal pvflNbEopCfaRepCurMtnAmt;

    @TableField("pvfl_nb_eop_cfa_rep_cur_rad_amt")
    private BigDecimal pvflNbEopCfaRepCurRadAmt;

    @TableField("pvfl_nb_eop_cfa_rep_cur_acq_amt")
    private BigDecimal pvflNbEopCfaRepCurAcqAmt;

    @TableField("pvfl_nb_eop_cca_rep_wlk_cla_amt")
    private BigDecimal pvflNbEopCcaRepWlkClaAmt;

    @TableField("pvfl_nb_eop_cca_rep_wlk_mtn_amt")
    private BigDecimal pvflNbEopCcaRepWlkMtnAmt;

    @TableField("pvfl_nb_eop_cca_rep_wlk_rad_amt")
    private BigDecimal pvflNbEopCcaRepWlkRadAmt;

    @TableField("pvfl_nb_eop_cca_rep_wlk_pre_amt")
    private BigDecimal pvflNbEopCcaRepWlkPreAmt;

    @TableField("pvfl_nb_eop_cca_rep_wlk_acq_amt")
    private BigDecimal pvflNbEopCcaRepWlkAcqAmt;

    @TableField("pvfl_nb_ini_cfa_rep_wlk_pre_amt")
    private BigDecimal pvflNbIniCfaRepWlkPreAmt;

    @TableField("pvfl_nb_ini_cca_rep_wlk_pre_amt")
    private BigDecimal pvflNbIniCcaRepWlkPreAmt;

    @TableField("pvfl_nb_ini_cfa_rep_wlk_acq_amt")
    private BigDecimal pvflNbIniCfaRepWlkAcqAmt;

    @TableField("pvfl_nb_ini_cca_rep_wlk_acq_amt")
    private BigDecimal pvflNbIniCcaRepWlkAcqAmt;

    @TableField("pvfl_nb_ini_cca_rep_wlk_cla_amt")
    private BigDecimal pvflNbIniCcaRepWlkClaAmt;

    @TableField("pvfl_nb_ini_cca_rep_wlk_mtn_amt")
    private BigDecimal pvflNbIniCcaRepWlkMtnAmt;

    @TableField("pvfl_nb_ini_cca_rep_wlk_rad_amt")
    private BigDecimal pvflNbIniCcaRepWlkRadAmt;

    @TableField("pvfl_nb_eop_cfa_rep_wlk_acq_amt")
    private BigDecimal pvflNbEopCfaRepWlkAcqAmt;

    @TableField("pvfl_nb_ini_cfa_rep_wlk_cla_amt")
    private BigDecimal pvflNbIniCfaRepWlkClaAmt;

    @TableField("pvfl_nb_ini_cfa_rep_wlk_mtn_amt")
    private BigDecimal pvflNbIniCfaRepWlkMtnAmt;

    @TableField("pvfl_nb_ini_cfa_rep_wlk_rad_amt")
    private BigDecimal pvflNbIniCfaRepWlkRadAmt;

    @TableField("pvfl_if_bop_cfa_beg_lcu_cla_amt")
    private BigDecimal pvflIfBopCfaBegLcuClaAmt;

    @TableField("pvfl_if_bop_cfa_beg_lcu_mtn_amt")
    private BigDecimal pvflIfBopCfaBegLcuMtnAmt;

    @TableField("pvfl_if_bop_cfa_beg_lcu_rad_amt")
    private BigDecimal pvflIfBopCfaBegLcuRadAmt;

    @TableField("pvfl_if_bop_cfa_rep_wlk_pre_amt")
    private BigDecimal pvflIfBopCfaRepWlkPreAmt;

    @TableField("pvfl_if_bop_cfa_rep_wlk_acq_amt")
    private BigDecimal pvflIfBopCfaRepWlkAcqAmt;

    @TableField("pvfl_if_bop_cfa_rep_wlk_cla_amt")
    private BigDecimal pvflIfBopCfaRepWlkClaAmt;

    @TableField("pvfl_if_bop_cfa_rep_wlk_mtn_amt")
    private BigDecimal pvflIfBopCfaRepWlkMtnAmt;

    @TableField("pvfl_if_bop_cfa_rep_wlk_rad_amt")
    private BigDecimal pvflIfBopCfaRepWlkRadAmt;

    @TableField("pvfl_if_bop_cca_rep_wlk_pre_amt")
    private BigDecimal pvflIfBopCcaRepWlkPreAmt;

    @TableField("pvfl_if_bop_cca_rep_wlk_acq_amt")
    private BigDecimal pvflIfBopCcaRepWlkAcqAmt;

    @TableField("pvfl_if_bop_cca_rep_wlk_cla_amt")
    private BigDecimal pvflIfBopCcaRepWlkClaAmt;

    @TableField("pvfl_if_bop_cca_rep_wlk_mtn_amt")
    private BigDecimal pvflIfBopCcaRepWlkMtnAmt;

    @TableField("pvfl_if_bop_cca_rep_wlk_rad_amt")
    private BigDecimal pvflIfBopCcaRepWlkRadAmt;

    @TableField("pvfl_if_bop_cfa_beg_wlk_cla_amt")
    private BigDecimal pvflIfBopCfaBegWlkClaAmt;

    @TableField("pvfl_if_bop_cfa_beg_wlk_mtn_amt")
    private BigDecimal pvflIfBopCfaBegWlkMtnAmt;

    @TableField("pvfl_if_bop_cfa_beg_wlk_rad_amt")
    private BigDecimal pvflIfBopCfaBegWlkRadAmt;

    @TableField("pvfl_if_eop_cfa_rep_wlk_pre_amt")
    private BigDecimal pvflIfEopCfaRepWlkPreAmt;

    @TableField("pvfl_if_eop_cfa_rep_wlk_acq_amt")
    private BigDecimal pvflIfEopCfaRepWlkAcqAmt;

    @TableField("pvfl_if_eop_cfa_rep_wlk_cla_amt")
    private BigDecimal pvflIfEopCfaRepWlkClaAmt;

    @TableField("pvfl_if_eop_cfa_rep_wlk_mtn_amt")
    private BigDecimal pvflIfEopCfaRepWlkMtnAmt;

    @TableField("pvfl_if_eop_cfa_rep_wlk_rad_amt")
    private BigDecimal pvflIfEopCfaRepWlkRadAmt;

    @TableField("pvfl_if_eop_cfa_rep_cur_cla_amt")
    private BigDecimal pvflIfEopCfaRepCurClaAmt;

    @TableField("pvfl_if_eop_cfa_rep_cur_mtn_amt")
    private BigDecimal pvflIfEopCfaRepCurMtnAmt;

    @TableField("pvfl_if_eop_cfa_rep_cur_rad_amt")
    private BigDecimal pvflIfEopCfaRepCurRadAmt;

    @TableField("pvfl_if_eop_cfa_rep_cur_pre_amt")
    private BigDecimal pvflIfEopCfaRepCurPreAmt;

    @TableField("pvfl_if_eop_cfa_rep_cur_acq_amt")
    private BigDecimal pvflIfEopCfaRepCurAcqAmt;

    @TableField("pvfl_if_eop_cca_rep_wlk_pre_amt")
    private BigDecimal pvflIfEopCcaRepWlkPreAmt;

    @TableField("pvfl_if_eop_cca_rep_wlk_acq_amt")
    private BigDecimal pvflIfEopCcaRepWlkAcqAmt;

    @TableField("pvfl_if_eop_cca_rep_wlk_cla_amt")
    private BigDecimal pvflIfEopCcaRepWlkClaAmt;

    @TableField("pvfl_if_eop_cca_rep_wlk_mtn_amt")
    private BigDecimal pvflIfEopCcaRepWlkMtnAmt;

    @TableField("pvfl_if_eop_cca_rep_wlk_rad_amt")
    private BigDecimal pvflIfEopCcaRepWlkRadAmt;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
