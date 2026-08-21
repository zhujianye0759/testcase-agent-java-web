package com.testcaseagent.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Tests the direct structured-record workbook path. [Req-ID]: REQ-SGD-003, REQ-SGD-004 */
class StructuredWorkbookExporterTest {

    @TempDir
    Path artifactRoot;

    @Test
    void exportsOnlyValidatedStructuredRowsWithTwoSheetsAndVisibleStatus() throws Exception {
        StructuredWorkbookExportRequest request = new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-key-2", 2, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                        "订单查询", "ambiguous", "补充登录超时处理", true),
                new StructuredReviewRow("finding-key-1", 1, StructuredReviewRow.Source.FEATURE_RECONCILIATION,
                        "订单查询", "conflict", "范围待确认", true)),
                List.of(
                        testcase("case-source-2", "待确认候选", StructuredTestCaseRow.Status.PENDING_CONFIRMATION),
                        testcase("case-source-1", "正式用例", StructuredTestCaseRow.Status.FORMAL)));

        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportStructured(request);

        assertThat(artifact.sha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.path()))));
        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(java.util.stream.IntStream.range(0, workbook.getNumberOfSheets()).mapToObj(workbook::getSheetName).toList())
                    .containsExactly("需求与功能清单审查发现", "测试用例");
            assertThat(headers(workbook, 0)).containsExactly("序号", "来源", "对象/功能点", "问题分类/核对结论", "说明");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "功能模块", "状态", "前提约束", "执行步骤", "预期结果", "对应需求内容", "缺失信息");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("功能核对");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("订单查询");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(4).getStringCellValue()).doesNotContain("finding-key-1");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(2).getStringCellValue()).isEqualTo("formal");
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(2).getStringCellValue()).isEqualTo("pending_confirmation");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(4).getStringCellValue()).isEqualTo("1. 提交订单");
        }
    }

    @Test
    void exportsAndVerifiesTheFixedTwoSheetsWhenNoBusinessRowsWereGenerated() throws Exception {
        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportStructured(
                new StructuredWorkbookExportRequest("task-empty", List.of(), List.of()));

        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(workbook.getNumberOfSheets()).isEqualTo(2);
            assertThat(java.util.stream.IntStream.range(0, workbook.getNumberOfSheets())
                    .mapToObj(workbook::getSheetName).toList())
                    .containsExactly("需求与功能清单审查发现", "测试用例");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isZero();
            assertThat(workbook.getSheetAt(1).getLastRowNum()).isZero();
            assertThat(headers(workbook, 0)).containsExactly("序号", "来源", "对象/功能点", "问题分类/核对结论", "说明");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "功能模块", "状态", "前提约束", "执行步骤", "预期结果", "对应需求内容", "缺失信息");
        }
    }

    @Test
    void rejectsDuplicateReviewAndTestcaseSourceIdentitiesInsteadOfSilentlyDroppingRows() {
        ApachePoiWorkbookExporter exporter = new ApachePoiWorkbookExporter(artifactRoot);
        StructuredReviewRow review = new StructuredReviewRow("review-1", 1,
                StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW, "订单", "issue", "说明", true);

        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-review-duplicate",
                List.of(review, new StructuredReviewRow("review-1", 2,
                        StructuredReviewRow.Source.FEATURE_RECONCILIATION, "另一个订单", "conflict", "不应丢行", true)),
                List.of(testcase("case-1", "正式用例", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class)
                .hasMessageContaining("duplicate");

        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-case-duplicate",
                List.of(review), List.of(
                        testcase("case-1", "正式用例", StructuredTestCaseRow.Status.FORMAL),
                        testcase("case-1", "另一个正式用例", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void rejectsUnvalidatedAndMachineOrPresentationUnsafeStructuredRecords() {
        ApachePoiWorkbookExporter exporter = new ApachePoiWorkbookExporter(artifactRoot);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(),
                List.of(new StructuredTestCaseRow("case-1", "title", "功能", StructuredTestCaseRow.Status.FORMAL, List.of(),
                        List.of(new StructuredTestStep(1, "action", "expected")), List.of("requirement"), List.of(), false)))))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-1", 1, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                        "功能", "issue", "evidence_key=internal", true)), List.of(testcase("case-1", "title", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-1", 1, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                        "功能", "issue", "{\"raw\":true}", true)), List.of(testcase("case-1", "title", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-1", 1, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                        "功能", "issue", "| Markdown |", true)), List.of(testcase("case-1", "title", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-1", 1, StructuredReviewRow.Source.FEATURE_RECONCILIATION,
                        "功能", "exact_match", "合并 fli-bc5dafcd3684fbf0005736a8110f1ef6adc1af19c63a3e8728e992cb534d0b95", true)),
                List.of(testcase("case-1", "title", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportStructured(new StructuredWorkbookExportRequest("task-1", List.of(
                new StructuredReviewRow("finding-1", 1, StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW,
                        "功能", "issue", "账号被禁用<internal-path>", true)),
                List.of(testcase("case-1", "title", StructuredTestCaseRow.Status.FORMAL)))))
                .isInstanceOf(WorkbookExportException.class);
    }

    private static StructuredTestCaseRow testcase(String sourceId, String title, StructuredTestCaseRow.Status status) {
        return new StructuredTestCaseRow(sourceId, title, "订单查询", status, List.of("已登录"),
                List.of(new StructuredTestStep(1, "提交订单", "订单提交成功")), List.of("订单提交需求"),
                status == StructuredTestCaseRow.Status.PENDING_CONFIRMATION ? List.of("等待产品确认") : List.of(), true);
    }

    private static List<String> headers(XSSFWorkbook workbook, int sheetIndex) {
        return java.util.stream.IntStream.range(0, workbook.getSheetAt(sheetIndex).getRow(0).getLastCellNum())
                .mapToObj(index -> workbook.getSheetAt(sheetIndex).getRow(0).getCell(index).getStringCellValue()).toList();
    }
}
