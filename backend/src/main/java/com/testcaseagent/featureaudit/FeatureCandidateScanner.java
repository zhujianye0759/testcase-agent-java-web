package com.testcaseagent.featureaudit;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * Converts one retained function-list unit into source occurrences after strict reconciliation-table validation.
 *
 * <p>This class neither reads Office files nor calls an agent. Its stable IDs use material coordinates, pass, and
 * response row position so a repeated visible sequence or repeated feature text remains traceable.</p>
 *
 * [Req-ID]: REQ-BFA-001, REQ-BFA-004
 */
public final class FeatureCandidateScanner {
    private final CandidateMarkdownParser parser = new CandidateMarkdownParser();

    /** Builds the fixed bounded request for exactly one FUNCTION_LIST material unit. */
    public String promptFor(MaterialInventoryUnit unit) {
        requireFunctionList(unit);
        return promptPrefix(unit, "从功能清单中逐行提取候选功能。保留重复展示的序号和文本，不要去重。若同一行的相邻列具有层级语义，按列顺序将非空值组成一个业务路径；不同层级值不是冲突。无表头或层级语义不足时标记证据不足。")
                + "只返回下列精确两张 Markdown 表；第一表可为零行，第二表必须为零行。\n"
                + tableContract() + unitContent(unit);
    }

    /** Validates one function-list pass and returns all source occurrences in response-row order. */
    public FeatureCandidateScanResult accept(MaterialInventoryUnit unit, int passNumber, String markdown) {
        requireFunctionList(unit);
        if (passNumber != 1) throw new IllegalArgumentException("FUNCTION_LIST accepts exactly one pass");
        List<FeatureSourceCandidate> candidates = parser.parse(markdown).stream()
                .map(row -> candidate(unit, FeatureCandidateKind.FUNCTION_LIST, passNumber, row))
                .toList();
        return new FeatureCandidateScanResult(candidates, true);
    }

    static FeatureSourceCandidate candidate(
            MaterialInventoryUnit unit, FeatureCandidateKind kind, int passNumber, CandidateMarkdownParser.AuditRow row) {
        CandidateMarkdownParser.requireExactEvidenceCoordinates(row.evidenceText(), unit);
        return new FeatureSourceCandidate(occurrenceId(unit, passNumber, row.rowPosition()), kind, unit.documentId(),
                unit.unitId(), unit.ordinal(), row.sequence(), row.featureText(), row.category(), row.evidenceText(),
                passNumber, row.rowPosition());
    }

    static String occurrenceId(MaterialInventoryUnit unit, int passNumber, int rowPosition) {
        String coordinate = unit.documentId() + "\u001f" + unit.unitId() + "\u001f" + unit.ordinal() + "\u001f"
                + passNumber + "\u001f" + rowPosition;
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(coordinate.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    static void requireFunctionList(MaterialInventoryUnit unit) {
        if (unit == null || !"FUNCTION_LIST".equals(unit.documentRole())) {
            throw new IllegalArgumentException("Feature candidate scanning requires a FUNCTION_LIST material unit");
        }
    }

    static String promptPrefix(MaterialInventoryUnit unit, String instruction) {
        return "仅处理一个材料单元；不得使用示例、不得读取其他文档、不得推断单元外内容。\n"
                + "documentId=" + unit.documentId() + "; unitId=" + unit.unitId() + "; ordinal=" + unit.ordinal() + "\n"
                + "每个非空候选行的证据对照必须原样包含上述 documentId 和 unitId。\n"
                + instruction + "\n";
    }

    static String tableContract() {
        return "第一表每个非空数据行必须且只能四列；第三列只能填写问题分类。第四列必须以 `documentId=<exact>; unitId=<exact>; ` 开头，"
                + "第二个 token 后必须紧跟分号和证据正文；不得用 <br> 紧接 unitId。第二表必须为零数据行。\n"
                + "## 需求与功能清单审查发现\n| 序号 | 对象/功能点 | 问题分类 | 证据对照 |\n|---|---|---|---|\n"
                + "## 测试用例\n| 用例名称 | 功能模块 | 前提约束 | 执行步骤 | 预期结果 | 对应需求内容 |\n|---|---|---|---|---|---|\n";
    }

    static String unitContent(MaterialInventoryUnit unit) {
        return "材料单元内容开始\n" + unit.content() + "\n材料单元内容结束\n";
    }
}
