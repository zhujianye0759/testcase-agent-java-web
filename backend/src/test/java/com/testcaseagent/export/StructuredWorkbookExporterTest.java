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
            assertThat(headers(workbook, 0)).containsExactly("序号", "来源", "对象/功能点", "问题分类/核对结论", "影响范围",
                    "说明", "实际坏例", "建议好例（待需求方确认）", "测试设计影响", "当前项目建议", "设计中心建议", "严重程度", "证据来源");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "用例标题", "功能模块", "优先级", "状态", "前提约束",
                    "硬件初始化", "软件初始化", "测试初始化", "参数初始化", "测试输入", "执行步骤", "逐步预期", "逐步评价",
                    "异常或终止提示", "逐步结果采集", "总体预期", "执行评价标准", "结果评价标准", "终止条件", "结果采集",
                    "编写人", "编写日期", "对应需求内容", "缺失信息");
            assertThat(workbook.getSheetAt(0).getLastRowNum()).isEqualTo(2);
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(0).getStringCellValue()).isEqualTo("1");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(1).getStringCellValue()).isEqualTo("功能核对");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(2).getStringCellValue()).isEqualTo("订单查询");
            assertThat(workbook.getSheetAt(0).getRow(1).getCell(5).getStringCellValue()).doesNotContain("finding-key-1");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(4).getStringCellValue()).isEqualTo("正式依据");
            assertThat(workbook.getSheetAt(1).getRow(2).getCell(4).getStringCellValue()).isEqualTo("待确认候选");
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(11).getStringCellValue()).isEqualTo("1. 提交订单");
        }
    }

    @Test
    void exportsFrozenHighGranularityFieldsWithChineseBusinessLabelsAndNoMachineEnums() throws Exception {
        StructuredReviewRow review = new StructuredReviewRow("internal-review-id", 1,
                StructuredReviewRow.Source.REQUIREMENT_MATERIAL_REVIEW, "账号登录", "需求描述含糊",
                "账号状态约束未说明", "账号登录材料范围", "原文未说明账号禁用后的处理",
                "待需求方确认：补充账号禁用后的处理规则", "影响异常场景设计", "本项目先标记待确认",
                "设计中心补充状态模板", "继续执行但信息不完整", "需求规格", true);
        StructuredTestCaseRow testcase = new StructuredTestCaseRow("internal-case-id", "账号登录正常场景",
                "验证账号登录", "用户中心→账号登录", StructuredTestCaseRow.Priority.HIGH,
                StructuredTestCaseRow.Status.FORMAL, List.of("已注册且状态正常的用户"),
                new StructuredTestCaseRow.Initialization(List.of("普通办公电脑"), List.of("浏览器"),
                        List.of("测试环境"), List.of("账号已准备")),
                List.of(new StructuredTestCaseRow.TestInput("账号和正确密码", StructuredTestCaseRow.InputNature.VALID,
                        StructuredTestCaseRow.InputSource.MANUAL, StructuredTestCaseRow.TestMethod.EQUIVALENCE_PARTITIONING,
                        StructuredTestCaseRow.Authenticity.SIMULATED, "先账号后密码")),
                List.of(new StructuredTestStep(1, "提交账号和正确密码", "系统进入首页并显示当前用户名称",
                        "实际结果满足本步骤预期结果。", "", "记录实际结果、提示信息及必要证据。")),
                List.of("系统进入首页并显示当前用户名称"), "满足前提和约束且未触发终止条件，逐步执行并记录结果。",
                "全部预期结果满足则通过，任一不满足则不通过。", List.of("系统服务终止，或执行过程中无法执行下一步操作。"),
                "记录实际结果、提示信息及必要证据。", new StructuredTestCaseRow.AuthoringInformation("测试人员", "2026-08-22"),
                List.of("账号登录需求"), List.of(), true);

        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportStructured(
                new StructuredWorkbookExportRequest("task-high-granularity", List.of(review), List.of(testcase)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(headers(workbook, 0)).containsExactly("序号", "来源", "对象/功能点", "问题分类/核对结论", "影响范围",
                    "说明", "实际坏例", "建议好例（待需求方确认）", "测试设计影响", "当前项目建议", "设计中心建议", "严重程度", "证据来源");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "用例标题", "功能模块", "优先级", "状态", "前提约束",
                    "硬件初始化", "软件初始化", "测试初始化", "参数初始化", "测试输入", "执行步骤", "逐步预期", "逐步评价",
                    "异常或终止提示", "逐步结果采集", "总体预期", "执行评价标准", "结果评价标准", "终止条件", "结果采集",
                    "编写人", "编写日期", "对应需求内容", "缺失信息");
            String auditRow = java.util.stream.IntStream.range(0, 13)
                    .mapToObj(index -> workbook.getSheetAt(0).getRow(1).getCell(index).getStringCellValue())
                    .collect(java.util.stream.Collectors.joining("|"));
            String caseRow = java.util.stream.IntStream.range(0, 25)
                    .mapToObj(index -> workbook.getSheetAt(1).getRow(1).getCell(index).getStringCellValue())
                    .collect(java.util.stream.Collectors.joining("|"));
            assertThat(caseRow).contains("高", "正式依据", "有效", "人工输入", "等价类划分", "模拟数据")
                    .doesNotContain("HIGH", "FORMAL", "VALID", "MANUAL", "EQUIVALENCE_PARTITIONING", "SIMULATED")
                    .doesNotContain("internal-case-id");
            assertThat(auditRow).doesNotContain("internal-review-id");
        }
    }

    @Test
    void preventsFormulaInjectionAfterWhitespaceTabBomAndOnEveryStructuredLine() throws Exception {
        StructuredTestCaseRow row = testcase("case-formula", " =1+1",
                StructuredTestCaseRow.Status.FORMAL);
        WorkbookArtifact artifact = new ApachePoiWorkbookExporter(artifactRoot).exportStructured(
                new StructuredWorkbookExportRequest("task-formula", List.of(), List.of(row)));

        try (XSSFWorkbook workbook = new XSSFWorkbook(artifact.path().toFile())) {
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getCellType()).isEqualTo(CellType.STRING);
            assertThat(workbook.getSheetAt(1).getRow(1).getCell(0).getStringCellValue()).startsWith("'");
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
            assertThat(headers(workbook, 0)).containsExactly("序号", "来源", "对象/功能点", "问题分类/核对结论", "影响范围",
                    "说明", "实际坏例", "建议好例（待需求方确认）", "测试设计影响", "当前项目建议", "设计中心建议", "严重程度", "证据来源");
            assertThat(headers(workbook, 1)).containsExactly("用例名称", "用例标题", "功能模块", "优先级", "状态", "前提约束",
                    "硬件初始化", "软件初始化", "测试初始化", "参数初始化", "测试输入", "执行步骤", "逐步预期", "逐步评价",
                    "异常或终止提示", "逐步结果采集", "总体预期", "执行评价标准", "结果评价标准", "终止条件", "结果采集",
                    "编写人", "编写日期", "对应需求内容", "缺失信息");
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
