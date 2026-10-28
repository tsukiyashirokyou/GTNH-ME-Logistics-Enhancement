package com.kyou.metransferdrive.gui;

/** Bounded normalization for ordinary configuration edits, never inventory or recovery evidence. */
public final class ConfigurationInput {

    public static final int MAX_NUMBER_LENGTH = 32;
    public static final int MAX_TEXT_BYTES = 256;

    private ConfigurationInput() {}

    public static int clampInteger(final String text, final int minimum, final int maximum) {
        if (text == null || text.isEmpty() || text.length() > MAX_NUMBER_LENGTH || minimum > maximum) {
            throw new IllegalArgumentException("Invalid configuration number");
        }
        final boolean negative = text.charAt(0) == '-';
        final int start = negative ? 1 : 0;
        if (start == text.length()) {
            throw new IllegalArgumentException("Expected decimal digits");
        }
        long magnitude = 0;
        for (int index = start; index < text.length(); index++) {
            final char digit = text.charAt(index);
            if (digit < '0' || digit > '9') {
                throw new IllegalArgumentException("Expected decimal digits");
            }
            // Saturate after the signed-int magnitude, but still validate every remaining digit.
            magnitude = Math.min(2147483649L, magnitude * 10 + digit - '0');
        }
        final long signed = negative ? -magnitude : magnitude;
        return (int) Math.max(minimum, Math.min(maximum, signed));
    }

    public static String alias(final String text) {
        if (text == null || text.length() > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Configuration text exceeds input budget");
        }
        // Validate the entire input, including the suffix to be truncated.
        for (int index = 0; index < text.length(); index++) {
            final char character = text.charAt(index);
            if (Character.isISOControl(character) || Character.isLowSurrogate(character)) {
                throw new IllegalArgumentException("Invalid configuration text");
            }
            if (Character.isHighSurrogate(character)) {
                if (++index >= text.length() || !Character.isLowSurrogate(text.charAt(index))) {
                    throw new IllegalArgumentException("Invalid surrogate pair");
                }
            }
        }
        final String trimmed = text.trim();
        int end = Math.min(64, trimmed.length());
        if (end > 0 && Character.isHighSurrogate(trimmed.charAt(end - 1))) {
            end--;
        }
        final String normalized = trimmed.substring(0, end)
            .trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("Empty target alias");
        }
        // At most 64 UTF-16 units, hence at most 192 UTF-8 bytes, below the 256 byte protocol budget.
        return normalized;
    }
}
