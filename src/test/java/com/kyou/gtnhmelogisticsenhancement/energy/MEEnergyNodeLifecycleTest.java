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

/** Isolated pinned GT/AE scenarios; simulated network state is distinguished from native pathing in the fixture. */
@RunWith(Parameterized.class)
public class MEEnergyNodeLifecycleTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "proxyFlagsPaintAndFacesUseOneNode" },
                { "faceConfigurationAndNodeDataHaveDifferentItemLifetimes" },
                { "invalidNodeAndSideDataRemainIsolated" }, { "nativeOwnerDataIsAppliedBeforeTheFirstGridJoin" },
                { "nativeEightAndNineChannelNetworksRecoverCorrectly" },
                { "nativeSplitAndJoinPreserveEndpointStockAndCredit" },
                { "configurationChecksOwnerDistanceGtAndAeBuild" }, { "sharedClockRegistersOnceAndSurvivesAeTeardown" },
                { "nativePowerAndBootEventsInvalidatePlansImmediately" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(((URLClassLoader) MEEnergyNodeLifecycleTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        // FML normally injects each Event's constructor/listener list before loading mod classes.
        loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyAENetworkFixture");
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
    public void networkScenario() throws Throwable {
        final Thread thread = Thread.currentThread();
        final String previousName = thread.getName();
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
                thread.setName(previousName);
            }
        }
    }
}
