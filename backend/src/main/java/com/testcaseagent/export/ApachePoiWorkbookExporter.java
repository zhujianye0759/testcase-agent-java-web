package com.testcaseagent.export;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import com.testcaseagent.validation.ReaderFacingTextPolicy;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/** Deterministic two-sheet Markdown and validated-structured workbook writer. [Req-ID]: REQ-EXP-001, REQ-EXP-002, REQ-EXP-003, REQ-EXP-004, REQ-EXP-005, REQ-EXP-007, REQ-CWR-003, REQ-FTG-009 */
public final class ApachePoiWorkbookExporter implements WorkbookExporter {
    private static final List<String> SHEETS = List.of("需求与功能清单审查发现", "测试用例");
    private static final List<String> AUDIT_HEADERS = List.of("序号", "对象/功能点", "问题分类", "证据对照");
    private static final List<String> CASE_HEADERS = List.of("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");
    private static final int[] AUDIT_COLUMN_WIDTHS = {8, 24, 18, 52};
    private static final int[] CASE_COLUMN_WIDTHS = {30, 25, 26, 45, 45, 55};
    private static final List<String> STRUCTURED_AUDIT_HEADERS = List.of("序号", "来源", "对象/功能点", "问题分类/核对结论",
            "影响范围", "说明", "实际坏例", "建议好例（待需求方确认）", "测试设计影响", "当前项目建议", "设计中心建议", "严重程度", "证据来源");
    private static final List<String> STRUCTURED_CASE_HEADERS = List.of("用例名称", "用例标题", "功能模块", "优先级", "状态", "前提约束",
            "硬件初始化", "软件初始化", "测试初始化", "参数初始化", "测试输入", "执行步骤", "逐步预期", "逐步评价",
            "异常或终止提示", "逐步结果采集", "总体预期", "执行评价标准", "结果评价标准", "终止条件", "结果采集",
            "编写人", "编写日期", "对应需求内容", "缺失信息");
    private static final int[] STRUCTURED_AUDIT_COLUMN_WIDTHS = {8, 14, 24, 22, 32, 52, 48, 48, 40, 40, 40, 18, 28};
    private static final int[] STRUCTURED_CASE_COLUMN_WIDTHS = {28, 32, 25, 12, 18, 32, 28, 28, 28, 28, 52, 50, 50,
            42, 42, 42, 50, 48, 48, 42, 42, 18, 18, 55, 35};
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

    /**
     * Writes already-validated Java business records directly to the fixed workbook projection.
     *
     * <p>Source identities are used only for deterministic de-duplication and never reach a cell. This path does not
     * parse, construct, or route through Markdown.</p>
     */
    @Override public WorkbookArtifact exportStructured(StructuredWorkbookExportRequest request) {
        if (request == null) throw new WorkbookExportException("Structured export request is required");
        List<StructuredReviewRow> reviews = distinctReviews(request.reviewRows());
        List<StructuredTestCaseRow> testcases = distinctTestcases(request.testCaseRows());
        reviews.forEach(this::requireSafeReview);
        testcases.forEach(this::requireSafeTestcase);
        try {
            Files.createDirectories(artifactRoot);
            String id = UUID.randomUUID().toString();
            Path file = artifactRoot.resolve(id + ".xlsx").normalize();
            if (!file.startsWith(artifactRoot)) throw new WorkbookExportException("Artifact path escapes root");
            try (XSSFWorkbook book = new XSSFWorkbook(); OutputStream output = Files.newOutputStream(file)) {
                write(book.createSheet(SHEETS.get(0)), STRUCTURED_AUDIT_HEADERS, reviews.stream().map(this::structuredAudit).toList(), STRUCTURED_AUDIT_COLUMN_WIDTHS);
                write(book.createSheet(SHEETS.get(1)), STRUCTURED_CASE_HEADERS, testcases.stream().map(this::structuredTestcase).toList(), STRUCTURED_CASE_COLUMN_WIDTHS);
                book.write(output);
            }
            verifyStructured(file);
            return new WorkbookArtifact(id, sha256(Files.readAllBytes(file)), file);
        } catch (WorkbookExportException exception) { throw exception; }
        catch (Exception exception) { throw new WorkbookExportException("Structured workbook export failed", exception); }
    }

    private static List<StructuredReviewRow> distinctReviews(List<StructuredReviewRow> rows) {
        Map<String, StructuredReviewRow> distinct = new LinkedHashMap<>();
        for (StructuredReviewRow row : rows) {
            if (distinct.putIfAbsent(requiredSource(row == null ? null : row.sourceId()), row) != null) {
                throw new WorkbookExportException("Structured review source identity is duplicate");
            }
        }
        return distinct.values().stream().sorted(Comparator.comparingInt(StructuredReviewRow::sequence)
                .thenComparing(row -> row.source().name()).thenComparing(StructuredReviewRow::sourceId)).toList();
    }

    private static List<StructuredTestCaseRow> distinctTestcases(List<StructuredTestCaseRow> rows) {
        Map<String, StructuredTestCaseRow> distinct = new LinkedHashMap<>();
        for (StructuredTestCaseRow row : rows) {
            if (distinct.putIfAbsent(requiredSource(row == null ? null : row.sourceId()), row) != null) {
                throw new WorkbookExportException("Structured testcase source identity is duplicate");
            }
        }
        return distinct.values().stream().sorted(Comparator.comparing((StructuredTestCaseRow row) -> row.status().ordinal())
                .thenComparing(StructuredTestCaseRow::functionName).thenComparing(StructuredTestCaseRow::title)
                .thenComparing(StructuredTestCaseRow::sourceId)).toList();
    }

    private void requireSafeReview(StructuredReviewRow row) {
        if (row == null || !row.validated() || row.sequence() < 1 || row.source() == null) {
            throw new WorkbookExportException("Validated structured review rows are required");
        }
        rejectStructured(row.subject(), row.classification(), row.affectedScope(), row.summary(), row.badSourceExample(),
                row.proposedGoodExample(), row.testDesignImpact(), row.currentProjectRecommendation(),
                row.designCenterGuidelineRecommendation(), row.severity(), row.evidenceSource());
    }

    private void requireSafeTestcase(StructuredTestCaseRow row) {
        if (row == null || !row.validated() || row.status() == null || row.steps().isEmpty()) {
            throw new WorkbookExportException("Validated structured test-case rows are required");
        }
        if (row.priority() == null) throw new WorkbookExportException("Structured testcase priority is required");
        rejectStructured(row.name(), row.title(), row.functionName(), row.evaluationCriteria(),
                row.resultEvaluationCriteria(), row.resultCollection(), row.authoringInformation().author(),
                row.authoringInformation().date());
        rejectStructured(row.preconditions().toArray(String[]::new));
        rejectStructured(row.initialization().hardwareConfiguration().toArray(String[]::new));
        rejectStructured(row.initialization().softwareConfiguration().toArray(String[]::new));
        rejectStructured(row.initialization().testConfiguration().toArray(String[]::new));
        rejectStructured(row.initialization().parameterConfiguration().toArray(String[]::new));
        for (StructuredTestCaseRow.TestInput input : row.inputs()) {
            if (input == null || input.nature() == null || input.source() == null || input.method() == null || input.authenticity() == null) {
                throw new WorkbookExportException("Structured testcase input fields are required");
            }
            rejectStructured(input.content(), input.sequence());
        }
        rejectStructured(row.requirementSummaries().toArray(String[]::new));
        rejectStructured(row.missingInformation().toArray(String[]::new));
        rejectStructured(row.expectedResults().toArray(String[]::new));
        rejectStructured(row.terminationConditions().toArray(String[]::new));
        for (int index = 0; index < row.steps().size(); index++) {
            StructuredTestStep step = row.steps().get(index);
            if (step == null || step.stepNo() != index + 1) throw new WorkbookExportException("Structured testcase steps must be consecutive");
            rejectStructured(step.action(), step.expected(), step.evaluationCriteria(), step.terminationOrError(), step.resultCollection());
        }
    }

    private List<String> structuredAudit(StructuredReviewRow row) {
        return List.of(String.valueOf(row.sequence()), row.source().display(), row.subject(), row.classification(),
                row.affectedScope(), row.summary(), row.badSourceExample(), row.proposedGoodExample(), row.testDesignImpact(),
                row.currentProjectRecommendation(), row.designCenterGuidelineRecommendation(), row.severity(), row.evidenceSource());
    }

    private List<String> structuredTestcase(StructuredTestCaseRow row) {
        return List.of(row.name(), row.title(), row.functionName(), row.priority().display(), row.status().display(),
                join(row.preconditions()), join(row.initialization().hardwareConfiguration()),
                join(row.initialization().softwareConfiguration()), join(row.initialization().testConfiguration()),
                join(row.initialization().parameterConfiguration()),
                row.inputs().stream().map(this::structuredInput).collect(java.util.stream.Collectors.joining("\n")),
                numberedSteps(row.steps(), StructuredTestStep::action), numberedSteps(row.steps(), StructuredTestStep::expected),
                numberedSteps(row.steps(), StructuredTestStep::evaluationCriteria),
                numberedSteps(row.steps(), StructuredTestStep::terminationOrError),
                numberedSteps(row.steps(), StructuredTestStep::resultCollection), join(row.expectedResults()),
                row.evaluationCriteria(), row.resultEvaluationCriteria(), join(row.terminationConditions()), row.resultCollection(),
                row.authoringInformation().author(), row.authoringInformation().date(), join(row.requirementSummaries()),
                join(row.missingInformation()));
    }

    private String structuredInput(StructuredTestCaseRow.TestInput input) {
        String sequence = input.sequence() == null || input.sequence().isBlank() ? "" : "；顺序：" + input.sequence();
        return input.content() + "（性质：" + input.nature().display() + "；来源：" + input.source().display()
                + "；方法：" + input.method().display() + "；真实性：" + input.authenticity().display() + sequence + "）";
    }

    private static String numberedSteps(List<StructuredTestStep> steps,
            java.util.function.Function<StructuredTestStep, String> value) {
        return steps.stream().filter(step -> value.apply(step) != null && !value.apply(step).isBlank())
                .map(step -> step.stepNo() + ". " + value.apply(step))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String join(List<String> values) {
        return String.join("\n", values);
    }

    private void rejectStructured(String... values) {
        for (String value : values) {
            if (value == null) throw new WorkbookExportException("Structured export values must not be null");
            try {
                if (!value.isBlank()) ReaderFacingTextPolicy.requireSafe(value, "structured export value");
            } catch (IllegalArgumentException exception) {
                throw new WorkbookExportException("Structured workbook cannot contain internal identities or placeholders", exception);
            }
            String trimmed = value.strip();
            if (trimmed.startsWith("{") || trimmed.startsWith("[") || trimmed.contains("```") || trimmed.contains("![")
                    || trimmed.matches("(?is).*https?://.*") || trimmed.matches("(?is).*\\b(?:candidateIds|groupAnchorId|documentId|unitId|[a-z]+_key|evidence_key)\\s*=.*")) {
                throw new WorkbookExportException("Structured workbook cannot contain raw JSON, Markdown, URLs, or internal evidence tokens");
            }
            for (String line : trimmed.split("\\R", -1)) if (line.strip().startsWith("|") && line.strip().endsWith("|")) {
                throw new WorkbookExportException("Structured workbook cannot contain raw JSON, Markdown, URLs, or internal evidence tokens");
            }
        }
    }

    private static String requiredSource(String sourceId) {
        if (sourceId == null || sourceId.isBlank()) throw new WorkbookExportException("Structured source identity must not be blank");
        return sourceId;
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
    private static String safe(String value) {
        if (value == null) return "";
        for (String line : value.split("\\R", -1)) {
            String candidate = line.replaceFirst("^[\\s\\uFEFF\\p{Cc}]+", "");
            if (candidate.startsWith("=") || candidate.startsWith("+") || candidate.startsWith("-") || candidate.startsWith("@")) {
                return "'" + value;
            }
        }
        return value;
    }
    private void verify(Path file) throws Exception { try (XSSFWorkbook book = new XSSFWorkbook(file.toFile())) {
        if (book.getNumberOfSheets() != SHEETS.size()) throw new WorkbookExportException("Markdown workbook structure is invalid");
        for (int index = 0; index < SHEETS.size(); index++) if (!SHEETS.get(index).equals(book.getSheetName(index))) throw new WorkbookExportException("Markdown workbook structure is invalid");
        headers(book.getSheetAt(0), AUDIT_HEADERS); headers(book.getSheetAt(1), CASE_HEADERS);
    }}
    private void verifyStructured(Path file) throws Exception { try (XSSFWorkbook book = new XSSFWorkbook(file.toFile())) {
        if (book.getNumberOfSheets() != SHEETS.size()) throw new WorkbookExportException("Structured workbook structure is invalid");
        for (int index = 0; index < SHEETS.size(); index++) if (!SHEETS.get(index).equals(book.getSheetName(index))) throw new WorkbookExportException("Structured workbook structure is invalid");
        headers(book.getSheetAt(0), STRUCTURED_AUDIT_HEADERS); headers(book.getSheetAt(1), STRUCTURED_CASE_HEADERS);
    }}
    private static void headers(Sheet sheet, List<String> expected) { Row header = sheet.getRow(0); if (header == null || header.getLastCellNum() != expected.size()) throw new WorkbookExportException("Markdown workbook structure is invalid"); for (int index = 0; index < expected.size(); index++) if (!expected.get(index).equals(header.getCell(index).getStringCellValue())) throw new WorkbookExportException("Markdown workbook structure is invalid"); }
    private static String sha256(byte[] data) throws Exception { byte[] hash = MessageDigest.getInstance("SHA-256").digest(data); StringBuilder value = new StringBuilder(); for (byte part : hash) value.append(String.format("%02x", part)); return value.toString(); }
}
