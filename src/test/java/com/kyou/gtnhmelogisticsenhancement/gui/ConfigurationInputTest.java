package com.kyou.gtnhmelogisticsenhancement.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

import org.junit.Test;

public class ConfigurationInputTest {

    @Test
    public void periodClampsAtBothBoundsIncludingBeyondLongRange() {
        for (String value : new String[] { "86401", "2147483648", "9223372036854775808",
            "99999999999999999999999999999999" }) {
            assertEquals(86400, ConfigurationInput.clampInteger(value, 1, 86400));
        }
        for (String value : new String[] { "0", "-1", "-9223372036854775809" }) {
            assertEquals(1, ConfigurationInput.clampInteger(value, 1, 86400));
        }
        assertEquals(60, ConfigurationInput.clampInteger("00060", 1, 86400));
    }

    @Test
    public void priorityUsesTheFullSignedRangeWithoutParsingOverflow() {
        assertEquals(
            Integer.MAX_VALUE,
            ConfigurationInput.clampInteger("9223372036854775808", Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(
            Integer.MIN_VALUE,
            ConfigurationInput.clampInteger("-9223372036854775809", Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(
            Integer.MIN_VALUE,
            ConfigurationInput.clampInteger("-2147483648", Integer.MIN_VALUE, Integer.MAX_VALUE));
        assertEquals(-3, ConfigurationInput.clampInteger("-3", Integer.MIN_VALUE, Integer.MAX_VALUE));
    }

    @Test
    public void invalidNumericSyntaxIsNeverMadeIntoAConfigurationValue() {
        for (String value : new String[] { "", "-", "+1", " 1", "1.5", "1e3", "NaN", "99999999999999999999999999x",
            "1\n", "\uFF11" }) {
            try {
                ConfigurationInput.clampInteger(value, 1, 86400);
                throw new AssertionError("Accepted malformed number: " + value);
            } catch (IllegalArgumentException expected) {
                // The full bounded input is validated even after the numeric accumulator saturates.
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void extremelyLongNumbersExceedTheMemoryBudgetRatherThanBeingParsed() {
        ConfigurationInput.clampInteger(String.join("", Collections.nCopies(10000, "9")), 1, 86400);
    }

    @Test
    public void longAliasesAreTruncatedWithoutSplittingUnicodePairs() {
        final String prefix = String.join("", Collections.nCopies(63, "x"));
        assertEquals(prefix, ConfigurationInput.alias(prefix + "\uD83D\uDE80tail"));
        final String pairs = String.join("", Collections.nCopies(40, "\uD83D\uDE80"));
        assertEquals(
            32,
            ConfigurationInput.alias(pairs)
                .codePointCount(0, 64));
        final String chinese = ConfigurationInput.alias(String.join("", Collections.nCopies(80, "\u7f51")));
        assertEquals(64, chinese.length());
        assertTrue(chinese.getBytes(StandardCharsets.UTF_8).length <= 256);
        assertEquals("local name", ConfigurationInput.alias(" local name "));
    }

    @Test
    public void malformedSurrogatesControlsAndEmptyAliasesStillFail() {
        for (String value : new String[] { "", " ", "bad\nname", "\uD83D", "\uDE80", "a\uD83Db" }) {
            try {
                ConfigurationInput.alias(value);
                throw new AssertionError("Accepted malformed alias");
            } catch (IllegalArgumentException expected) {
                // Configuration normalization must not conceal malformed data.
            }
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unboundedAliasCannotReachTheNormalizer() {
        ConfigurationInput.alias(String.join("", Collections.nCopies(257, "a")));
    }
}
