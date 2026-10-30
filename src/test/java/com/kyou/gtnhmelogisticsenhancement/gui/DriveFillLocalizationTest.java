package com.kyou.gtnhmelogisticsenhancement.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import org.junit.Test;

public class DriveFillLocalizationTest {

    @Test
    public void fillLabelsAndHintsFormatInBothLanguagesWithoutTreatingPercentAsAPlaceholder() throws Exception {
        for (final String language : new String[] { "en_US", "zh_CN" }) {
            final String path = "assets/gtnhmelogisticsenhancement/lang/" + language + ".lang";
            final Properties values = new Properties();
            try (InputStream stream = this.getClass()
                .getClassLoader()
                .getResourceAsStream(path)) {
                assertNotNull(stream);
                values.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
            }
            for (final String key : new String[] { "fill_short", "fill_percent", "fill_hint", "fill_bound_hint" }) {
                final String text = values.getProperty("gtnhmelogisticsenhancement.gui." + key);
                assertNotNull(text);
                // StatCollector.translateToLocalFormatted ultimately uses String.format with these empty args.
                assertEquals(text.replace("%%", "%"), String.format(Locale.ROOT, text));
            }
        }
    }
}
