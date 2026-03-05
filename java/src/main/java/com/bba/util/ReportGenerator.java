package com.bba.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ReportGenerator {

    private static final DecimalFormat DF = new DecimalFormat("#,##0.00");
    private static final BigDecimal VERIFY_EPS = new BigDecimal("0.01");

    public static void generate103Report(List<Map<String, Object>> results, String outputPath) {
        Map<Integer, Map<String, BigDecimal>> dataByYear = convertResultsToDecimal(results);
        List<Integer> years = new ArrayList<>(dataByYear.keySet());
        Collections.sort(years);

        if (years.isEmpty()) {
            writeToFile("<html><body><p>无数据</p></body></html>", outputPath);
            return;
        }

        Map<String, Object> firstRow = results.get(0);
        String policyNo = toStringSafe(firstRow.get("policy_no"));
        String certiNo = toStringSafe(firstRow.get("certi_no"));

        List<Map<String, Object>> allRows = new ArrayList<>();
        Map<Integer, List<Map<String, String>>> explanationsByYear = new LinkedHashMap<>();

        BigDecimal openingLrcNonLc = BigDecimal.ZERO;
        BigDecimal openingLrcLc = BigDecimal.ZERO;
        BigDecimal openingLic = BigDecimal.ZERO;

        int minYear = years.get(0);
        int maxYear = years.get(years.size() - 1);

        for (Integer year : years) {
            Map<String, BigDecimal> data = dataByYear.get(year);
            // 调试日志已移除


            List<Map<String, Object>> yearRows = new ArrayList<>();
            List<Map<String, String>> yearExps = new ArrayList<>();

            boolean isInitialYear = year == minYear;
            boolean isFinalYear = year == maxYear;

            Map<String, BigDecimal> opening = new HashMap<>();
            opening.put("lrc_non_lc", openingLrcNonLc);
            opening.put("lrc_lc", openingLrcLc);
            opening.put("lic", openingLic);

            if (isInitialYear) {
                BigDecimal openingBel = getBd(data, "opening_bel");
                BigDecimal openingRa = getBd(data, "opening_ra");
                BigDecimal openingCsm = getBd(data, "opening_csm");
                BigDecimal openingLcLog = getBd(data, "opening_lc");
                BigDecimal openingLicVal = getBd(data, "opening_lic");

                BigDecimal openingLrcTotal = openingBel.add(openingRa).add(openingCsm);
                BigDecimal openingLcDisplay = openingLcLog.compareTo(BigDecimal.ZERO) < 0 ? openingLcLog.negate() : openingLcLog;
                BigDecimal openingNonLc = openingLrcTotal.subtract(openingLcDisplay);

                openingLrcNonLc = openingNonLc;
                openingLrcLc = openingLcDisplay;
                openingLic = openingLicVal;
            }

            Map<String, String> expOpening = new LinkedHashMap<>();
            expOpening.put("title", "1. 年初余额");
            StringBuilder openingContent = new StringBuilder();
            openingContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            openingContent.append("<tr style=\"background-color: #f8f9fa;\">");
            openingContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            openingContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
            openingContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
            openingContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
            openingContent.append("</tr>");
            openingContent.append("<tr>");
            openingContent.append("<td style=\"padding: 8px;\">年初的保险合同负债(1)</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcNonLc)).append("</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcLc)).append("</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLic)).append("</td>");
            openingContent.append("</tr>");
            openingContent.append("<tr>");
            openingContent.append("<td style=\"padding: 8px;\">年初的保险合同资产(2)</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            openingContent.append("</tr>");
            openingContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
            openingContent.append("<td style=\"padding: 8px;\">年初的保险合同净负债(3)=(1)+(2)</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcNonLc)).append("</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcLc)).append("</td>");
            openingContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLic)).append("</td>");
            openingContent.append("</tr>");
            openingContent.append("</table>");
            openingContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            if (isInitialYear) {
                if (getBd(data, "nb_init_prem").compareTo(BigDecimal.ZERO) != 0) {
                    BigDecimal nbBel = getBd(data, "nb_init_claims")
                            .add(getBd(data, "nb_init_maint"))
                            .add(getBd(data, "nb_init_iacf"))
                            .subtract(getBd(data, "nb_init_prem"));
                    openingContent.append("<b>初始确认BEL计算公式</b>: BEL = (预期赔付 ").append(format(getBd(data, "nb_init_claims")))
                            .append(" + 预期维费 ").append(format(getBd(data, "nb_init_maint")))
                            .append(" + 预期获取费用 ").append(format(getBd(data, "nb_init_iacf")))
                            .append(") - 预期保费 ").append(format(getBd(data, "nb_init_prem")))
                            .append(" = ").append(format(nbBel)).append("<br>");
                }
                openingContent.append("<b>说明</b>: 初始年度年初余额来自输入/日志的 opening_* 字段。LRC-非亏损 = BEL + RA + CSM - LC，LRC-亏损 = LC（显示为绝对值）。");
            } else {
                openingContent.append("<b>说明</b>: 年初余额来自上一年度的期末余额。LRC-非亏损 = BEL + RA + CSM - LC，LRC-亏损 = LC（显示为绝对值）。");
            }
            openingContent.append("</p>");
            expOpening.put("content", openingContent.toString());
            yearExps.add(expOpening);

            yearRows.add(buildRow(year, "年初的保险合同负债(1)", openingLrcNonLc, openingLrcLc, openingLic, true, 0));
            yearRows.add(buildRow(year, "年初的保险合同资产(2)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "年初的保险合同净负债(3)=(1)+(2)", openingLrcNonLc, openingLrcLc, openingLic, false, 0));

            BigDecimal revCsm = getBd(data, "保险合同收入_摊销的CSM");
            BigDecimal revIacf = getBd(data, "保险合同收入_摊销的IACF").negate();
            BigDecimal revExp = getBd(data, "保险合同收入_经验调整");
            BigDecimal revLcReleaseClaims = getBd(data, "保险合同收入_预期赔付与费用_亏损分摊");
            BigDecimal revLcReleaseRa = getBd(data, "保险合同收入_预期释放的非金融风险调整_亏损分摊");
            BigDecimal revenueFromLcRelease = revLcReleaseClaims.add(revLcReleaseRa);
            BigDecimal revClaimsGross = getBd(data, "保险合同收入_预期赔付与费用_含亏损");
            BigDecimal revRaGross = getBd(data, "保险合同收入_预期释放的非金融风险调整_含亏损");
            BigDecimal revClaimsNet = revClaimsGross.subtract(revLcReleaseClaims);
            BigDecimal revRaNet = revRaGross.subtract(revLcReleaseRa);
            // [修复] 各项收入均为负数（负债减少），应直接相加
            BigDecimal revenueNonLc = (revCsm.add(revIacf).add(revExp).add(revClaimsNet).add(revRaNet)).negate();
            BigDecimal revenueLc = BigDecimal.ZERO;

            yearRows.add(buildRow(year, "保险服务收入合计(4)", revenueNonLc, revenueLc, BigDecimal.ZERO, true, 0));

            Map<String, String> expRevenue = new LinkedHashMap<>();
            expRevenue.put("title", "4. 保险服务收入合计");
            StringBuilder expRevContent = new StringBuilder();
            expRevContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expRevContent.append("<tr style=\"background-color: #f8f9fa;\">");
            expRevContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expRevContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
            expRevContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
            expRevContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">预期赔付与费用释放 (净额)</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revClaimsNet.negate())).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;总额 (含亏损释放)</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revClaimsGross)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;减: 亏损分摊部分</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revLcReleaseClaims.negate())).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">预期释放的非金融风险调整 (净额)</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revRaNet.negate())).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;总额 (含亏损释放)</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revRaGross)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;减: 亏损分摊部分</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revLcReleaseRa.negate())).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">摊销的CSM</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revCsm)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">摊销的IACF</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revIacf)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr>");
            expRevContent.append("<td style=\"padding: 8px;\">经验调整 (负号)</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revExp)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
            expRevContent.append("<td style=\"padding: 8px;\">合计</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revenueNonLc)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(revenueLc)).append("</td>");
            expRevContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expRevContent.append("</tr>");
            expRevContent.append("</table>");
            expRevContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            expRevContent.append("<b>计算公式</b>: 收入 = CSM摊销 + IACF摊销 + 经验调整 - 预期赔付与费用释放(净额) - RA释放(净额)<br>");
            expRevContent.append("<b>说明</b>: 收入减少负债，显示为负数。CSM摊销和IACF摊销为负数（产生收入），预期释放组件取负号以保持显示一致性。亏损部分的收入为0。");
            expRevContent.append("</p>");
            expRevenue.put("content", expRevContent.toString());
            yearExps.add(expRevenue);

            BigDecimal incurredClaims = BigDecimal.ZERO;
            yearRows.add(buildRow(year, "当期发生赔款及其他相关费用(保险获取现金流量除外)(5)", BigDecimal.ZERO, BigDecimal.ZERO, incurredClaims, false, 1));

            BigDecimal iacfAmortExp = getBd(data, "赔付与费用_摊销的IACF");
            yearRows.add(buildRow(year, "保险获取现金流量的摊销(6)", iacfAmortExp, BigDecimal.ZERO, BigDecimal.ZERO, false, 1));

            Map<String, String> expIacf = new LinkedHashMap<>();
            expIacf.put("title", "6. 保险获取现金流量的摊销");
            StringBuilder expIacfContent = new StringBuilder();
            expIacfContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expIacfContent.append("<tr style=\"background-color: #f8f9fa;\">");
            expIacfContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expIacfContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
            expIacfContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
            expIacfContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
            expIacfContent.append("</tr>");
            expIacfContent.append("<tr>");
            expIacfContent.append("<td style=\"padding: 8px;\">IACF摊销金额</td>");
            expIacfContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(iacfAmortExp)).append("</td>");
            expIacfContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expIacfContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expIacfContent.append("</tr>");
            expIacfContent.append("</table>");
            expIacfContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            expIacfContent.append("<b>说明</b>: IACF摊销增加负债，所以显示为正数。仅影响非亏损部分。");
            expIacfContent.append("</p>");
            expIacf.put("content", expIacfContent.toString());
            if (iacfAmortExp.compareTo(BigDecimal.ZERO) != 0) {
                yearExps.add(expIacf);
            }

            BigDecimal initialLossRecog = getBd(data, "nb_initial_lc").negate();
            BigDecimal lcChangeCf = getBd(data, "亏损合同损益_不调整CSM的预期现金流变动").negate();
            BigDecimal lcChangeRa = getBd(data, "亏损合同损益_不调整CSM的非金融风险调整变动").negate();
            BigDecimal lcChangeEst = lcChangeCf.add(lcChangeRa);
            BigDecimal lcReleaseClaims = revLcReleaseClaims;
            BigDecimal lcReleaseRa = revLcReleaseRa;
            BigDecimal lcReleaseTotal = lcReleaseClaims.add(lcReleaseRa);

            BigDecimal netLcRecogNonLc = BigDecimal.ZERO;
            BigDecimal netLcRecog = initialLossRecog.add(lcChangeEst).subtract(lcReleaseTotal);

            yearRows.add(buildRow(year, "亏损部分的确认及转回(7)", netLcRecogNonLc, netLcRecog, BigDecimal.ZERO, false, 1));

            boolean hasLcActivity = (netLcRecog.compareTo(BigDecimal.ZERO) != 0 || netLcRecogNonLc.compareTo(BigDecimal.ZERO) != 0 ||
                    initialLossRecog.compareTo(BigDecimal.ZERO) != 0 || lcChangeEst.compareTo(BigDecimal.ZERO) != 0 ||
                    lcReleaseTotal.compareTo(BigDecimal.ZERO) != 0);

            if (hasLcActivity) {
                Map<String, String> expLc = new LinkedHashMap<>();
                expLc.put("title", "7. 亏损部分的确认及转回");
                StringBuilder expLcContent = new StringBuilder();
                expLcContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
                expLcContent.append("<tr style=\"background-color: #f8f9fa;\">");
                expLcContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
                expLcContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
                expLcContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
                expLcContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">初始确认亏损</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(initialLossRecog)).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">预期现金流变动</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(lcChangeCf)).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">非金融风险调整变动</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(lcChangeRa)).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">减: LC释放 (亏损分摊)</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(lcReleaseTotal.negate())).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;预期赔付与费用亏损分摊</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(lcReleaseClaims.negate())).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr>");
                expLcContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;非金融风险调整亏损分摊</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(lcReleaseRa.negate())).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
                expLcContent.append("</tr>");

                expLcContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
                expLcContent.append("<td style=\"padding: 8px;\">净确认</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(netLcRecogNonLc)).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(netLcRecog)).append("</td>");
                expLcContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expLcContent.append("</tr>");

                expLcContent.append("</table>");
                expLcContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
                expLcContent.append("<b>计算公式</b>: 净确认 = 初始确认亏损 + 预期现金流变动 + 非金融风险调整变动 - LC释放<br>");
                expLcContent.append("<b>说明</b>: 亏损确认增加LC负债，显示为正数。LC释放减少LC负债，显示为负数。");
                expLcContent.append("</p>");

                expLc.put("content", expLcContent.toString());
                yearExps.add(expLc);
            }

            BigDecimal licChanges = BigDecimal.ZERO;
            yearRows.add(buildRow(year, "已发生赔款负债相关履约现金流量变动(8)", BigDecimal.ZERO, BigDecimal.ZERO, licChanges, false, 1));

            BigDecimal serviceExpenseNonLc = iacfAmortExp.add(netLcRecogNonLc);
            BigDecimal serviceExpenseLc = netLcRecog;
            BigDecimal serviceExpenseLic = incurredClaims.add(licChanges);
            yearRows.add(buildRow(year, "保险服务费用(10)", serviceExpenseNonLc, serviceExpenseLc, serviceExpenseLic, true, 0));

            BigDecimal resNonLc = revenueNonLc.add(serviceExpenseNonLc);
            BigDecimal resLc = revenueLc.add(serviceExpenseLc);
            BigDecimal resLic = serviceExpenseLic;

            yearRows.add(buildRow(year, "保险服务业绩(11)=(4)+(10)", resNonLc, resLc, resLic, true, 0));

            BigDecimal ifiePlCfNonLcRaw = getBd(data, "IFIE_P&L_未到期_预期现金流_非亏损");
            BigDecimal ifiePlRaNonLcRaw = getBd(data, "IFIE_P&L_未到期_非金融风险调整_非亏损");
            BigDecimal ifiePlCsm = getBd(data, "IFIE_P&L_未到期_CSM");
            BigDecimal ifiePlCfLcLog = getBd(data, "IFIE_P&L_未到期_预期现金流_亏损");
            BigDecimal ifiePlRaLcLog = getBd(data, "IFIE_P&L_未到期_非金融风险调整_亏损");
            BigDecimal ifiePlTotal = ifiePlCfNonLcRaw.add(ifiePlCfLcLog)
                    .add(ifiePlRaNonLcRaw).add(ifiePlRaLcLog).subtract(ifiePlCsm);
            BigDecimal ifiePlLcLogSum = ifiePlCfLcLog.add(ifiePlRaLcLog);
            BigDecimal ifiePlLcDisplay = ifiePlLcLogSum.negate();
            BigDecimal ifiePlNonLc = ifiePlTotal.subtract(ifiePlLcDisplay);
            BigDecimal ifiePlLic = BigDecimal.ZERO;

            yearRows.add(buildRow(year, "保险合同金融变动额(12)", ifiePlNonLc, ifiePlLcDisplay, ifiePlLic, false, 0));

            BigDecimal ifieOciCfNonLcRaw = getBd(data, "IFIE_OCI_未到期_预期现金流_非亏损");
            BigDecimal ifieOciRaNonLcRaw = getBd(data, "IFIE_OCI_未到期_非金融风险调整_非亏损");
            BigDecimal ifieOciCfLcLog = getBd(data, "IFIE_OCI_未到期_预期现金流_亏损");
            BigDecimal ifieOciRaLcLog = getBd(data, "IFIE_OCI_未到期_非金融风险调整_亏损");
            BigDecimal ifieOciTotal = ifieOciCfNonLcRaw.add(ifieOciCfLcLog)
                    .add(ifieOciRaNonLcRaw).add(ifieOciRaLcLog);
            BigDecimal ifieOciLcLogSum = ifieOciCfLcLog.add(ifieOciRaLcLog);
            BigDecimal ifieOciLcDisplay = ifieOciLcLogSum.negate();
            BigDecimal ifieOciNonLc = ifieOciTotal.subtract(ifieOciLcDisplay);

            yearRows.add(buildRow(year, "其他综合收益其他变动(14)", ifieOciNonLc, ifieOciLcDisplay, BigDecimal.ZERO, false, 0));

            // Add explanation for IFIE OCI
            if (ifieOciNonLc.compareTo(BigDecimal.ZERO) != 0 || ifieOciLcDisplay.compareTo(BigDecimal.ZERO) != 0) {
                // Calculate component breakdown for Non-LC (derived from Total - LC)
                BigDecimal ifieOciCfLcDisplayComp = ifieOciCfLcLog.negate();
                BigDecimal ifieOciRaLcDisplayComp = ifieOciRaLcLog.negate();
                BigDecimal ifieOciCfTotal = ifieOciCfNonLcRaw.add(ifieOciCfLcLog);
                BigDecimal ifieOciRaTotal = ifieOciRaNonLcRaw.add(ifieOciRaLcLog);
                BigDecimal ifieOciCfNonLcDerived = ifieOciCfTotal.subtract(ifieOciCfLcDisplayComp);
                BigDecimal ifieOciRaNonLcDerived = ifieOciRaTotal.subtract(ifieOciRaLcDisplayComp);

                Map<String, String> expIfieOci = new LinkedHashMap<>();
                expIfieOci.put("title", "14. 其他综合收益其他变动 (IFIE_OCI)");
                StringBuilder expOciContent = new StringBuilder();
                expOciContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
                expOciContent.append("<tr style=\"background-color: #f8f9fa;\">");
                expOciContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
                expOciContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
                expOciContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
                expOciContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr>");
                expOciContent.append("<td style=\"padding: 8px;\">预期现金流 IFIE_OCI (非亏损)</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciCfNonLcDerived)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr>");
                expOciContent.append("<td style=\"padding: 8px;\">非金融风险调整 IFIE_OCI (非亏损)</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciRaNonLcDerived)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr>");
                expOciContent.append("<td style=\"padding: 8px;\">预期现金流 IFIE_OCI (亏损)</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciCfLcDisplayComp)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr>");
                expOciContent.append("<td style=\"padding: 8px;\">非金融风险调整 IFIE_OCI (亏损)</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciRaLcDisplayComp)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
                expOciContent.append("<td style=\"padding: 8px;\">合计</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciNonLc)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciLcDisplay)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
                expOciContent.append("</tr>");
                expOciContent.append("<tr style=\"background-color: #fff9e6;\">");
                expOciContent.append("<td style=\"padding: 8px;\">验证: Non-LC + LC = Total</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciNonLc)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciLcDisplay)).append("</td>");
                expOciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciTotal)).append("</td>");
                expOciContent.append("</tr>");
                expOciContent.append("</table>");
                expOciContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
                expOciContent.append("<b>计算公式</b>: 非亏损部分 = 总额 - 亏损部分（确保 Non-LC + LC = Total，与104报表一致）<br>");
                expOciContent.append("<b>说明</b>: IFIE_OCI仅包含利率变化影响，不包含计息影响。日志中的\"非亏损\"字段是毛额口径，需通过倒挤得到净额口径。");
                expOciContent.append("</p>");
                expIfieOci.put("content", expOciContent.toString());
                yearExps.add(expIfieOci);
            }

            yearRows.add(buildRow(year, "其他损益变动(13)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));

            BigDecimal tciNonLc = resNonLc.add(ifiePlNonLc).add(ifieOciNonLc);
            BigDecimal tciLc = resLc.add(ifiePlLcDisplay).add(ifieOciLcDisplay);
            BigDecimal tciLic = resLic.add(ifiePlLic);

            yearRows.add(buildRow(year, "相关综合收益变动合计(15)=(11)+(12)+(13)+(14)", tciNonLc, tciLc, tciLic, true, 0));

            // Add explanation for TCI
            Map<String, String> expTci = new LinkedHashMap<>();
            expTci.put("title", "15. 相关综合收益变动合计");
            StringBuilder expTciContent = new StringBuilder();
            expTciContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expTciContent.append("<tr style=\"background-color: #f8f9fa;\">");
            expTciContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expTciContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
            expTciContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
            expTciContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
            expTciContent.append("</tr>");
            expTciContent.append("<tr>");
            expTciContent.append("<td style=\"padding: 8px;\">保险服务业绩(11)</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(resNonLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(resLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(resLic)).append("</td>");
            expTciContent.append("</tr>");
            expTciContent.append("<tr>");
            expTciContent.append("<td style=\"padding: 8px;\">保险合同金融变动额(12)</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifiePlNonLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifiePlLcDisplay)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifiePlLic)).append("</td>");
            expTciContent.append("</tr>");
            expTciContent.append("<tr>");
            expTciContent.append("<td style=\"padding: 8px;\">其他损益变动(13)</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expTciContent.append("</tr>");
            expTciContent.append("<tr>");
            expTciContent.append("<td style=\"padding: 8px;\">其他综合收益其他变动(14)</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciNonLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(ifieOciLcDisplay)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expTciContent.append("</tr>");
            expTciContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
            expTciContent.append("<td style=\"padding: 8px;\">相关综合收益变动合计(15)=(11)+(12)+(13)+(14)</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciNonLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciLc)).append("</td>");
            expTciContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciLic)).append("</td>");
            expTciContent.append("</tr>");
            expTciContent.append("</table>");
            expTciContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            expTciContent.append("<b>计算公式</b>: 相关综合收益变动合计 = 保险服务业绩 + 金融变动额 + 其他损益 + OCI<br>");
            expTciContent.append("<b>说明</b>: 包含所有影响负债变动的项目。");
            expTciContent.append("</p>");
            expTci.put("content", expTciContent.toString());
            yearExps.add(expTci);

            BigDecimal invComp = BigDecimal.ZERO;
            yearRows.add(buildRow(year, "投资成分(16)", invComp.negate(), BigDecimal.ZERO, invComp, false, 0));

            BigDecimal cfPremium = getBd(data, "现金流_收到的保费");
            BigDecimal cfIacf = getBd(data, "现金流_支付的获取费用").negate();
            BigDecimal cfClaims = BigDecimal.ZERO;
            BigDecimal cfOther = BigDecimal.ZERO;
            BigDecimal cfTotalNonLc = cfPremium.add(cfIacf).add(cfClaims).add(cfOther);
            BigDecimal cfTotalLc = BigDecimal.ZERO;
            BigDecimal cfTotalLic = BigDecimal.ZERO;

            yearRows.add(buildRow(year, "收到的保费(17)", cfPremium, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "支付的保险获取现金流量(18)", cfIacf, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "支付的赔款及其他相关费用(含投资成分)(19)", cfClaims, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "其他现金流量(20)", cfOther, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "现金流量合计(21)=(17)+(18)+(19)+(20)", cfTotalNonLc, cfTotalLc, cfTotalLic, true, 0));

            BigDecimal otherChangesNonLc = BigDecimal.ZERO;
            BigDecimal otherChangesLc = BigDecimal.ZERO;
            BigDecimal otherChangesLic = BigDecimal.ZERO;

            yearRows.add(buildRow(year, "其他变动(22)", otherChangesNonLc, otherChangesLc, otherChangesLic, false, 0));

            BigDecimal closingNonLc = openingLrcNonLc.add(tciNonLc).subtract(invComp).add(cfTotalNonLc).add(otherChangesNonLc);
            BigDecimal closingLc = openingLrcLc.add(tciLc).add(otherChangesLc);
            BigDecimal closingLic = openingLic.add(tciLic).add(invComp).add(cfTotalLic).add(otherChangesLic);
            BigDecimal closingLcDisplay = closingLc.compareTo(BigDecimal.ZERO) < 0 ? closingLc.negate() : closingLc;

            yearRows.add(buildRow(year, "年末的保险合同净负债(23)=(3)+(15)+(16)+(21)+(22)", closingNonLc, closingLcDisplay, closingLic, true, 0));
            yearRows.add(buildRow(year, "年末的保险合同资产(24)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, 0));
            yearRows.add(buildRow(year, "年末的保险合同负债(25)", closingNonLc, closingLcDisplay, closingLic, true, 0));

            boolean isTerminationYear = false;
            if (isFinalYear) {
                BigDecimal sum = getBd(data, "closing_bel")
                        .add(getBd(data, "closing_ra"))
                        .add(getBd(data, "closing_csm"))
                        .add(getBd(data, "closing_lic"));
                isTerminationYear = sum.compareTo(new BigDecimal("0.01")) < 0
                        && sum.compareTo(new BigDecimal("-0.01")) > 0;
            }

            BigDecimal logNonLcDisplay;
            BigDecimal logLcDisplay;
            BigDecimal logLic;

            if (isTerminationYear) {
                logNonLcDisplay = BigDecimal.ZERO;
                logLcDisplay = BigDecimal.ZERO;
                logLic = BigDecimal.ZERO;
            } else {
                BigDecimal logClosingBel = getBd(data, "closing_bel");
                BigDecimal logClosingRa = getBd(data, "closing_ra");
                BigDecimal logClosingCsm = getBd(data, "closing_csm");
                BigDecimal logClosingLc = data.get("closing_lc");
                if (logClosingLc == null) {
                    logLcDisplay = closingLcDisplay;
                } else {
                    logLcDisplay = logClosingLc.abs();
                }
                logNonLcDisplay = logClosingBel.add(logClosingRa).add(logClosingCsm).subtract(logLcDisplay);
                logLic = getBd(data, "closing_lic");
            }

            BigDecimal diffNonLc = closingNonLc.subtract(logNonLcDisplay);
            BigDecimal diffLc = closingLcDisplay.subtract(logLcDisplay);
            BigDecimal diffLic = closingLic.subtract(logLic);

            Map<String, String> expVerifyDetail = new LinkedHashMap<>();
            expVerifyDetail.put("title", "23. 年末的保险合同净负债 - 计算明细");
            StringBuilder expVerifyDetailContent = new StringBuilder();
            expVerifyDetailContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expVerifyDetailContent.append("<tr style=\"background-color: #f8f9fa;\">");
            expVerifyDetailContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expVerifyDetailContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-非亏损</th>");
            expVerifyDetailContent.append("<th style=\"text-align: right; padding: 8px;\">LRC-亏损</th>");
            expVerifyDetailContent.append("<th style=\"text-align: right; padding: 8px;\">LIC</th>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr>");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">期初余额(3)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcNonLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLrcLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(openingLic)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr>");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">相关综合收益变动合计(15)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciNonLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(tciLic)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr>");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">投资成分(16)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(invComp.negate())).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">0.00</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(invComp)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr>");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">现金流量合计(21)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(cfTotalNonLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(cfTotalLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(cfTotalLic)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr>");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">其他变动(22)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(otherChangesNonLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(otherChangesLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(otherChangesLic)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
            expVerifyDetailContent.append("<td style=\"padding: 8px;\">期末余额(23)</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingNonLc)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingLcDisplay)).append("</td>");
            expVerifyDetailContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingLic)).append("</td>");
            expVerifyDetailContent.append("</tr>");
            expVerifyDetailContent.append("</table>");
            expVerifyDetailContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            expVerifyDetailContent.append("<b>计算公式</b>: 期末 = 期初 + 综合收益变动 + 投资成分 + 现金流 + 其他变动<br>");
            expVerifyDetailContent.append("<b>验算</b>: 期末余额应与日志中的期末余额一致");
            expVerifyDetailContent.append("</p>");
            expVerifyDetail.put("content", expVerifyDetailContent.toString());
            yearExps.add(expVerifyDetail);

            Map<String, String> expVerify = new LinkedHashMap<>();
            expVerify.put("title", "期末余额验算 (计算值 vs 日志值)");
            StringBuilder expVerifyContent = new StringBuilder();
            expVerifyContent.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expVerifyContent.append("<tr style=\"background-color: #f8f9fa;\">");
            expVerifyContent.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expVerifyContent.append("<th style=\"text-align: right; padding: 8px;\">计算期末</th>");
            expVerifyContent.append("<th style=\"text-align: right; padding: 8px;\">日志期末</th>");
            expVerifyContent.append("<th style=\"text-align: right; padding: 8px;\">验算结果</th>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">LRC-非亏损</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingNonLc)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logNonLcDisplay)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml(diffNonLc)).append("</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;BEL</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(isFinalYear ? BigDecimal.ZERO : getBd(data, "closing_bel"))).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;RA</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(isFinalYear ? BigDecimal.ZERO : getBd(data, "closing_ra"))).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;CSM</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(isFinalYear ? BigDecimal.ZERO : getBd(data, "closing_csm"))).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">&nbsp;&nbsp;减: LC</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(isFinalYear ? BigDecimal.ZERO : logLcDisplay)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">-</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">LRC-亏损</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingLcDisplay)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logLcDisplay)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml(diffLc)).append("</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr>");
            expVerifyContent.append("<td style=\"padding: 8px;\">LIC</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingLic)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logLic)).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml(diffLic)).append("</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("<tr style=\"background-color: #f8f9fa; font-weight: bold;\">");
            expVerifyContent.append("<td style=\"padding: 8px;\">合计</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(closingNonLc.add(closingLcDisplay).add(closingLic))).append("</td>");
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logNonLcDisplay.add(logLcDisplay).add(logLic))).append("</td>");
            BigDecimal totalDiff = closingNonLc.add(closingLcDisplay).add(closingLic)
                    .subtract(logNonLcDisplay.add(logLcDisplay).add(logLic));
            expVerifyContent.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml(totalDiff)).append("</td>");
            expVerifyContent.append("</tr>");
            expVerifyContent.append("</table>");
            expVerifyContent.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 10px;\">");
            expVerifyContent.append("<b>计算公式</b>: LRC-非亏损 = Total - LC (倒挤法)<br>");
            expVerifyContent.append("<b>说明</b>: 日志期末值提取逻辑：<br>");
            expVerifyContent.append("1. Total = 期末未到期责任负债余额<br>");
            expVerifyContent.append("2. LC = 期末LC余额（绝对值）<br>");
            expVerifyContent.append("3. Non-LC = Total - LC<br>");
            expVerifyContent.append("4. 终止年度所有余额强制为0.00<br>");
            expVerifyContent.append("计算期末值来自调节表的滚算。");
            expVerifyContent.append("</p>");
            expVerify.put("content", expVerifyContent.toString());
            yearExps.add(expVerify);

            openingLrcNonLc = closingNonLc;
            openingLrcLc = closingLcDisplay;
            openingLic = closingLic;

            allRows.addAll(yearRows);
            explanationsByYear.put(year, yearExps);
        }

        if (isReportDebugEnabled()) {
            for (Map<String, Object> row : allRows) {
                Integer year = (Integer) row.get("year");
                String category = toStringSafe(row.get("category"));
                int segNo = parseSegmentNo(category);
                String catB64 = encodeBase64(category);
                Integer indent = (Integer) row.get("indent");
                BigDecimal nonLc = (BigDecimal) row.get("lrc_non_lc");
                BigDecimal lc = (BigDecimal) row.get("lrc_lc");
                BigDecimal lic = (BigDecimal) row.get("lic");
                BigDecimal total = (BigDecimal) row.get("total");
                System.out.println(
                        "JAVA103|" + year + "|" + segNo + "|" + catB64 + "|" + (indent == null ? 0 : indent)
                                + "|" + formatPlain(nonLc)
                                + "|" + formatPlain(lc)
                                + "|" + formatPlain(lic)
                                + "|" + formatPlain(total)
                );
            }
        }

        String html = render103Html(allRows, explanationsByYear, policyNo, certiNo);
        writeToFile(html, outputPath);
    }

    public static void generate104Report(List<Map<String, Object>> results, String outputPath) {
        Map<Integer, Map<String, BigDecimal>> dataByYear = convertResultsToDecimal(results);
        List<Integer> years = new ArrayList<>(dataByYear.keySet());
        Collections.sort(years);

        if (years.isEmpty()) {
            writeToFile("<html><body><p>无数据</p></body></html>", outputPath);
            return;
        }

        Map<String, Object> firstRow = results.get(0);
        String policyNo = toStringSafe(firstRow.get("policy_no"));
        String certiNo = toStringSafe(firstRow.get("certi_no"));

        List<Map<String, Object>> allRows = new ArrayList<>();
        Map<Integer, List<Map<String, String>>> explanationsByYear = new LinkedHashMap<>();

        BigDecimal openingPv = BigDecimal.ZERO;
        BigDecimal openingRa = BigDecimal.ZERO;
        BigDecimal openingCsm = BigDecimal.ZERO;

        int minYear = years.get(0);
        for (Integer year : years) {
            Map<String, BigDecimal> data = dataByYear.get(year);
            List<Map<String, Object>> yearRows = new ArrayList<>();
            List<Map<String, String>> yearExps = new ArrayList<>();

            boolean isInitialYear = year == minYear;
            if (isInitialYear) {
                openingPv = getBd(data, "opening_bel");
                openingRa = getBd(data, "opening_ra");
                openingCsm = getBd(data, "opening_csm");
            }

            Map<String, Object> row1 = build104Row(year, "年初的保险合同负债(1)", openingPv, openingRa, openingCsm, 0, false);
            yearRows.add(row1);
            Map<String, Object> row2 = build104Row(year, "年初的保险合同资产(2)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false);
            yearRows.add(row2);
            Map<String, Object> row3 = build104Row(year, "年初的保险合同净负债(3)=(1)+(2)",
                    openingPv, openingRa, openingCsm, 0, false);
            yearRows.add(row3);

            Map<String, String> expOpening = new LinkedHashMap<>();
            expOpening.put("title", "1. 年初余额");
            StringBuilder openingContent = new StringBuilder();
            openingContent.append("<ul>");
            openingContent.append("<li><b>BEL (未来现金流量现值)</b>: ").append(format(openingPv)).append("</li>");
            openingContent.append("<li><b>RA (非金融风险调整)</b>: ").append(format(openingRa)).append("</li>");
            openingContent.append("<li><b>CSM (合同服务边际)</b>: ").append(format(openingCsm)).append("</li>");
            openingContent.append("<li><b>合计</b>: ").append(format(openingPv.add(openingRa).add(openingCsm))).append("</li>");
            openingContent.append("</ul>");
            expOpening.put("content", openingContent.toString());
            yearExps.add(expOpening);

            BigDecimal csmAmort = getBd(data, "保险合同收入_摊销的CSM").negate();
            yearRows.add(build104Row(year, "合同服务边际的摊销(4)", BigDecimal.ZERO, BigDecimal.ZERO, csmAmort, 1, false));

            BigDecimal raReleaseGross = getBd(data, "保险合同收入_预期释放的非金融风险调整_含亏损");
            BigDecimal raChange = raReleaseGross.negate();
            yearRows.add(build104Row(year, "非金融风险调整的变动(5)", BigDecimal.ZERO, raChange, BigDecimal.ZERO, 1, false));

            Map<String, String> expRaChange = new LinkedHashMap<>();
            expRaChange.put("title", "5. 非金融风险调整的变动 (RA)");
            StringBuilder expRaChangeContent = new StringBuilder();
            expRaChangeContent.append("<ul>");
            expRaChangeContent.append("<li><b>数值</b>: ").append(format(raChange)).append("</li>");
            expRaChangeContent.append("<li><b>公式</b>: -预期释放RA_含亏损</li>");
            expRaChangeContent.append("<li><b>计算</b>: -").append(format(raReleaseGross)).append(" = ").append(format(raChange)).append("</li>");
            expRaChangeContent.append("<li><b>说明</b>: RA随着服务提供而释放，导致负债减少。使用含亏损总额，不重复加亏损分摊。</li>");
            expRaChangeContent.append("</ul>");
            expRaChange.put("content", expRaChangeContent.toString());
            yearExps.add(expRaChange);

            BigDecimal expectedClaimsExp = getBd(data, "保险合同收入_预期赔付与费用_含亏损");
            BigDecimal actualClaimsExp = BigDecimal.ZERO;
            BigDecimal expAdjLog = getBd(data, "保险合同收入_经验调整");
            BigDecimal currServicePvVal = actualClaimsExp.subtract(expectedClaimsExp).add(expAdjLog);
            yearRows.add(build104Row(year, "当期经验调整(6)", currServicePvVal, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));

            Map<String, String> expCurrService = new LinkedHashMap<>();
            expCurrService.put("title", "6. 当期经验调整 (BEL)");
            StringBuilder expCurrServiceContent = new StringBuilder();
            expCurrServiceContent.append("<ul>");
            expCurrServiceContent.append("<li><b>数值</b>: ").append(format(currServicePvVal)).append("</li>");
            expCurrServiceContent.append("<li><b>公式</b>: 实际赔付与费用 - 预期赔付与费用 + 保费经验调整</li>");
            expCurrServiceContent.append("<li><b>计算</b>: ").append(format(actualClaimsExp))
                    .append(" - ").append(format(expectedClaimsExp))
                    .append(" + ").append(format(expAdjLog)).append("</li>");
            expCurrServiceContent.append("<li><b>说明</b>: 反映实际现金流与预期现金流的差异，以及预期现金流的释放。</li>");
            expCurrServiceContent.append("</ul>");
            expCurrService.put("content", expCurrServiceContent.toString());
            yearExps.add(expCurrService);

            BigDecimal currServicePv = currServicePvVal;
            BigDecimal currServiceRa = raChange;
            BigDecimal currServiceCsm = csmAmort;
            yearRows.add(build104Row(year, "与当期服务相关的变动(7)=(4)+(5)+(6)", currServicePv, currServiceRa, currServiceCsm, 0, false));

            BigDecimal nbBel;
            BigDecimal nbRa;
            BigDecimal nbCsm;
            BigDecimal nbInitPrem = getBd(data, "nb_init_prem");

            if (year == minYear && nbInitPrem.compareTo(BigDecimal.ZERO) != 0) {
                // 使用初始确认数据
                BigDecimal nbInitClaims = getBd(data, "nb_init_claims");
                BigDecimal nbInitMaint = getBd(data, "nb_init_maint");
                BigDecimal nbInitIacf = getBd(data, "nb_init_iacf");
                nbBel = nbInitClaims.add(nbInitMaint).add(nbInitIacf).subtract(nbInitPrem);
                nbRa = getBd(data, "nb_init_ra");
                nbCsm = getBd(data, "nb_init_csm");
            } else {
                BigDecimal nbClaimsProfit = getBd(data, "新增合同预期现金流_赔付与费用现金流_盈利合同");
                BigDecimal nbIacfProfit = getBd(data, "新增合同预期现金流_IACF_盈利合同");
                BigDecimal nbPremProfit = getBd(data, "新增合同预期现金流_保费现金流_盈利合同");
                BigDecimal nbClaimsLossNonLc = getBd(data, "新增合同预期现金流_赔付与费用现金流_亏损合同_非亏损");
                BigDecimal lossNbCf = getBd(data, "亏损合同损益_新增合同预期现金流_赔付与费用现金流_亏损");
                BigDecimal nbIacfLoss = getBd(data, "新增合同预期现金流_IACF_亏损合同");
                BigDecimal nbPremLoss = getBd(data, "新增合同预期现金流_保费现金流_亏损合同");

                // [Fix] Removed lossNbCf from calculation to match Python logic
                // Python puts full claims amount into nbClaimsLossNonLc, so adding lossNbCf would be double counting (or incorrect definition)
                nbBel = nbClaimsProfit.add(nbIacfProfit).subtract(nbPremProfit)
                        .add(nbClaimsLossNonLc.add(nbIacfLoss).subtract(nbPremLoss));

                // [Fix] Removed LC RA component to match Python logic
                nbRa = getBd(data, "新增合同非金融风险调整_盈利合同")
                        .add(getBd(data, "新增合同非金融风险调整_亏损合同_非亏损"));

                nbCsm = getBd(data, "新增合同CSM_盈利合同");
            }

            yearRows.add(build104Row(year, "当期初始确认的保险合同影响(8)", nbBel, nbRa, nbCsm, 1, false));

            BigDecimal nbAbsSum = nbBel.abs().add(nbRa.abs()).add(nbCsm.abs());
            if (year == minYear || nbAbsSum.compareTo(BigDecimal.ZERO) > 0) {
                Map<String, String> expNb = new LinkedHashMap<>();
                expNb.put("title", "8. 当期初始确认的保险合同影响");
                StringBuilder expNbContent = new StringBuilder();
                expNbContent.append("<ul>");
                expNbContent.append("<li><b>BEL</b>: ").append(format(nbBel)).append("</li>");
                expNbContent.append("<li><b>RA</b>: ").append(format(nbRa)).append("</li>");
                expNbContent.append("<li><b>CSM</b>: ").append(format(nbCsm)).append("</li>");
                expNbContent.append("</ul>");
                expNb.put("content", expNbContent.toString());
                yearExps.add(expNb);
            }

            BigDecimal csmAdjPv = getBd(data, "未到期_调整CSM的预期现金流变动").negate();
            BigDecimal csmAdjRa = getBd(data, "未到期_调整CSM的非金融风险调整变动").negate();
            BigDecimal csmAdjCsm = getBd(data, "未到期_调整CSM的估计变更");
            yearRows.add(build104Row(year, "调整合同服务边际的估计变更(9)", csmAdjPv, csmAdjRa, csmAdjCsm, 1, false));

            BigDecimal nonCsmPv = getBd(data, "亏损合同损益_不调整CSM的预期现金流变动").negate();
            BigDecimal nonCsmRa = getBd(data, "亏损合同损益_不调整CSM的非金融风险调整变动").negate();
            yearRows.add(build104Row(year, "不调整合同服务边际的估计变更(10)", nonCsmPv, nonCsmRa, BigDecimal.ZERO, 1, false));

            yearRows.add(build104Row(year, "其他与未来服务相关变动(11)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));

            BigDecimal futureServicePv = nbBel.add(csmAdjPv).add(nonCsmPv);
            BigDecimal futureServiceRa = nbRa.add(csmAdjRa).add(nonCsmRa);
            BigDecimal futureServiceCsm = nbCsm.add(csmAdjCsm);
            BigDecimal insServicePv = currServicePv.add(futureServicePv);
            BigDecimal insServiceRa = currServiceRa.add(futureServiceRa);
            BigDecimal insServiceCsm = currServiceCsm.add(futureServiceCsm);

            yearRows.add(build104Row(year, "与未来服务相关的变动(12)=(8)+(9)+(10)+(11)", futureServicePv, futureServiceRa, futureServiceCsm, 0, false));

            yearRows.add(build104Row(year, "已发生赔款负债相关履约现金流量变动(13)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "其他与过去服务相关的变动(14)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "与过去服务相关的变动(15)=(13)+(14)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
            yearRows.add(build104Row(year, "保险服务业绩(16)=(7)+(12)+(15)", insServicePv, insServiceRa, insServiceCsm, 0, false));

            BigDecimal ifiePlCfNonLc = getBd(data, "IFIE_P&L_未到期_预期现金流_非亏损");
            BigDecimal ifiePlCfLc = getBd(data, "IFIE_P&L_未到期_预期现金流_亏损");
            BigDecimal ifiePv = ifiePlCfNonLc.add(ifiePlCfLc);
            BigDecimal ifiePlRaNonLc = getBd(data, "IFIE_P&L_未到期_非金融风险调整_非亏损");
            BigDecimal ifiePlRaLc = getBd(data, "IFIE_P&L_未到期_非金融风险调整_亏损");
            BigDecimal ifieRa = ifiePlRaNonLc.add(ifiePlRaLc);
            BigDecimal ifieCsmLog = getBd(data, "IFIE_P&L_未到期_CSM");
            BigDecimal ifieCsm = ifieCsmLog.negate();

            yearRows.add(build104Row(year, "保险合同金融变动额(17)", ifiePv, ifieRa, ifieCsm, 0, false));

            Map<String, String> expIfie = new LinkedHashMap<>();
            expIfie.put("title", "17. 保险合同金融变动额 (IFIE_P&L)");
            StringBuilder expIfieContent = new StringBuilder();
            expIfieContent.append("<ul>");
            expIfieContent.append("<li><b>BEL</b>: ").append(format(ifiePv))
                    .append(" = ").append(format(ifiePlCfNonLc)).append(" (非亏损) + ")
                    .append(format(ifiePlCfLc)).append(" (亏损)</li>");
            expIfieContent.append("<li><b>RA</b>: ").append(format(ifieRa))
                    .append(" = ").append(format(ifiePlRaNonLc)).append(" (非亏损) + ")
                    .append(format(ifiePlRaLc)).append(" (亏损)</li>");
            expIfieContent.append("<li><b>CSM</b>: ").append(format(ifieCsm)).append("</li>");
            expIfieContent.append("</ul>");
            expIfie.put("content", expIfieContent.toString());
            yearExps.add(expIfie);

            yearRows.add(build104Row(year, "其他损益变动(18)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));

            BigDecimal ifieOciPvNonLc = getBd(data, "IFIE_OCI_未到期_预期现金流_非亏损");
            BigDecimal ifieOciPvLc = getBd(data, "IFIE_OCI_未到期_预期现金流_亏损");
            BigDecimal ifieOciPv = ifieOciPvNonLc.add(ifieOciPvLc);
            BigDecimal ifieOciRaNonLc = getBd(data, "IFIE_OCI_未到期_非金融风险调整_非亏损");
            BigDecimal ifieOciRaLc = getBd(data, "IFIE_OCI_未到期_非金融风险调整_亏损");
            BigDecimal ifieOciRa = ifieOciRaNonLc.add(ifieOciRaLc);

            yearRows.add(build104Row(year, "其他综合收益其他变动(19)", ifieOciPv, ifieOciRa, BigDecimal.ZERO, 0, false));

            Map<String, String> expOci = new LinkedHashMap<>();
            expOci.put("title", "19. 其他综合收益 (OCI)");
            StringBuilder expOciContent = new StringBuilder();
            expOciContent.append("<ul>");
            expOciContent.append("<li><b>BEL</b>: ").append(format(ifieOciPv)).append(" = IFIE_OCI_预期现金流（包含亏损和非亏损）</li>");
            expOciContent.append("<li><b>RA</b>: ").append(format(ifieOciRa)).append(" = IFIE_OCI_非金融风险调整（包含亏损和非亏损）</li>");
            expOciContent.append("</ul>");
            expOci.put("content", expOciContent.toString());
            yearExps.add(expOci);

            BigDecimal totalCiPv = insServicePv.add(ifiePv).add(ifieOciPv);
            BigDecimal totalCiRa = insServiceRa.add(ifieRa).add(ifieOciRa);
            BigDecimal totalCiCsm = insServiceCsm.add(ifieCsm);

            yearRows.add(build104Row(year, "相关综合收益变动合计(20)=(16)+(17)+(18)+(19)", totalCiPv, totalCiRa, totalCiCsm, 0, false));

            BigDecimal cfPrem = cfPremiumFrom104(data);
            BigDecimal cfAcq = getBd(data, "现金流_支付的获取费用").negate();
            BigDecimal cfTotal = cfPrem.add(cfAcq);

            yearRows.add(build104Row(year, "收到的保费(21)", cfPrem, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "支付的保险获取现金流量(22)", cfAcq, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "支付的赔款及其他相关费用(含投资成分)(23)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "其他现金流量(24)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 1, false));
            yearRows.add(build104Row(year, "现金流量合计(25)=(21)+(22)+(23)+(24)", cfTotal, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));

            Map<String, String> expCashFlow = new LinkedHashMap<>();
            expCashFlow.put("title", "21 & 22. 现金流");
            StringBuilder expCfContent = new StringBuilder();
            expCfContent.append("<ul>");
            expCfContent.append("<li><b>(21) 收到的保费</b>: ").append(format(cfPrem)).append("</li>");
            expCfContent.append("<li><b>(22) 支付的获取费用</b>: ").append(format(cfAcq)).append(" (流出为负)</li>");
            expCfContent.append("<li><b>(25) 现金流量合计</b>: ").append(format(cfTotal)).append("</li>");
            expCfContent.append("</ul>");
            expCashFlow.put("content", expCfContent.toString());
            yearExps.add(expCashFlow);

            BigDecimal logClosingBel = getBd(data, "未到期责任负债_预期现金流_非亏损")
                    .add(getBd(data, "未到期责任负债_预期现金流_亏损"));
            BigDecimal logClosingRa = getBd(data, "未到期责任负债_非金融风险调整_非亏损")
                    .add(getBd(data, "未到期责任负债_非金融风险调整_亏损"));
            BigDecimal logClosingCsm = getBd(data, "未到期责任负债_CSM");

            BigDecimal otherChangePv = BigDecimal.ZERO;
            BigDecimal otherChangeRa = BigDecimal.ZERO;

            yearRows.add(build104Row(year, "其他变动(26)", otherChangePv, otherChangeRa, BigDecimal.ZERO, 0, false));

            BigDecimal calcClosingPv = openingPv.add(totalCiPv).add(cfTotal).add(otherChangePv);
            BigDecimal calcClosingRa = openingRa.add(totalCiRa).add(otherChangeRa);
            BigDecimal calcClosingCsm = openingCsm.add(totalCiCsm);

            yearRows.add(build104Row(year, "年末的保险合同净负债(27)=(3)+(20)+(25)+(26)", calcClosingPv, calcClosingRa, calcClosingCsm, 0, false));
            yearRows.add(build104Row(year, "年末的保险合同资产(28)", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0, false));
            yearRows.add(build104Row(year, "年末的保险合同负债(29)", calcClosingPv, calcClosingRa, calcClosingCsm, 0, false));

            BigDecimal diffPv = calcClosingPv.subtract(logClosingBel);
            BigDecimal diffRa = calcClosingRa.subtract(logClosingRa);
            BigDecimal diffCsm = calcClosingCsm.subtract(logClosingCsm);

            Map<String, String> expVerify = new LinkedHashMap<>();
            expVerify.put("title", "期末余额验算 (计算值 vs 日志值)");
            StringBuilder expVerifyContent104 = new StringBuilder();
            expVerifyContent104.append("<table style=\"width:100%; border-collapse: collapse; margin-top: 5px; border: 1px solid #eee;\">");
            expVerifyContent104.append("<tr style=\"background-color: #f8f9fa;\">");
            expVerifyContent104.append("<th style=\"text-align: left; padding: 8px;\">项目</th>");
            expVerifyContent104.append("<th style=\"text-align: right; padding: 8px;\">计算期末</th>");
            expVerifyContent104.append("<th style=\"text-align: right; padding: 8px;\">日志期末</th>");
            expVerifyContent104.append("<th style=\"text-align: right; padding: 8px;\">差异</th>");
            expVerifyContent104.append("</tr>");
            expVerifyContent104.append("<tr>");
            expVerifyContent104.append("<td style=\"padding: 8px;\">BEL</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(calcClosingPv)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logClosingBel)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml104(diffPv)).append("</td>");
            expVerifyContent104.append("</tr>");
            expVerifyContent104.append("<tr>");
            expVerifyContent104.append("<td style=\"padding: 8px;\">RA</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(calcClosingRa)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logClosingRa)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml104(diffRa)).append("</td>");
            expVerifyContent104.append("</tr>");
            expVerifyContent104.append("<tr>");
            expVerifyContent104.append("<td style=\"padding: 8px;\">CSM</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(calcClosingCsm)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(format(logClosingCsm)).append("</td>");
            expVerifyContent104.append("<td style=\"text-align: right; padding: 8px;\">").append(getVerifyStatusHtml104(diffCsm)).append("</td>");
            expVerifyContent104.append("</tr>");
            expVerifyContent104.append("</table>");
            expVerifyContent104.append("<p style=\"font-size: 0.9em; color: #666; margin-top: 5px;\">注：计算期末 = 期初 + 综合收益变动 + 现金流 + 其他变动</p>");
            expVerify.put("content", expVerifyContent104.toString());
            yearExps.add(expVerify);

            openingPv = calcClosingPv;
            openingRa = calcClosingRa;
            openingCsm = calcClosingCsm;

            allRows.addAll(yearRows);
            explanationsByYear.put(year, yearExps);
        }

        if (isReportDebugEnabled()) {
            for (Map<String, Object> row : allRows) {
                Integer year = (Integer) row.get("year");
                String categoryName = toStringSafe(row.get("category_name"));
                int segNo = parseSegmentNo(categoryName);
                String catB64 = encodeBase64(categoryName);
                Integer indent = (Integer) row.get("indent");
                BigDecimal pv = (BigDecimal) row.get("pv");
                BigDecimal ra = (BigDecimal) row.get("ra");
                BigDecimal csm = (BigDecimal) row.get("csm");
                BigDecimal total = (pv == null ? BigDecimal.ZERO : pv)
                        .add(ra == null ? BigDecimal.ZERO : ra)
                        .add(csm == null ? BigDecimal.ZERO : csm);
                System.out.println(
                        "JAVA104|" + year + "|" + segNo + "|" + catB64 + "|" + (indent == null ? 0 : indent)
                                + "|" + formatPlain(pv)
                                + "|" + formatPlain(ra)
                                + "|" + formatPlain(csm)
                                + "|" + formatPlain(total)
                );
            }
        }

        String html = render104Html(allRows, explanationsByYear, policyNo, certiNo);
        writeToFile(html, outputPath);
    }

    public static final class ValidationSummary {
        private final String policyNo;
        private final String certiNo;
        private final List<Integer> years;
        private final boolean passed103;
        private final boolean passed104;
        private final Map<Integer, Map<String, BigDecimal>> diff103ByYear;
        private final Map<Integer, Map<String, BigDecimal>> diff104ByYear;
        private final Map<String, BigDecimal> maxDiff103;
        private final Map<String, BigDecimal> maxDiff104;

        private ValidationSummary(
                String policyNo,
                String certiNo,
                List<Integer> years,
                boolean passed103,
                boolean passed104,
                Map<Integer, Map<String, BigDecimal>> diff103ByYear,
                Map<Integer, Map<String, BigDecimal>> diff104ByYear,
                Map<String, BigDecimal> maxDiff103,
                Map<String, BigDecimal> maxDiff104
        ) {
            this.policyNo = policyNo;
            this.certiNo = certiNo;
            this.years = years;
            this.passed103 = passed103;
            this.passed104 = passed104;
            this.diff103ByYear = diff103ByYear;
            this.diff104ByYear = diff104ByYear;
            this.maxDiff103 = maxDiff103;
            this.maxDiff104 = maxDiff104;
        }

        public String getPolicyNo() {
            return policyNo;
        }

        public String getCertiNo() {
            return certiNo;
        }

        public List<Integer> getYears() {
            return years;
        }

        public boolean isPassed103() {
            return passed103;
        }

        public boolean isPassed104() {
            return passed104;
        }

        public Map<Integer, Map<String, BigDecimal>> getDiff103ByYear() {
            return diff103ByYear;
        }

        public Map<Integer, Map<String, BigDecimal>> getDiff104ByYear() {
            return diff104ByYear;
        }

        public Map<String, BigDecimal> getMaxDiff103() {
            return maxDiff103;
        }

        public Map<String, BigDecimal> getMaxDiff104() {
            return maxDiff104;
        }
    }

    public static ValidationSummary validate103And104(List<Map<String, Object>> results) {
        if (results == null || results.isEmpty()) {
            return new ValidationSummary("", "", new ArrayList<>(), true, true, new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>(), new LinkedHashMap<>());
        }

        Map<Integer, Map<String, BigDecimal>> dataByYear = convertResultsToDecimal(results);
        List<Integer> years = new ArrayList<>(dataByYear.keySet());
        Collections.sort(years);

        Map<String, Object> firstRow = results.get(0);
        String policyNo = toStringSafe(firstRow.get("policy_no"));
        String certiNo = toStringSafe(firstRow.get("certi_no"));

        Map<Integer, Map<String, BigDecimal>> diff103ByYear = new LinkedHashMap<>();
        Map<Integer, Map<String, BigDecimal>> diff104ByYear = new LinkedHashMap<>();

        Map<String, BigDecimal> maxDiff103 = new LinkedHashMap<>();
        maxDiff103.put("non_lc", BigDecimal.ZERO);
        maxDiff103.put("lc", BigDecimal.ZERO);
        maxDiff103.put("lic", BigDecimal.ZERO);

        Map<String, BigDecimal> maxDiff104 = new LinkedHashMap<>();
        maxDiff104.put("pv", BigDecimal.ZERO);
        maxDiff104.put("ra", BigDecimal.ZERO);
        maxDiff104.put("csm", BigDecimal.ZERO);

        boolean passed103All = true;
        boolean passed104All = true;

        BigDecimal openingNonLc = BigDecimal.ZERO;
        BigDecimal openingLcDisplay = BigDecimal.ZERO;
        BigDecimal openingLic = BigDecimal.ZERO;

        BigDecimal openingPv = BigDecimal.ZERO;
        BigDecimal openingRa = BigDecimal.ZERO;
        BigDecimal openingCsm = BigDecimal.ZERO;

        int minYear = years.get(0);
        int maxYear = years.get(years.size() - 1);

        for (Integer year : years) {
            Map<String, BigDecimal> data = dataByYear.get(year);
            boolean isInitialYear = year == minYear;
            boolean isFinalYear = year == maxYear;

            if (isInitialYear) {
                BigDecimal openingBel = getBd(data, "opening_bel");
                BigDecimal openingRaLog = getBd(data, "opening_ra");
                BigDecimal openingCsmLog = getBd(data, "opening_csm");
                BigDecimal openingLcLog = getBd(data, "opening_lc");
                BigDecimal openingLicLog = getBd(data, "opening_lic");

                BigDecimal openingLrcTotal = openingBel.add(openingRaLog).add(openingCsmLog);
                BigDecimal openingLcAbs = openingLcLog.compareTo(BigDecimal.ZERO) < 0 ? openingLcLog.negate() : openingLcLog;
                BigDecimal derivedOpeningNonLc = openingLrcTotal.subtract(openingLcAbs);

                openingNonLc = derivedOpeningNonLc;
                openingLcDisplay = openingLcAbs;
                openingLic = openingLicLog;

                openingPv = openingBel;
                openingRa = openingRaLog;
                openingCsm = openingCsmLog;
            }

            BigDecimal revCsm = getBd(data, "保险合同收入_摊销的CSM");
            BigDecimal revIacf = getBd(data, "保险合同收入_摊销的IACF").negate();
            BigDecimal revExp = getBd(data, "保险合同收入_经验调整");

            BigDecimal revLcReleaseClaims = getBd(data, "保险合同收入_预期赔付与费用_亏损分摊");
            BigDecimal revLcReleaseRa = getBd(data, "保险合同收入_预期释放的非金融风险调整_亏损分摊");

            BigDecimal revClaimsGross = getBd(data, "保险合同收入_预期赔付与费用_含亏损");
            BigDecimal revRaGross = getBd(data, "保险合同收入_预期释放的非金融风险调整_含亏损");

            BigDecimal revClaimsNet = revClaimsGross.subtract(revLcReleaseClaims);
            BigDecimal revRaNet = revRaGross.subtract(revLcReleaseRa);

            BigDecimal revenueNonLc = revCsm.add(revIacf).add(revExp).subtract(revClaimsNet).subtract(revRaNet);
            BigDecimal revenueLc = BigDecimal.ZERO;

            BigDecimal iacfAmortExp = getBd(data, "赔付与费用_摊销的IACF");

            BigDecimal initialLossRecog = isInitialYear ? getBd(data, "nb_initial_lc").negate() : BigDecimal.ZERO;
            BigDecimal lcChangeEst = getBd(data, "亏损合同损益_不调整CSM的预期现金流变动")
                    .add(getBd(data, "亏损合同损益_不调整CSM的非金融风险调整变动"));

            BigDecimal lcReleaseClaims = revLcReleaseClaims;
            BigDecimal lcReleaseRa = revLcReleaseRa;
            BigDecimal lcReleaseTotal = lcReleaseClaims.add(lcReleaseRa);

            BigDecimal netLcRecogNonLc = BigDecimal.ZERO;
            BigDecimal netLcRecog;

            if (isFinalYear) {
                BigDecimal ifiePlCfLcTemp = getBd(data, "IFIE_P&L_未到期_预期现金流_亏损");
                BigDecimal ifiePlRaLcTemp = getBd(data, "IFIE_P&L_未到期_非金融风险调整_亏损");
                BigDecimal ifieOciCfLcTemp = getBd(data, "IFIE_OCI_未到期_预期现金流_亏损");
                BigDecimal ifieOciRaLcTemp = getBd(data, "IFIE_OCI_未到期_非金融风险调整_亏损");
                BigDecimal ifieLcTotal = ifiePlCfLcTemp.add(ifiePlRaLcTemp).add(ifieOciCfLcTemp).add(ifieOciRaLcTemp);

                BigDecimal openingLcAbs = openingLcDisplay.compareTo(BigDecimal.ZERO) < 0 ? openingLcDisplay.negate() : openingLcDisplay;
                BigDecimal lcAfterIfie = openingLcAbs.add(ifieLcTotal);
                BigDecimal totalReversal = lcReleaseTotal;

                if (totalReversal.compareTo(lcAfterIfie) > 0) {
                    BigDecimal excessToNonLc = totalReversal.subtract(lcAfterIfie);
                    netLcRecogNonLc = excessToNonLc.negate();
                    netLcRecog = lcAfterIfie.negate();
                } else {
                    netLcRecog = totalReversal.negate();
                }
            } else {
                netLcRecog = initialLossRecog.add(lcChangeEst).subtract(lcReleaseTotal);
            }

            BigDecimal serviceExpenseNonLc = iacfAmortExp.add(netLcRecogNonLc);
            BigDecimal serviceExpenseLc = netLcRecog;

            BigDecimal resNonLc = revenueNonLc.add(serviceExpenseNonLc);
            BigDecimal resLc = revenueLc.add(serviceExpenseLc);
            BigDecimal resLic = BigDecimal.ZERO;

            BigDecimal ifiePlCfNonLcRaw = getBd(data, "IFIE_P&L_未到期_预期现金流_非亏损");
            BigDecimal ifiePlRaNonLcRaw = getBd(data, "IFIE_P&L_未到期_非金融风险调整_非亏损");
            BigDecimal ifiePlCsm = getBd(data, "IFIE_P&L_未到期_CSM");
            BigDecimal ifiePlCfLcLog = getBd(data, "IFIE_P&L_未到期_预期现金流_亏损");
            BigDecimal ifiePlRaLcLog = getBd(data, "IFIE_P&L_未到期_非金融风险调整_亏损");

            BigDecimal ifiePlTotal = ifiePlCfNonLcRaw.add(ifiePlCfLcLog).add(ifiePlRaNonLcRaw).add(ifiePlRaLcLog).subtract(ifiePlCsm);
            BigDecimal ifiePlLcLogSum = ifiePlCfLcLog.add(ifiePlRaLcLog);
            BigDecimal ifiePlLcDisplay = ifiePlLcLogSum.negate();
            BigDecimal ifiePlNonLc = ifiePlTotal.subtract(ifiePlLcDisplay);

            BigDecimal ifieOciCfNonLcRaw = getBd(data, "IFIE_OCI_未到期_预期现金流_非亏损");
            BigDecimal ifieOciRaNonLcRaw = getBd(data, "IFIE_OCI_未到期_非金融风险调整_非亏损");
            BigDecimal ifieOciCfLcLog = getBd(data, "IFIE_OCI_未到期_预期现金流_亏损");
            BigDecimal ifieOciRaLcLog = getBd(data, "IFIE_OCI_未到期_非金融风险调整_亏损");

            BigDecimal ifieOciTotal = ifieOciCfNonLcRaw.add(ifieOciCfLcLog).add(ifieOciRaNonLcRaw).add(ifieOciRaLcLog);
            BigDecimal ifieOciLcLogSum = ifieOciCfLcLog.add(ifieOciRaLcLog);
            BigDecimal ifieOciLcDisplay = ifieOciLcLogSum.negate();
            BigDecimal ifieOciNonLc = ifieOciTotal.subtract(ifieOciLcDisplay);

            BigDecimal tciNonLc = resNonLc.add(ifiePlNonLc).add(ifieOciNonLc);
            BigDecimal tciLc = resLc.add(ifiePlLcDisplay).add(ifieOciLcDisplay);
            BigDecimal tciLic = resLic;

            BigDecimal invComp = BigDecimal.ZERO;

            BigDecimal cfPremium = getBd(data, "现金流_收到的保费");
            BigDecimal cfIacf = getBd(data, "现金流_支付的获取费用").negate();
            BigDecimal cfTotalNonLc = cfPremium.add(cfIacf);

            BigDecimal otherChangesNonLc = BigDecimal.ZERO;
            BigDecimal otherChangesLc = BigDecimal.ZERO;
            BigDecimal otherChangesLic = BigDecimal.ZERO;

            BigDecimal closingNonLc = openingNonLc.add(tciNonLc).subtract(invComp).add(cfTotalNonLc).add(otherChangesNonLc);
            BigDecimal closingLc = openingLcDisplay.add(tciLc).add(otherChangesLc);
            BigDecimal closingLic = openingLic.add(tciLic).add(invComp).add(otherChangesLic);
            BigDecimal closingLcDisplay = closingLc.compareTo(BigDecimal.ZERO) < 0 ? closingLc.negate() : closingLc;

            boolean isTerminationYear = false;
            if (isFinalYear) {
                BigDecimal closingSum = getBd(data, "closing_bel").add(getBd(data, "closing_ra")).add(getBd(data, "closing_csm")).add(getBd(data, "closing_lic"));
                isTerminationYear = isWithinAbs(closingSum, new BigDecimal("0.01"));
            }

            BigDecimal logNonLcDisplay;
            BigDecimal logLcDisplay;
            BigDecimal logLic;

            if (isTerminationYear) {
                logNonLcDisplay = BigDecimal.ZERO;
                logLcDisplay = BigDecimal.ZERO;
                logLic = BigDecimal.ZERO;
            } else {
                BigDecimal logClosingBel = getBd(data, "closing_bel");
                BigDecimal logClosingRa = getBd(data, "closing_ra");
                BigDecimal logClosingCsm = getBd(data, "closing_csm");
                BigDecimal logClosingLc = data.get("closing_lc");
                if (logClosingLc == null) {
                    logLcDisplay = closingLcDisplay;
                } else {
                    logLcDisplay = logClosingLc.abs();
                }
                logNonLcDisplay = logClosingBel.add(logClosingRa).add(logClosingCsm).subtract(logLcDisplay);
                logLic = getBd(data, "closing_lic");
            }

            BigDecimal diffNonLc = closingNonLc.subtract(logNonLcDisplay);
            BigDecimal diffLc = closingLcDisplay.subtract(logLcDisplay);
            BigDecimal diffLic = closingLic.subtract(logLic);

            Map<String, BigDecimal> diff103 = new LinkedHashMap<>();
            diff103.put("non_lc", diffNonLc);
            diff103.put("lc", diffLc);
            diff103.put("lic", diffLic);
            diff103ByYear.put(year, diff103);

            boolean passed103Year = isWithinAbs(diffNonLc, VERIFY_EPS) && isWithinAbs(diffLc, VERIFY_EPS) && isWithinAbs(diffLic, VERIFY_EPS);
            if (!passed103Year) {
                passed103All = false;
            }
            updateMaxAbs(maxDiff103, "non_lc", diffNonLc);
            updateMaxAbs(maxDiff103, "lc", diffLc);
            updateMaxAbs(maxDiff103, "lic", diffLic);

            BigDecimal csmAmort = getBd(data, "保险合同收入_摊销的CSM");
            BigDecimal raReleaseGross = getBd(data, "保险合同收入_预期释放的非金融风险调整_含亏损");
            BigDecimal raChange = raReleaseGross.negate();

            BigDecimal expectedClaimsExp = getBd(data, "保险合同收入_预期赔付与费用_含亏损");
            BigDecimal actualClaimsExp = BigDecimal.ZERO;
            BigDecimal expAdjLog = getBd(data, "保险合同收入_经验调整");
            BigDecimal currServicePvVal = actualClaimsExp.subtract(expectedClaimsExp).add(expAdjLog);

            BigDecimal nbClaimsProfit = getBd(data, "新增合同预期现金流_赔付与费用现金流_盈利合同");
            BigDecimal nbIacfProfit = getBd(data, "新增合同预期现金流_IACF_盈利合同");
            BigDecimal nbPremProfit = getBd(data, "新增合同预期现金流_保费现金流_盈利合同");
            BigDecimal nbClaimsLossNonLc = getBd(data, "新增合同预期现金流_赔付与费用现金流_亏损合同_非亏损");
            BigDecimal lossNbCf = getBd(data, "亏损合同损益_新增合同预期现金流_赔付与费用现金流_亏损");
            BigDecimal nbIacfLoss = getBd(data, "新增合同预期现金流_IACF_亏损合同");
            BigDecimal nbPremLoss = getBd(data, "新增合同预期现金流_保费现金流_亏损合同");
            BigDecimal nbBel = nbClaimsProfit.add(nbIacfProfit).subtract(nbPremProfit)
                    .add(nbClaimsLossNonLc.add(lossNbCf).add(nbIacfLoss).subtract(nbPremLoss));

            BigDecimal nbRa = getBd(data, "新增合同非金融风险调整_盈利合同")
                    .add(getBd(data, "新增合同非金融风险调整_亏损合同_非亏损"))
                    .add(getBd(data, "亏损合同损益_新增合同非金融风险调整_亏损"));
            BigDecimal nbCsm = getBd(data, "新增合同CSM_盈利合同");

            BigDecimal csmAdjPv = getBd(data, "未到期_调整CSM的预期现金流变动");
            BigDecimal csmAdjRa = getBd(data, "未到期_调整CSM的非金融风险调整变动");
            BigDecimal csmAdjCsm = getBd(data, "未到期_调整CSM的估计变更");

            BigDecimal nonCsmPv = getBd(data, "亏损合同损益_不调整CSM的预期现金流变动");
            BigDecimal nonCsmRa = getBd(data, "亏损合同损益_不调整CSM的非金融风险调整变动");

            BigDecimal insServicePv = currServicePvVal.add(nbBel).add(csmAdjPv).add(nonCsmPv);
            BigDecimal insServiceRa = raChange.add(nbRa).add(csmAdjRa).add(nonCsmRa);
            BigDecimal insServiceCsm = csmAmort.add(nbCsm).add(csmAdjCsm);

            BigDecimal ifiePlCfNonLc = getBd(data, "IFIE_P&L_未到期_预期现金流_非亏损");
            BigDecimal ifiePlCfLc = getBd(data, "IFIE_P&L_未到期_预期现金流_亏损");
            BigDecimal ifiePv = ifiePlCfNonLc.add(ifiePlCfLc);

            BigDecimal ifiePlRaNonLc = getBd(data, "IFIE_P&L_未到期_非金融风险调整_非亏损");
            BigDecimal ifiePlRaLc = getBd(data, "IFIE_P&L_未到期_非金融风险调整_亏损");
            BigDecimal ifieRa = ifiePlRaNonLc.add(ifiePlRaLc);

            BigDecimal ifieCsmLog = getBd(data, "IFIE_P&L_未到期_CSM");
            BigDecimal ifieCsm = ifieCsmLog.negate();

            BigDecimal ifieOciPvNonLc = getBd(data, "IFIE_OCI_未到期_预期现金流_非亏损");
            BigDecimal ifieOciPvLc = getBd(data, "IFIE_OCI_未到期_预期现金流_亏损");
            BigDecimal ifieOciPv = ifieOciPvNonLc.add(ifieOciPvLc);

            BigDecimal ifieOciRaNonLc = getBd(data, "IFIE_OCI_未到期_非金融风险调整_非亏损");
            BigDecimal ifieOciRaLc = getBd(data, "IFIE_OCI_未到期_非金融风险调整_亏损");
            BigDecimal ifieOciRa = ifieOciRaNonLc.add(ifieOciRaLc);

            BigDecimal totalCiPv = insServicePv.add(ifiePv).add(ifieOciPv);
            BigDecimal totalCiRa = insServiceRa.add(ifieRa).add(ifieOciRa);
            BigDecimal totalCiCsm = insServiceCsm.add(ifieCsm);

            BigDecimal cfPrem = cfPremiumFrom104(data);
            BigDecimal cfAcq = getBd(data, "现金流_支付的获取费用").negate();
            BigDecimal cfTotal = cfPrem.add(cfAcq);

            BigDecimal calcClosingPv = openingPv.add(totalCiPv).add(cfTotal);
            BigDecimal calcClosingRa = openingRa.add(totalCiRa);
            BigDecimal calcClosingCsm = openingCsm.add(totalCiCsm);

            BigDecimal logClosingBel = getBd(data, "未到期责任负债_预期现金流_非亏损")
                    .add(getBd(data, "未到期责任负债_预期现金流_亏损"));
            BigDecimal logClosingRa = getBd(data, "未到期责任负债_非金融风险调整_非亏损")
                    .add(getBd(data, "未到期责任负债_非金融风险调整_亏损"));
            BigDecimal logClosingCsm = getBd(data, "未到期责任负债_CSM");

            BigDecimal diffPv = calcClosingPv.subtract(logClosingBel);
            BigDecimal diffRa = calcClosingRa.subtract(logClosingRa);
            BigDecimal diffCsm = calcClosingCsm.subtract(logClosingCsm);

            Map<String, BigDecimal> diff104 = new LinkedHashMap<>();
            diff104.put("pv", diffPv);
            diff104.put("ra", diffRa);
            diff104.put("csm", diffCsm);
            diff104ByYear.put(year, diff104);

            boolean passed104Year = isWithinAbs(diffPv, VERIFY_EPS) && isWithinAbs(diffRa, VERIFY_EPS) && isWithinAbs(diffCsm, VERIFY_EPS);
            if (!passed104Year) {
                passed104All = false;
            }
            updateMaxAbs(maxDiff104, "pv", diffPv);
            updateMaxAbs(maxDiff104, "ra", diffRa);
            updateMaxAbs(maxDiff104, "csm", diffCsm);

            openingNonLc = closingNonLc;
            openingLcDisplay = closingLcDisplay;
            openingLic = closingLic;

            openingPv = calcClosingPv;
            openingRa = calcClosingRa;
            openingCsm = calcClosingCsm;
        }

        return new ValidationSummary(
                policyNo,
                certiNo,
                years,
                passed103All,
                passed104All,
                diff103ByYear,
                diff104ByYear,
                maxDiff103,
                maxDiff104
        );
    }

    public static void generateValidationReport(List<Map<String, Object>> results, String outputPath) {
        ValidationSummary summary = validate103And104(results);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>IFRS 17 103/104 平衡性校验结果</title>");
        html.append("<style>");
        html.append(":root{--header-bg:#f8f9fa;--border-color:#e9ecef;--text-color:#212529;}");
        html.append("body{font-family:'Microsoft YaHei',Arial,sans-serif;margin:0;padding:20px;background-color:#f4f4f4;color:var(--text-color);}");
        html.append(".container{max-width:1200px;margin:0 auto;background:white;padding:40px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.05);}");
        html.append("h1{text-align:center;font-size:24px;margin-bottom:5px;}");
        html.append(".subtitle{text-align:center;color:#6c757d;margin-bottom:30px;font-size:14px;}");
        html.append("table{width:100%;border-collapse:collapse;font-size:13px;margin-top:10px;}");
        html.append("th,td{padding:8px 12px;border-bottom:1px solid var(--border-color);text-align:right;}");
        html.append("th{background-color:var(--header-bg);font-weight:bold;color:#495057;text-align:center;vertical-align:middle;}");
        html.append("td:first-child{text-align:left;width:20%;color:#212529;}");
        html.append(".num{font-family:Consolas,monospace;}");
        html.append(".zero{color:#adb5bd;}");
        html.append(".negative{color:#d9534f;}");
        html.append(".pass{color:green;font-weight:bold;}");
        html.append(".fail{color:red;font-weight:bold;}");
        html.append(".section{margin-top:25px;border-top:1px solid #eee;padding-top:20px;}");
        html.append(".section h2{font-size:18px;color:#333;margin:0 0 10px 0;border-left:4px solid #0d6efd;padding-left:10px;}");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"container\">");
        html.append("<h1>IFRS 17 103/104 平衡性校验结果</h1>");

        String policyInfo = (summary.getPolicyNo() == null || summary.getPolicyNo().isEmpty()) ? "未知保单" : summary.getPolicyNo();
        if (summary.getCertiNo() != null && !summary.getCertiNo().isEmpty()) {
            policyInfo = policyInfo + " (批单号: " + summary.getCertiNo() + ")";
        }

        String yearRange = "";
        if (summary.getYears() != null && !summary.getYears().isEmpty()) {
            List<Integer> years = summary.getYears();
            yearRange = years.size() > 1 ? (years.get(0) + "-" + years.get(years.size() - 1)) : String.valueOf(years.get(0));
        }

        html.append("<p class=\"subtitle\">保单号: ").append(policyInfo).append(" | 模拟区间: ").append(yearRange).append("</p>");

        html.append("<div class=\"section\">");
        html.append("<h2>103 报表验算</h2>");
        html.append("<p>通过状态: ").append(summary.isPassed103() ? "<span class=\"pass\">通过</span>" : "<span class=\"fail\">不通过</span>").append("</p>");
        html.append("<table>");
        html.append("<thead><tr><th style=\"text-align:left;\">年度</th><th>non_lc</th><th>lc</th><th>lic</th><th>年度状态</th></tr></thead>");
        html.append("<tbody>");
        for (Integer year : summary.getYears()) {
            Map<String, BigDecimal> diff = summary.getDiff103ByYear().getOrDefault(year, new LinkedHashMap<>());
            BigDecimal diffNonLc = diff.getOrDefault("non_lc", BigDecimal.ZERO);
            BigDecimal diffLc = diff.getOrDefault("lc", BigDecimal.ZERO);
            BigDecimal diffLic = diff.getOrDefault("lic", BigDecimal.ZERO);
            boolean passedYear = isWithinAbs(diffNonLc, VERIFY_EPS) && isWithinAbs(diffLc, VERIFY_EPS) && isWithinAbs(diffLic, VERIFY_EPS);
            html.append("<tr>");
            html.append("<td style=\"text-align:left;\">").append(year).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffNonLc)).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffLc)).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffLic)).append("</td>");
            html.append("<td>").append(passedYear ? "<span class=\"pass\">通过</span>" : "<span class=\"fail\">不通过</span>").append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody>");
        html.append("</table>");
        html.append("</div>");

        html.append("<div class=\"section\">");
        html.append("<h2>104 报表验算</h2>");
        html.append("<p>通过状态: ").append(summary.isPassed104() ? "<span class=\"pass\">通过</span>" : "<span class=\"fail\">不通过</span>").append("</p>");
        html.append("<table>");
        html.append("<thead><tr><th style=\"text-align:left;\">年度</th><th>pv</th><th>ra</th><th>csm</th><th>年度状态</th></tr></thead>");
        html.append("<tbody>");
        for (Integer year : summary.getYears()) {
            Map<String, BigDecimal> diff = summary.getDiff104ByYear().getOrDefault(year, new LinkedHashMap<>());
            BigDecimal diffPv = diff.getOrDefault("pv", BigDecimal.ZERO);
            BigDecimal diffRa = diff.getOrDefault("ra", BigDecimal.ZERO);
            BigDecimal diffCsm = diff.getOrDefault("csm", BigDecimal.ZERO);
            boolean passedYear = isWithinAbs(diffPv, VERIFY_EPS) && isWithinAbs(diffRa, VERIFY_EPS) && isWithinAbs(diffCsm, VERIFY_EPS);
            html.append("<tr>");
            html.append("<td style=\"text-align:left;\">").append(year).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffPv)).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffRa)).append("</td>");
            html.append("<td class=\"num\">").append(formatCell(diffCsm)).append("</td>");
            html.append("<td>").append(passedYear ? "<span class=\"pass\">通过</span>" : "<span class=\"fail\">不通过</span>").append("</td>");
            html.append("</tr>");
        }
        html.append("</tbody>");
        html.append("</table>");
        html.append("</div>");

        html.append("<div class=\"section\">");
        html.append("<h2>阈值说明</h2>");
        html.append("<p>差异绝对值小于 ").append(VERIFY_EPS.toPlainString()).append(" 视为通过。</p>");
        html.append("</div>");

        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        writeToFile(html.toString(), outputPath);
    }

    private static Map<String, Object> buildRow(int year, String category, BigDecimal lrcNonLc, BigDecimal lrcLc, BigDecimal lic, boolean isHeader, int indent) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("year", year);
        row.put("category", category);
        row.put("lrc_non_lc", lrcNonLc);
        row.put("lrc_lc", lrcLc);
        row.put("lic", lic);
        row.put("total", lrcNonLc.add(lrcLc).add(lic));
        row.put("is_header", isHeader);
        row.put("indent", indent);
        return row;
    }

    private static Map<String, Object> build104Row(int year, String categoryName, BigDecimal pv, BigDecimal ra, BigDecimal csm, int indent, boolean isHeader) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("year", year);
        row.put("category_name", categoryName);
        row.put("pv", pv);
        row.put("ra", ra);
        row.put("csm", csm);
        row.put("indent", indent);
        row.put("is_header", isHeader);
        return row;
    }

    private static String render103Html(List<Map<String, Object>> rows, Map<Integer, List<Map<String, String>>> explanationsByYear, String policyNo, String certiNo) {
        Map<Integer, List<Map<String, Object>>> rowsByYear = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Integer year = (Integer) row.get("year");
            rowsByYear.computeIfAbsent(year, k -> new ArrayList<>()).add(row);
        }
        List<Integer> years = new ArrayList<>(rowsByYear.keySet());
        Collections.sort(years);

        String yearRange;
        if (years.size() > 1) {
            yearRange = years.get(0) + "-" + years.get(years.size() - 1);
        } else {
            yearRange = String.valueOf(years.get(0));
        }

        String policyInfo = (policyNo == null || policyNo.isEmpty()) ? "未知保单" : policyNo;
        if (certiNo != null && !certiNo.isEmpty()) {
            policyInfo = policyInfo + " (批单号: " + certiNo + ")";
        }

        StringBuilder tabsHtml = new StringBuilder();
        StringBuilder contentHtml = new StringBuilder();

        for (int i = 0; i < years.size(); i++) {
            Integer year = years.get(i);
            String activeClass = i == 0 ? " active" : "";
            tabsHtml.append("<button class=\"tab-btn").append(activeClass).append("\" onclick=\"openTab(event, 'y")
                    .append(year).append("')\">").append(year).append(" 年度</button>\n");

            List<Map<String, Object>> yearRows = rowsByYear.get(year);
            StringBuilder tableRows = new StringBuilder();

            for (Map<String, Object> row : yearRows) {
                BigDecimal total = (BigDecimal) row.get("total");
                String rowClass = "";
                if (Boolean.TRUE.equals(row.get("is_header"))) {
                    rowClass = "border-top-heavy";
                }
                String category = String.valueOf(row.get("category"));
                if (category != null && category.contains("年末的保险合同净负债")) {
                    rowClass = rowClass + " border-double-bottom";
                }
                int indent = (Integer) row.get("indent");
                String indentClass = indent > 0 ? " indent-" + indent : "";

                String cellNonLc = formatCell((BigDecimal) row.get("lrc_non_lc"));
                String cellLc = formatCell((BigDecimal) row.get("lrc_lc"));
                String cellLic = formatCell((BigDecimal) row.get("lic"));
                String cellTotal = formatCell(total);

                tableRows.append("<tr class=\"").append(rowClass).append("\">");
                tableRows.append("<td class=\"").append(indentClass).append("\">").append(category).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellNonLc).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellLc).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellLic).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellTotal).append("</td>");
                tableRows.append("</tr>");
            }

            StringBuilder explanationHtml = new StringBuilder();
            List<Map<String, String>> expList = explanationsByYear.get(year);
            if (expList != null && !expList.isEmpty()) {
                explanationHtml.append("<div class=\"explanation-box\">");
                explanationHtml.append("<h3>").append(year).append(" 年度计算明细与验算</h3>");
                for (Map<String, String> exp : expList) {
                    explanationHtml.append("<div class=\"explanation-item\">");
                    explanationHtml.append("<h4 class=\"exp-title\">").append(exp.get("title")).append("</h4>");
                    explanationHtml.append("<div class=\"exp-content\">");
                    explanationHtml.append(exp.get("content"));
                    explanationHtml.append("</div></div>");
                }
                explanationHtml.append("</div>");
            }

            contentHtml.append("<div id=\"y").append(year).append("\" class=\"tab-content").append(activeClass).append("\">");
            contentHtml.append("<table>");
            contentHtml.append("<thead>");
            contentHtml.append("<tr>");
            contentHtml.append("<th rowspan=\"2\" style=\"text-align: left;\">项目</th>");
            contentHtml.append("<th colspan=\"2\">未到期责任负债</th>");
            contentHtml.append("<th rowspan=\"2\">已发生<br>赔款负债</th>");
            contentHtml.append("<th rowspan=\"2\">合计</th>");
            contentHtml.append("</tr>");
            contentHtml.append("<tr>");
            contentHtml.append("<th>非亏损部分</th>");
            contentHtml.append("<th>亏损部分</th>");
            contentHtml.append("</tr>");
            contentHtml.append("</thead>");
            contentHtml.append("<tbody>");
            contentHtml.append(tableRows);
            contentHtml.append("</tbody>");
            contentHtml.append("</table>");
            contentHtml.append(explanationHtml);
            contentHtml.append("</div>");
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>IFRS 17 未到期责任负债和已发生赔款负债调节表 (103报表)</title>");
        html.append("<style>");
        html.append(":root{--header-bg:#f8f9fa;--border-color:#e9ecef;--text-color:#212529;}");
        html.append("body{font-family:'Microsoft YaHei',Arial,sans-serif;margin:0;padding:20px;background-color:#f4f4f4;color:var(--text-color);}");
        html.append(".container{max-width:1200px;margin:0 auto;background:white;padding:40px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.05);}");
        html.append("h1{text-align:center;font-size:24px;margin-bottom:5px;}");
        html.append(".subtitle{text-align:center;color:#6c757d;margin-bottom:30px;font-size:14px;}");
        html.append(".tabs{display:flex;border-bottom:1px solid #dee2e6;margin-bottom:20px;}");
        html.append(".tab-btn{padding:10px 20px;background:none;border:none;border-bottom:3px solid transparent;font-size:16px;cursor:pointer;color:#495057;font-weight:500;}");
        html.append(".tab-btn.active{color:#0d6efd;border-bottom-color:#0d6efd;}");
        html.append(".tab-content{display:none;}");
        html.append(".tab-content.active{display:block;}");
        html.append("table{width:100%;border-collapse:collapse;font-size:13px;}");
        html.append("th,td{padding:8px 12px;border-bottom:1px solid var(--border-color);text-align:right;}");
        html.append("th{background-color:var(--header-bg);font-weight:bold;color:#495057;text-align:center;vertical-align:middle;}");
        html.append("td:first-child{text-align:left;width:40%;color:#212529;}");
        html.append(".indent-1{padding-left:20px !important;}");
        html.append(".border-top-heavy{border-top:2px solid #6c757d;}");
        html.append(".border-double-bottom{border-bottom:3px double #6c757d;}");
        html.append(".num{font-family:Consolas,monospace;}");
        html.append(".zero{color:#adb5bd;}");
        html.append(".negative{color:#d9534f;}");
        html.append(".explanation-box{margin-top:30px;border-top:1px solid #eee;padding-top:20px;}");
        html.append(".explanation-box h3{font-size:18px;color:#333;margin-bottom:15px;border-left:4px solid #0d6efd;padding-left:10px;}");
        html.append(".explanation-item{margin-bottom:15px;background-color:#fafafa;border:1px solid #eee;border-radius:4px;padding:10px 15px;}");
        html.append(".exp-title{margin:0 0 10px 0;font-size:14px;color:#0d6efd;font-weight:bold;}");
        html.append(".exp-content ul{margin:0;padding-left:20px;font-size:13px;color:#555;}");
        html.append(".exp-content li{margin-bottom:4px;}");
        html.append(".exp-content b{color:#333;}");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"container\">");
        html.append("<h1>IFRS 17 未到期责任负债和已发生赔款负债调节表</h1>");
        html.append("<p class=\"subtitle\">保单号: ").append(policyInfo).append(" | 模拟区间: ").append(yearRange).append("</p>");
        html.append("<div class=\"tabs\">");
        html.append(tabsHtml);
        html.append("</div>");
        html.append(contentHtml);
        html.append("</div>");
        html.append("<script>");
        html.append("function openTab(evt,yearName){var i,tabcontent,tablinks;tabcontent=document.getElementsByClassName('tab-content');for(i=0;i<tabcontent.length;i++){tabcontent[i].style.display='none';tabcontent[i].classList.remove('active');}tablinks=document.getElementsByClassName('tab-btn');for(i=0;i<tablinks.length;i++){tablinks[i].className=tablinks[i].className.replace(' active','');}document.getElementById(yearName).style.display='block';document.getElementById(yearName).classList.add('active');evt.currentTarget.className+=' active';}");
        html.append("</script>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private static String render104Html(List<Map<String, Object>> rows, Map<Integer, List<Map<String, String>>> explanationsByYear, String policyNo, String certiNo) {
        Map<Integer, List<Map<String, Object>>> rowsByYear = new LinkedHashMap<>();
        for (Map<String, Object> row : rows) {
            Integer year = (Integer) row.get("year");
            rowsByYear.computeIfAbsent(year, k -> new ArrayList<>()).add(row);
        }
        List<Integer> years = new ArrayList<>(rowsByYear.keySet());
        Collections.sort(years);

        String yearRange;
        if (years.size() > 1) {
            yearRange = years.get(0) + "-" + years.get(years.size() - 1);
        } else {
            yearRange = String.valueOf(years.get(0));
        }

        String policyInfo = (policyNo == null || policyNo.isEmpty()) ? "未知保单" : policyNo;
        if (certiNo != null && !certiNo.isEmpty()) {
            policyInfo = policyInfo + " (批单号: " + certiNo + ")";
        }

        StringBuilder tabsHtml = new StringBuilder();
        StringBuilder contentHtml = new StringBuilder();

        for (int i = 0; i < years.size(); i++) {
            Integer year = years.get(i);
            String activeClass = i == 0 ? " active" : "";
            tabsHtml.append("<button class=\"tab-btn").append(activeClass).append("\" onclick=\"openTab(event, 'y")
                    .append(year).append("')\">").append(year).append(" 年度</button>\n");

            List<Map<String, Object>> yearRows = rowsByYear.get(year);
            StringBuilder tableRows = new StringBuilder();

            for (Map<String, Object> row : yearRows) {
                BigDecimal pv = (BigDecimal) row.get("pv");
                BigDecimal ra = (BigDecimal) row.get("ra");
                BigDecimal csm = (BigDecimal) row.get("csm");
                BigDecimal total = pv.add(ra).add(csm);

                String rowClass = "";
                String categoryName = String.valueOf(row.get("category_name"));
                if (categoryName != null) {
                    if (categoryName.contains("年初的保险合同负债(1)")
                            || categoryName.contains("合同服务边际的摊销(4)")
                            || categoryName.contains("当期初始确认")
                            || categoryName.contains("已发生赔款")
                            || categoryName.contains("保险服务业绩")
                            || categoryName.contains("现金流量合计")
                            || categoryName.contains("年末的保险合同净负债")) {
                        rowClass = "border-top-heavy";
                    }
                    if (categoryName.contains("年末的保险合同净负债")) {
                        rowClass = rowClass + " border-double-bottom";
                    }
                }
                int indent = (Integer) row.get("indent");
                String indentClass = indent > 0 ? " indent-" + indent : "";

                String cellPv = formatCell(pv);
                String cellRa = formatCell(ra);
                String cellCsm = formatCell(csm);
                String cellTotal = formatCell(total);

                tableRows.append("<tr class=\"").append(rowClass).append("\">");
                tableRows.append("<td class=\"").append(indentClass).append("\">").append(categoryName).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellPv).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellRa).append("</td>");
                tableRows.append("<td class=\"num col-csm\">").append(cellCsm).append("</td>");
                tableRows.append("<td class=\"num\">").append(cellTotal).append("</td>");
                tableRows.append("</tr>");
            }

            StringBuilder explanationHtml = new StringBuilder();
            List<Map<String, String>> expList = explanationsByYear.get(year);
            if (expList != null && !expList.isEmpty()) {
                explanationHtml.append("<div class=\"explanation-box\">");
                explanationHtml.append("<h3>").append(year).append(" 年度计算明细与验算</h3>");
                for (Map<String, String> exp : expList) {
                    explanationHtml.append("<div class=\"explanation-item\">");
                    explanationHtml.append("<h4 class=\"exp-title\">").append(exp.get("title")).append("</h4>");
                    explanationHtml.append("<div class=\"exp-content\">");
                    explanationHtml.append(exp.get("content"));
                    explanationHtml.append("</div></div>");
                }
                explanationHtml.append("</div>");
            }

            contentHtml.append("<div id=\"y").append(year).append("\" class=\"tab-content").append(activeClass).append("\">");
            contentHtml.append("<table>");
            contentHtml.append("<thead>");
            contentHtml.append("<tr>");
            contentHtml.append("<th rowspan=\"2\" style=\"text-align: left;\">项目</th>");
            contentHtml.append("<th colspan=\"4\">").append(year).append("年度 (单位: 元)</th>");
            contentHtml.append("</tr>");
            contentHtml.append("<tr>");
            contentHtml.append("<th>未来现金流量<br>的现值</th>");
            contentHtml.append("<th>非金融风险<br>调整</th>");
            contentHtml.append("<th class=\"header-csm\">合同服务<br>边际(a)</th>");
            contentHtml.append("<th>合计</th>");
            contentHtml.append("</tr>");
            contentHtml.append("</thead>");
            contentHtml.append("<tbody>");
            contentHtml.append(tableRows);
            contentHtml.append("</tbody>");
            contentHtml.append("</table>");
            contentHtml.append(explanationHtml);
            contentHtml.append("</div>");
        }

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html lang=\"zh-CN\">");
        html.append("<head>");
        html.append("<meta charset=\"UTF-8\">");
        html.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        html.append("<title>IFRS 17 合同负债余额调节表 (详细版)</title>");
        html.append("<style>");
        html.append(":root{--header-bg:#f8f9fa;--border-color:#e9ecef;--text-color:#212529;}");
        html.append("body{font-family:'Microsoft YaHei',Arial,sans-serif;margin:0;padding:20px;background-color:#f4f4f4;color:var(--text-color);}");
        html.append(".container{max-width:1200px;margin:0 auto;background:white;padding:40px;border-radius:8px;box-shadow:0 2px 10px rgba(0,0,0,0.05);}");
        html.append("h1{text-align:center;font-size:24px;margin-bottom:5px;}");
        html.append(".subtitle{text-align:center;color:#6c757d;margin-bottom:30px;font-size:14px;}");
        html.append(".tabs{display:flex;border-bottom:1px solid #dee2e6;margin-bottom:20px;}");
        html.append(".tab-btn{padding:10px 20px;background:none;border:none;border-bottom:3px solid transparent;font-size:16px;cursor:pointer;color:#495057;font-weight:500;}");
        html.append(".tab-btn.active{color:#0d6efd;border-bottom-color:#0d6efd;}");
        html.append(".tab-content{display:none;}");
        html.append(".tab-content.active{display:block;}");
        html.append("table{width:100%;border-collapse:collapse;font-size:13px;}");
        html.append("th,td{padding:8px 12px;border-bottom:1px solid var(--border-color);text-align:right;}");
        html.append("th{background-color:var(--header-bg);font-weight:bold;color:#495057;text-align:center;vertical-align:middle;}");
        html.append("td:first-child{text-align:left;width:40%;color:#212529;}");
        html.append(".indent-1{padding-left:20px !important;}");
        html.append(".border-top-heavy{border-top:2px solid #6c757d;}");
        html.append(".border-double-bottom{border-bottom:3px double #6c757d;}");
        html.append(".num{font-family:Consolas,monospace;}");
        html.append(".zero{color:#adb5bd;}");
        html.append(".negative{color:#d9534f;}");
        html.append(".col-csm{background-color:#fffbeb;}");
        html.append(".header-csm{color:#d68b00;}");
        html.append(".explanation-box{margin-top:30px;border-top:1px solid #eee;padding-top:20px;}");
        html.append(".explanation-box h3{font-size:18px;color:#333;margin-bottom:15px;border-left:4px solid #0d6efd;padding-left:10px;}");
        html.append(".explanation-item{margin-bottom:15px;background-color:#fafafa;border:1px solid #eee;border-radius:4px;padding:10px 15px;}");
        html.append(".exp-title{margin:0 0 10px 0;font-size:14px;color:#0d6efd;font-weight:bold;}");
        html.append(".exp-content ul{margin:0;padding-left:20px;font-size:13px;color:#555;}");
        html.append(".exp-content li{margin-bottom:4px;}");
        html.append(".exp-content b{color:#333;}");
        html.append("</style>");
        html.append("</head>");
        html.append("<body>");
        html.append("<div class=\"container\">");
        html.append("<h1>IFRS 17 合同负债余额调节表</h1>");
        html.append("<p class=\"subtitle\">保单号: ").append(policyInfo).append(" | 模拟区间: ").append(yearRange).append("</p>");
        html.append("<div class=\"tabs\">");
        html.append(tabsHtml);
        html.append("</div>");
        html.append(contentHtml);
        html.append("</div>");
        html.append("<script>");
        html.append("function openTab(evt,yearName){var i,tabcontent,tablinks;tabcontent=document.getElementsByClassName('tab-content');for(i=0;i<tabcontent.length;i++){tabcontent[i].style.display='none';tabcontent[i].classList.remove('active');}tablinks=document.getElementsByClassName('tab-btn');for(i=0;i<tablinks.length;i++){tablinks[i].className=tablinks[i].className.replace(' active','');}document.getElementById(yearName).style.display='block';document.getElementById(yearName).classList.add('active');evt.currentTarget.className+=' active';}");
        html.append("</script>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private static String formatCell(BigDecimal val) {
        if (val == null) {
            return "<span class=\"zero\">0.00</span>";
        }
        if (val.abs().compareTo(new BigDecimal("0.005")) < 0) {
            return "<span class=\"zero\">0.00</span>";
        }
        String formatted = DF.format(val);
        if (val.compareTo(BigDecimal.ZERO) < 0) {
            String cleaned = formatted.replace("-", "");
            return "<span class=\"negative\">(" + cleaned + ")</span>";
        }
        return formatted;
    }

    private static String format(BigDecimal val) {
        if (val == null) {
            return "0.00";
        }
        if (val.abs().compareTo(new BigDecimal("0.005")) < 0) {
            return "0.00";
        }
        return DF.format(val);
    }

    private static boolean isReportDebugEnabled() {
        String env = System.getenv("BBA_REPORT_DEBUG");
        return "1".equals(env);
    }

    private static String formatPlain(BigDecimal val) {
        return format(val).replace(",", "");
    }

    private static String encodeBase64(String s) {
        if (s == null) {
            s = "";
        }
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static int parseSegmentNo(String s) {
        if (s == null) {
            return -1;
        }
        int idx = 0;
        while (idx < s.length()) {
            int start = s.indexOf('(', idx);
            if (start < 0 || start + 1 >= s.length()) {
                break;
            }
            int i = start + 1;
            int value = 0;
            boolean hasDigits = false;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c >= '0' && c <= '9') {
                    hasDigits = true;
                    value = value * 10 + (c - '0');
                    i++;
                    continue;
                }
                break;
            }
            if (hasDigits && i < s.length() && s.charAt(i) == ')') {
                // 报表行名称里经常包含“(段号)=...(1)+(2)...”这样的公式，
                // 我们只需要第一个括号里的“段号”，不能取后续公式中的括号数字。
                return value;
            }
            idx = start + 1;
        }
        return -1;
    }

    private static BigDecimal getBd(Map<String, BigDecimal> map, String key) {
        BigDecimal val = map.get(key);
        return val != null ? val : BigDecimal.ZERO;
    }

    private static boolean isWithinAbs(BigDecimal v, BigDecimal eps) {
        if (v == null) {
            return true;
        }
        return v.abs().compareTo(eps) < 0;
    }

    private static void updateMaxAbs(Map<String, BigDecimal> maxDiff, String key, BigDecimal diff) {
        BigDecimal prev = maxDiff.get(key);
        BigDecimal absDiff = diff == null ? BigDecimal.ZERO : diff.abs();
        if (prev == null || absDiff.compareTo(prev.abs()) > 0) {
            maxDiff.put(key, diff == null ? BigDecimal.ZERO : diff);
        }
    }

    private static Map<Integer, Map<String, BigDecimal>> convertResultsToDecimal(List<Map<String, Object>> results) {
        Map<Integer, Map<String, BigDecimal>> map = new HashMap<>();
        for (Map<String, Object> row : results) {
            Object yearObj = row.get("year");
            if (yearObj == null) {
                continue;
            }
            int year = Integer.parseInt(yearObj.toString());
            Map<String, BigDecimal> converted = new HashMap<>();
            for (Map.Entry<String, Object> e : row.entrySet()) {
                String key = e.getKey();
                if ("year".equals(key) || "policy_no".equals(key) || "certi_no".equals(key)) {
                    continue;
                }
                Object val = e.getValue();
                BigDecimal bd;
                if (val instanceof BigDecimal) {
                    bd = (BigDecimal) val;
                } else if (val instanceof Number) {
                    bd = new BigDecimal(val.toString());
                } else if (val == null) {
                    bd = BigDecimal.ZERO;
                } else {
                    String s = val.toString().trim().replace(",", "");
                    if (s.startsWith("(") && s.endsWith(")")) {
                        s = "-" + s.substring(1, s.length() - 1);
                    }
                    try {
                        bd = new BigDecimal(s);
                    } catch (Exception ex) {
                        bd = BigDecimal.ZERO;
                    }
                }
                converted.put(key, bd);
            }
            map.put(year, converted);
        }

        return map;
    }

    private static String getVerifyStatusHtml(BigDecimal diff) {
        if (diff.compareTo(new BigDecimal("-0.01")) > 0 && diff.compareTo(new BigDecimal("0.01")) < 0) {
            return "<span style='color:green'>✓ 无差异</span>";
        }
        return "<span style='color:red'>✗ 差异: " + format(diff) + "</span>";
    }

    private static String getVerifyStatusHtml104(BigDecimal diff) {
        if (diff.compareTo(new BigDecimal("-0.01")) > 0 && diff.compareTo(new BigDecimal("0.01")) < 0) {
            return "<span style='color:green'>无差异</span>";
        }
        return "<span style='color:red'>差异: " + format(diff) + "</span>";
    }

    private static BigDecimal cfPremiumFrom104(Map<String, BigDecimal> data) {
        return getBd(data, "现金流_收到的保费");
    }

    private static String toStringSafe(Object v) {
        return v == null ? "" : v.toString();
    }

    private static void writeToFile(String content, String path) {
        File file = new File(path);
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        try (java.io.Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            writer.write(content);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
