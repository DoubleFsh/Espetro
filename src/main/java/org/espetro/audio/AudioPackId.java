package org.espetro.audio;

/** Validation shared by the server-side formation index and client-side path resolver. */
public final class AudioPackId {
    public static final int MAX_LENGTH = 80;

    private AudioPackId() {
    }

    /**
     * Returns a trimmed, safe direct-child directory name, or {@code null} when invalid.
     */
    public static String normalize(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_LENGTH
            || ".".equals(normalized) || "..".equals(normalized)) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c < 32 || c == '/' || c == '\\' || c == ':' || c == '\0'
                || c == '*' || c == '?' || c == '"' || c == '<' || c == '>' || c == '|') {
                return null;
            }
        }
        return normalized;
    }
}
