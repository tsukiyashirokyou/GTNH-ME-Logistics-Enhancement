package com.kyou.gtnhmelogisticsenhancement.energy;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class MEEnergyPerformanceTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;
    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "periodComparisonExposes125PercentP20GapsAndValidatesP10" },
                { "internalPolicyRejectsInvalidPeriodsAndPublicDefaultIsFixed" },
                { "optionalBenchmarkRecordsRealCacheCostsAndConservation" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(((URLClassLoader) MEEnergyPerformanceTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyPerformanceFixture");
        fixture.getMethod("registries")
            .invoke(null);
    }

    @AfterClass
    public static void close() throws Exception {
        try {
            if (fixture != null) fixture.getMethod("cleanup")
                .invoke(null);
        } finally {
            if (loader != null) loader.close();
            loader = null;
            fixture = null;
        }
    }

    @Test
    public void compatibilityScenario() throws Throwable {
        final Thread thread = Thread.currentThread();
        final String previous = thread.getName();
        try {
            thread.setName("Server thread");
            fixture.getMethod("begin")
                .invoke(null);
            fixture.getMethod(this.scenario)
                .invoke(
                    fixture.getDeclaredConstructor()
                        .newInstance());
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            try {
                fixture.getMethod("end")
                    .invoke(null);
            } finally {
                thread.setName(previous);
            }
        }
    }
}
