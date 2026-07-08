package com.probeflow.testagent.llm;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class LlmAuditSanitizer {

    public static final int MAX_PROMPT_SUMMARY_LENGTH = 2_000;
    public static final int MAX_RESPONSE_SUMMARY_LENGTH = 2_000;
    public static final int MAX_ERROR_MESSAGE_LENGTH = 1_000;

    private static final Pattern CREDENTIAL_PATTERN = Pattern.compile(
        "(?i)\\b(api[-_ ]?key|x-api-key)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern AUTHORIZATION = Pattern.compile(
        "(?i)\\b(authorization)\\b\\s*[:=]\\s*([^\\r\\n,;]+)"
    );
    private static final Pattern SENSITIVE_HEADER = Pattern.compile(
        "(?i)\\b(cookie|set-cookie|proxy-authorization)\\b\\s*[:=]\\s*([^\\r\\n,;]+)"
    );
    private static final Pattern TOKEN = Pattern.compile(
        "(?i)\\b(access_token|refresh_token|token)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern PASSWORD = Pattern.compile(
        "(?i)\\b(password|passwd|secret)\\b\\s*[:=]\\s*([^\\s,;]+)"
    );
    private static final Pattern BEARER = Pattern.compile(
        "(?i)\\bbearer\\s+[^\\s,;]+"
    );

    public String promptSummary(String prompt) {
        return sanitizeAndTruncate(prompt, MAX_PROMPT_SUMMARY_LENGTH);
    }

    public String responseSummary(String response) {
        return sanitizeAndTruncate(response, MAX_RESPONSE_SUMMARY_LENGTH);
    }

    public String errorMessage(String errorMessage) {
        return sanitizeAndTruncate(errorMessage, MAX_ERROR_MESSAGE_LENGTH);
    }

    private String sanitizeAndTruncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        var sanitized = redact(value);
        if (sanitized.length() <= maxLength) {
            return sanitized;
        }
        var suffix = "\n[TRUNCATED chars=" + sanitized.length()
            + " sha256=" + sha256(sanitized).substring(0, 16) + "]";
        var headLength = Math.max(0, maxLength - suffix.length());
        return sanitized.substring(0, headLength) + suffix;
    }

    private String redact(String value) {
        var redacted = CREDENTIAL_PATTERN.matcher(value).replaceAll("$1=[REDACTED]");
        redacted = AUTHORIZATION.matcher(redacted).replaceAll("$1: [REDACTED]");
        redacted = SENSITIVE_HEADER.matcher(redacted).replaceAll("$1: [REDACTED]");
        redacted = TOKEN.matcher(redacted).replaceAll("$1=[REDACTED]");
        redacted = PASSWORD.matcher(redacted).replaceAll("$1=[REDACTED]");
        return BEARER.matcher(redacted).replaceAll("Bearer [REDACTED]");
    }

    private String sha256(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 not available", exception);
        }
    }
}
