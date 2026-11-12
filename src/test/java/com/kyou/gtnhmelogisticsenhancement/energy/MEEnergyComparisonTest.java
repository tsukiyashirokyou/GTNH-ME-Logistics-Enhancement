package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import net.minecraft.launchwrapper.LaunchClassLoader;

import org.junit.Assume;
import org.junit.Test;

/** Opt-in, alternating old/new measurements of the same production ME cache workload, not native P2P. */
public class MEEnergyComparisonTest {

    private static final String BASELINE = "gtnhmelogisticsenhancement-0.1.0-rc1-main.20+0a4554861b-dirty-dev.jar";

    @Test
    public void optionalAlternatingOldAndNewCacheComparison() throws Throwable {
        Assume.assumeTrue("Set ME_ENERGY_AB_BENCHMARK=1", "1".equals(System.getenv("ME_ENERGY_AB_BENCHMARK")));
        final String configuredOutput = System.getenv("ME_ENERGY_AB_OUTPUT");
        final String configuredBaseline = System.getenv("ME_ENERGY_AB_BASELINE");
        final boolean colorIsolation = "1".equals(System.getenv("ME_ENERGY_AB_COLOR_ISOLATION"));
        final Path folder = Paths.get(configuredOutput == null ? "build/energy-optimization" : configuredOutput);
        final File baselineFile = (configuredBaseline == null ? Paths.get("build/energy-optimization", BASELINE)
            : Paths.get(configuredBaseline)).toFile()
                .getCanonicalFile();
        assertTrue("Preserved pre-optimization dev JAR is required", baselineFile.isFile());
        Files.createDirectories(folder);
        final URL[] currentUrls = ((URLClassLoader) getClass().getClassLoader()).getURLs();
        final URL[] baselineUrls = new URL[currentUrls.length + 1];
        baselineUrls[0] = baselineFile.toURI()
            .toURL();
        System.arraycopy(currentUrls, 0, baselineUrls, 1, currentUrls.length);
        final Thread thread = Thread.currentThread();
        final String previousName = thread.getName();
        thread.setName("Server thread");
        try (Version baseline = new Version(baselineUrls);
            Version current = new Version(currentUrls);
            PrintWriter output = new PrintWriter(
                Files.newBufferedWriter(folder.resolve("comparison.csv"), StandardCharsets.UTF_8))) {
            assertEquals(baselineFile, baseline.implementationFile());
            assertFalse(
                "Current classes must not resolve to the preserved baseline",
                baselineFile.equals(current.implementationFile()));
            assertEquals("Baseline polling version", colorIsolation, baseline.hasDemandWake());
            assertTrue("Current path must include dormant-target polling", current.hasDemandWake());
            assertTrue("Baseline must use default-color wildcard matching", baseline.defaultIsWildcard());
            assertFalse("Current default color must be isolated", current.defaultIsWildcard());
            output.println(
                "implementation,scenario,trial,order,warmup_ticks,timed_ticks,mean_ns,p95_ns,total_ns,allocated_bytes,missed_requests,warmup_missed,reads,writes,attempts,commits,active_queries,grid_queries,local_probes,max_work");
            for (int trial = 0; trial < 3; trial++) {
                final String[] scenarios = colorIsolation
                    ? new String[] { "active100", "active125", "colors", "tiers_colors", "full_idle" }
                    : new String[] { "active100", "active125", "tiers_colors", "full_idle" };
                for (final String scenario : scenarios) {
                    final boolean oldFirst = (trial & 1) == 0;
                    output.println(
                        (oldFirst ? "old" : "new") + ","
                            + scenario
                            + ","
                            + trial
                            + ",first,"
                            + (oldFirst ? baseline : current).run(scenario));
                    output.flush();
                    output.println(
                        (oldFirst ? "new" : "old") + ","
                            + scenario
                            + ","
                            + trial
                            + ",second,"
                            + (oldFirst ? current : baseline).run(scenario));
                    output.flush();
                }
            }
            final String metadata = "baseline=" + baseline.implementationFile()
                + "\ncurrent="
                + current.implementationFile()
                + "\njava="
                + System.getProperty("java.runtime.version")
                + "\nvm="
                + System.getProperty("java.vm.name")
                + "\nos="
                + System.getProperty("os.name")
                + " "
                + System.getProperty("os.arch")
                + "\nprocessors="
                + Runtime.getRuntime()
                    .availableProcessors()
                + "\nThree AB/BA/AB trials; 500 native hatches; 2000 warmup + 2000 timed game ticks per row."
                + "\nTimed: production cache + available local dormant polling. Simulated generation, demand, and conservation checks are outside timing."
                + "\nControlled AE availability; not a real game MSPT/TPS or native P2P benchmark.\n";
            Files.write(folder.resolve("comparison-environment.txt"), metadata.getBytes(StandardCharsets.UTF_8));
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            thread.setName(previousName);
        }
    }

    private static final class Version implements AutoCloseable {

        private final LaunchClassLoader loader;
        private final Class<?> fixture;

        Version(final URL[] urls) throws Exception {
            this.loader = new LaunchClassLoader(urls);
            this.loader.addClassLoaderExclusion("org.junit.");
            this.loader.addClassLoaderExclusion("org.hamcrest.");
            this.loader.registerTransformer("cpw.mods.fml.common.asm.transformers.EventSubscriptionTransformer");
            this.fixture = this.loader
                .loadClass("com.kyou.gtnhmelogisticsenhancement.energy.MEEnergyComparisonFixture");
            this.fixture.getMethod("registries")
                .invoke(null);
        }

        File implementationFile() throws Exception {
            URL location = new URL(
                (String) this.fixture.getMethod("implementationLocation")
                    .invoke(null));
            if ("jar".equals(location.getProtocol())) {
                location = ((JarURLConnection) location.openConnection()).getJarFileURL();
            }
            assertEquals("Expected a local production class source: " + location, "file", location.getProtocol());
            return new File(location.toURI()).getCanonicalFile();
        }

        boolean hasDemandWake() throws Exception {
            return (Boolean) this.fixture.getMethod("hasDemandWake")
                .invoke(null);
        }

        boolean defaultIsWildcard() throws Exception {
            return (Boolean) this.fixture.getMethod("defaultIsWildcard")
                .invoke(null);
        }

        String run(final String scenario) throws Exception {
            this.fixture.getMethod("begin")
                .invoke(null);
            try {
                return (String) this.fixture.getMethod("runComparison", String.class)
                    .invoke(
                        this.fixture.getDeclaredConstructor()
                            .newInstance(),
                        scenario);
            } finally {
                this.fixture.getMethod("end")
                    .invoke(null);
            }
        }

        @Override
        public void close() throws Exception {
            try {
                this.fixture.getMethod("cleanup")
                    .invoke(null);
            } finally {
                this.loader.close();
            }
        }
    }
}
