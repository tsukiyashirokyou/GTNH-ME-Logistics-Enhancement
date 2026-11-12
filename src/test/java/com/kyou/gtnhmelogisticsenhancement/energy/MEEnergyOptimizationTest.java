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

/** Pinned native stocks with explicitly controlled AE availability; not a full-pack TPS benchmark. */
@RunWith(Parameterized.class)
public class MEEnergyOptimizationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "dirtyRefreshAndRepeatedNetworkEventsRemainBounded" },
                { "coalescedOfflineEventsPreserveEarnedTokensWithoutCatchup" },
                { "hundredsOfFullTargetsSleepAndWakeWithoutAePollingOrPowerLoss" },
                { "explicitGridPhasesDistributeServiceAndKeep125PercentLoadsPowered" },
                { "nineTiersWithFragmentedSourcesSustain125PercentDemand" },
                { "coldIdleRefillCannotInterruptAnObservedRunningConsumer" },
                { "starvedConsumerRetainsPriorityAndStoppedConsumerReleasesSupply" },
                { "wakingTailCannotReplaceAnUnvisitedRotatedDemandMember" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(((URLClassLoader) MEEnergyOptimizationTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyOptimizationFixture");
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
            fixture = null;
            loader = null;
        }
    }

    @Test
    public void optimizationScenario() throws Throwable {
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
