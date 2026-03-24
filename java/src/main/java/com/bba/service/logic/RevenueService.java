package com.bba.service.logic;

import com.bba.model.CalculationContext;
import com.bba.model.pv.PVSourceData;
import com.bba.util.CalculationLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * 保险合同收入服务类。
 * 负责计算保险合同收入，包括预期赔付释放、RA释放、CSM摊销、IACF摊销等。
 */
@Service
@RequiredArgsConstructor
public class RevenueService {

    private final CoverageUnitsService coverageUnitsService;

    public void run(CalculationContext context, CalculationLogger logger) {
        logger.logSection("Part 7: 保险合同收入 (Insurance Revenue)");

        // 强制要求PV原材料数据必须存在
        if (context.getPvSourceData() == null) {
            // 这里应该在前面已经保证了，但为了安全起见
            throw new RuntimeException("PV原材料数据不可用！请先运行 pv_calculator 生成数据。");
        }

        // 7.1 预期赔付与费用释放（从PV原材料数据读取现值）
        String eopMonthStr = context.getEopDate() != null ? context.getEopDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMM")) : context.getValMonthStr();
        PVSourceData pvData = context.getPvSourceData().getData(eopMonthStr);

        if (pvData == null) {
            // 尝试查找替代数据
            TreeMap<String, PVSourceData> dataMap = new TreeMap<>(context.getPvSourceData().getDataByMonth());
            String fallbackMonth = dataMap.floorKey(eopMonthStr);
            if (fallbackMonth == null && !dataMap.isEmpty()) {
                fallbackMonth = dataMap.lastKey();
            }

            if (fallbackMonth != null) {
                pvData = context.getPvSourceData().getData(fallbackMonth);
                logger.logText("⚠️  警告: 评估月 " + eopMonthStr + " 的PV原材料数据不存在，使用 " + fallbackMonth + " 的数据作为替代");
            } else {
                throw new RuntimeException("❌ 错误: 找不到期末评估月 " + eopMonthStr + " 的PV原材料数据，且没有可用的替代数据！");
            }
        }

        // 从PV原材料数据读取预期赔付与费用现值（加权初始确认利率，预期当期）
        BigDecimal pvClaimsIf = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
        BigDecimal pvMaintIf = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);
        BigDecimal pvClaimsNb = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Cla_Amt", BigDecimal.ZERO);
        BigDecimal pvMaintNb = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Mtn_Amt", BigDecimal.ZERO);

        BigDecimal revenueClaimsExpensesGross = pvClaimsIf.add(pvMaintIf).add(pvClaimsNb).add(pvMaintNb);

        // 亏损分摊：分摊的LC_预期现金流 + LC调整_预期现金流
        BigDecimal allocatedLcCf = context.getAllocatedLcCf() != null ? context.getAllocatedLcCf() : BigDecimal.ZERO;
        BigDecimal lcAdjustCf = context.getLcAdjustCf() != null ? context.getLcAdjustCf() : BigDecimal.ZERO;
        BigDecimal revenueClaimsExpensesLcAlloc = allocatedLcCf.add(lcAdjustCf);

        context.setRevenueClaimsExpensesGross(revenueClaimsExpensesGross);
        context.setRevenueClaimsExpensesLcAlloc(revenueClaimsExpensesLcAlloc);
        context.setRevenueClaimsExpensesNet(revenueClaimsExpensesGross.subtract(revenueClaimsExpensesLcAlloc));

        Map<String, Object> meta71 = new HashMap<>();
        meta71.put("有效合同_预期当期_赔付（Wlk）", pvClaimsIf);
        meta71.put("新增合同_预期当期_赔付（Wlk）", pvClaimsNb);
        meta71.put("有效合同_预期当期_维费（Wlk）", pvMaintIf);
        meta71.put("新增合同_预期当期_维费（Wlk）", pvMaintNb);
        meta71.put("预期赔付与费用合计（含亏损）", revenueClaimsExpensesGross);
        logger.logItem(
                "保险合同收入_预期赔付与费用_含亏损",
                "当期预期的赔付和维持费用释放（有效合同+新增合同，预期当期）",
                "PV字段求和",
                meta71,
                revenueClaimsExpensesGross
        );

        Map<String, Object> meta71_alloc = new HashMap<>();
        meta71_alloc.put("预期赔付与费用_含亏损", revenueClaimsExpensesGross);
        meta71_alloc.put("分摊的LC_预期现金流", allocatedLcCf);
        meta71_alloc.put("LC调整_预期现金流", lcAdjustCf);
        meta71_alloc.put("亏损分摊合计", revenueClaimsExpensesLcAlloc);
        logger.logItem(
                "保险合同收入_预期赔付与费用_亏损分摊",
                "分摊到亏损成分的预期赔付与费用",
                "分摊的LC_预期现金流 + LC调整_预期现金流",
                meta71_alloc,
                revenueClaimsExpensesLcAlloc
        );

        // 7.2 RA 释放
        BigDecimal raReleaseIf = pvData.getField("Pvfl_If_Bop_Cca_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
        BigDecimal raReleaseNb = pvData.getField("Pvfl_Nb_Ini_Cca_Rep_Wlk_Rad_Amt", BigDecimal.ZERO);
        BigDecimal raReleaseGross = raReleaseIf.add(raReleaseNb);

        BigDecimal allocatedLcRa = context.getAllocatedLcRa() != null ? context.getAllocatedLcRa() : BigDecimal.ZERO;
        BigDecimal lcAdjustRa = context.getLcAdjustRa() != null ? context.getLcAdjustRa() : BigDecimal.ZERO;
        BigDecimal raReleaseLcAlloc = allocatedLcRa.add(lcAdjustRa);

        context.setRaReleaseNet(raReleaseGross.subtract(raReleaseLcAlloc));
        context.setRaReleaseGross(raReleaseGross);
        context.setRaReleaseLcAlloc(raReleaseLcAlloc);

        Map<String, Object> meta72 = new HashMap<>();
        meta72.put("有效合同_预期当期_RA（Wlk）", raReleaseIf);
        meta72.put("新增合同_预期当期_RA（Wlk）", raReleaseNb);
        meta72.put("RA释放（含亏损）", raReleaseGross);
        logger.logItem(
                "保险合同收入_预期释放的非金融风险调整_含亏损",
                "当期释放的非金融风险调整（有效合同+新增合同，预期当期）",
                "PV字段求和",
                meta72,
                raReleaseGross
        );

        Map<String, Object> meta72_alloc = new HashMap<>();
        meta72_alloc.put("RA释放_含亏损", raReleaseGross);
        meta72_alloc.put("分摊的LC_非金融风险调整", allocatedLcRa);
        meta72_alloc.put("LC调整_非金融风险调整", lcAdjustRa);
        meta72_alloc.put("亏损分摊合计", raReleaseLcAlloc);
        logger.logItem(
                "保险合同收入_预期释放的非金融风险调整_亏损分摊",
                "分摊到亏损成分的非金融风险调整",
                "分摊的LC_非金融风险调整 + LC调整_非金融风险调整",
                meta72_alloc,
                raReleaseLcAlloc
        );

        // 7.3 CSM 摊销
        BigDecimal csmAmortRatio;
        if (context.getCsmAmortRatio() != null) {
            // 优先使用在 CSM 计量模块中已计算并保存的摊销比例
            csmAmortRatio = context.getCsmAmortRatio();
        } else if (context.getPolicies() != null && !context.getPolicies().isEmpty()) {
            LocalDate valuationDate = context.getEopDate() != null ? context.getEopDate() : LocalDate.of(context.getYear(), 12, 31);
            if (valuationDate == null) {
                valuationDate = LocalDate.of(context.getYear(), 12, 31);
            }
            LocalDate startOfYear = LocalDate.of(valuationDate.getYear(), 1, 1);
            
            csmAmortRatio = coverageUnitsService.calculateCoverageUnitsReleased(
                    context.getPolicies(), valuationDate, startOfYear, logger, context.isInitialYear()
            );
            BigDecimal cuRemaining = coverageUnitsService.calculateCoverageUnitsRemaining(
                    context.getPolicies(), valuationDate, logger
            );
            BigDecimal denominator = csmAmortRatio.add(cuRemaining);
            if (denominator.compareTo(BigDecimal.ZERO) != 0) {
                csmAmortRatio = csmAmortRatio.divide(denominator, 10, BigDecimal.ROUND_HALF_UP);
            } else {
                csmAmortRatio = BigDecimal.ONE;
            }
        } else {
            // 兼容模式
            if (context.getTotalMonths() > 0) {
                csmAmortRatio = new BigDecimal(context.getMonthsPassed()).divide(new BigDecimal(context.getTotalMonths()), 10, BigDecimal.ROUND_HALF_UP);
            } else {
                csmAmortRatio = BigDecimal.ONE;
            }
        }
        
        // [FIX] CSM 摊销金额应直接使用在 CSM 计量模块中计算的结果
        // 在组级扭亏为盈时，摊销前 CSM 是由吸收变化动态倒推得出的，直接用已算好的金额最准确
        BigDecimal csmAmortAmount = context.getCsmAmortAmount() != null ? context.getCsmAmortAmount() : BigDecimal.ZERO;
        context.setRevenueCsmAmort(csmAmortAmount); // Same as amount

        Map<String, Object> meta73 = new HashMap<>();
        meta73.put("摊销比例", csmAmortRatio);
        meta73.put("CSM摊销金额(负)", csmAmortAmount);
        logger.logItem(
                "保险合同收入_摊销的CSM",
                "[Sec 8.9] 当期确认的合同服务边际",
                "-(CSM_beg + ...) * Ratio",
                meta73,
                csmAmortAmount,
                "CSM摊销产生收入，减少负债，显示为负数"
        );

        // 7.4 IACF 摊销
        BigDecimal iacfAmortAmount = context.getIacfAmortAmount() != null ? context.getIacfAmortAmount() : BigDecimal.ZERO;
        // [修复] IACF 摊销在 Revenue 中应体现为负数（Liability decrease），与 CSM 摊销符号一致
        context.setRevenueIacfAmort(iacfAmortAmount);
        
        Map<String, Object> meta74 = new HashMap<>();
        meta74.put("IACF Amort Expense (Negative)", iacfAmortAmount);
        logger.logItem(
                "保险合同收入_IACF摊销",
                "当期回收的获取费用",
                "-(IACF Amortization Expense)",
                meta74,
                context.getRevenueIacfAmort(),
                "IACF摊销产生收入，减少负债，显示为负数"
        );

        // 7.5 经验调整
        BigDecimal premExpAdj = context.getPremVar() != null ? context.getPremVar() : BigDecimal.ZERO;
        BigDecimal iacfExpAdj = context.getIacfVar() != null ? context.getIacfVar() : BigDecimal.ZERO;
        BigDecimal revenueExpAdj = premExpAdj.add(iacfExpAdj);
        context.setRevenueExpAdj(revenueExpAdj);

        Map<String, Object> meta75 = new HashMap<>();
        meta75.put("保费经验调整", premExpAdj);
        meta75.put("IACF经验调整", iacfExpAdj);
        logger.logItem(
                "保险合同收入_经验调整",
                "与当期服务相关的经验调整",
                "保费 + IACF",
                meta75,
                revenueExpAdj
        );

        // 7.6 合计
        // Sum(含亏损各项 - 亏损分摊各项 + CSM摊销 + IACF摊销 + 经验调整)
        BigDecimal totalRevenue = revenueClaimsExpensesGross.subtract(revenueClaimsExpensesLcAlloc)
                .add(raReleaseGross).subtract(raReleaseLcAlloc)
                .add(context.getRevenueCsmAmort())
                .add(context.getRevenueIacfAmort())
                .add(context.getRevenueExpAdj());
        
        context.setTotalRevenue(totalRevenue);

        Map<String, Object> metaTotal = new HashMap<>();
        metaTotal.put("保险合同收入_预期赔付与费用_含亏损", revenueClaimsExpensesGross);
        metaTotal.put("减：保险合同收入_预期赔付与费用_亏损分摊", revenueClaimsExpensesLcAlloc);
        metaTotal.put("保险合同收入_预期释放的非金融风险调整_含亏损", raReleaseGross);
        metaTotal.put("减：保险合同收入_预期释放的非金融风险调整_亏损分摊", raReleaseLcAlloc);
        metaTotal.put("保险合同收入_摊销的CSM", context.getRevenueCsmAmort());
        metaTotal.put("保险合同收入_摊销的IACF", context.getRevenueIacfAmort());
        metaTotal.put("保险合同收入_经验调整", context.getRevenueExpAdj());
        
        logger.logItem(
                "保险合同收入_合计",
                "当期确认的总保险合同收入",
                "Sum(...)",
                metaTotal,
                totalRevenue
        );
    }
}
