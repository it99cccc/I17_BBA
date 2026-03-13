package com.bba.model.pv;

import com.alibaba.fastjson.annotation.JSONField;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * PV 原材料数据容器
 * 存储特定评估月份的所有现值指标。
 *
 * 字段命名规则说明：
 * Pvfl: PV Field (现值字段)
 * Nb/If: New Business (新业务) / In-Force (有效业务/存量业务)
 * Ini/Bop/Eop: Initial Recognition (初始确认) / Beginning of Period (期初) / End of Period (期末)
 * Cfa/Cca: Cash Flow After (未来现金流/现值) / Cash Flow Current/Accumulated (当前/累积现金流)
 * Rec/Rep/Beg: Recognition (确认时点) / Reporting (报告时点) / Beginning (期初/年初)
 * Lkd/Wlk/Cur/Lcu: Locked Rate (锁定利率) / Weighted Locked Rate (加权锁定利率) / Current Rate (当前利率) / Last Current Rate (上期当前利率)
 * Pre/Acq/Cla/Mtn/Rad: Premium (保费) / Acquisition (获客成本) / Claims (赔付) / Maintenance (维持费用) / Risk Adjustment (风险调整)
 */
@Data
public class PVSourceData {
    @JSONField(name = "unit_id")
    private String unitId; // 保单号

    @JSONField(name = "valuation_month")
    private String valuationMonth; // 评估月份 (YYYYMM)

    @JSONField(name = "valuation_date", format = "yyyy-MM-dd")
    private LocalDate valuationDate; // 评估日期

    @JSONField(name = "under_write_date", format = "yyyy-MM-dd")
    private LocalDate underWriteDate; // 签单日期

    // --- New Business (Nb) Fields / 新业务字段 ---

    // Initial Recognition (Ini) - Locked Rate (Lkd)
    // 初始确认 - 锁定利率
    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt")
    private BigDecimal pvNbIniCfaRecLkdPreAmt; // 保费现值 (Premium)

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt")
    private BigDecimal pvNbIniCfaRecLkdAcqAmt; // 获客成本现值 (Acquisition Cost / IACF)

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt")
    private BigDecimal pvNbIniCfaRecLkdClaAmt; // 赔付现值 (Claims)

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt")
    private BigDecimal pvNbIniCfaRecLkdMtnAmt; // 维持费用现值 (Maintenance)

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt")
    private BigDecimal pvNbIniCfaRecLkdRadAmt; // 风险调整现值 (RA)

    // EOP - Weighted Locked Rate (Wlk)
    // 期末 - 加权锁定利率
    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Wlk_Pre_Amt")
    private BigDecimal pvNbEopCfaRepWlkPreAmt; // 保费现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Wlk_Cla_Amt")
    private BigDecimal pvNbEopCfaRepWlkClaAmt; // 赔付现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvNbEopCfaRepWlkMtnAmt; // 维持费用现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Wlk_Rad_Amt")
    private BigDecimal pvNbEopCfaRepWlkRadAmt; // 风险调整现值

    // EOP - Current Rate (Cur)
    // 期末 - 当前利率
    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Cur_Pre_Amt")
    private BigDecimal pvNbEopCfaRepCurPreAmt; // 保费现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Cur_Cla_Amt")
    private BigDecimal pvNbEopCfaRepCurClaAmt; // 赔付现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Cur_Mtn_Amt")
    private BigDecimal pvNbEopCfaRepCurMtnAmt; // 维持费用现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Cur_Rad_Amt")
    private BigDecimal pvNbEopCfaRepCurRadAmt; // 风险调整现值

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Cur_Acq_Amt")
    private BigDecimal pvNbEopCfaRepCurAcqAmt; // 保险获取现金流

    // EOP - Current Period (Cca) - Wlk
    // 期末 - 当期累积 (Cca) - 加权锁定利率 (用于利息增值计算)
    @JSONField(name = "Pvfl_Nb_Eop_Cca_Rep_Wlk_Cla_Amt")
    private BigDecimal pvNbEopCcaRepWlkClaAmt; // 赔付累积值

    @JSONField(name = "Pvfl_Nb_Eop_Cca_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvNbEopCcaRepWlkMtnAmt; // 维持费用累积值

    @JSONField(name = "Pvfl_Nb_Eop_Cca_Rep_Wlk_Rad_Amt")
    private BigDecimal pvNbEopCcaRepWlkRadAmt; // 风险调整累积值

    @JSONField(name = "Pvfl_Nb_Eop_Cca_Rep_Wlk_Pre_Amt")
    private BigDecimal pvNbEopCcaRepWlkPreAmt; // 保费累积值

    @JSONField(name = "Pvfl_Nb_Eop_Cca_Rep_Wlk_Acq_Amt")
    private BigDecimal pvNbEopCcaRepWlkAcqAmt; // 保险获取现金流累积值

    // Initial at EOP (for Exp Adj)
    // 初始确认值在期末的表现 (用于经验调整)
    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rep_Wlk_Pre_Amt")
    private BigDecimal pvNbIniCfaRepWlkPreAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cca_Rep_Wlk_Pre_Amt")
    private BigDecimal pvNbIniCcaRepWlkPreAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rep_Wlk_Acq_Amt")
    private BigDecimal pvNbIniCfaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt")
    private BigDecimal pvNbIniCcaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cca_Rep_Wlk_Cla_Amt")
    private BigDecimal pvNbIniCcaRepWlkClaAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cca_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvNbIniCcaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cca_Rep_Wlk_Rad_Amt")
    private BigDecimal pvNbIniCcaRepWlkRadAmt;

    @JSONField(name = "Pvfl_Nb_Eop_Cfa_Rep_Wlk_Acq_Amt")
    private BigDecimal pvNbEopCfaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rep_Wlk_Cla_Amt")
    private BigDecimal pvNbIniCfaRepWlkClaAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvNbIniCfaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_Nb_Ini_Cfa_Rep_Wlk_Rad_Amt")
    private BigDecimal pvNbIniCfaRepWlkRadAmt;

    // --- In-Force (If) Fields / 有效业务 (存量) 字段 ---

    // BOP - Last Current Rate (Lcu) - Beg (Beginning)
    // 期初 - 上期当前利率 - 年初 (Beg) (用于计算期初准备金在当前利率下的价值)
    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Lcu_Cla_Amt")
    private BigDecimal pvIfBopCfaBegLcuClaAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Lcu_Mtn_Amt")
    private BigDecimal pvIfBopCfaBegLcuMtnAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Lcu_Rad_Amt")
    private BigDecimal pvIfBopCfaBegLcuRadAmt;

    // BOP - Weighted Locked Rate (Wlk) - Rep (Reporting)
    // 期初 - 加权锁定利率 - 报告时点 (用于计算期初准备金在锁定利率下的价值)
    @JSONField(name = "Pvfl_If_Bop_Cfa_Rep_Wlk_Pre_Amt")
    private BigDecimal pvIfBopCfaRepWlkPreAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Rep_Wlk_Acq_Amt")
    private BigDecimal pvIfBopCfaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Rep_Wlk_Cla_Amt")
    private BigDecimal pvIfBopCfaRepWlkClaAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvIfBopCfaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Rep_Wlk_Rad_Amt")
    private BigDecimal pvIfBopCfaRepWlkRadAmt;

    @JSONField(name = "Pvfl_If_Bop_Cca_Rep_Wlk_Pre_Amt")
    private BigDecimal pvIfBopCcaRepWlkPreAmt;

    @JSONField(name = "Pvfl_If_Bop_Cca_Rep_Wlk_Acq_Amt")
    private BigDecimal pvIfBopCcaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_If_Bop_Cca_Rep_Wlk_Cla_Amt")
    private BigDecimal pvIfBopCcaRepWlkClaAmt;

    @JSONField(name = "Pvfl_If_Bop_Cca_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvIfBopCcaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_If_Bop_Cca_Rep_Wlk_Rad_Amt")
    private BigDecimal pvIfBopCcaRepWlkRadAmt;

    // BOP - Locked Rate (Lkd) - Beg (Beginning)
    // 期初 - 锁定利率 - 年初
    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt")
    private BigDecimal pvIfBopCfaBegWlkClaAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Wlk_Mtn_Amt")
    private BigDecimal pvIfBopCfaBegWlkMtnAmt;

    @JSONField(name = "Pvfl_If_Bop_Cfa_Beg_Wlk_Rad_Amt")
    private BigDecimal pvIfBopCfaBegWlkRadAmt;

    // EOP - Weighted Locked Rate (Wlk) - Rep
    // 期末 - 加权锁定利率 - 报告时点
    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Wlk_Pre_Amt")
    private BigDecimal pvIfEopCfaRepWlkPreAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Wlk_Acq_Amt")
    private BigDecimal pvIfEopCfaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Wlk_Cla_Amt")
    private BigDecimal pvIfEopCfaRepWlkClaAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvIfEopCfaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Wlk_Rad_Amt")
    private BigDecimal pvIfEopCfaRepWlkRadAmt;

    // EOP - Current Rate (Cur) - Rep
    // 期末 - 当前利率 - 报告时点
    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Cur_Cla_Amt")
    private BigDecimal pvIfEopCfaRepCurClaAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Cur_Mtn_Amt")
    private BigDecimal pvIfEopCfaRepCurMtnAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Cur_Rad_Amt")
    private BigDecimal pvIfEopCfaRepCurRadAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Cur_Pre_Amt")
    private BigDecimal pvIfEopCfaRepCurPreAmt;

    @JSONField(name = "Pvfl_If_Eop_Cfa_Rep_Cur_Acq_Amt")
    private BigDecimal pvIfEopCfaRepCurAcqAmt;

    // EOP - Weighted Locked Rate (Wlk) - Rep - Cca
    // 期末 - 加权锁定利率 - 报告时点 - 当期累积
    @JSONField(name = "Pvfl_If_Eop_Cca_Rep_Wlk_Pre_Amt")
    private BigDecimal pvIfEopCcaRepWlkPreAmt;

    @JSONField(name = "Pvfl_If_Eop_Cca_Rep_Wlk_Acq_Amt")
    private BigDecimal pvIfEopCcaRepWlkAcqAmt;

    @JSONField(name = "Pvfl_If_Eop_Cca_Rep_Wlk_Cla_Amt")
    private BigDecimal pvIfEopCcaRepWlkClaAmt;

    @JSONField(name = "Pvfl_If_Eop_Cca_Rep_Wlk_Mtn_Amt")
    private BigDecimal pvIfEopCcaRepWlkMtnAmt;

    @JSONField(name = "Pvfl_If_Eop_Cca_Rep_Wlk_Rad_Amt")
    private BigDecimal pvIfEopCcaRepWlkRadAmt;

    // Metadata / 元数据
    private Map<String, Object> metadata = new HashMap<>();

    // Dynamic fields storage (fallback) / 动态字段存储 (后备)
    @JSONField(name = "pv_fields")
    private Map<String, BigDecimal> pvFields = new HashMap<>();

    // Helper to get field with fallback to map
    public BigDecimal getField(String key) {
        // Try to reflectively find the field if needed, or just use the map if the user populated it.
        // However, for performance and type safety, we prefer direct field access.
        // This method is for backward compatibility or dynamic access.
        if (pvFields.containsKey(key)) {
            return pvFields.get(key);
        }
        return BigDecimal.ZERO;
    }

    public BigDecimal getField(String key, BigDecimal defaultValue) {
         BigDecimal val = getField(key);
         return val != null ? val : defaultValue;
    }

    /**
     * Unpacks values from the pvFields map into the explicit class fields.
     * This allows us to use strong typing while still loading from the dynamic JSON structure.
     */
    public void unpack() {
        if (this.pvFields == null || this.pvFields.isEmpty()) {
            return;
        }

        // Nb - Ini
        this.pvNbIniCfaRecLkdPreAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Pre_Amt");
        this.pvNbIniCfaRecLkdAcqAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Acq_Amt");
        this.pvNbIniCfaRecLkdClaAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Cla_Amt");
        this.pvNbIniCfaRecLkdMtnAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Mtn_Amt");
        this.pvNbIniCfaRecLkdRadAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rec_Lkd_Rad_Amt");

        // Nb - Eop
        this.pvNbEopCfaRepWlkPreAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Pre_Amt");
        this.pvNbEopCfaRepWlkClaAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Cla_Amt");
        this.pvNbEopCfaRepWlkMtnAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Mtn_Amt");
        this.pvNbEopCfaRepWlkRadAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Wlk_Rad_Amt");

        this.pvNbEopCfaRepCurPreAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Cur_Pre_Amt");
        this.pvNbEopCfaRepCurClaAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Cur_Cla_Amt");
        this.pvNbEopCfaRepCurMtnAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Cur_Mtn_Amt");
        this.pvNbEopCfaRepCurRadAmt = this.pvFields.get("Pvfl_Nb_Eop_Cfa_Rep_Cur_Rad_Amt");

        this.pvNbEopCcaRepWlkClaAmt = this.pvFields.get("Pvfl_Nb_Eop_Cca_Rep_Wlk_Cla_Amt");
        this.pvNbEopCcaRepWlkMtnAmt = this.pvFields.get("Pvfl_Nb_Eop_Cca_Rep_Wlk_Mtn_Amt");
        this.pvNbEopCcaRepWlkRadAmt = this.pvFields.get("Pvfl_Nb_Eop_Cca_Rep_Wlk_Rad_Amt");

        this.pvNbIniCfaRepWlkPreAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Pre_Amt");
        this.pvNbIniCcaRepWlkPreAmt = this.pvFields.get("Pvfl_Nb_Ini_Cca_Rep_Wlk_Pre_Amt");
        this.pvNbIniCfaRepWlkAcqAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Acq_Amt");
        this.pvNbIniCcaRepWlkAcqAmt = this.pvFields.get("Pvfl_Nb_Ini_Cca_Rep_Wlk_Acq_Amt");

        this.pvNbIniCfaRepWlkClaAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Cla_Amt");
        this.pvNbIniCcaRepWlkClaAmt = this.pvFields.get("Pvfl_Nb_Ini_Cca_Rep_Wlk_Cla_Amt");

        this.pvNbIniCfaRepWlkMtnAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Mtn_Amt");
        this.pvNbIniCcaRepWlkMtnAmt = this.pvFields.get("Pvfl_Nb_Ini_Cca_Rep_Wlk_Mtn_Amt");

        this.pvNbIniCfaRepWlkRadAmt = this.pvFields.get("Pvfl_Nb_Ini_Cfa_Rep_Wlk_Rad_Amt");
        this.pvNbIniCcaRepWlkRadAmt = this.pvFields.get("Pvfl_Nb_Ini_Cca_Rep_Wlk_Rad_Amt");

        // If - Bop
        this.pvIfBopCfaBegLcuClaAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Lcu_Cla_Amt");
        this.pvIfBopCfaBegLcuMtnAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Lcu_Mtn_Amt");
        this.pvIfBopCfaBegLcuRadAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Lcu_Rad_Amt");

        this.pvIfBopCfaRepWlkPreAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Rep_Wlk_Pre_Amt");
        this.pvIfBopCfaRepWlkAcqAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Rep_Wlk_Acq_Amt");
        this.pvIfBopCfaRepWlkClaAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Rep_Wlk_Cla_Amt");
        this.pvIfBopCfaRepWlkMtnAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Rep_Wlk_Mtn_Amt");
        this.pvIfBopCfaRepWlkRadAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Rep_Wlk_Rad_Amt");

        this.pvIfBopCcaRepWlkPreAmt = this.pvFields.get("Pvfl_If_Bop_Cca_Rep_Wlk_Pre_Amt");
        this.pvIfBopCcaRepWlkAcqAmt = this.pvFields.get("Pvfl_If_Bop_Cca_Rep_Wlk_Acq_Amt");
        this.pvIfBopCcaRepWlkClaAmt = this.pvFields.get("Pvfl_If_Bop_Cca_Rep_Wlk_Cla_Amt");
        this.pvIfBopCcaRepWlkMtnAmt = this.pvFields.get("Pvfl_If_Bop_Cca_Rep_Wlk_Mtn_Amt");
        this.pvIfBopCcaRepWlkRadAmt = this.pvFields.get("Pvfl_If_Bop_Cca_Rep_Wlk_Rad_Amt");

        this.pvIfBopCfaBegWlkClaAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Wlk_Cla_Amt");
        this.pvIfBopCfaBegWlkMtnAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Wlk_Mtn_Amt");
        this.pvIfBopCfaBegWlkRadAmt = this.pvFields.get("Pvfl_If_Bop_Cfa_Beg_Wlk_Rad_Amt");

        // If - Eop
        this.pvIfEopCfaRepWlkPreAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Wlk_Pre_Amt");
        this.pvIfEopCfaRepWlkAcqAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Wlk_Acq_Amt");
        this.pvIfEopCfaRepWlkClaAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Wlk_Cla_Amt");
        this.pvIfEopCfaRepWlkMtnAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Wlk_Mtn_Amt");
        this.pvIfEopCfaRepWlkRadAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Wlk_Rad_Amt");

        this.pvIfEopCfaRepCurPreAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Cur_Pre_Amt");
        this.pvIfEopCfaRepCurAcqAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Cur_Acq_Amt");
        this.pvIfEopCfaRepCurClaAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Cur_Cla_Amt");
        this.pvIfEopCfaRepCurMtnAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Cur_Mtn_Amt");
        this.pvIfEopCfaRepCurRadAmt = this.pvFields.get("Pvfl_If_Eop_Cfa_Rep_Cur_Rad_Amt");

        this.pvIfEopCcaRepWlkPreAmt = this.pvFields.get("Pvfl_If_Eop_Cca_Rep_Wlk_Pre_Amt");
        this.pvIfEopCcaRepWlkAcqAmt = this.pvFields.get("Pvfl_If_Eop_Cca_Rep_Wlk_Acq_Amt");
        this.pvIfEopCcaRepWlkClaAmt = this.pvFields.get("Pvfl_If_Eop_Cca_Rep_Wlk_Cla_Amt");
        this.pvIfEopCcaRepWlkMtnAmt = this.pvFields.get("Pvfl_If_Eop_Cca_Rep_Wlk_Mtn_Amt");
        this.pvIfEopCcaRepWlkRadAmt = this.pvFields.get("Pvfl_If_Eop_Cca_Rep_Wlk_Rad_Amt");
    }
}
