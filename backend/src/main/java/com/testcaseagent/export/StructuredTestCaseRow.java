package com.testcaseagent.export;

import java.util.List;

/** One already-validated structured testcase business projection. [Req-ID]: REQ-SGD-003, REQ-SGD-004 */
public record StructuredTestCaseRow(
        String sourceId, String name, String title, String functionName, Priority priority, Status status,
        List<String> preconditions, Initialization initialization, List<TestInput> inputs,
        List<StructuredTestStep> steps, List<String> expectedResults, String evaluationCriteria,
        String resultEvaluationCriteria, List<String> terminationConditions, String resultCollection,
        AuthoringInformation authoringInformation, List<String> requirementSummaries,
        List<String> missingInformation, boolean validated) {

    /** Formal coverage status displayed exactly as stored by Java. */
    public enum Status {
        FORMAL("正式依据"), PENDING_CONFIRMATION("待确认候选");
        private final String display;
        Status(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum Priority {
        HIGH("高"), MEDIUM("中"), LOW("低");
        private final String display;
        Priority(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum InputNature {
        VALID("有效"), INVALID("无效"), BOUNDARY("边界值"), OTHER("其他"), UNSPECIFIED("未指定");
        private final String display;
        InputNature(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum InputSource {
        MANUAL("人工输入"), PROGRAM("程序生成"), FILE("文件输入"), SIMULATION("仿真输入"), OTHER("其他"), UNSPECIFIED("未指定");
        private final String display;
        InputSource(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum TestMethod {
        EQUIVALENCE_PARTITIONING("等价类划分"), BOUNDARY_VALUE_ANALYSIS("边界值分析"), ERROR_GUESSING("错误推测"),
        CAUSE_EFFECT_GRAPH("因果图"), OTHER("其他"), UNSPECIFIED("未指定");
        private final String display;
        TestMethod(String display) { this.display = display; }
        public String display() { return display; }
    }

    public enum Authenticity {
        REAL("真实数据"), SIMULATED("模拟数据"), UNSPECIFIED("未指定");
        private final String display;
        Authenticity(String display) { this.display = display; }
        public String display() { return display; }
    }

    /** Copies nested collections before export sorting and rendering. */
    public StructuredTestCaseRow {
        if (preconditions == null || initialization == null || inputs == null || steps == null
                || expectedResults == null || terminationConditions == null || authoringInformation == null
                || requirementSummaries == null || missingInformation == null) {
            throw new IllegalArgumentException("Structured testcase collections must not be null");
        }
        preconditions = List.copyOf(preconditions);
        inputs = List.copyOf(inputs);
        steps = List.copyOf(steps);
        expectedResults = List.copyOf(expectedResults);
        terminationConditions = List.copyOf(terminationConditions);
        requirementSummaries = List.copyOf(requirementSummaries);
        missingInformation = List.copyOf(missingInformation);
    }

    /** Backward-compatible projection for persisted V12 rows. */
    public StructuredTestCaseRow(String sourceId, String title, String functionName, Status status,
            List<String> preconditions, List<StructuredTestStep> steps, List<String> requirementSummaries,
            List<String> missingInformation, boolean validated) {
        this(sourceId, title, title, functionName, Priority.MEDIUM, status, preconditions, Initialization.empty(),
                List.of(), steps, steps.stream().map(StructuredTestStep::expected).toList(), "", "", List.of(), "",
                AuthoringInformation.empty(), requirementSummaries, missingInformation, validated);
    }

    public record Initialization(List<String> hardwareConfiguration, List<String> softwareConfiguration,
            List<String> testConfiguration, List<String> parameterConfiguration) {
        public Initialization {
            hardwareConfiguration = List.copyOf(hardwareConfiguration);
            softwareConfiguration = List.copyOf(softwareConfiguration);
            testConfiguration = List.copyOf(testConfiguration);
            parameterConfiguration = List.copyOf(parameterConfiguration);
        }
        public static Initialization empty() { return new Initialization(List.of(), List.of(), List.of(), List.of()); }
    }

    public record TestInput(String content, InputNature nature, InputSource source, TestMethod method,
            Authenticity authenticity, String sequence) { }

    public record AuthoringInformation(String author, String date) {
        public static AuthoringInformation empty() { return new AuthoringInformation("", ""); }
    }
}
