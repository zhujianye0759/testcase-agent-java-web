package com.testcaseagent.validation;

import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Input;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result;
import com.testcaseagent.knowledgeagent.RequirementFactExtractionV2Result.FactType;
import com.testcaseagent.knowledgeagent.StructuredSourceQuoteV2;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Performs Java-owned V2 fact echo, evidence, stable identity, and cross-window deduplication.
 * Every method validates the complete window before returning anything publishable.
 *
 * [Req-ID]: REQ-TGV2-004, REQ-TGV2-008, REQ-TGV2-012
 */
public final class RequirementFactV2Validator {

    private static final Pattern COORDINATOR = Pattern.compile(
            "(?:\\band\\b|\\bor\\b|并且|同时|以及|并|且|和|与|或|及|,|，|、)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHINESE_EXPLICIT_OBLIGATION = Pattern.compile(
            "(?:必须|应当|不得|禁止|严禁|不可|不能|需要|允许|可以|须)");
    private static final Pattern ENGLISH_EXPLICIT_OBLIGATION = Pattern.compile(
            "\\b(?:must|shall|should|may|can|is required to|are required to)\\b",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern PREDICATE_COORDINATOR = Pattern.compile("^(?:and|并且|同时|以及|并|且|和)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COLLECTION_MEMBER_COORDINATOR = Pattern.compile("^(?:and|or|和|与|及|或|、)$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COLLECTION_INTRODUCER = Pattern.compile(
            "(?:包含|包括|列出|由.+组成|\\binclude(?:s|d)?\\b|\\bcontain(?:s|ed)?\\b|\\blist(?:s|ed)?\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern COMPARISON = Pattern.compile(
            "(?:不高于|不低于|高于|低于|大于|小于|至少|至多|不少于|不超过|>=|<=|>|<|"
                    + "\\b(?:greater|less) than\\b|\\bat least\\b|\\bat most\\b)",
             Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ASCII_INITIALISM = Pattern.compile("\\b[A-Z]{2,10}\\b");
    private static final Pattern ASCII_WORD = Pattern.compile("[A-Za-z]+");
    private static final Pattern QUOTED_LITERAL = Pattern.compile(
            "\"([^\"]+)\"|'([^']+)'|“([^”]+)”|‘([^’]+)’");
    private static final Pattern LITERAL_MAPPING_RELATION = Pattern.compile(
            "(?:表示|代表|对应|\\bmeans?\\b|\\bdenotes?\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern SEMANTIC_CONTROL = Pattern.compile(
            "(?:不允许|不可以|不低于|不高于|不少于|不超过|严禁|不得|禁止|不可|不能|必须|应当|需要|允许|可以|至少|至多|高于|低于|大于|小于|等于|仅限|仅当|只有|仅|只|"
                    + "不|未|"
                    + "\\bmust not\\b|\\bmay not\\b|\\bcannot\\b|\\bmust\\b|\\bshall\\b|\\bshould\\b|\\bmay\\b|\\bcan\\b|"
                    + "\\bnot\\b|\\bwithout\\b|\\bonly\\b|\\bunless\\b|\\bif\\b|\\bwhen\\b|\\bat least\\b|\\bat most\\b|\\bgreater than\\b|\\bless than\\b)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern CHINESE_FIELD_COLLECTION = Pattern.compile(
            "(?:包含|包括|列出)([^。；;！？!?]+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern ENGLISH_FIELD_COLLECTION = Pattern.compile(
            "\\b(?:includes?|contains?|lists?)\\b([^.;!?]+)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Set<String> REPEATABLE_MODAL_CONTROLS = Set.of(
            "必须", "应当", "需要", "must", "shall", "should");
    private static final Set<String> OMITTABLE_ENGLISH_TOKENS = Set.of(
            "the", "a", "an", "must", "shall", "should");
    private static final List<String> PRESERVED_STATE_OR_SCOPE_MODIFIERS = List.of(
            "currently", "current", "only", "now", "当前", "现在", "目前", "只", "仅");
    private static final Pattern OMITTABLE_HAN_GAP = Pattern.compile("^(?:必须|应当|需要|并且|同时|以及)*$");
    private static final Pattern CHINESE_BINDING_PREFIX = Pattern.compile(
            "^((?:在[^，,；;。！？!?]*(?:情况下|情形下|前提下|之后|后|时))|"
                    + "(?:(?:如果|仅当|只在|仅在|若|当)[^，,；;。！？!?]*(?:时|则)))");
    private static final Pattern CHINESE_PUNCTUATION_BINDING_CLAUSE = Pattern.compile(
            "^(?:(?:在[^，,；;。！？!?]*(?:情况下|情形下|前提下|之后|后))|"
                    + "(?:如果[^，,；;。！？!?]+(?:时|则)?)|"
                    + "(?:(?:仅当|只在|仅在)[^，,；;。！？!?]+(?:时|之后|后))|"
                    + "(?:只要[^，,；;。！？!?]+))$");
    private static final Pattern ENGLISH_BINDING_PREFIX = Pattern.compile(
            "^((?:only\\s+(?:if|when)|provided\\s+that|subject\\s+to|unless|if|when|after|before)\\b[^,;.!?]+)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    /** Validates one complete KEE result and assigns deterministic Java fact identities. [Req-ID]: REQ-TGV2-012 */
    public AcceptedWindow validate(RequirementFactExtractionV2Input input,
            RequirementFactExtractionV2Result result) {
        Objects.requireNonNull(input, "input must not be null");
        Objects.requireNonNull(result, "result must not be null");
        if (!input.functionKey().equals(result.functionKey()) || !input.windowKey().equals(result.windowKey())) {
            throw failure(StructuredValidationFailure.Code.FACT_RESULT_ECHO_INVALID, "$");
        }
        Map<String, String> evidence = new LinkedHashMap<>();
        java.util.stream.Stream.concat(input.units().stream(), input.contextUnits().stream())
                .forEach(unit -> evidence.put(unit.unitKey(), unit.content()));

        List<AcceptedFact> facts = new ArrayList<>();
        Map<String, AcceptedFact> factByKey = new LinkedHashMap<>();
        for (int index = 0; index < result.requirementFacts().size(); index++) {
            var candidate = result.requirementFacts().get(index);
            ReaderFacingTextPolicy.requireSafe(candidate.statement(), "requirement fact statement");
            validateAtomicStatement(candidate.statement(), index);
            validateQuotes(candidate.sourceQuotes(), evidence,
                    "$.requirement_facts[" + index + "].source_quotes");
            validateDirectEvidence(candidate.statement(), candidate.sourceQuotes(), index);
            String key = stableFactKey(input.functionKey(), candidate.factType(), candidate.statement());
            AcceptedFact accepted = new AcceptedFact(key, candidate.factType(), candidate.statement(),
                    candidate.sourceQuotes());
            if (factByKey.putIfAbsent(key, accepted) != null) {
                throw failure(StructuredValidationFailure.Code.FACT_DUPLICATE,
                        "$.requirement_facts[" + index + "]");
            }
            facts.add(accepted);
        }

        List<AcceptedObservation> observations = new ArrayList<>();
        for (int index = 0; index < result.testabilityObservations().size(); index++) {
            var candidate = result.testabilityObservations().get(index);
            ReaderFacingTextPolicy.requireSafe(candidate.description(), "testability observation description");
            validateQuotes(candidate.sourceQuotes(), evidence,
                    "$.testability_observations[" + index + "].source_quotes");
            observations.add(new AcceptedObservation(candidate.observationType(), candidate.description(),
                    candidate.affectedFactTypes(), candidate.sourceQuotes()));
        }
        return new AcceptedWindow(input.functionKey(), input.windowKey(), List.copyOf(facts),
                List.copyOf(observations));
    }

    /** Merges already validated windows without making a later model result authoritative. */
    public AcceptedFunction merge(List<AcceptedWindow> windows) {
        List<AcceptedWindow> checked = List.copyOf(Objects.requireNonNull(windows, "windows must not be null"));
        if (checked.isEmpty()) return new AcceptedFunction(List.of(), List.of());
        String functionKey = checked.get(0).functionKey();
        Map<String, AcceptedFact> facts = new java.util.TreeMap<>();
        List<AcceptedObservation> observations = new ArrayList<>();
        for (AcceptedWindow window : checked) {
            if (!functionKey.equals(window.functionKey())) {
                throw new IllegalArgumentException("fact windows must belong to one function");
            }
            for (AcceptedFact fact : window.facts()) {
                facts.merge(fact.factKey(), fact, RequirementFactV2Validator::mergeFactEvidence);
            }
            observations.addAll(window.observations());
        }
        return new AcceptedFunction(List.copyOf(facts.values()), List.copyOf(observations));
    }

    private static void validateQuotes(List<StructuredSourceQuoteV2> quotes, Map<String, String> evidence,
            String path) {
        for (int index = 0; index < quotes.size(); index++) {
            StructuredSourceQuoteV2 quote = quotes.get(index);
            String source = evidence.get(quote.evidenceKey());
            if (source == null) {
                throw failure(StructuredValidationFailure.Code.FACT_EVIDENCE_OUT_OF_SCOPE,
                        path + "[" + index + "].evidence_key");
            }
            if (!normalizedGrounding(source).contains(normalizedGrounding(quote.quote()))) {
                throw failure(StructuredValidationFailure.Code.FACT_QUOTE_NOT_GROUNDED,
                        path + "[" + index + "].quote");
            }
        }
    }

    /**
     * A fact may contain a condition and its consequence, but two independently terminated clauses are two facts.
     * Keeping this boundary explicit prevents a single durable key from hiding multiple coverage obligations.
     */
    private static void validateAtomicStatement(String statement, int index) {
        if (!hasBalancedQuotes(statement)) {
            throw failure(StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                    "$.requirement_facts[" + index + "].statement");
        }
        long clauses = java.util.Arrays.stream(statement.split("[\\r\\n；;。！？!?]+"))
                .map(String::strip)
                .filter(value -> !value.isEmpty())
                .count();
        if (clauses != 1) {
            throw failure(StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                    "$.requirement_facts[" + index + "].statement");
        }
        // Connector characters also occur in field lists, range bounds and condition clauses. We use only grammatical
        // structure, never a project action dictionary: independently repeated obligation markers are rejected, while
        // structurally provable collections and paired bounds remain one atomic fact.
        String normalized = obligationClause(normalizedText(statement));
        if (containsHighConfidenceMultipleObligations(normalized)
                || containsIndependentCommaClauses(normalized)) {
            throw failure(StructuredValidationFailure.Code.FACT_ATOMICITY_INVALID,
                    "$.requirement_facts[" + index + "].statement");
        }
    }

    /**
     * Comma-separated clauses fail closed unless grammar proves a condition/consequence or structured field mapping.
     * This avoids a project-specific actor/action dictionary while still keeping one atomic output collection intact.
     */
    private static boolean containsIndependentCommaClauses(String statement) {
        List<String> clauses = commaClausesOutsideQuotes(statement);
        if (clauses.size() < 2) return false;
        String first = clauses.get(0).strip();
        if (isBindingClause(first)) {
            List<String> consequences = clauses.subList(1, clauses.size());
            return consequences.size() > 1 && !isStructuredCommaCollection(consequences);
        }
        return !isStructuredCommaCollection(clauses);
    }

    private static boolean isStructuredCommaCollection(List<String> clauses) {
        if (clauses.size() < 2) return false;
        if (COLLECTION_INTRODUCER.matcher(clauses.get(0)).find()) {
            // The direct-evidence grammar already proves every collection member as either an explicitly quoted label
            // or a boolean field. Reusing it here avoids treating an arbitrary comma-delimited action as a field.
            return fieldCollection(String.join("，", clauses)) != null;
        }
        return isStructuredLiteralMapping(clauses);
    }

    /**
     * Accepts one quoted-value mapping whose later clauses only continue the same map.
     * A later clause with its own unquoted subject is a separate action and therefore fails closed.
     */
    private static boolean isStructuredLiteralMapping(List<String> clauses) {
        String sharedLabel = null;
        for (int index = 0; index < clauses.size(); index++) {
            String clause = clauses.get(index).strip();
            Matcher relation = LITERAL_MAPPING_RELATION.matcher(clause);
            Matcher literal = QUOTED_LITERAL.matcher(clause);
            if (!literal.find() || !relation.find()) {
                return false;
            }
            String label = normalizedSemanticText(clause.substring(0, literal.start()));
            if (index == 0) {
                sharedLabel = label;
            } else if (!label.isEmpty() && !label.equals(sharedLabel)) {
                return false;
            }
            // A mapping value must end this clause. A coordinator after the relation starts another semantic action,
            // so it cannot borrow the quoted value as an atomicity exemption.
            if (COORDINATOR.matcher(clause.substring(relation.end())).find()) return false;
        }
        return true;
    }

    /** A punctuation splitter must never turn malformed reader text into an atomicity bypass. */
    private static boolean hasBalancedQuotes(String value) {
        char quoteEnd = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char current = value.charAt(offset);
            if (quoteEnd != 0) {
                if (closesQuotedSpan(value, offset, current, quoteEnd)) quoteEnd = 0;
                continue;
            }
            quoteEnd = openingQuote(value, offset, current);
        }
        return quoteEnd == 0;
    }

    private static boolean containsHighConfidenceMultipleObligations(String obligation) {
        var connectors = COORDINATOR.matcher(obligation);
        while (connectors.find()) {
            String left = obligation.substring(0, connectors.start()).strip();
            String right = obligation.substring(connectors.end()).strip();
            String connector = connectors.group();
            if (!hasExplicitObligationMarker(left)) {
                continue;
            }
            boolean rightHasMarker = hasExplicitObligationMarker(right);
            boolean rightHasSubject = hasSubjectBeforeObligationMarker(right);
            // A boolean field label may itself contain a modal word (for example, “whether users can edit”). The
            // collection exemption must not hide a new subject with its own obligation after the same connector.
            if (isCollectionEnumeration(left) && COLLECTION_MEMBER_COORDINATOR.matcher(connector).matches()) {
                // Only the complete quoted/boolean field grammar proves that every coordinated member is a label.
                // An unquoted sentence-shaped tail is ambiguous and therefore fails closed without an action lexicon.
                if (fieldCollection(obligation) != null) continue;
                return true;
            }
            if (isPairedRangeConstraint(left, right, connector)) continue;
            if (!rightHasMarker && PREDICATE_COORDINATOR.matcher(connector).matches()
                    && hasSharedModalIndependentPredicate(left, right, connector)) return true;
            // A field label can start with a modal-looking word (for example, “允许操作列表”). When a longer
            // left-hand phrase and Chinese “和” provide no second subject, the grammar alone cannot prove a second
            // obligation. Keep it eligible for the remaining evidence checks rather than treating the label as code.
            if ("和".equals(connector) && rightHasMarker && !rightHasSubject
                    && textAfterLastObligationMarker(left).strip().codePointCount(
                            0, textAfterLastObligationMarker(left).strip().length()) > 2) continue;
            boolean repeatedObligation = rightHasMarker
                    && (rightHasSubject || PREDICATE_COORDINATOR.matcher(connector).matches());
            if (repeatedObligation) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detects two independently testable predicates that share one modal. Chinese predicates are separated by an
     * explicit predicate coordinator; English adjective lists remain atomic when both coordinated heads are participles.
     */
    private static boolean hasSharedModalIndependentPredicate(String left, String right, String connector) {
        String predicate = textAfterLastObligationMarker(left).strip();
        if (predicate.isEmpty() || right.isEmpty()) return false;
        // Chinese single-character coordinators can also be part of the ordinary words "合并" and "并发". The
        // single-character left half proves "合并" without accidentally accepting "整合并发布". A shared terminal
        // classifier under "同时" proves a coordinated label pair such as 中文/英文 without maintaining business verbs.
        if (!"and".equalsIgnoreCase(connector)) {
            if ("并".equals(connector) && "合".equals(predicate)) return false;
            if ("并".equals(connector) && right.startsWith("发")
                    && !right.startsWith("发出") && !right.startsWith("发送")
                    && !right.startsWith("发布") && !right.startsWith("发起")) return false;
            if ("和".equals(connector) && predicate.startsWith("同时")
                    && sameTerminalCodePoint(predicate, right)) return false;
            return true;
        }

        List<String> leftTokens = englishWords(predicate);
        List<String> rightTokens = englishWords(right);
        if (rightTokens.size() == 1) return false;
        return leftTokens.isEmpty() || !isPastParticiple(leftTokens.get(leftTokens.size() - 1))
                || !isPastParticiple(rightTokens.get(0));
    }

    private static boolean sameTerminalCodePoint(String left, String right) {
        return !left.isEmpty() && !right.isEmpty()
                && left.codePointBefore(left.length()) == right.codePointBefore(right.length());
    }

    private static String textAfterLastObligationMarker(String value) {
        int end = -1;
        Matcher chinese = CHINESE_EXPLICIT_OBLIGATION.matcher(value);
        while (chinese.find()) end = Math.max(end, chinese.end());
        Matcher english = ENGLISH_EXPLICIT_OBLIGATION.matcher(value);
        while (english.find()) end = Math.max(end, english.end());
        return end < 0 ? "" : value.substring(end);
    }

    private static List<String> englishWords(String value) {
        Matcher matcher = ASCII_WORD.matcher(value.toLowerCase(Locale.ROOT));
        List<String> words = new ArrayList<>();
        while (matcher.find()) words.add(matcher.group());
        return words;
    }

    private static boolean isPastParticiple(String word) {
        return word.endsWith("ed") || word.endsWith("en");
    }

    private static boolean hasExplicitObligationMarker(String value) {
        return CHINESE_EXPLICIT_OBLIGATION.matcher(value).find()
                || ENGLISH_EXPLICIT_OBLIGATION.matcher(value).find();
    }

    private static boolean hasSubjectBeforeObligationMarker(String value) {
        var chinese = CHINESE_EXPLICIT_OBLIGATION.matcher(value);
        if (chinese.find() && isSubjectPrefix(value.substring(0, chinese.start()))) return true;
        var english = ENGLISH_EXPLICIT_OBLIGATION.matcher(value);
        return english.find() && isSubjectPrefix(value.substring(0, english.start()));
    }

    private static boolean isSubjectPrefix(String value) {
        String prefix = value.strip();
        return !prefix.isEmpty() && !"是否".equals(prefix) && !"可否".equals(prefix);
    }

    private static boolean isCollectionEnumeration(String left) {
        return COLLECTION_INTRODUCER.matcher(left).find();
    }

    private static boolean isBooleanFieldMember(String value) {
        String member = value.strip();
        int booleanMarker = member.startsWith("whether ") ? 0 : firstIndex(member, "是否", "可否");
        if (booleanMarker < 0) return false;
        int obligationMarker = firstObligationMarker(member);
        return obligationMarker < 0 || booleanMarker < obligationMarker;
    }

    private static int firstObligationMarker(String value) {
        int first = Integer.MAX_VALUE;
        var chinese = CHINESE_EXPLICIT_OBLIGATION.matcher(value);
        if (chinese.find()) first = chinese.start();
        var english = ENGLISH_EXPLICIT_OBLIGATION.matcher(value);
        if (english.find()) first = Math.min(first, english.start());
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private static int firstIndex(String value, String... candidates) {
        int first = Integer.MAX_VALUE;
        for (String candidate : candidates) {
            int index = value.indexOf(candidate);
            if (index >= 0) first = Math.min(first, index);
        }
        return first == Integer.MAX_VALUE ? -1 : first;
    }

    private static boolean isPairedRangeConstraint(String left, String right, String connector) {
        if (!("且".equals(connector) || "和".equals(connector) || "与".equals(connector)
                || "and".equalsIgnoreCase(connector))) {
            return false;
        }
        String rightConstraint = removeLeadingObligationMarker(right);
        return COMPARISON.matcher(left).find() && COMPARISON.matcher(rightConstraint).lookingAt();
    }

    private static String removeLeadingObligationMarker(String value) {
        var chinese = CHINESE_EXPLICIT_OBLIGATION.matcher(value);
        if (chinese.lookingAt()) return value.substring(chinese.end()).stripLeading();
        var english = ENGLISH_EXPLICIT_OBLIGATION.matcher(value);
        return english.lookingAt() ? value.substring(english.end()).stripLeading() : value;
    }

    private static String obligationClause(String statement) {
        if (statement.startsWith("if ") || statement.startsWith("when ")) {
            int then = statement.indexOf(" then ");
            if (then >= 0 && then + " then ".length() < statement.length()) {
                return statement.substring(then + " then ".length()).stripLeading();
            }
            int comma = firstComma(statement);
            if (comma >= 0) return statement.substring(comma + 1).stripLeading();
        }
        if (statement.startsWith("当") || statement.startsWith("若") || statement.startsWith("如果")) {
            int boundary = chineseConditionBoundary(statement);
            if (boundary >= 0 && boundary + 1 < statement.length()) {
                return stripLeadingComma(statement.substring(boundary + 1));
            }
            // “当前/当班”等 ordinary nouns also start with 当. Only 如果 has an unambiguous condition prefix when
            // punctuation, rather than 时/则, is the sole boundary proof.
            if (statement.startsWith("如果")) {
                int comma = firstComma(statement);
                if (comma >= 0) return statement.substring(comma + 1).stripLeading();
            }
        }
        return statement;
    }

    private static int chineseConditionBoundary(String statement) {
        // A punctuation-delimited boundary is authoritative and must win over “时/则” inside field names.
        for (int index = 1; index < statement.length(); index++) {
            char value = statement.charAt(index);
            if (value != '时' && value != '则') continue;
            String remainder = statement.substring(index + 1).stripLeading();
            if (remainder.startsWith(",") || remainder.startsWith("，")) return index;
        }
        for (int index = 1; index < statement.length(); index++) {
            char value = statement.charAt(index);
            if (value != '时' && value != '则') continue;
            int next = nextChineseConditionMarker(statement, index + 1);
            String followingClause = statement.substring(index + 1, next < 0 ? statement.length() : next);
            if (hasExplicitObligationMarker(followingClause)) return index;
        }
        return -1;
    }

    private static int nextChineseConditionMarker(String statement, int start) {
        int time = statement.indexOf('时', start);
        int then = statement.indexOf('则', start);
        if (time < 0) return then;
        if (then < 0) return time;
        return Math.min(time, then);
    }

    private static String stripLeadingComma(String value) {
        String stripped = value.stripLeading();
        return stripped.startsWith(",") || stripped.startsWith("，")
                ? stripped.substring(1).stripLeading() : stripped;
    }

    private static int firstComma(String value) {
        int ascii = value.indexOf(',');
        int fullWidth = value.indexOf('，');
        if (ascii < 0) return fullWidth;
        if (fullWidth < 0) return ascii;
        return Math.min(ascii, fullWidth);
    }

    /**
     * Verifies conservative semantic closure inside one already-grounded continuous quotation.
     *
     * <p>The statement is a Java-owned atomic semantic description, not another verbatim quote. It may omit or
     * reorder source components and may use a conventional initialism, but it cannot introduce a new letter,
     * number, unit symbol, quoted literal, modality, comparison, or business-bearing Han character. Each quote is
     * evaluated alone so adjacent units or multiple quotes can never be stitched into an invented fact.</p>
     *
     * [Req-ID]: REQ-TGV2-012
     */
    private static void validateDirectEvidence(String statement, List<StructuredSourceQuoteV2> quotes, int index) {
        EnumSet<StructuredValidationFailure.DirectEvidenceReason> reasons =
                EnumSet.noneOf(StructuredValidationFailure.DirectEvidenceReason.class);
        for (String quote : quotes.stream().map(StructuredSourceQuoteV2::quote).distinct().toList()) {
            DirectEvidenceAnalysis analysis = quoteSupportsStatement(quote, statement);
            if (analysis.supported()) return;
            reasons.addAll(analysis.reasons());
        }
        throw new StructuredValidationException(StructuredValidationFailure.directEvidence(
                "$.requirement_facts[" + index + "].statement", reasons));
    }

    private static DirectEvidenceAnalysis quoteSupportsStatement(String quote, String statement) {
        if (!quotedLiteralsSupported(statement, quote)) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.LITERAL_UNSUPPORTED);
        }
        EnumSet<StructuredValidationFailure.DirectEvidenceReason> reasons =
                EnumSet.noneOf(StructuredValidationFailure.DirectEvidenceReason.class);
        for (String candidate : statementVariants(statement, quote)) {
            DirectEvidenceAnalysis analysis = quoteSupportsStatementVariant(quote, candidate);
            if (analysis.supported()) return analysis;
            reasons.addAll(analysis.reasons());
        }
        return DirectEvidenceAnalysis.rejected(reasons);
    }

    private static DirectEvidenceAnalysis quoteSupportsStatementVariant(String quote, String statement) {
        if (sameSemanticTokens(quote, statement)) return DirectEvidenceAnalysis.accepted();
        if (differsOnlyByBindingClausePunctuation(quote, statement)) return DirectEvidenceAnalysis.accepted();
        MatchingTextPair pair = reduceMatchingFieldCollection(quote, statement);
        if (pair == null) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.TOKEN_ORDER_OR_ADDITION);
        }
        if (sameSemanticTokens(pair.quote(), pair.statement())) return DirectEvidenceAnalysis.accepted();

        List<String> quoteClauses = semanticClauses(pair.quote());
        List<String> statementClauses = semanticClauses(pair.statement());
        // A model-selected quote can be narrower than its parsed unit. Once selected, however, dropping a complete
        // quote clause is not a provable connector/subject omission because that clause may carry a condition or role.
        if (quoteClauses.isEmpty() || statementClauses.isEmpty()
                || quoteClauses.size() != statementClauses.size()) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.CLAUSE_COUNT_MISMATCH);
        }
        if (statementClauses.size() > 1) {
            return clausesSupportedContiguously(quoteClauses, statementClauses);
        }

        String statementClause = statementClauses.get(0);
        String quoteClause = quoteClauses.get(0);
        if (dropsBindingPrefix(quoteClause, statementClause)) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.BINDING_PREFIX_DROPPED);
        }
        return clauseSupportsStatement(quoteClause, statementClause);
    }

    private static List<String> semanticClauses(String value) {
        return java.util.Arrays.stream(normalizedSemanticText(value).split("[，,；;。！？!?]+"))
                .map(String::strip).filter(clause -> !clause.isEmpty()).toList();
    }

    private static DirectEvidenceAnalysis clausesSupportedContiguously(
            List<String> quoteClauses, List<String> statementClauses) {
        for (int index = 0; index < statementClauses.size(); index++) {
            String quoteClause = quoteClauses.get(index);
            String statementClause = statementClauses.get(index);
            if (dropsBindingPrefix(quoteClause, statementClause)) {
                return DirectEvidenceAnalysis.rejected(
                        StructuredValidationFailure.DirectEvidenceReason.BINDING_PREFIX_DROPPED);
            }
            DirectEvidenceAnalysis analysis = clauseSupportsStatement(quoteClause, statementClause);
            if (!analysis.supported()) return analysis;
        }
        return DirectEvidenceAnalysis.accepted();
    }

    private static DirectEvidenceAnalysis clauseSupportsStatement(String quoteClause, String statementClause) {
        return orderedClauseSupport(quoteClause, statementClause);
    }

    private static DirectEvidenceAnalysis orderedClauseSupport(String quoteClause, String statementClause) {
        List<SemanticToken> quoteTokens = semanticTokens(quoteClause);
        List<SemanticToken> statementTokens = semanticTokens(statementClause);
        if (statementTokens.isEmpty() || quoteTokens.isEmpty()) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.TOKEN_ORDER_OR_ADDITION);
        }
        if (!canonicalControls(statementClause).equals(canonicalControls(quoteClause))) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.CONTROL_MISMATCH);
        }
        List<SemanticToken> matched = orderedSuffixMatch(statementTokens, quoteTokens);
        if (matched.isEmpty()) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.TOKEN_ORDER_OR_ADDITION);
        }
        if (!hasOnlyOmittableInternalGaps(quoteTokens, matched)
                || omitsConnectorBetweenRepeatedObligations(statementClause, quoteTokens, matched)) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.UNSAFE_INTERNAL_GAP);
        }
        if (!hasSafeBoundaryOmissions(quoteClause, quoteTokens, matched)) {
            return DirectEvidenceAnalysis.rejected(
                    StructuredValidationFailure.DirectEvidenceReason.UNSAFE_BOUNDARY_OMISSION);
        }
        return DirectEvidenceAnalysis.accepted();
    }

    private static boolean omitsConnectorBetweenRepeatedObligations(
            String statementClause, List<SemanticToken> quoteTokens, List<SemanticToken> matched) {
        if (explicitObligationCount(statementClause) < 2) return false;
        Map<Integer, Integer> quoteIndexes = new java.util.HashMap<>();
        for (int index = 0; index < quoteTokens.size(); index++) quoteIndexes.put(quoteTokens.get(index).start(), index);
        for (int index = 1; index < matched.size(); index++) {
            int previous = quoteIndexes.get(matched.get(index - 1).start());
            int current = quoteIndexes.get(matched.get(index).start());
            String gap = quoteTokens.subList(previous + 1, current).stream()
                    .map(SemanticToken::value).collect(java.util.stream.Collectors.joining());
            if (!gap.isEmpty() && COORDINATOR.matcher(gap).find()) return true;
        }
        return false;
    }

    private static int explicitObligationCount(String value) {
        int count = 0;
        Matcher chinese = CHINESE_EXPLICIT_OBLIGATION.matcher(value);
        while (chinese.find()) count++;
        Matcher english = ENGLISH_EXPLICIT_OBLIGATION.matcher(value);
        while (english.find()) count++;
        return count;
    }

    private static boolean dropsBindingPrefix(String quoteClause, String statementClause) {
        String quote = normalizedSemanticText(quoteClause);
        String statement = normalizedSemanticText(statementClause);
        String prefix = bindingPrefix(quote);
        if (prefix != null && !statement.startsWith(prefix)) return true;
        if ((quote.startsWith("only ") || quote.startsWith("solely "))
                && !(statement.startsWith("only ") || statement.startsWith("solely "))) return true;
        return false;
    }

    private static String bindingPrefix(String value) {
        Matcher chinese = CHINESE_BINDING_PREFIX.matcher(value);
        if (chinese.find()) return chinese.group(1);
        Matcher english = ENGLISH_BINDING_PREFIX.matcher(value);
        return english.find() ? english.group(1) : null;
    }

    private static List<String> canonicalControls(String value) {
        List<String> controls = new ArrayList<>();
        var matcher = SEMANTIC_CONTROL.matcher(normalizedText(value));
        while (matcher.find()) {
            String control = matcher.group().toLowerCase(Locale.ROOT);
            if (!REPEATABLE_MODAL_CONTROLS.contains(control) || !controls.contains(control)) controls.add(control);
        }
        return List.copyOf(controls);
    }

    private static boolean differsOnlyByBindingClausePunctuation(String quote, String statement) {
        String normalizedQuote = Normalizer.normalize(quote, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        int comma = firstComma(normalizedQuote);
        if (comma < 0 || !isBindingClause(normalizedQuote.substring(0, comma))
                || hasAmbiguousRoleStatePrefix(normalizedQuote.substring(0, comma))) return false;
        String withoutComma = normalizedQuote.substring(0, comma) + normalizedQuote.substring(comma + 1);
        return sameSemanticTokens(withoutComma, statement);
    }

    private static boolean isBindingClause(String value) {
        String clause = value.strip();
        if (CHINESE_PUNCTUATION_BINDING_CLAUSE.matcher(clause).matches()) return true;
        Matcher english = ENGLISH_BINDING_PREFIX.matcher(clause);
        return english.find() && normalizedSemanticText(english.group(1)).equals(normalizedSemanticText(clause));
    }

    /** Preserves punctuation when the same characters can form a role-state phrase instead of a condition prefix. */
    private static boolean hasAmbiguousRoleStatePrefix(String clause) {
        return clause.startsWith("仅当班") || clause.startsWith("只在岗") || clause.startsWith("仅在岗");
    }

    private static boolean quotedLiteralsSupported(String statement, String quote) {
        String normalizedQuote = normalizedCaseSensitiveGrounding(quote);
        var matcher = QUOTED_LITERAL.matcher(Normalizer.normalize(statement, Normalizer.Form.NFKC));
        while (matcher.find()) {
            String literal = null;
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    literal = matcher.group(group);
                    break;
                }
            }
            if (literal != null && !normalizedQuote.contains(normalizedCaseSensitiveGrounding(literal))) return false;
        }
        return true;
    }

    private static List<SemanticToken> semanticTokens(String value) {
        List<SemanticToken> tokens = new ArrayList<>();
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        for (int offset = 0; offset < normalized.length();) {
            int codePoint = normalized.codePointAt(offset);
            NumericToken numeric = signedNumericToken(normalized, offset);
            if (numeric != null) {
                tokens.add(new SemanticToken(numeric.value(), offset, numeric.end()));
                offset = numeric.end();
                continue;
            }
            if (isNumericSign(codePoint) && hasFollowingAsciiDigit(normalized, offset)) {
                int end = offset + Character.charCount(codePoint);
                while (end < normalized.length()) {
                    int next = normalized.codePointAt(end);
                    if (!(next >= '0' && next <= '9') && next != '.') break;
                    end += Character.charCount(next);
                }
                tokens.add(new SemanticToken(normalized.substring(offset, end), offset, end));
                offset = end;
                continue;
            }
            if (isIgnorableSemanticSeparator(codePoint)) {
                offset += Character.charCount(codePoint);
                continue;
            }
            if (isAsciiAlphaNumeric(codePoint)) {
                int end = offset + Character.charCount(codePoint);
                while (end < normalized.length() && isAsciiAlphaNumeric(normalized.codePointAt(end))) {
                    end += Character.charCount(normalized.codePointAt(end));
                }
                tokens.add(new SemanticToken(normalized.substring(offset, end), offset, end));
                offset = end;
                continue;
            }
            if (Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN) {
                int end = offset + Character.charCount(codePoint);
                tokens.add(new SemanticToken(new String(Character.toChars(codePoint)), offset, end));
            } else if (Character.isLetterOrDigit(codePoint)) {
                int end = offset + Character.charCount(codePoint);
                while (end < normalized.length()) {
                    int next = normalized.codePointAt(end);
                    if (!Character.isLetterOrDigit(next)
                            || Character.UnicodeScript.of(next) == Character.UnicodeScript.HAN) break;
                    end += Character.charCount(next);
                }
                tokens.add(new SemanticToken(normalized.substring(offset, end), offset, end));
                offset = end;
                continue;
            } else {
                int end = offset + Character.charCount(codePoint);
                tokens.add(new SemanticToken(new String(Character.toChars(codePoint)), offset, end));
            }
            offset += Character.charCount(codePoint);
        }
        return List.copyOf(tokens);
    }

    private static boolean sameSemanticTokens(String left, String right) {
        return semanticTokens(left).stream().map(SemanticToken::value).toList()
                .equals(semanticTokens(right).stream().map(SemanticToken::value).toList());
    }

    private static List<SemanticToken> orderedSuffixMatch(
            List<SemanticToken> statement, List<SemanticToken> quote) {
        List<SemanticToken> reversed = new ArrayList<>(statement.size());
        int quoteIndex = quote.size() - 1;
        for (int statementIndex = statement.size() - 1; statementIndex >= 0; statementIndex--) {
            SemanticToken expected = statement.get(statementIndex);
            while (quoteIndex >= 0 && !expected.value().equals(quote.get(quoteIndex).value())) {
                quoteIndex--;
            }
            if (quoteIndex < 0) return List.of();
            reversed.add(quote.get(quoteIndex--));
        }
        java.util.Collections.reverse(reversed);
        return List.copyOf(reversed);
    }

    private static boolean hasOnlyOmittableInternalGaps(
            List<SemanticToken> quoteTokens, List<SemanticToken> matched) {
        Map<Integer, Integer> quoteIndexes = new java.util.HashMap<>();
        for (int index = 0; index < quoteTokens.size(); index++) {
            quoteIndexes.put(quoteTokens.get(index).start(), index);
        }
        for (int index = 1; index < matched.size(); index++) {
            int previous = quoteIndexes.get(matched.get(index - 1).start());
            int current = quoteIndexes.get(matched.get(index).start());
            List<SemanticToken> gap = quoteTokens.subList(previous + 1, current);
            if (!isOmittableGap(gap)) return false;
        }
        return true;
    }

    private static boolean isOmittableGap(List<SemanticToken> gap) {
        if (gap.isEmpty()) return true;
        if (gap.stream().allMatch(token -> OMITTABLE_ENGLISH_TOKENS.contains(token.value()))) return true;
        String joined = gap.stream().map(SemanticToken::value).collect(java.util.stream.Collectors.joining());
        return OMITTABLE_HAN_GAP.matcher(joined).matches();
    }

    /** Only a generic system subject may be omitted; roles, conditions and relationship participants remain. */
    private static boolean hasSafeBoundaryOmissions(
            String quoteClause, List<SemanticToken> quoteTokens, List<SemanticToken> matched) {
        Map<Integer, Integer> indexes = new java.util.HashMap<>();
        for (int index = 0; index < quoteTokens.size(); index++) indexes.put(quoteTokens.get(index).start(), index);
        int first = indexes.get(matched.get(0).start());
        int last = indexes.get(matched.get(matched.size() - 1).start());
        if (last != quoteTokens.size() - 1) return false;
        if (first == 0) return true;
        String normalized = Normalizer.normalize(quoteClause, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        String prefix = normalized.substring(0, matched.get(0).start()).strip();
        if (Set.of("the", "a", "an").contains(prefix)) return true;
        String remainder = normalized.substring(matched.get(0).start()).stripLeading();
        if (Set.of("该", "本").contains(prefix) && remainder.startsWith("系统")) return true;
        if (!Set.of("系统", "本系统", "该系统", "system", "the system").contains(prefix)) return false;
        return startsWithExplicitObligation(remainder)
                || startsWithObligationAfterPreservedPrefix(remainder)
                || startsWithScopeControlledObligation(remainder);
    }

    private static boolean startsWithExplicitObligation(String value) {
        return CHINESE_EXPLICIT_OBLIGATION.matcher(value).lookingAt()
                || ENGLISH_EXPLICIT_OBLIGATION.matcher(value).lookingAt();
    }

    private static boolean startsWithObligationAfterPreservedPrefix(String value) {
        String remainder = value.stripLeading();
        int obligationStart = firstObligationMarker(remainder);
        if (obligationStart <= 0) return false;
        return isPreservedPrefixSequence(remainder.substring(0, obligationStart).strip());
    }

    /**
     * A generic system subject can disappear only when every word before the obligation remains a complete condition,
     * state or scope control. The deliberately small grammar proves the omission without learning project verbs.
     */
    private static boolean isPreservedPrefixSequence(String value) {
        if (value.isEmpty()) return false;
        if (isPreservedStateOrScopeModifier(value) || isCompleteBindingPrefix(value)) return true;
        return stripPreservedModifierPrefix(value).isEmpty()
                || isCompleteBindingPrefix(stripPreservedModifierPrefix(value))
                || stripPreservedModifierSuffix(value).isEmpty()
                || isCompleteBindingPrefix(stripPreservedModifierSuffix(value));
    }

    private static String stripPreservedModifierPrefix(String value) {
        String remainder = value.strip();
        boolean changed;
        do {
            changed = false;
            for (String modifier : PRESERVED_STATE_OR_SCOPE_MODIFIERS) {
                if (!startsWithWordOrHan(remainder, modifier)) continue;
                remainder = remainder.substring(modifier.length()).stripLeading();
                changed = true;
                break;
            }
        } while (changed && !remainder.isEmpty());
        return remainder;
    }

    private static String stripPreservedModifierSuffix(String value) {
        String prefix = value.strip();
        boolean changed;
        do {
            changed = false;
            for (String modifier : PRESERVED_STATE_OR_SCOPE_MODIFIERS) {
                if (!endsWithWordOrHan(prefix, modifier)) continue;
                prefix = prefix.substring(0, prefix.length() - modifier.length()).stripTrailing();
                changed = true;
                break;
            }
        } while (changed && !prefix.isEmpty());
        return prefix;
    }

    private static boolean startsWithScopeControlledObligation(String value) {
        String normalized = normalizedSemanticText(value);
        if (normalized.startsWith("仅限") && normalized.length() > "仅限".length()) return true;
        if (!normalized.startsWith("只有") || normalized.length() <= "只有".length()) return false;
        return firstObligationMarker(normalized.substring("只有".length())) >= 0;
    }

    private static boolean isCompleteBindingPrefix(String value) {
        String binding = bindingPrefix(value);
        return binding != null && normalizedSemanticText(binding).equals(normalizedSemanticText(value));
    }

    private static boolean isPreservedStateOrScopeModifier(String value) {
        return PRESERVED_STATE_OR_SCOPE_MODIFIERS.stream()
                .anyMatch(modifier -> normalizedSemanticText(modifier).equals(normalizedSemanticText(value)));
    }

    private static boolean startsWithWordOrHan(String value, String prefix) {
        if (!value.regionMatches(true, 0, prefix, 0, prefix.length())) return false;
        return containsHan(prefix) || value.length() == prefix.length()
                || !Character.isLetterOrDigit(value.codePointAt(prefix.length()));
    }

    private static boolean endsWithWordOrHan(String value, String suffix) {
        int start = value.length() - suffix.length();
        if (start < 0 || !value.regionMatches(true, start, suffix, 0, suffix.length())) return false;
        return containsHan(suffix) || start == 0 || !Character.isLetterOrDigit(value.codePointBefore(start));
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint ->
                Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static boolean isIgnorableSemanticSeparator(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint);
    }

    private static boolean isAsciiAlphaNumeric(int codePoint) {
        return codePoint >= 'a' && codePoint <= 'z' || codePoint >= '0' && codePoint <= '9';
    }

    private static boolean isNumericSign(int codePoint) {
        return codePoint == '-' || codePoint == '+' || codePoint == 0x2212;
    }

    private static NumericToken signedNumericToken(String value, int offset) {
        int codePoint = value.codePointAt(offset);
        boolean negative = codePoint == '负' || codePoint == '-' || codePoint == 0x2212;
        if (!negative && codePoint != '+') return null;
        int numberStart = offset + Character.charCount(codePoint);
        while (numberStart < value.length()) {
            int next = value.codePointAt(numberStart);
            if (!Character.isWhitespace(next) && !Character.isSpaceChar(next)) break;
            numberStart += Character.charCount(next);
        }
        if (numberStart >= value.length() || value.charAt(numberStart) < '0' || value.charAt(numberStart) > '9') {
            return null;
        }
        int end = numberStart;
        while (end < value.length()) {
            int next = value.codePointAt(end);
            if (!(next >= '0' && next <= '9') && next != '.') break;
            end += Character.charCount(next);
        }
        return new NumericToken((negative ? "-" : "+") + value.substring(numberStart, end), end);
    }

    private static boolean hasFollowingAsciiDigit(String value, int offset) {
        int nextOffset = offset + Character.charCount(value.codePointAt(offset));
        return nextOffset < value.length() && value.charAt(nextOffset) >= '0' && value.charAt(nextOffset) <= '9';
    }

    private static FieldCollection fieldCollection(String clause) {
        String normalized = normalizedSemanticText(clause);
        FieldCollection chinese = parseChineseFieldCollection(normalized);
        FieldCollection english = parseEnglishFieldCollection(normalized);
        return chinese == null ? english : english == null ? chinese : null;
    }

    private static FieldCollection parseChineseFieldCollection(String normalized) {
        return parseFieldCollection(normalized, CHINESE_FIELD_COLLECTION, FieldSyntax.CHINESE);
    }

    private static FieldCollection parseEnglishFieldCollection(String normalized) {
        return parseFieldCollection(normalized, ENGLISH_FIELD_COLLECTION, FieldSyntax.ENGLISH);
    }

    private static FieldCollection parseFieldCollection(
            String normalized, Pattern pattern, FieldSyntax syntax) {
        FieldCollection found = null;
        var matcher = pattern.matcher(normalized);
        while (matcher.find()) {
            List<String> parsedMembers = syntax == FieldSyntax.CHINESE
                    ? chineseFieldMembers(matcher.group(1))
                    : englishFieldMembers(matcher.group(1));
            if (parsedMembers.isEmpty() || found != null) return null;
            Set<String> members = new LinkedHashSet<>(parsedMembers);
            if (members.size() != parsedMembers.size()) return null;
            String base = normalized.substring(0, matcher.start(1)) + " __field_collection__ "
                    + normalized.substring(matcher.end(1));
            found = new FieldCollection(normalizedSemanticText(base), Set.copyOf(members), syntax);
        }
        return found;
    }

    private static List<String> chineseFieldMembers(String value) {
        List<String> members = new ArrayList<>();
        for (String commaPart : splitOutsideQuotedLabels(value, FieldSyntax.CHINESE)) {
            List<String> coordinated = splitChineseCoordinatedMembers(commaPart);
            if (coordinated.size() > 1 && coordinated.stream().allMatch(member ->
                    isBooleanFieldMember(member) || explicitFieldLabel(normalizedSemanticText(member)) != null)) {
                members.addAll(coordinated);
            } else {
                members.add(normalizedSemanticText(commaPart));
            }
        }
        // A delimiter proves boundaries, not that arbitrary phrases are field labels. Every member must independently
        // identify itself as a quoted label or a boolean question; this avoids language- or project-specific verb lists.
        return validatedFieldMembers(members);
    }

    private static List<String> splitChineseCoordinatedMembers(String value) {
        List<String> members = new ArrayList<>();
        int start = 0;
        char quoteEnd = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char current = value.charAt(offset);
            if (quoteEnd != 0) {
                if (closesQuotedSpan(value, offset, current, quoteEnd)) quoteEnd = 0;
                continue;
            }
            quoteEnd = openingQuote(value, offset, current);
            if (quoteEnd != 0) continue;
            if (current != '和' && current != '与' && current != '及' && current != '或') continue;
            String left = normalizedSemanticText(value.substring(start, offset));
            if (left.isEmpty()) continue;
            members.add(left);
            start = offset + 1;
        }
        if (quoteEnd != 0) return List.of();
        members.add(normalizedSemanticText(value.substring(start)));
        return members;
    }

    private static List<String> englishFieldMembers(String value) {
        return validatedFieldMembers(splitOutsideQuotedLabels(value, FieldSyntax.ENGLISH));
    }

    private static List<String> splitOutsideQuotedLabels(String value, FieldSyntax syntax) {
        List<String> members = new ArrayList<>();
        int start = 0;
        char quoteEnd = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char current = value.charAt(offset);
            if (quoteEnd != 0) {
                if (closesQuotedSpan(value, offset, current, quoteEnd)) quoteEnd = 0;
                continue;
            }
            quoteEnd = openingQuote(value, offset, current);
            if (quoteEnd != 0) continue;
            if (syntax == FieldSyntax.CHINESE && (current == '，' || current == ',' || current == '、')) {
                members.add(value.substring(start, offset));
                start = offset + 1;
                continue;
            }
            if (syntax == FieldSyntax.ENGLISH && current == ',') {
                members.add(value.substring(start, offset));
                start = offset + 1;
                continue;
            }
            if (syntax == FieldSyntax.ENGLISH) {
                int coordinatorEnd = englishCoordinatorEnd(value, offset);
                if (coordinatorEnd > offset) {
                    String before = value.substring(start, offset);
                    if (!before.isBlank()) members.add(before);
                    else if (members.isEmpty()) return List.of();
                    start = coordinatorEnd;
                    offset = coordinatorEnd - 1;
                }
            }
        }
        if (quoteEnd != 0) return List.of();
        members.add(value.substring(start));
        return List.copyOf(members);
    }

    private static List<String> commaClausesOutsideQuotes(String value) {
        List<String> clauses = new ArrayList<>();
        int start = 0;
        char quoteEnd = 0;
        for (int offset = 0; offset < value.length(); offset++) {
            char current = value.charAt(offset);
            if (quoteEnd != 0) {
                if (closesQuotedSpan(value, offset, current, quoteEnd)) quoteEnd = 0;
                continue;
            }
            quoteEnd = openingQuote(value, offset, current);
            if (quoteEnd != 0) continue;
            if (current != ',' && current != '，') continue;
            clauses.add(value.substring(start, offset));
            start = offset + 1;
        }
        if (quoteEnd != 0) return List.of();
        clauses.add(value.substring(start));
        return List.copyOf(clauses);
    }

    private static int englishCoordinatorEnd(String value, int offset) {
        for (String coordinator : List.of("and", "or")) {
            int end = offset + coordinator.length();
            if (end > value.length() || !value.regionMatches(true, offset, coordinator, 0, coordinator.length())) continue;
            boolean leftBoundary = offset == 0 || !Character.isLetterOrDigit(value.codePointBefore(offset));
            boolean rightBoundary = end == value.length() || !Character.isLetterOrDigit(value.codePointAt(end));
            if (leftBoundary && rightBoundary) return end;
        }
        return -1;
    }

    private static boolean closesQuotedSpan(String value, int offset, char current, char quoteEnd) {
        if (current != quoteEnd) return false;
        return !isWordInternalApostrophe(value, offset, current);
    }

    private static char closingQuote(char value) {
        return switch (value) {
            case '"', '\'' -> value;
            case '“' -> '”';
            case '‘' -> '’';
            default -> 0;
        };
    }

    private static char openingQuote(String value, int offset, char opener) {
        if (isWordInternalApostrophe(value, offset, opener)) return 0;
        return closingQuote(opener);
    }

    private static boolean isWordInternalApostrophe(String value, int offset, char candidate) {
        if (candidate != '\'' && candidate != '’') return false;
        int next = offset + Character.charCount(candidate);
        if (offset <= 0 || next >= value.length()) return false;
        int before = value.codePointBefore(offset);
        int after = value.codePointAt(next);
        // Apostrophes are word-internal only inside a Latin word or number. CJK text adjacent to an ASCII quote is
        // therefore parsed as a quoted literal, while user's/O’Connor and numeric separators remain intact.
        return isLatinWordCharacter(before) && isLatinWordCharacter(after);
    }

    private static boolean isLatinWordCharacter(int codePoint) {
        return Character.isDigit(codePoint)
                || Character.isLetter(codePoint)
                && Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN;
    }

    private static List<String> validatedFieldMembers(List<String> rawMembers) {
        List<String> members = new ArrayList<>();
        for (String raw : rawMembers) {
            String member = normalizedSemanticText(raw);
            if (member.isEmpty()) return List.of();
            if (isBooleanFieldMember(member)) {
                members.add(member);
                continue;
            }
            String label = explicitFieldLabel(member);
            if (label == null) return List.of();
            members.add(label);
        }
        return List.copyOf(members);
    }

    private static String explicitFieldLabel(String member) {
        if (member.length() < 2) return null;
        int first = member.codePointAt(0);
        int lastIndex = member.offsetByCodePoints(member.length(), -1);
        int last = member.codePointAt(lastIndex);
        boolean paired = first == '"' && last == '"' || first == '\'' && last == '\''
                || first == '“' && last == '”' || first == '‘' && last == '’';
        if (!paired) return null;
        String label = normalizedSemanticText(member.substring(Character.charCount(first), lastIndex));
        return label.isEmpty() ? null : label;
    }

    private static List<String> statementVariants(String statement, String quote) {
        Set<String> variants = new LinkedHashSet<>();
        variants.add(Normalizer.normalize(statement, Normalizer.Form.NFKC));
        for (String clause : semanticClauses(quote)) {
            String expanded = expandInitialisms(statement, quote, clause);
            if (expanded != null) variants.add(expanded);
        }
        return List.copyOf(variants);
    }

    private static String expandInitialisms(String statement, String quote, String clause) {
        String normalized = Normalizer.normalize(statement, Normalizer.Form.NFKC);
        var matcher = ASCII_INITIALISM.matcher(normalized);
        StringBuffer expanded = new StringBuffer(normalized.length());
        while (matcher.find()) {
            String initialism = matcher.group();
            String lower = initialism.toLowerCase(Locale.ROOT);
            String replacement = containsAsciiWord(clause, lower)
                    ? initialism : uniqueInitialismExpansion(quote, lower);
            if (replacement == null) return null;
            matcher.appendReplacement(expanded, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(expanded);
        return expanded.toString();
    }

    private static boolean containsAsciiWord(String value, String expected) {
        Matcher matcher = ASCII_WORD.matcher(Normalizer.normalize(value, Normalizer.Form.NFKC));
        while (matcher.find()) if (expected.equals(matcher.group().toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    private static String uniqueInitialismExpansion(String quote, String initialism) {
        String normalizedQuote = Normalizer.normalize(quote, Normalizer.Form.NFKC);
        List<EnglishWord> words = new ArrayList<>();
        var matcher = ASCII_WORD.matcher(normalizedQuote);
        while (matcher.find()) words.add(new EnglishWord(matcher.group().toLowerCase(Locale.ROOT), matcher.start(), matcher.end()));
        Set<String> matches = new LinkedHashSet<>();
        for (int start = 0; start + initialism.length() <= words.size(); start++) {
            StringBuilder initials = new StringBuilder(initialism.length());
            boolean continuous = true;
            for (int index = 0; index < initialism.length(); index++) {
                EnglishWord word = words.get(start + index);
                initials.append(word.value().charAt(0));
                if (index > 0 && !isOnlyLayoutWhitespace(normalizedQuote,
                        words.get(start + index - 1).end(), word.start())) continuous = false;
            }
            if (continuous && initialism.contentEquals(initials)) {
                matches.add(words.subList(start, start + initialism.length()).stream()
                        .map(EnglishWord::value).collect(java.util.stream.Collectors.joining(" ")));
            }
        }
        return matches.size() == 1 ? matches.iterator().next() : null;
    }

    private static boolean isOnlyLayoutWhitespace(String value, int start, int end) {
        if (start >= end) return false;
        for (int offset = start; offset < end;) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
    }

    private static MatchingTextPair reduceMatchingFieldCollection(String quote, String statement) {
        FieldCollection quoteCollection = fieldCollection(quote);
        FieldCollection statementCollection = fieldCollection(statement);
        if ((quoteCollection == null) != (statementCollection == null)) return null;
        if (quoteCollection == null) return new MatchingTextPair(quote, statement);
        if (quoteCollection.syntax() != statementCollection.syntax()
                || quoteCollection.members().size() < 2
                || !quoteCollection.members().containsAll(statementCollection.members())) return null;
        return new MatchingTextPair(quoteCollection.baseClause(), statementCollection.baseClause());
    }

    private record SemanticToken(String value, int start, int end) { }

    private record EnglishWord(String value, int start, int end) { }

    private enum FieldSyntax { CHINESE, ENGLISH }

    private record FieldCollection(String baseClause, Set<String> members, FieldSyntax syntax) { }

    private record MatchingTextPair(String quote, String statement) { }

    private record DirectEvidenceAnalysis(
            boolean supported, Set<StructuredValidationFailure.DirectEvidenceReason> reasons) {
        private DirectEvidenceAnalysis {
            reasons = Set.copyOf(reasons);
            if (supported == !reasons.isEmpty()) {
                throw new IllegalArgumentException("Direct-evidence analysis must be either accepted or rejected");
            }
        }

        private static DirectEvidenceAnalysis accepted() {
            return new DirectEvidenceAnalysis(true, Set.of());
        }

        private static DirectEvidenceAnalysis rejected(StructuredValidationFailure.DirectEvidenceReason reason) {
            return rejected(EnumSet.of(reason));
        }

        private static DirectEvidenceAnalysis rejected(
                Set<StructuredValidationFailure.DirectEvidenceReason> reasons) {
            if (reasons.isEmpty()) {
                throw new IllegalArgumentException("Rejected direct evidence requires a safe reason");
            }
            return new DirectEvidenceAnalysis(false, reasons);
        }
    }

    private record NumericToken(String value, int end) { }


    /** Returns the cross-window fact identity used by validation, persistence, and bounded recovery replay. */
    public static String stableFactKey(String functionKey, FactType type, String statement) {
        return "fact-" + sha256("requirement-fact-v2\n" + functionKey + "\n" + type.wireValue()
                + "\n" + normalizedText(statement));
    }

    /**
     * Keeps semantic fact identity independent from the window that happened to quote it while retaining the
     * complete union of exact source fragments. This is what makes restart order irrelevant without weakening
     * the per-window quote check performed before this method can run.
     */
    private static AcceptedFact mergeFactEvidence(AcceptedFact left, AcceptedFact right) {
        if (left.factType() != right.factType()
                || !normalizedText(left.statement()).equals(normalizedText(right.statement()))) {
            throw failure(StructuredValidationFailure.Code.FACT_DUPLICATE, "$.requirement_facts");
        }
        Map<String, StructuredSourceQuoteV2> quotes = new java.util.TreeMap<>(RequirementFactV2Validator::compareUtf8);
        java.util.stream.Stream.concat(left.sourceQuotes().stream(), right.sourceQuotes().stream())
                .forEach(quote -> quotes.putIfAbsent(
                        quote.evidenceKey() + "\u0000" + quote.quote(), quote));
        String statement = compareUtf8(left.statement(), right.statement()) <= 0
                ? left.statement() : right.statement();
        return new AcceptedFact(left.factKey(), left.factType(), statement, List.copyOf(quotes.values()));
    }

    private static int compareUtf8(String left, String right) {
        return java.util.Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8),
                right.getBytes(StandardCharsets.UTF_8));
    }

    private static String normalizedGrounding(String value) {
        String lower = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        lower.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)).forEach(result::appendCodePoint);
        return result.toString();
    }

    /** Preserves identifiers and quoted literals whose case can carry business meaning. */
    private static String normalizedCaseSensitiveGrounding(String value) {
        String normalized = Normalizer.normalize(value, Normalizer.Form.NFKC);
        StringBuilder result = new StringBuilder(normalized.length());
        normalized.codePoints().filter(codePoint -> !Character.isWhitespace(codePoint)
                && !Character.isSpaceChar(codePoint)).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static String normalizedSemanticText(String value) {
        String lower = Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        StringBuilder result = new StringBuilder(lower.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < lower.length();) {
            int codePoint = lower.codePointAt(offset);
            if (Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)) {
                pendingSpace = result.length() > 0;
            } else {
                if (pendingSpace) result.append(' ');
                result.appendCodePoint(codePoint);
                pendingSpace = false;
            }
            offset += Character.charCount(codePoint);
        }
        return result.toString().strip();
    }

    private static String normalizedText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ").strip();
    }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static StructuredValidationException failure(StructuredValidationFailure.Code code, String path) {
        return new StructuredValidationException(StructuredValidationFailure.of(code, path));
    }

    /** One Java-owned immutable fact ready for transactional persistence. */
    public record AcceptedFact(String factKey, FactType factType, String statement,
            List<StructuredSourceQuoteV2> sourceQuotes) {
        public AcceptedFact {
            sourceQuotes = List.copyOf(sourceQuotes);
        }
    }

    /** One non-blocking observation ready for the feedback projection. */
    public record AcceptedObservation(RequirementFactExtractionV2Result.ObservationType observationType,
            String description, List<FactType> affectedFactTypes, List<StructuredSourceQuoteV2> sourceQuotes) {
        public AcceptedObservation {
            affectedFactTypes = List.copyOf(affectedFactTypes);
            sourceQuotes = List.copyOf(sourceQuotes);
        }
    }

    /** Complete accepted output of one function/material window. */
    public record AcceptedWindow(String functionKey, String windowKey, List<AcceptedFact> facts,
            List<AcceptedObservation> observations) {
        public AcceptedWindow {
            facts = List.copyOf(facts);
            observations = List.copyOf(observations);
        }
    }

    /** Deterministically merged facts and non-blocking observations for one function. */
    public record AcceptedFunction(List<AcceptedFact> facts, List<AcceptedObservation> observations) {
        public AcceptedFunction {
            facts = List.copyOf(facts);
            observations = List.copyOf(observations);
        }
    }
}
