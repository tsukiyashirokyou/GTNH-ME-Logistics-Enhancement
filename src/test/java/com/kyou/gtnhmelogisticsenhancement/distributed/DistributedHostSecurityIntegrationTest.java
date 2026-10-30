package com.kyou.gtnhmelogisticsenhancement.distributed;

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

/** Exercises the production host permission methods without starting a world, server or AE grid. */
@RunWith(Parameterized.class)
public class DistributedHostSecurityIntegrationTest {

    private static LaunchClassLoader loader;
    private static Class<?> fixture;

    @Parameterized.Parameter
    public String scenario;

    @Parameterized.Parameters(name = "{0}")
    public static Collection<Object[]> scenarios() {
        return Arrays.asList(
            new Object[][] { { "inactiveHostsAllowOnlyTheirOwnerWithoutQueryingSecurity" },
                { "activeHostsRequireNativeBuildPermission" }, { "gridFailureRetainsOwnerOnlyFallback" },
                { "hostValidityAndGtAccessRemainRequired" }, { "malformedBusNodeIsIsolatedAndPreserved" },
                { "validBusNodeRetainsItsNormalRoundTrip" } });
    }

    @BeforeClass
    public static void bootstrap() throws Exception {
        loader = new LaunchClassLoader(
            ((URLClassLoader) DistributedHostSecurityIntegrationTest.class.getClassLoader()).getURLs());
        loader.addClassLoaderExclusion("org.junit.");
        loader.addClassLoaderExclusion("org.hamcrest.");
        fixture = loader.loadClass("com.kyou.gtnhmelogisticsenhancement.distributed.DistributedHostSecurityFixture");
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
        try {
            fixture.getMethod(this.scenario)
                .invoke(
                    fixture.getDeclaredConstructor()
                        .newInstance());
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
