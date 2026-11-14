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

/** Real pinned GT classes in isolation, not a running GTNH world or AE network. */
@RunWith(Parameterized.class)
public class MEEnergyHatchIntegrationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "allSpecsHaveNativeIdentityCapabilityAndIndependentClones" },
                { "sixPhysicalFacesAreClosedWithoutHidingElectricalCapability" },
                { "multiAmperageCannotBeChangedByToolsNbtOrParentFields" },
                { "realGtAddersAcceptOnlyTheirNativeHatchKinds" },
                { "nativeConsumersDrainOfflineStockAndNonPacketAmounts" },
                { "nativeGeneratorsWriteTheSameDynamoStock" }, { "nativeStockBridgeRetainsOverCapacityAndMarksDirty" },
                { "worldNbtRoundTripsEverySpecAndDoesNotDuplicateEu" },
                { "everyRoundingCreditSurvivesNativeWorldReload" },
                { "unloadAndRemovalStopWorkWithoutErasingWorldStock" },
                { "nativeDropsAndReplacementNeverCarryEuOrCredit" }, { "malformedMetadataStaysIsolatedAcrossSaves" },
                { "businessPaintAndReplacementInvalidateRuntimeSnapshots" },
                { "wailaSnapshotsReadNativeOfflineStockAndAllColorGroups" },
                { "paintRemainsVisibleOnEveryFaceWithControllerCasing" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(
            ((URLClassLoader) MEEnergyHatchIntegrationTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyHatchFixture");
        fixture.getMethod("registries")
            .invoke(null);
    }

    @AfterClass
    public static void close() throws Exception {
        if (loader != null) loader.close();
        loader = null;
        fixture = null;
    }

    @Test
    public void nativeScenario() throws Throwable {
        final Thread thread = Thread.currentThread();
        final String previousName = thread.getName();
        try {
            // Pinned Forge's world-less side lookup is exactly a "Server thread" name check.
            thread.setName("Server thread");
            fixture.getMethod(this.scenario)
                .invoke(
                    fixture.getDeclaredConstructor()
                        .newInstance());
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            thread.setName(previousName);
        }
    }
}
