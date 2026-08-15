package com.testcaseagent.export;

import com.testcaseagent.markdown.MarkdownAuditRow;
import com.testcaseagent.markdown.MarkdownTestCaseRow;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** [Test-Ref]: MarkdownWorkbookExporterTest [Req-ID]: REQ-EXP-001, REQ-EXP-002, REQ-EXP-003, REQ-EXP-004, REQ-EXP-005, REQ-EXP-006, REQ-EXP-007 */
class MarkdownWorkbookExporterTest {

    @TempDir
    Path artifactRoot;

    @Test
    void exportsExactlyTwoOrderedSheetsWithAccumulatedRowsAndSafeWrappedText() throws Exception {
        MarkdownWorkbookExportRequest request = new MarkdownWorkbookExportRequest("task-1",
                List.of(new MarkdownAuditRow(2, "第二功能", "完整性", "第二证据"),
                        new MarkdownAuditRow(1, "+首个功能", "-一致性", "@首个证据")),
                List.of(new MarkdownTestCaseRow("=首个用例", "模块A", "前提A", "步骤一\n步骤二", "预期A", "需求A"),
                        new MarkdownTestCaseRow("用例二", "+模块B", "-前提B", "@步骤B", "预期B", "需求B")),
                true, false);

        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportMarkdown(request);

        assertThat(artifact.artifactId()).isNotBlank();
        assertThat(artifact.path()).startsWith(artifactRoot.toAbsolutePath().normalize());
        assertThat(artifact.path().getFileName().toString()).matches("[0-9a-f-]+\\.xlsx");
        assertThat(artifact.sha256()).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact.path()))));
        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(java.util.stream.IntStream.range(0, workbook.getNumberOfSheets()).mapToObj(workbook::getSheetName).toList())
                    .containsExactly("需求与功能清单审查发现", "测试用例");
            assertThat(headers(workbook, 0)).containsExactly("序号", "对象/功能点", "问题分类", "证据对照");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "功能模块", "前提约束", "执行步骤", "预期结果", "对应需求内容");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("第二功能");
            assertThat(workbook.getSheetAt(0).getRow(2).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).isEqualTo("'=首个用例");
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(1).getStringCellValue()).isEqualTo("'+模块B");
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(2).getStringCellValue()).isEqualTo("'-前提B");
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(3).getStringCellValue()).isEqualTo("'@步骤B");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(3).getStringCellValue()).isEqualTo("步骤一\n步骤二");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(3).getCellStyle().getWrapText()).isTrue();
            assertThat(workbook.getSheetAt(0).getPaneInformation().isFreezePane()).isTrue();
            assertThat(workbook.getSheetAt(1).getPaneInformation().isFreezePane()).isTrue();
            assertThat(workbook.getSheetAt(0).getColumnWidth(0)).isLessThan(workbook.getSheetAt(0).getColumnWidth(3));
            assertThat(workbook.getSheetAt(1).getColumnWidth(0)).isLessThan(workbook.getSheetAt(1).getColumnWidth(3));
            assertThat(workbook.getSheetAt(1).getColumnWidth(3)).isGreaterThan(0);
            assertThat(workbook.getFontAt(workbook.getSheetAt(1).getRow(0).getCell(0).getCellStyle().getFontIndex()).getBold()).isTrue();
            for (int sheetIndex = 0; sheetIndex < workbook.getNumberOfSheets(); sheetIndex++) {
                var sheet = workbook.getSheetAt(sheetIndex);
                for (int rowIndex = 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                    Row row = sheet.getRow(rowIndex);
                    for (int column = 0; column < row.getLastCellNum(); column++) {
                        assertThat(row.getCell(column).getCellStyle().getWrapText()).isTrue();
                        assertThat(row.getCell(column).getCellStyle().getVerticalAlignment()).isEqualTo(VerticalAlignment.TOP);
                    }
                }
            }
            assertThat(workbook.getAllPictures()).isEmpty();
            assertThat(workbook.getSheetAt(0).getDrawingPatriarch()).isNull();
            assertThat(workbook.getSheetAt(1).getDrawingPatriarch()).isNull();
        }
    }

    @Test
    void permitsValidatedPartialAcceptedRows() {
        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportMarkdown(new MarkdownWorkbookExportRequest("task-1",
                List.of(), List.of(new MarkdownTestCaseRow("用例", "模块", "前提", "步骤", "预期", "需求")), true, true));

        assertThat(artifact.path()).exists();
    }

    @Test
    void leavesAnEarlierArtifactIntactWhenAnotherTaskIsExported() {
        ApachePoiWorkbookExporter exporter = new ApachePoiWorkbookExporter(artifactRoot);
        MarkdownWorkbookExportRequest request = new MarkdownWorkbookExportRequest("task-1", List.of(),
                List.of(new MarkdownTestCaseRow("用例", "模块", "前提", "步骤", "预期", "需求")), true, false);
        WorkbookArtifact first = exporter.exportMarkdown(request);

        exporter.exportMarkdown(new MarkdownWorkbookExportRequest("task-2", List.of(),
                List.of(new MarkdownTestCaseRow("另一用例", "模块", "前提", "步骤", "预期", "需求")), true, false));

        assertThat(first.path()).exists();
    }

    @Test
    void rejectsUnvalidatedEmptyOrImageBearingRows() {
        ApachePoiWorkbookExporter exporter = new ApachePoiWorkbookExporter(artifactRoot);
        assertThatThrownBy(() -> exporter.exportMarkdown(new MarkdownWorkbookExportRequest("task-1", List.of(),
                List.of(new MarkdownTestCaseRow("用例", "模块", "前提", "步骤", "预期", "需求")), false, false)))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportMarkdown(new MarkdownWorkbookExportRequest("task-1", List.of(), List.of(), true, false)))
                .isInstanceOf(WorkbookExportException.class);
        assertThatThrownBy(() -> exporter.exportMarkdown(new MarkdownWorkbookExportRequest("task-1", List.of(),
                List.of(new MarkdownTestCaseRow("![截图](https://example.test/image.png)", "模块", "前提", "步骤", "预期", "需求")), true, false)))
                .isInstanceOf(WorkbookExportException.class);
    }

    private List<String> headers(XSSFWorkbook workbook, int sheetIndex) {
        return java.util.stream.IntStream.range(0, workbook.getSheetAt(sheetIndex).getRow(0).getLastCellNum())
                .mapToObj(index -> workbook.getSheetAt(sheetIndex).getRow(0).getCell(index).getStringCellValue()).toList()
                ;
    }
}
