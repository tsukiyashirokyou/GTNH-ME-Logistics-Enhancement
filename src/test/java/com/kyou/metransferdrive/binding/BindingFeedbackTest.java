package com.kyou.metransferdrive.binding;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import org.junit.Test;

/** Validates the server outcome/localization contract without claiming actual Forge interaction coverage. */
public class BindingFeedbackTest {

    @Test
    public void everyServerBindingOutcomeHasEnglishAndChineseFeedback() throws Exception {
        for (String locale : new String[] { "en_US", "zh_CN" }) {
            final Properties messages = language(locale);
            for (BindingToolLogic.Outcome outcome : BindingToolLogic.Outcome.values()) {
                final String key = "metransferdrive.binding." + outcome.name()
                    .toLowerCase(Locale.ROOT);
                final String message = messages.getProperty(key);
                assertNotNull(key, message);
                assertFalse(
                    message.trim()
                        .isEmpty());
                // The real server supplies the clicked dimension and coordinates, for this player only.
                final String rendered = String.format(Locale.ROOT, message, 7, 12345, 67, -23456);
                if (outcome == BindingToolLogic.Outcome.SOURCE_SELECTED || outcome == BindingToolLogic.Outcome.BOUND
                    || outcome == BindingToolLogic.Outcome.BOUND_PENDING
                    || outcome == BindingToolLogic.Outcome.BOUND_SAME_GRID
                    || outcome == BindingToolLogic.Outcome.DUPLICATE_INTERFACE) {
                    assertTrue(rendered.contains("12345"));
                    assertTrue(rendered.contains("-23456"));
                }
            }
        }
    }

    private static Properties language(final String locale) throws Exception {
        final Properties properties = new Properties();
        try (InputStream input = BindingFeedbackTest.class
            .getResourceAsStream("/assets/metransferdrive/lang/" + locale + ".lang")) {
            assertNotNull(input);
            properties.load(new InputStreamReader(input, StandardCharsets.UTF_8));
        }
        return properties;
    }
}
