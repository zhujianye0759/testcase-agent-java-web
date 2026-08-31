package com.testcaseagent.validation;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Safe, enumerated business-validation diagnostic that never carries rejected source or model text.
 *
 * <p>The field path is intentionally limited to JSON-style property names and numeric indexes. Messages are
 * derived from the enum rather than accepted from callers. [Req-ID]: REQ-FSC-007</p>
 */
public final class StructuredValidationFailure {
    private static final Pattern SAFE_PATH = Pattern.compile(
            "\\$(?:(?:\\.[a-z][a-z0-9_]*)|(?:\\[[0-9]+]))*");
    private final Code type;
    private final String path;

    private StructuredValidationFailure(Code type, String path) {
        this.type = Objects.requireNonNull(type, "type must not be null");
        if (!isSafePath(path)) {
            throw new IllegalArgumentException("Validation failure path is not safe");
        }
        this.path = path;
    }

    /** Creates one diagnostic from the fixed code/message catalog and a bounded safe field path. */
    public static StructuredValidationFailure of(Code type, String path) {
        return new StructuredValidationFailure(type, path);
    }

    /** Returns whether a stored path is a bounded JSON-style property/index path. */
    public static boolean isSafePath(String path) {
        return path != null && path.length() <= 512 && SAFE_PATH.matcher(path).matches();
    }

    /** Stable storage and wire code. */
    public String code() {
        return type.name();
    }

    /** Safe JSON-style location of the rejected field. */
    public String path() {
        return path;
    }

    /** Fixed reader-safe Chinese explanation. */
    public String message() {
        return type.message;
    }

    @Override
    public String toString() {
        return code() + "@" + path + ":" + message();
    }

    /** Fixed diagnostic catalog for the requirement-review acceptance boundary. */
    public enum Code {
        REVIEW_RESULT_INVALID("需求材料审查结果结构不符合业务约束"),
        REVIEW_FIELD_REQUIRED("需求材料审查结果缺少必填字段"),
        REVIEW_KEY_DUPLICATE("需求材料审查结果包含重复业务键"),
        REVIEW_READER_TEXT_UNSAFE("面向读者的文本包含内部标识或占位符"),
        REVIEW_FACT_SUPPLEMENTARY_SOURCE("补充材料不能独立形成正式需求事实"),
        REVIEW_FACT_EVIDENCE_REQUIRED("正式需求事实缺少引用材料证据"),
        REVIEW_EVIDENCE_DUPLICATE("需求材料审查结果包含重复证据引用"),
        REVIEW_EVIDENCE_OUT_OF_SLICE("引用证据不属于当前材料切片"),
        REVIEW_FACT_DIRECT_EVIDENCE_UNSUPPORTED("正式需求事实未在引用材料单元中直接出现"),
        REVIEW_FINDING_HANDLING_LEVEL_REQUIRED("需求审查发现缺少处理级别"),
        REVIEW_FINDING_ROOT_CAUSE_DUPLICATE("同一批审查结果包含重复根因"),
        REVIEW_FINDING_ROOT_CAUSE_REQUIRED("需求审查发现缺少冻结根因"),
        REVIEW_FINDING_AFFECTED_SCOPE_INVALID("需求审查发现的影响范围无效"),
        REVIEW_FINDING_CHINESE_ANALYSIS_REQUIRED("需求审查分析字段必须包含中文说明"),
        REVIEW_FINDING_BAD_SOURCE_INVALID("需求审查坏例不是引用材料中的连续原文"),
        REVIEW_FINDING_PENDING_PROPOSAL_INVALID("需求审查建议好例必须明确待需求方确认"),
        STRUCTURED_COORDINATOR_ARGUMENT_FAILURE("结构化任务在参数处理阶段失败"),
        STRUCTURED_COORDINATOR_STATE_FAILURE("结构化任务在状态处理阶段失败"),
        STRUCTURED_COORDINATOR_CONCURRENCY_FAILURE("结构化任务在并发控制阶段失败"),
        STRUCTURED_COORDINATOR_DEPENDENCY_FAILURE("结构化任务在依赖调用阶段失败"),
        STRUCTURED_COORDINATOR_UNEXPECTED_FAILURE("结构化任务在未预期阶段失败"),
        RECONCILIATION_V2_PLANNING_INVALID("功能范围全量核对计划与已保存来源不一致"),
        RECONCILIATION_V2_RESULT_INVALID("功能范围全量核对结果未通过任务级闭合校验"),
        FACT_RESULT_ECHO_INVALID("需求事实结果未回显当前功能和窗口"),
        FACT_EVIDENCE_OUT_OF_SCOPE("需求事实引用超出当前材料窗口"),
        FACT_QUOTE_NOT_GROUNDED("需求事实引文不是对应解析单元的连续原文"),
        FACT_DIRECT_EVIDENCE_UNSUPPORTED("需求事实正文未由任一引用材料单元直接支撑"),
        FACT_ATOMICITY_INVALID("需求事实包含多个应独立保存的业务断言"),
        FACT_DUPLICATE("需求事实窗口包含重复事实"),
        TESTCASE_RESULT_ECHO_INVALID("测试用例结果未回显当前功能和测试点"),
        TESTCASE_OUTCOME_INCONSISTENT("测试用例生成结果与正式、待确认或无法生成状态不一致"),
        TESTCASE_FACT_OUT_OF_SCOPE("测试用例引用了当前测试点以外的需求事实"),
        TESTCASE_EVIDENCE_CLOSURE_INVALID("测试用例证据未与所选需求事实闭合"),
        TESTCASE_UNSUPPORTED_BUSINESS_DETAIL("测试用例增加了需求事实未支持的业务细节"),
        TESTCASE_EXPECTED_ORDER_INVALID("测试用例整体预期未按步骤顺序逐项对应");

        private final String message;

        Code(String message) {
            this.message = message;
        }
    }
}
