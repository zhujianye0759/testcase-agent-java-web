package com.testcaseagent.featureaudit;

import java.text.Normalizer;
import java.util.Locale;

/** Shared stable business-path identity used by reconciliation and freeze gates. */
final class BusinessPathNormalizer {
    private BusinessPathNormalizer() { }

    static String normalize(String path) {
        return Normalizer.normalize(path, Normalizer.Form.NFKC).strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }
}
