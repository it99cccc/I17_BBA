package com.bba.util;

import com.bba.dto.YearlyLogData;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Excel生成服务
 * 负责生成符合Python版本样式的详细PV计算日志。
 * 
 * 主要功能：
 * 1. 生成多Sheet的Excel文件（每年一个Sheet）
 * 2. 格式化输出精度（小数点后10位）
 * 3. 匹配Python版本的样式和布局
 */
@Slf4j
@Service
public class ExcelService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /**
     * 将PV计算结果写入Excel文件
     * @param filePath 输出文件路径
     * @param dataList 年度日志数据列表
     */
    public void writePvResults(String filePath, List<YearlyLogData> dataList) {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            
            // 定义样式
            CellStyle headerStyle = createHeaderStyle(wb);
            CellStyle subHeaderStyle = createSubHeaderStyle(wb);
            CellStyle normalStyle = createNormalStyle(wb);
            CellStyle numberStyle = createNumberStyle(wb);
            CellStyle centerStyle = createCenterStyle(wb);

            for (YearlyLogData yearData : dataList) {
                createYearSheet(wb, yearData, headerStyle, subHeaderStyle, normalStyle, numberStyle, centerStyle);
            }

            try (FileOutputStream fos = new FileOutputStream(filePath)) {
                wb.write(fos);
            }
            log.info("Excel written to {}", filePath);
        } catch (Exception e) {
            log.error("Error writing Excel", e);
            throw new RuntimeException(e);
        }
    }

    /**
     * 创建年度Sheet
     */
    private void createYearSheet(XSSFWorkbook wb, YearlyLogData data, 
                                 CellStyle headerStyle, CellStyle subHeaderStyle, 
                                 CellStyle normalStyle, CellStyle numberStyle, CellStyle centerStyle) {
        String sheetName = data.getYear() + "年";
        // 防止重名处理
        Sheet sheet = wb.getSheet(sheetName);
        if (sheet == null) {
            sheet = wb.createSheet(sheetName);
        } else {
            sheet = wb.createSheet(sheetName + "_1");
        }

        int currentRow = 0;

        // 1. 保单信息
        currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - 保单信息", 2, headerStyle);
        String[][] policyInfo = {
            {"保单号", data.getPolicyInfo().getPolicyNo()},
            {"批单号", data.getPolicyInfo().getCertiNo()},
            {"签单日期", format(data.getPolicyInfo().getUnderWriteDate())},
            {"起保日期", format(data.getPolicyInfo().getStartDate())},
            {"终保日期", format(data.getPolicyInfo().getEndDate())},
            {"保修结束日期", format(data.getPolicyInfo().getWarrantyEndDate())},
            {"签单保费", format(data.getPolicyInfo().getWrittenPremium())}
        };
        currentRow = writeTable(sheet, currentRow, new String[]{"项目", "数值"}, policyInfo, subHeaderStyle, normalStyle, numberStyle, centerStyle);
        currentRow += 2;

        // 2. 精算假设
        currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - 精算假设", 2, headerStyle);
        String[][] assumpInfo = {
            {"赔付率", format(data.getAssumptions().getLossRatio())},
            {"间接理赔费用率", format(data.getAssumptions().getIndirectClaimsExpenseRatio())},
            {"维持费用率", format(data.getAssumptions().getMaintenanceExpenseRatio())},
            {"非金融风险调整率", format(data.getAssumptions().getRaRatio())},
            {"获取费用率", format(data.getAssumptions().getAcquisitionExpenseRatio())}
        };
        currentRow = writeTable(sheet, currentRow, new String[]{"项目", "数值"}, assumpInfo, subHeaderStyle, normalStyle, numberStyle, centerStyle);
        currentRow += 2;

        // 3. 利率曲线
        if (data.getRateCurves() != null) {
            for (String curveType : data.getRateCurves().keySet()) {
                String title = (curveType.equals("locked") ? "锁定利率曲线" : "期末利率曲线");
                currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - " + title, 4, headerStyle);
                
                List<YearlyLogData.RateCurvePointDTO> points = data.getRateCurves().get(curveType);
                // 只取前100个展示
                int limit = Math.min(points.size(), 100);
                String[][] rateData = new String[limit][4];
                for (int i = 0; i < limit; i++) {
                    YearlyLogData.RateCurvePointDTO p = points.get(i);
                    rateData[i][0] = String.valueOf(p.getTermMonth());
                    rateData[i][1] = format(p.getForwardRate());
                    rateData[i][2] = format(p.getDiscountFactor());
                    rateData[i][3] = p.getDescription();
                }
                currentRow = writeTable(sheet, currentRow, new String[]{"期限（月）", "远期月利率", "折现因子（累计）", "说明"}, 
                        rateData, subHeaderStyle, normalStyle, numberStyle, centerStyle);
                currentRow += 2;
            }
        }

        // 4. 月度现金流投射明细
        currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - 月度现金流投射明细", 8, headerStyle);
        List<YearlyLogData.CashFlowDetailDTO> cfs = data.getCashFlows();
        if (cfs != null && !cfs.isEmpty()) {
            String[][] cfData = new String[cfs.size()][8];
            for (int i = 0; i < cfs.size(); i++) {
                YearlyLogData.CashFlowDetailDTO cf = cfs.get(i);
                cfData[i][0] = cf.getYyyymm();
                cfData[i][1] = format(cf.getDateObj());
                cfData[i][2] = format(cf.getPremium());
                cfData[i][3] = format(cf.getIacf());
                cfData[i][4] = format(cf.getClaims()); // 这里的claims已包含间接理赔费用
                cfData[i][5] = format(cf.getExpenses());
                cfData[i][6] = cf.isInRisk() ? "✅" : "❌";
                cfData[i][7] = cf.getRiskDesc();
            }
            currentRow = writeTable(sheet, currentRow, 
                    new String[]{"年月", "日期", "保费流入", "IACF流出", "赔付流出", "维持费用流出", "风险期", "说明"}, 
                    cfData, subHeaderStyle, normalStyle, numberStyle, centerStyle);
        } else {
            createCell(sheet.createRow(currentRow++), 0, "现金流数据为空", normalStyle);
        }
        currentRow += 2;

        // 5. PV原材料值计算明细
        currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - PV原材料值汇总", 3, headerStyle);
        List<YearlyLogData.PvCalcDetailDTO> pvs = data.getPvCalculations();
        if (pvs != null && !pvs.isEmpty()) {
            String[][] pvData = new String[pvs.size()][3];
            for (int i = 0; i < pvs.size(); i++) {
                YearlyLogData.PvCalcDetailDTO pv = pvs.get(i);
                pvData[i][0] = pv.getFieldName();
                pvData[i][1] = pv.getDescription();
                pvData[i][2] = format(pv.getValue());
            }
            currentRow = writeTable(sheet, currentRow, new String[]{"PV字段名", "中文描述", "数值"}, 
                    pvData, subHeaderStyle, normalStyle, numberStyle, centerStyle);
        }
        currentRow += 2;

        // 6. 折现因子计算示例 (占位)
        currentRow = writeHeader(sheet, currentRow, data.getYear() + "年 - 折现因子计算示例", 7, headerStyle);
        createCell(sheet.createRow(currentRow++), 0, "折现因子计算明细为空", normalStyle);
        
        // 自动调整列宽
        for (int i = 0; i < 10; i++) {
            sheet.autoSizeColumn(i);
            // 限制最大宽度
            if (sheet.getColumnWidth(i) > 15000) sheet.setColumnWidth(i, 15000);
        }
    }

    private int writeHeader(Sheet sheet, int rowNum, String text, int colSpan, CellStyle style) {
        Row row = sheet.createRow(rowNum);
        Cell cell = createCell(row, 0, text, style);
        if (colSpan > 1) {
            sheet.addMergedRegion(new CellRangeAddress(rowNum, rowNum, 0, colSpan - 1));
        }
        return rowNum + 1;
    }

    private int writeTable(Sheet sheet, int rowNum, String[] headers, String[][] data, 
                           CellStyle headerStyle, CellStyle normalStyle, CellStyle numberStyle, CellStyle centerStyle) {
        // 写入表头
        Row headerRow = sheet.createRow(rowNum++);
        for (int i = 0; i < headers.length; i++) {
            createCell(headerRow, i, headers[i], headerStyle);
        }

        // 写入数据
        for (String[] rowData : data) {
            Row row = sheet.createRow(rowNum++);
            for (int i = 0; i < rowData.length; i++) {
                String val = rowData[i];
                CellStyle style = normalStyle;
                // 根据内容和列位置选择样式
                if (i == 0) style = centerStyle; // 第一列居中
                else if (isNumber(val)) style = numberStyle; // 数字居右
                else style = normalStyle; // 其他默认
                
                createCell(row, i, val, style);
            }
        }
        return rowNum;
    }

    private Cell createCell(Row row, int col, String val, CellStyle style) {
        Cell cell = row.createCell(col);
        cell.setCellValue(val);
        cell.setCellStyle(style);
        return cell;
    }

    private boolean isNumber(String str) {
        if (str == null) return false;
        return str.matches("-?\\d+(\\.\\d+)?(E-?\\d+)?") || str.matches("-?[\\d,]+\\.\\d+");
    }

    /**
     * 格式化对象为字符串
     * BigDecimal格式化为小数点后10位
     */
    private String format(Object obj) {
        if (obj == null) return "";
        if (obj instanceof BigDecimal) {
            BigDecimal bd = (BigDecimal) obj;
            // 统一使用10位小数精度
            if (bd.compareTo(BigDecimal.ZERO) == 0) return "0.0000000000";
            return String.format("%,.10f", bd);
        }
        if (obj instanceof LocalDate) {
            return ((LocalDate) obj).format(DATE_FMT);
        }
        return obj.toString();
    }

    // --- 样式创建方法 ---

    private CellStyle createHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(new java.awt.Color(54, 96, 146), null)); // 366092
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = wb.createFont();
        font.setColor(IndexedColors.WHITE.getIndex());
        font.setBold(true);
        font.setFontHeightInPoints((short) 11);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        return style;
    }

    private CellStyle createSubHeaderStyle(XSSFWorkbook wb) {
        XSSFCellStyle style = wb.createCellStyle();
        style.setFillForegroundColor(new XSSFColor(new java.awt.Color(217, 225, 242), null)); // D9E1F2
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        Font font = wb.createFont();
        font.setBold(true);
        font.setFontHeightInPoints((short) 10);
        style.setFont(font);
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style);
        return style;
    }

    private CellStyle createNormalStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.LEFT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style);
        return style;
    }
    
    private CellStyle createCenterStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.CENTER);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style);
        return style;
    }

    private CellStyle createNumberStyle(XSSFWorkbook wb) {
        CellStyle style = wb.createCellStyle();
        style.setAlignment(HorizontalAlignment.RIGHT);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        setBorder(style);
        return style;
    }

    private void setBorder(CellStyle style) {
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
    }
}
