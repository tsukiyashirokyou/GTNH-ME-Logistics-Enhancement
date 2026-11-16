package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

import javax.imageio.ImageIO;

import org.junit.Test;

public class MEEnergyMonitorResourcesTest {

    @Test
    public void itemAndEveryBadgeArePowerOfTwoTransparentTextures() throws Exception {
        for (final String name : new String[] { "items/me_energy_monitor", "blocks/me_eu_badge",
            "blocks/me_eu_badge_active", "blocks/me_eu_badge_high", "blocks/me_eu_badge_high_active",
            "blocks/me_eu_group_frame", "blocks/me_eu_group_stripe" }) {
            try (InputStream input = getClass()
                .getResourceAsStream("/assets/gtnhmelogisticsenhancement/textures/" + name + ".png")) {
                assertNotNull(name, input);
                final BufferedImage image = ImageIO.read(input);
                final int size = name.startsWith("items/") ? 32 : 16;
                assertEquals(size, image.getWidth());
                assertEquals(size, image.getHeight());
                assertTrue(
                    image.getColorModel()
                        .hasAlpha());
                assertEquals(0, image.getRGB(0, 0) >>> 24);
                int opaque = 0;
                for (int y = 0; y < size; y++)
                    for (int x = 0; x < size; x++) if ((image.getRGB(x, y) >>> 24) != 0) opaque++;
                assertTrue(opaque > 20);
                if (name.startsWith("blocks/")) {
                    // The native amperage/voltage port in the middle must remain completely visible.
                    for (int y = 4; y < 12; y++)
                        for (int x = 4; x < 12; x++) assertEquals(0, image.getRGB(x, y) >>> 24);
                }
            }
        }
    }

    @Test
    public void bothLanguagesCoverColorsPagesAndExactMeasurementTooltips() throws Exception {
        final Set<String> expected = new HashSet<>();
        for (final String key : new String[] { "name", "hint", "note", "denied", "warming", "no_sample", "pending",
            "ready", "counts", "loss", "row.flow", "row.stock", "row.limit" }) expected.add(key);
        for (int index = 0; index < 17; index++) expected.add("color." + index);
        for (int index = 0; index < 9; index++) expected.add("amount." + index);
        for (int index = 0; index < 3; index++) {
            expected.add("page." + index);
            expected.add("heading." + index);
        }
        for (final String language : new String[] { "en_US", "zh_CN" }) {
            final Properties entries = new Properties();
            try (InputStreamReader input = new InputStreamReader(
                getClass().getResourceAsStream("/assets/gtnhmelogisticsenhancement/lang/" + language + ".lang"),
                StandardCharsets.UTF_8)) {
                entries.load(input);
            }
            assertNotNull(entries.getProperty("item.meEnergyMonitor.name"));
            for (final String key : expected)
                assertNotNull(language + ": " + key, entries.getProperty("gtnhmelogisticsenhancement.monitor." + key));
            assertTrue(
                entries.getProperty("gtnhmelogisticsenhancement.monitor.amount.6")
                    .contains("%s"));
        }
    }
}
