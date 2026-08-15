package com.testcaseagent.markdown;

/**
 * [Req-ID]: REQ-KAG-004
 *
 * <p>Signals that a completed model answer cannot be assigned unambiguously to the fixed Markdown
 * contract. The message is deliberately safe to surface as a recoverable batch failure.</p>
 */
public final class MarkdownContractException extends RuntimeException {

    public MarkdownContractException(String reason) {
        super("Markdown contract invalid: " + reason);
    }
}
