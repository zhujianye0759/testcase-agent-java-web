package com.testcaseagent.export;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Deterministic two-sheet Markdown workbook writer. [Req-ID]: REQ-EXP-001, REQ-EXP-002, REQ-EXP-003, REQ-EXP-004, REQ-EXP-005, REQ-EXP-007, REQ-CWR-003 */
public final class ApachePoiWorkbookExporter implements WorkbookExporter {
    private static final List<String> SHEETS = List.of("需求与功能清单审查发现", "测试用例");
    private static final List<String> AUDIT_HEADERS = List.of("序号", "对象/功能点", "问题分类", "证据对照");
    private static final List<String> CASE_HEADERS = List.of("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");
    private static final int[] AUDIT_COLUMN_WIDTHS = {8, 24, 18, 52};
    private static final int[] CASE_COLUMN_WIDTHS = {30, 25, 26, 45, 45, 55};
    private final Path artifactRoot;
    public ApachePoiWorkbookExporter(Path artifactRoot) { this.artifactRoot = artifactRoot.toAbsolutePath().normalize(); }

    @Override public WorkbookArtifact exportMarkdown(MarkdownWorkbookExportRequest request) {
        if (!request.validationPassed() || request.testCaseRows().isEmpty()) throw new WorkbookExportException("Validated test-case rows are required");
        rejectImages(request);
        rejectMachineEvidenceTokens(request);
        try {
            Files.createDirectories(artifactRoot);
            String id = UUID.randomUUID().toString();
            Path file = artifactRoot.resolve(id + ".xlsx").normalize();
            if (!file.startsWith(artifactRoot)) throw new WorkbookExportException("Artifact path escapes root");
            try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
                write(book.createSheet(SHEETS.get(0)), AUDIT_HEADERS, request.auditRows().stream().map(this::audit).toList(), AUDIT_COLUMN_WIDTHS);
                write(book.createSheet(SHEETS.get(1)), CASE_HEADERS, request.testCaseRows().stream().map(this::testCase).toList(), CASE_COLUMN_WIDTHS);
                book.write(output);
            }
            verify(file);
            return new WorkbookArtifact(id, sha256(Files.readAllBytes(file)), file);
        } catch (WorkbookExportException exception) { throw exception; }
        catch (Exception exception) { throw new WorkbookExportException("Markdown workbook export failed", exception); }
    }
    private List<String> audit(MarkdownAuditRow row) { return List.of(String.valueOf(row.sequence()), row.subjectOrFeature(), row.issueCategory(), row.evidenceComparison()); }
    private List<String> testCase(MarkdownTestCaseRow row) { return List.of(row.caseName(), row.featureModule(), row.preconditions(), row.executionSteps(), row.expectedResult(), row.requirementContent()); }
    private void write(Sheet sheet, List<String> headers, List<List<String>> rows, int[] columnWidths) {
        for (int column = 0; column < columnWidths.length; column++) sheet.setColumnWidth(column, columnWidths[column] * 256);
        sheet.createFreezePane(0, 1);
        Font bold = sheet.getWorkbook().createFont();
        bold.setBold(true);
        CellStyle headerStyle = sheet.getWorkbook().createCellStyle();
        headerStyle.setFont(bold);
        CellStyle bodyStyle = sheet.getWorkbook().createCellStyle();
        bodyStyle.setWrapText(true);
        bodyStyle.setVerticalAlignment(VerticalAlignment.TOP);
        Row header = sheet.createRow(0);
        for (int column = 0; column < headers.size(); column++) {
            Cell cell = header.createCell(column);
            cell.setCellValue(headers.get(column));
            cell.setCellStyle(headerStyle);
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            Row row = sheet.createRow(rowIndex + 1);
            for (int column = 0; column < rows.get(rowIndex).size(); column++) {
                String value = safe(rows.get(rowIndex).get(column)); Cell cell = row.createCell(column);
                cell.setCellValue(value);
                cell.setCellStyle(bodyStyle);
            }
        }
    }
    private void rejectImages(MarkdownWorkbookExportRequest request) {
        request.auditRows().forEach(row -> reject(row.subjectOrFeature(), row.issueCategory(), row.evidenceComparison()));
        request.testCaseRows().forEach(row -> reject(row.caseName(), row.featureModule(), row.preconditions(), row.executionSteps(), row.expectedResult(), row.requirementContent()));
    }
    private void rejectMachineEvidenceTokens(MarkdownWorkbookExportRequest request) {
        request.auditRows().forEach(row -> rejectMachineEvidenceTokens(row.subjectOrFeature(), row.issueCategory(), row.evidenceComparison()));
        request.testCaseRows().forEach(row -> rejectMachineEvidenceTokens(
                row.caseName(), row.featureModule(), row.preconditions(), row.executionSteps(), row.expectedResult(), row.requirementContent()));
    }
    private void rejectMachineEvidenceTokens(String... values) {
        for (String value : values) if (value != null && value.matches("(?is).*\\b(?:candidateIds|groupAnchorId|documentId|unitId)\\s*=.*")) {
            throw new WorkbookExportException("Reader-facing workbook cannot contain internal evidence binding tokens");
        }
    }
    private void reject(String... values) { for (String value : values) if (value != null && value.contains("![")) throw new WorkbookExportException("Markdown image syntax is not supported in workbook exports"); }
    private static String safe(String value) { if (value == null) return ""; return value.startsWith("=") || value.startsWith("+") || value.startsWith("-") || value.startsWith("@") ? "'" + value : value; }
    private void verify(Path file) throws Exception { try (XSSFWorkbook book = new XSSFWorkbook(file.toFile())) {
        if (book.getNumberOfSheets() != SHEETS.size()) throw new WorkbookExportException("Markdown workbook structure is invalid");
        for (int index = 0; index < SHEETS.size(); index++) if (!SHEETS.get(index).equals(book.getSheetName(index))) throw new WorkbookExportException("Markdown workbook structure is invalid");
        headers(book.getSheetAt(0), AUDIT_HEADERS); headers(book.getSheetAt(1), CASE_HEADERS);
    }}
    private static void headers(Sheet sheet, List<String> expected) { Row header = sheet.getRow(0); if (header == null || header.getLastCellNum() != expected.size()) throw new WorkbookExportException("Markdown workbook structure is invalid"); for (int index = 0; index < expected.size(); index++) if (!expected.get(index).equals(header.getCell(index).getStringCellValue())) throw new WorkbookExportException("Markdown workbook structure is invalid"); }
    private static String sha256(byte[] data) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder value = new StringBuilder(); for (byte part : hash) value.append(String.format("%02x", part)); return value.toString(); }
}
