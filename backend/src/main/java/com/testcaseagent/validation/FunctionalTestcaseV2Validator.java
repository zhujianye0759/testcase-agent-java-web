package com.testcaseagent.validation;

import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Input;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.CaseStatus;
import com.testcaseagent.knowledgeagent.FunctionalTestcaseDesignV2Result.GenerationOutcome;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates V2 generation outcomes and evidence closure before Java assigns durable case identities.
 * Pending and unable outcomes are accepted as explicit non-formal results rather than task failures.
 *
 * [Req-ID]: REQ-TGV2-005, REQ-TGV2-006, REQ-TGV2-008, REQ-TGV2-014
 */
public final class FunctionalTestcaseV2Validator {

    private static final List<String> PROTECTED_BUSINESS_MARKERS = List.of(
            "管理员", "操作员", "审核员", "审批员", "经办人", "审核人", "审批人", "用户", "角色",
            "专责", "主管", "经理", "账号", "账户", "用户名", "密码", "口令", "浏览器", "操作系统",
            "windows", "linux", "chrome", "edge", "firefox", "硬件", "cpu", "内存", "测试环境",
            "生产环境", "网络环境", "接口", "api", "url", "端口", "资源", "待审核", "已审核",
            "已完成", "已提交", "锁定", "启用", "禁用", "成功", "失败", "异常", "错误码", "超时");
    private static final Pattern QUANTIFIED_DETAIL = Pattern.compile(
            "(?:不超过|不少于|小于|大于|等于|<=|>=|<|>)?\\d+(?:\\.\\d+)?(?:毫秒|秒|分钟|小时|次|条|个|天|%|％|mb|gb)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ERROR_CODE = Pattern.compile("(?i)(?<![a-z0-9])[a-z]{1,6}-?\\d{2,}(?![a-z0-9])");
    private static final Set<String> GENERIC_TEST_LANGUAGE = Set.of(
            "实际结果符合预期", "全部步骤符合预期", "任一步失败则不通过", "符合预期", "不符合预期",
            "记录结果", "记录实际结果", "收集测试结果");
    private static final List<String> GENERIC_PREFIXES = List.of("验证", "检查", "确认", "执行", "操作");
    private static final List<String> GENERIC_SUFFIXES = List.of("正常场景", "异常场景", "测试场景", "测试用例", "用例");

    /** Validates one complete test-point result and returns only publishable Java-owned candidates. */
    public AcceptedDesign validate(FunctionalTestcaseDesignV2Input input,
            FunctionalTestcaseDesignV2Result result) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!input.functionKey().equals(result.functionKey())
                || !input.testPoint().testPointKey().equals(result.testPointKey())) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_RESULT_ECHO_INVALID, "$");
        }
        validateOutcome(result);
        Map<String, FunctionalTestcaseDesignV2Input.RequirementFact> facts = new LinkedHashMap<>();
        input.requirementFacts().forEach(fact -> facts.put(fact.factKey(), fact));
        List<AcceptedTestcase> accepted = new java.util.ArrayList<>();
        Set<String> caseKeys = new HashSet<>();
        boolean formalCoverage = false;
        for (int index = 0; index < result.testcases().size(); index++) {
            var candidate = result.testcases().get(index);
            validateReaderShape(candidate, index);
            List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts = selectedFacts(
                    candidate.requirementFactKeys(), facts, index);
            validateEvidenceClosure(candidate.evidenceKeys(), selectedFacts, index);
            validateBusinessGrounding(candidate, input, selectedFacts, index);
            if (candidate.caseStatus() == CaseStatus.FORMAL) {
                if (input.testPoint().basis() != FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT
                        || selectedFacts.isEmpty() || candidate.evidenceKeys().isEmpty()
                        || !candidate.missingInformation().isEmpty()) {
                    throw failure(StructuredValidationFailure.Code.TESTCASE_OUTCOME_INCONSISTENT,
                            "$.testcases[" + index + "]");
                }
                formalCoverage = true;
            } else if (candidate.missingInformation().isEmpty()) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_OUTCOME_INCONSISTENT,
                        "$.testcases[" + index + "].missing_information");
            }
            FunctionalTestcaseDesignV2Result.Testcase publishable = normalizedMetadata(candidate);
            String caseKey = caseKey(input.functionKey(), input.testPoint().testPointKey(), publishable);
            if (!caseKeys.add(caseKey)) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_OUTCOME_INCONSISTENT,
                        "$.testcases[" + index + "]");
            }
            accepted.add(new AcceptedTestcase(caseKey, publishable));
        }
        return new AcceptedDesign(result.generationOutcome(), result.missingInformation(),
                List.copyOf(accepted), formalCoverage);
    }

    private static void validateOutcome(FunctionalTestcaseDesignV2Result result) {
        boolean nonempty = !result.testcases().isEmpty();
        boolean formal = result.testcases().stream().anyMatch(value -> value.caseStatus() == CaseStatus.FORMAL);
        Set<String> pendingMissing = result.testcases().stream()
                .filter(value -> value.caseStatus() == CaseStatus.PENDING_CONFIRMATION)
                .flatMap(value -> value.missingInformation().stream())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Set<String> outcomeMissing = new LinkedHashSet<>(result.missingInformation());
        if (result.generationOutcome() == GenerationOutcome.GENERATED && (!nonempty || !formal)
                || result.generationOutcome() == GenerationOutcome.PENDING_ONLY
                        && (!nonempty || result.testcases().stream().anyMatch(
                                value -> value.caseStatus() != CaseStatus.PENDING_CONFIRMATION))
                || result.generationOutcome() == GenerationOutcome.UNABLE_TO_GENERATE
                        && (nonempty || result.missingInformation().isEmpty())
                || result.generationOutcome() != GenerationOutcome.UNABLE_TO_GENERATE
                        && !outcomeMissing.equals(pendingMissing)) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_OUTCOME_INCONSISTENT, "$");
        }
    }

    /**
     * The frozen result does not carry an audited rule for execution metadata. Java therefore publishes neutral
     * values instead of presenting model-selected priority, source, method, nature or authenticity as facts.
     */
    private static FunctionalTestcaseDesignV2Result.Testcase normalizedMetadata(
            FunctionalTestcaseDesignV2Result.Testcase candidate) {
        List<FunctionalTestcaseDesignV2Result.Input> inputs = candidate.inputs().stream()
                .map(value -> new FunctionalTestcaseDesignV2Result.Input(value.content(),
                        FunctionalTestcaseDesignV2Result.InputNature.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.InputSource.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.TestMethod.UNSPECIFIED,
                        FunctionalTestcaseDesignV2Result.Authenticity.UNSPECIFIED, value.sequence()))
                .toList();
        return new FunctionalTestcaseDesignV2Result.Testcase(candidate.name(), candidate.title(),
                FunctionalTestcaseDesignV2Result.Priority.MEDIUM, candidate.preconditions(),
                candidate.initialization(), inputs, candidate.steps(), candidate.expectedResults(),
                candidate.evaluationCriteria(), candidate.resultEvaluationCriteria(), candidate.terminationConditions(),
                candidate.resultCollection(), candidate.requirementFactKeys(), candidate.evidenceKeys(),
                candidate.caseStatus(), candidate.missingInformation());
    }

    private static void validateReaderShape(FunctionalTestcaseDesignV2Result.Testcase candidate, int index) {
        ReaderFacingTextPolicy.requireSafe(candidate.name(), "case name");
        ReaderFacingTextPolicy.requireSafe(candidate.title(), "case title");
        ReaderFacingTextPolicy.requireSafeItems(candidate.preconditions(), "case precondition");
        candidate.inputs().forEach(value -> {
            ReaderFacingTextPolicy.requireSafe(value.content(), "case input");
            if (!value.sequence().isEmpty()) ReaderFacingTextPolicy.requireSafe(value.sequence(), "input sequence");
        });
        candidate.steps().forEach(value -> {
            ReaderFacingTextPolicy.requireSafe(value.action(), "step action");
            ReaderFacingTextPolicy.requireSafe(value.expected(), "step expected");
            ReaderFacingTextPolicy.requireSafe(value.evaluationCriteria(), "step evaluationCriteria");
            if (!value.terminationOrError().isEmpty()) {
                ReaderFacingTextPolicy.requireSafe(value.terminationOrError(), "step terminationOrError");
            }
            ReaderFacingTextPolicy.requireSafe(value.resultCollection(), "step resultCollection");
        });
        // Overall expectations summarize the case outcome; step expectations remain independently ordered by step_no.
        ReaderFacingTextPolicy.requireSafeItems(candidate.expectedResults(), "expected result");
        ReaderFacingTextPolicy.requireSafe(candidate.evaluationCriteria(), "evaluationCriteria");
        ReaderFacingTextPolicy.requireSafe(candidate.resultEvaluationCriteria(), "resultEvaluationCriteria");
        ReaderFacingTextPolicy.requireSafeItems(candidate.terminationConditions(), "termination condition");
        ReaderFacingTextPolicy.requireSafe(candidate.resultCollection(), "resultCollection");
        ReaderFacingTextPolicy.requireSafeItems(candidate.missingInformation(), "case missingInformation");
    }

    private static List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts(List<String> factKeys,
            Map<String, FunctionalTestcaseDesignV2Input.RequirementFact> facts, int index) {
        List<FunctionalTestcaseDesignV2Input.RequirementFact> selected = new java.util.ArrayList<>();
        for (String key : factKeys) {
            FunctionalTestcaseDesignV2Input.RequirementFact fact = facts.get(key);
            if (fact == null) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_FACT_OUT_OF_SCOPE,
                        "$.testcases[" + index + "].requirement_fact_keys");
            }
            selected.add(fact);
        }
        return List.copyOf(selected);
    }

    private static void validateEvidenceClosure(List<String> evidenceKeys,
            List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts, int index) {
        LinkedHashSet<String> expected = new LinkedHashSet<>();
        selectedFacts.forEach(fact -> fact.sourceQuotes().forEach(quote -> expected.add(quote.evidenceKey())));
        if (!expected.equals(new LinkedHashSet<>(evidenceKeys))) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_EVIDENCE_CLOSURE_INVALID,
                    "$.testcases[" + index + "].evidence_keys");
        }
    }

    /**
     * KEE supplies readable candidates, while Java remains authoritative for business grounding. The check focuses
     * on high-risk details that materially change how a case is executed; ordinary connective testing language is
     * deliberately not treated as a business fact.
     */
    private static void validateBusinessGrounding(FunctionalTestcaseDesignV2Result.Testcase candidate,
            FunctionalTestcaseDesignV2Input input,
            List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts, int index) {
        // Approved scope chooses what to test; only cited requirement facts and quotes may justify formal details.
        String support = normalizedGrounding(selectedFacts.stream()
                .flatMap(fact -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(fact.statement()),
                        fact.sourceQuotes().stream().map(value -> value.quote())))
                .collect(java.util.stream.Collectors.joining(" ")));
        String prefix = "$.testcases[" + index + "]";
        requireSupported(candidate.name(), prefix + ".name", support, false);
        requireSupported(candidate.title(), prefix + ".title", support, false);
        for (int item = 0; item < candidate.preconditions().size(); item++) {
            requireSupported(candidate.preconditions().get(item), prefix + ".preconditions[" + item + "]",
                    support, false);
        }
        validateInitialization(candidate.initialization(), prefix + ".initialization", support);
        for (int item = 0; item < candidate.inputs().size(); item++) {
            requireSupported(candidate.inputs().get(item).content(), prefix + ".inputs[" + item + "].content",
                    support, false);
            requireSupported(candidate.inputs().get(item).sequence(), prefix + ".inputs[" + item + "].sequence",
                    support, false);
        }
        for (int item = 0; item < candidate.steps().size(); item++) {
            var step = candidate.steps().get(item);
            String stepPath = prefix + ".steps[" + item + "]";
            requireSupported(step.action(), stepPath + ".action", support, false);
            requireSupported(step.expected(), stepPath + ".expected", support, false);
            requireSupported(step.evaluationCriteria(), stepPath + ".evaluation_criteria", support, false);
            requireSupported(step.terminationOrError(), stepPath + ".termination_or_error", support, false);
            requireSupported(step.resultCollection(), stepPath + ".result_collection", support, false);
        }
        // Overall expectations are testcase-level conclusions, not step aliases. They remain independently grounded
        // so separating both layers cannot admit a state, threshold, role or result absent from the cited facts.
        for (int item = 0; item < candidate.expectedResults().size(); item++) {
            requireSupported(candidate.expectedResults().get(item),
                    prefix + ".expected_results[" + item + "]", support, false);
        }
        for (int item = 0; item < candidate.terminationConditions().size(); item++) {
            requireSupported(candidate.terminationConditions().get(item),
                    prefix + ".termination_conditions[" + item + "]", support, false);
        }
        requireSupported(candidate.evaluationCriteria(), prefix + ".evaluation_criteria", support, false);
        requireSupported(candidate.resultEvaluationCriteria(), prefix + ".result_evaluation_criteria", support,
                false);
        requireSupported(candidate.resultCollection(), prefix + ".result_collection", support, false);
    }

    private static void validateInitialization(FunctionalTestcaseDesignV2Result.Initialization initialization,
            String path, String support) {
        requireConfiguredItems(initialization.hardwareConfiguration(), path + ".hardware_configuration", support);
        requireConfiguredItems(initialization.softwareConfiguration(), path + ".software_configuration", support);
        requireConfiguredItems(initialization.testConfiguration(), path + ".test_configuration", support);
        requireConfiguredItems(initialization.parameterConfiguration(), path + ".parameter_configuration", support);
    }

    private static void requireConfiguredItems(List<String> values, String path, String support) {
        for (int index = 0; index < values.size(); index++) {
            // A concrete configuration is itself a business assertion, so the whole value must be evidenced.
            requireSupported(values.get(index), path + "[" + index + "]", support, true);
        }
    }

    private static void requireSupported(String candidate, String path, String support, boolean requireWholeValue) {
        if (candidate == null || candidate.isBlank()) return;
        String normalized = normalizedGrounding(candidate);
        if (GENERIC_TEST_LANGUAGE.contains(normalized)) return;
        String businessText = stripGenericTestScaffolding(normalized);
        if ((requireWholeValue || !businessText.isEmpty()) && !support.contains(businessText)) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
        }
        for (String marker : PROTECTED_BUSINESS_MARKERS) {
            if (normalized.contains(marker) && !support.contains(marker)) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
        java.util.regex.Matcher quantified = QUANTIFIED_DETAIL.matcher(normalized);
        while (quantified.find()) {
            if (!support.contains(quantified.group())) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
        java.util.regex.Matcher errorCode = ERROR_CODE.matcher(normalized);
        while (errorCode.find()) {
            if (!support.contains(errorCode.group())) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
    }

    /** Removes only fixed testing-language wrappers; business nouns, verbs, states and thresholds remain grounded. */
    private static String stripGenericTestScaffolding(String value) {
        String result = value;
        for (String prefix : GENERIC_PREFIXES) {
            if (result.startsWith(prefix) && result.length() > prefix.length()) {
                result = result.substring(prefix.length());
                break;
            }
        }
        for (String suffix : GENERIC_SUFFIXES) {
            if (result.endsWith(suffix) && result.length() > suffix.length()) {
                result = result.substring(0, result.length() - suffix.length());
                break;
            }
        }
        return result;
    }

    private static String caseKey(String functionKey, String testPointKey,
            FunctionalTestcaseDesignV2Result.Testcase candidate) {
        StringBuilder canonical = new StringBuilder("functional-testcase-v2\n");
        appendIdentity(canonical, functionKey);
        appendIdentity(canonical, testPointKey);
        appendIdentity(canonical, candidate.name());
        appendIdentity(canonical, candidate.title());
        appendIdentity(canonical, candidate.priority().wireValue());
        appendIdentity(canonical, candidate.caseStatus().wireValue());
        appendIdentityList(canonical, candidate.preconditions());
        appendIdentityList(canonical, candidate.initialization().hardwareConfiguration());
        appendIdentityList(canonical, candidate.initialization().softwareConfiguration());
        appendIdentityList(canonical, candidate.initialization().testConfiguration());
        appendIdentityList(canonical, candidate.initialization().parameterConfiguration());
        candidate.inputs().forEach(value -> {
            appendIdentity(canonical, value.content());
            appendIdentity(canonical, value.nature().wireValue());
            appendIdentity(canonical, value.source().wireValue());
            appendIdentity(canonical, value.method().wireValue());
            appendIdentity(canonical, value.authenticity().wireValue());
            appendIdentity(canonical, value.sequence());
        });
        candidate.steps().forEach(value -> {
            appendIdentity(canonical, Integer.toString(value.stepNo()));
            appendIdentity(canonical, value.action());
            appendIdentity(canonical, value.expected());
            appendIdentity(canonical, value.evaluationCriteria());
            appendIdentity(canonical, value.terminationOrError());
            appendIdentity(canonical, value.resultCollection());
        });
        appendIdentityList(canonical, candidate.expectedResults());
        appendIdentity(canonical, candidate.evaluationCriteria());
        appendIdentity(canonical, candidate.resultEvaluationCriteria());
        appendIdentityList(canonical, candidate.terminationConditions());
        appendIdentity(canonical, candidate.resultCollection());
        appendIdentityList(canonical, candidate.requirementFactKeys().stream().sorted().toList());
        appendIdentityList(canonical, candidate.evidenceKeys().stream().sorted().toList());
        appendIdentityList(canonical, candidate.missingInformation().stream().sorted().toList());
        try {
            return "case-" + java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    /** Length-prefixes normalized values so field boundaries cannot collide in a durable testcase identity. */
    private static void appendIdentity(StringBuilder target, String value) {
        String normalized = normalized(value);
        target.append(normalized.getBytes(StandardCharsets.UTF_8).length).append(':').append(normalized).append('\n');
    }

    private static void appendIdentityList(StringBuilder target, List<String> values) {
        target.append(values.size()).append('\n');
        values.forEach(value -> appendIdentity(target, value));
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private static String normalizedGrounding(String value) {
        String lower = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        lower.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static StructuredValidationException failure(StructuredValidationFailure.Code code, String path) {
        return new StructuredValidationException(StructuredValidationFailure.of(code, path));
    }

    /** Java-owned case identity paired with the exact validated KEE candidate. */
    public record AcceptedTestcase(String caseKey, FunctionalTestcaseDesignV2Result.Testcase testcase) { }

    /** Complete accepted outcome for one test point. */
    public record AcceptedDesign(GenerationOutcome generationOutcome, List<String> missingInformation,
            List<AcceptedTestcase> testcases, boolean formalCoverageSatisfied) {
        public AcceptedDesign {
            missingInformation = List.copyOf(missingInformation);
            testcases = List.copyOf(testcases);
        }
    }
}
