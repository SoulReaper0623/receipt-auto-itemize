package org.project.web;

/** Strips anything that could forge or break a log line (newlines, control chars) from user-controlled values. */
public final class LogSafe {

    private static final int MAX = 200;

    private LogSafe() {
    }

    public static String of(String value) {
        if (value == null) {
            return "null";
        }
        String cleaned = value.replaceAll("[^A-Za-z0-9/._-]", "_");
        if (cleaned.length() > MAX) {
            return cleaned.substring(0, MAX) + "...";
        }
        return cleaned;
    }
}
