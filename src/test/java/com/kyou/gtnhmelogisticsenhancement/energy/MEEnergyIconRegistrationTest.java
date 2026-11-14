package com.kyou.gtnhmelogisticsenhancement.energy;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.Test;

public class MEEnergyIconRegistrationTest {

    @Test
    public void eachHatchRegistersItsIconsBeforeAnyTooltipOrRender() throws Throwable {
        for (final String type : new String[] { "MTEHatchMEEnergyStandard", "MTEHatchMEDynamoStandard",
            "MTEHatchMEEnergyMulti", "MTEHatchMEDynamoMulti" }) {
            // Each hatch must work as the first ME EU item encountered in a fresh client.
            try (LaunchClassLoader loader = new LaunchClassLoader(
                ((URLClassLoader) getClass().getClassLoader()).getURLs())) {
                loader.addClassLoaderExclusion("org.junit.");
                loader.addClassLoaderExclusion("org.hamcrest.");
                loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
                final Class<?> fixture = loader
                    .loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyIconRegistrationFixture");
                try {
                    fixture.getMethod("registries")
                        .invoke(null);
                    fixture.getMethod("verify", String.class)
                        .invoke(null, type);
                } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                }
            }
        }
    }
}
