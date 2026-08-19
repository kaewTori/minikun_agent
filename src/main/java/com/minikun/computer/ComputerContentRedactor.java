package com.minikun.computer;

import java.util.regex.Pattern;

/** Prevents common credentials from entering model context or conversation history. */
final class ComputerContentRedactor {
    private static final Pattern ASSIGNMENT = Pattern.compile(
            "(?i)(password|passwd|secret|token|api[_-]?key|authorization)(\\s*[:=]\\s*)([^\\s,;]+)");
    private static final Pattern URL_CREDENTIALS = Pattern.compile("(https?://)[^/@\\s:]+:[^/@\\s]+@");
    private static final Pattern BEARER = Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*");

    String redact(String value) {
        String result = ASSIGNMENT.matcher(value).replaceAll("$1$2[REDACTED]");
        result = URL_CREDENTIALS.matcher(result).replaceAll("$1[REDACTED]@");
        return BEARER.matcher(result).replaceAll("$1[REDACTED]");
    }
}
