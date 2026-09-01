package com.testcaseagent.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Public contract tests for independently reviewed V2 functions and pending test points. [Req-ID]: REQ-TGV2-016 */
class ApprovedFunctionScopeTest {

    @Test
    void freezesOrderedPendingPointsAndReadsAnOlderSnapshotAsEmpty() throws Exception {
        var point = point("point-b", "function-a");
        var scope = new ApprovedFunctionScope("scope-3", List.of(function("function-a")), List.of(point));

        assertThat(scope.testPoints()).containsExactly(point);

        ApprovedFunctionScope historical = new ObjectMapper().readValue("""
                {"scopeVersion":"scope-2","functions":[{
                  "functionKey":"function-a","name":"提交申请","path":"业务/提交申请","description":""
                }]}
                """, ApprovedFunctionScope.class);
        assertThat(historical.testPoints()).isEmpty();
    }

    @Test
    void rejectsDuplicateUnknownOrNonPendingReviewedPoints() {
        var function = function("function-a");
        var point = point("point-a", "function-a");

        assertThatThrownBy(() -> new ApprovedFunctionScope("scope", List.of(function), List.of(point, point)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unique");
        assertThatThrownBy(() -> new ApprovedFunctionScope("scope", List.of(function),
                List.of(point("point-x", "function-other"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("function");
        assertThatThrownBy(() -> new ApprovedFunctionScope("scope", List.of(function), List.of(
                new ApprovedFunctionScope.ApprovedTestPoint("point-x", "function-a",
                        ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                        ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                        ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                        "待确认时限", List.of()))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missingInformation");
    }

    @Test
    void rejectsUnknownSourceStatusAndTextBeyondTheFrozenBoundary() {
        ObjectMapper mapper = new ObjectMapper();
        String invalidEnumSnapshot = """
                {"scopeVersion":"scope","functions":[{
                  "functionKey":"function-a","name":"提交申请","path":"业务/提交申请","description":""
                }],"testPoints":[{
                  "testPointKey":"point-a","functionKey":"function-a","type":"NORMAL_BEHAVIOR",
                  "source":"UNREVIEWED_MODEL","status":"PENDING_CONFIRMATION",
                  "description":"待确认时限","missingInformation":["缺少时限"]
                }]}
                """;

        assertThatThrownBy(() -> mapper.readValue(invalidEnumSnapshot, ApprovedFunctionScope.class))
                .isInstanceOf(com.fasterxml.jackson.databind.JsonMappingException.class);
        assertThatThrownBy(() -> new ApprovedFunctionScope("scope", List.of(function("function-a")), List.of(
                new ApprovedFunctionScope.ApprovedTestPoint("point-a", "function-a",
                        ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                        ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                        ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                        "x".repeat(16_385), List.of("缺少时限")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("description");
    }

    private static ApprovedFunctionScope.ApprovedFunction function(String key) {
        return new ApprovedFunctionScope.ApprovedFunction(key, "提交申请", "业务/提交申请", "");
    }

    private static ApprovedFunctionScope.ApprovedTestPoint point(String key, String functionKey) {
        return new ApprovedFunctionScope.ApprovedTestPoint(key, functionKey,
                ApprovedFunctionScope.ApprovedTestPointType.NORMAL_BEHAVIOR,
                ApprovedFunctionScope.ApprovedTestPointSource.GENERAL_EXPERIENCE,
                ApprovedFunctionScope.ApprovedTestPointStatus.PENDING_CONFIRMATION,
                "验证到期提醒", List.of("提醒提前量尚未确认"));
    }
}
