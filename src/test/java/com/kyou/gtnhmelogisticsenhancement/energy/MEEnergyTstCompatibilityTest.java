package com.kyou.gtnhmelogisticsenhancement.energy;

import java.lang.reflect.InvocationTargetException;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collection;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/** Optional verified TST 0.8.0 profile; never silently substitute TST source or a test stub. */
@RunWith(Parameterized.class)
public class MEEnergyTstCompatibilityTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;
    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "acalRunsOriginalPostStructureAmpAndMixingChecks" },
                { "generatorBaseClassifiesEveryDynamoSpecAndWritesRealCapacity" },
                { "generatorNativeFillCannotBypassMeGrossBudget" },
                { "cleanRoomActuallyDrainsEnergyAndFillsOrdinaryAndExoticDynamos" },
                { "pinnedBytecodeConfirmsDistinctGeneratorAndWirelessPaths" },
                { "ordinaryAndMultiConsumersUseInheritedNativeDrainOffline" },
                { "manufacturingAndMagnetarStillRejectEveryLowAmpExoticInput" },
                { "meteorMinerOriginalEnergyCheckRejectsOnlyMixedOrEmptyLists" },
                { "realGlassChecksDistinguishMeMultiFromNativeLaser" },
                { "universalGeneratorRejectsIvByItsOwnTierLimit" }, { "steamAndUniversalKeepStandardDynamoAdmission" },
                { "legacyGeneratorActuallyWritesOfflineMeDynamos" },
                { "legacyGeneratorIntOverflowReproducesWithBothNativeAndMeHatches" },
                { "megaEggKeepsNonLaserOutputAndBigBroBytecodeUsesNativeStock" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        Assume.assumeTrue(
            "Enable -PmeEnergyTstDevJar=<official TST 0.8.0 dev.jar> for real TST compatibility tests",
            Boolean.getBoolean("meEnergyTstCompatibility"));
        loader = new LaunchClassLoader(
            ((URLClassLoader) MEEnergyTstCompatibilityTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyTstCompatibilityFixture");
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
