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
 * [Req-ID]: REQ-TGV2-005, REQ-TGV2-006, REQ-TGV2-008, REQ-TGV2-014, REQ-TGV2-015
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
        List<String> supportSources = selectedFacts.stream()
                .flatMap(fact -> java.util.stream.Stream.concat(
                        java.util.stream.Stream.of(fact.statement()),
                        fact.sourceQuotes().stream().map(value -> value.quote())))
                .map(FunctionalTestcaseV2Validator::normalizedGrounding)
                .filter(value -> !value.isEmpty())
                .toList();
        String prefix = "$.testcases[" + index + "]";
        requireIdentityLabelSupported(candidate.name(), prefix + ".name", supportSources, input, selectedFacts,
                candidate.caseStatus());
        requireIdentityLabelSupported(candidate.title(), prefix + ".title", supportSources, input, selectedFacts,
                candidate.caseStatus());
        for (int item = 0; item < candidate.preconditions().size(); item++) {
            requireSupported(candidate.preconditions().get(item), prefix + ".preconditions[" + item + "]",
                    supportSources, false);
        }
        validateInitialization(candidate.initialization(), prefix + ".initialization", supportSources);
        for (int item = 0; item < candidate.inputs().size(); item++) {
            requireSupported(candidate.inputs().get(item).content(), prefix + ".inputs[" + item + "].content",
                    supportSources, false);
            requireSupported(candidate.inputs().get(item).sequence(), prefix + ".inputs[" + item + "].sequence",
                    supportSources, false);
        }
        for (int item = 0; item < candidate.steps().size(); item++) {
            var step = candidate.steps().get(item);
            String stepPath = prefix + ".steps[" + item + "]";
            requireSupported(step.action(), stepPath + ".action", supportSources, false);
            requireSupported(step.expected(), stepPath + ".expected", supportSources, false);
            requireSupported(step.evaluationCriteria(), stepPath + ".evaluation_criteria", supportSources, false);
            requireSupported(step.terminationOrError(), stepPath + ".termination_or_error", supportSources, false);
            requireSupported(step.resultCollection(), stepPath + ".result_collection", supportSources, false);
        }
        // Overall expectations are testcase-level conclusions, not step aliases. They remain independently grounded
        // so separating both layers cannot admit a state, threshold, role or result absent from the cited facts.
        for (int item = 0; item < candidate.expectedResults().size(); item++) {
            requireSupported(candidate.expectedResults().get(item),
                    prefix + ".expected_results[" + item + "]", supportSources, false);
        }
        for (int item = 0; item < candidate.terminationConditions().size(); item++) {
            requireSupported(candidate.terminationConditions().get(item),
                    prefix + ".termination_conditions[" + item + "]", supportSources, false);
        }
        requireSupported(candidate.evaluationCriteria(), prefix + ".evaluation_criteria", supportSources, false);
        requireSupported(candidate.resultEvaluationCriteria(), prefix + ".result_evaluation_criteria", supportSources,
                false);
        requireSupported(candidate.resultCollection(), prefix + ".result_collection", supportSources, false);
    }

    private static void validateInitialization(FunctionalTestcaseDesignV2Result.Initialization initialization,
            String path, List<String> supportSources) {
        requireConfiguredItems(initialization.hardwareConfiguration(), path + ".hardware_configuration", supportSources);
        requireConfiguredItems(initialization.softwareConfiguration(), path + ".software_configuration", supportSources);
        requireConfiguredItems(initialization.testConfiguration(), path + ".test_configuration", supportSources);
        requireConfiguredItems(initialization.parameterConfiguration(), path + ".parameter_configuration", supportSources);
    }

    private static void requireConfiguredItems(List<String> values, String path, List<String> supportSources) {
        for (int index = 0; index < values.size(); index++) {
            // A concrete configuration is itself a business assertion, so the whole value must be evidenced.
            requireSupported(values.get(index), path + "[" + index + "]", supportSources, true);
        }
    }

    private static void requireSupported(
            String candidate, String path, List<String> supportSources, boolean requireWholeValue) {
        if (candidate == null || candidate.isBlank()) return;
        String normalized = normalizedGrounding(candidate);
        if (GENERIC_TEST_LANGUAGE.contains(normalized)) return;
        String businessText = stripGenericTestScaffolding(normalized);
        if ((requireWholeValue || !businessText.isEmpty()) && !isSupportedByOneSource(businessText, supportSources)) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
        }
        requireSupportedSpecificDetails(normalized, path, supportSources);
    }

    /**
     * Validates only the reader-facing identity label against the current approved function and test point.
     * Execution details deliberately continue through {@link #requireSupported(String, String, List, boolean)} so
     * an approved-scope description can never invent a role, environment, threshold, state or result.
     * [Req-ID]: REQ-TGV2-015
     */
    private static void requireIdentityLabelSupported(String candidate, String path, List<String> factSupportSources,
            FunctionalTestcaseDesignV2Input input,
            List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts, CaseStatus caseStatus) {
        if (candidate == null || candidate.isBlank()) return;
        String normalized = normalizedGrounding(candidate);
        List<IdentityAtom> atoms = identityAtoms(input, selectedFacts, caseStatus);
        List<String> identitySupportSources = java.util.stream.Stream.concat(
                        factSupportSources.stream(), atoms.stream().map(IdentityAtom::text))
                .distinct().toList();
        requireSupportedSpecificDetails(normalized, path, identitySupportSources);
        if (!isSupportedIdentityText(normalized, atoms)) {
            throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
        }
    }

    /** Matches reachable wrapper boundaries without materializing their left/right cross-product as strings. */
    private static boolean isSupportedIdentityText(String value, List<IdentityAtom> atoms) {
        IdentityBoundaries boundaries = identityBoundaries(value);
        // A real approved scope may legitimately be named "记录结果" or end in "异常场景". Reachable scope matches
        // therefore win before any reachable boundary can classify the whole label as generic-only.
        if (hasReachableScopeComposition(value, boundaries, atoms)) return true;
        if (hasReachableGenericOnlyIdentity(value, boundaries)) return false;
        return hasTerminalSemanticContainment(value, boundaries, atoms)
                || hasReachableIdentityComposition(value, boundaries, atoms);
    }

    private static boolean hasReachableScopeComposition(
            String value, IdentityBoundaries boundaries, List<IdentityAtom> atoms) {
        List<String> scopes = atoms.stream().filter(atom -> atom.kind() == IdentityAtomKind.SCOPE)
                .map(IdentityAtom::text).distinct().toList();
        List<ScopeComposition> compositions = new java.util.ArrayList<>();
        addScopeCompositions(scopes, 0, "", compositions);
        return compositions.stream().filter(composition -> !composition.text().isEmpty())
                .anyMatch(composition -> boundaries.matches(value, composition.text()));
    }

    private static boolean hasReachableGenericOnlyIdentity(String value, IdentityBoundaries boundaries) {
        if (boundaries.hasEmptySlice()) return true;
        return java.util.stream.Stream.of(GENERIC_TEST_LANGUAGE, Set.copyOf(GENERIC_PREFIXES),
                        Set.copyOf(GENERIC_SUFFIXES))
                .flatMap(Set::stream).anyMatch(generic -> boundaries.matches(value, generic));
    }

    private static IdentityBoundaries identityBoundaries(String value) {
        boolean[] starts = new boolean[value.length() + 1];
        boolean[] ends = new boolean[value.length() + 1];
        starts[0] = true;
        for (int start = 0; start <= value.length(); start++) {
            if (!starts[start]) continue;
            for (String prefix : GENERIC_PREFIXES) {
                if (value.regionMatches(start, prefix, 0, prefix.length())) starts[start + prefix.length()] = true;
            }
        }
        ends[value.length()] = true;
        for (int end = value.length(); end >= 0; end--) {
            if (!ends[end]) continue;
            for (String suffix : GENERIC_SUFFIXES) {
                int start = end - suffix.length();
                if (start >= 0 && value.regionMatches(start, suffix, 0, suffix.length())) ends[start] = true;
            }
        }
        return new IdentityBoundaries(starts, ends, trueIndexes(starts), trueIndexes(ends));
    }

    private static int[] trueIndexes(boolean[] values) {
        int count = 0;
        for (boolean value : values) if (value) count++;
        int[] indexes = new int[count];
        for (int index = 0, target = 0; index < values.length; index++) {
            if (values[index]) indexes[target++] = index;
        }
        return indexes;
    }

    private static List<IdentityAtom> identityAtoms(FunctionalTestcaseDesignV2Input input,
            List<FunctionalTestcaseDesignV2Input.RequirementFact> selectedFacts, CaseStatus caseStatus) {
        LinkedHashMap<String, IdentityAtomKind> atoms = new LinkedHashMap<>();
        addIdentityAtom(atoms, input.functionName(), IdentityAtomKind.SCOPE);
        addIdentityAtom(atoms, pathLeaf(input.functionPath()), IdentityAtomKind.SCOPE);
        // A pending/experience-only point must not become the identity basis of a formal candidate.
        if (caseStatus != CaseStatus.FORMAL
                || input.testPoint().basis() == FunctionalTestcaseDesignV2Input.Basis.FORMAL_REQUIREMENT) {
            addIdentityAtom(atoms, input.testPoint().description(), IdentityAtomKind.SCOPE);
        }
        selectedFacts.forEach(fact -> {
            addIdentityAtom(atoms, fact.statement(), IdentityAtomKind.SEMANTIC);
            fact.sourceQuotes().forEach(quote -> addIdentityAtom(atoms, quote.quote(), IdentityAtomKind.SEMANTIC));
        });
        return atoms.entrySet().stream().map(entry -> new IdentityAtom(entry.getKey(), entry.getValue())).toList();
    }

    /**
     * Preserves the historical ability to use a concise phrase from one cited fact or quote. Sources are checked
     * independently so two unrelated facts can never be concatenated into a new reader-facing identity.
     */
    private static boolean hasTerminalSemanticContainment(
            String value, IdentityBoundaries boundaries, List<IdentityAtom> atoms) {
        List<IdentitySlice> terminalSlices = boundaries.terminalSlices(value);
        return terminalSlices.stream().anyMatch(slice -> atoms.stream()
                .filter(atom -> atom.kind() == IdentityAtomKind.SEMANTIC)
                .anyMatch(atom -> containsSlice(atom.text(), value, slice)));
    }

    /** KMP keeps one fact/quote containment check linear even when source and label share a long near-match prefix. */
    private static boolean containsSlice(String source, String value, IdentitySlice slice) {
        int matched = 0;
        int length = slice.end() - slice.start();
        for (int sourceIndex = 0; sourceIndex < source.length(); sourceIndex++) {
            char current = source.charAt(sourceIndex);
            while (matched > 0 && current != value.charAt(slice.start() + matched)) {
                matched = slice.failure()[matched - 1];
            }
            if (current == value.charAt(slice.start() + matched)) matched++;
            if (matched == length) return true;
        }
        return false;
    }

    private static IdentitySlice identitySlice(String value, int start, int end) {
        int[] failure = new int[end - start];
        for (int index = 1, matched = 0; index < failure.length; index++) {
            while (matched > 0 && value.charAt(start + index) != value.charAt(start + matched)) {
                matched = failure[matched - 1];
            }
            if (value.charAt(start + index) == value.charAt(start + matched)) matched++;
            failure[index] = matched;
        }
        return new IdentitySlice(start, end, failure);
    }

    private static void addIdentityAtom(Map<String, IdentityAtomKind> atoms, String value, IdentityAtomKind kind) {
        String normalized = normalizedGrounding(value == null ? "" : value);
        if (!normalized.isEmpty()) {
            atoms.merge(normalized, kind, (left, right) ->
                    left == IdentityAtomKind.SCOPE || right == IdentityAtomKind.SCOPE
                            ? IdentityAtomKind.SCOPE : IdentityAtomKind.SEMANTIC);
        }
    }

    /**
     * Consumes every scope atom at most once and permits at most one complete semantic statement/quote atom.
     * Only three scope atoms exist (function name, path leaf and current point), so the composition enumeration is
     * constant-sized; source containment checks remain deliberately linear in the selected fact text.
     */
    private static boolean hasReachableIdentityComposition(
            String value, IdentityBoundaries boundaries, List<IdentityAtom> atoms) {
        List<String> scopes = atoms.stream().filter(atom -> atom.kind() == IdentityAtomKind.SCOPE)
                .map(IdentityAtom::text).distinct().toList();
        Set<String> semantics = atoms.stream().filter(atom -> atom.kind() == IdentityAtomKind.SEMANTIC)
                .map(IdentityAtom::text).collect(java.util.stream.Collectors.toSet());
        List<ScopeComposition> compositions = new java.util.ArrayList<>();
        addScopeCompositions(scopes, 0, "", compositions);
        for (ScopeComposition prefix : compositions) {
            for (ScopeComposition suffix : compositions) {
                if ((prefix.mask() & suffix.mask()) != 0) continue;
                for (String semantic : semantics) {
                    if (boundaries.matches(value, prefix.text(), semantic, suffix.text())) return true;
                }
            }
        }
        return false;
    }

    private static void addScopeCompositions(
            List<String> scopes, int usedMask, String text, List<ScopeComposition> compositions) {
        compositions.add(new ScopeComposition(text, usedMask));
        for (int index = 0; index < scopes.size(); index++) {
            int bit = 1 << index;
            if ((usedMask & bit) == 0) {
                addScopeCompositions(scopes, usedMask | bit, text + scopes.get(index), compositions);
            }
        }
    }

    private static String pathLeaf(String path) {
        if (path == null || path.isBlank()) return "";
        String[] segments = path.split("[/\\\\→>›»|／]+", -1);
        for (int index = segments.length - 1; index >= 0; index--) {
            if (!segments[index].isBlank()) return segments[index];
        }
        return "";
    }

    private static void requireSupportedSpecificDetails(
            String normalized, String path, List<String> supportSources) {
        for (String marker : PROTECTED_BUSINESS_MARKERS) {
            if (normalized.contains(marker) && !isSupportedByOneSource(marker, supportSources)) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
        java.util.regex.Matcher quantified = QUANTIFIED_DETAIL.matcher(normalized);
        while (quantified.find()) {
            if (!isSupportedByOneSource(quantified.group(), supportSources)) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
        java.util.regex.Matcher errorCode = ERROR_CODE.matcher(normalized);
        while (errorCode.find()) {
            if (!isSupportedByOneSource(errorCode.group(), supportSources)) {
                throw failure(StructuredValidationFailure.Code.TESTCASE_UNSUPPORTED_BUSINESS_DETAIL, path);
            }
        }
    }

    /** Checks each persisted fact or quote independently so no synthetic separator can become business evidence. */
    private static boolean isSupportedByOneSource(String value, List<String> supportSources) {
        return supportSources.stream().anyMatch(source -> source.contains(value));
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

    private enum IdentityAtomKind { SCOPE, SEMANTIC }

    private record IdentityAtom(String text, IdentityAtomKind kind) { }

    private record ScopeComposition(String text, int mask) { }

    private record IdentitySlice(int start, int end, int[] failure) { }

    /**
     * Wrapper removal changes only two indexes. Keeping those indexes makes memory linear in the label length even
     * when hundreds of removable prefixes and suffixes would otherwise create a quadratic number of copied strings.
     */
    private record IdentityBoundaries(boolean[] starts, boolean[] ends, int[] reachableStarts, int[] reachableEnds) {
        boolean matches(String value, String... parts) {
            int length = java.util.Arrays.stream(parts).mapToInt(String::length).sum();
            // Wrapper reachability is normally sparse. Iterating only those indexes prevents each fact from scanning
            // an unrelated long label while retaining the same exact start/end boundary predicate.
            for (int start : reachableStarts) {
                int end = start + length;
                if (end > value.length() || !ends[end]) continue;
                int offset = start;
                boolean matched = true;
                for (String part : parts) {
                    if (!value.regionMatches(offset, part, 0, part.length())) {
                        matched = false;
                        break;
                    }
                    offset += part.length();
                }
                if (matched) return true;
            }
            return false;
        }

        boolean hasEmptySlice() {
            for (int boundary : reachableStarts) {
                if (ends[boundary]) return true;
            }
            return false;
        }

        List<IdentitySlice> terminalSlices(String value) {
            List<Integer> terminalStarts = new java.util.ArrayList<>();
            List<Integer> terminalEnds = new java.util.ArrayList<>();
            for (int start : reachableStarts) {
                if (start >= value.length()) continue;
                boolean removable = false;
                for (String prefix : GENERIC_PREFIXES) {
                    if (value.regionMatches(start, prefix, 0, prefix.length())) {
                        removable = true;
                        break;
                    }
                }
                if (!removable) terminalStarts.add(start);
            }
            for (int end : reachableEnds) {
                if (end == 0) continue;
                boolean removable = false;
                for (String suffix : GENERIC_SUFFIXES) {
                    int start = end - suffix.length();
                    if (start >= 0 && value.regionMatches(start, suffix, 0, suffix.length())) {
                        removable = true;
                        break;
                    }
                }
                if (!removable) terminalEnds.add(end);
            }
            List<IdentitySlice> slices = new java.util.ArrayList<>();
            for (int start : terminalStarts) {
                // Terminal slices with the same start are nested prefixes. If any longer slice is contained by one
                // semantic source, the shortest non-empty slice is contained too, so longer slices are dominated.
                for (int end : terminalEnds) {
                    if (start < end) {
                        slices.add(identitySlice(value, start, end));
                        break;
                    }
                }
            }
            return List.copyOf(slices);
        }
    }
}
