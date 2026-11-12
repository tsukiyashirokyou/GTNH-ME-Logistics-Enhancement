package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import appeng.api.AEApi;
import cpw.mods.fml.common.FMLCommonHandler;

/** One-time AE service registration and server teardown. Weak tracking never keeps dead worlds alive. */
public final class MEEnergyNetwork {

    private static final Set<MEEnergyGridCache> CACHES = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Set<MEEnergyNodeSupport> HOSTS = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean initialized;
    private static boolean stopping;
    private static int nextPhase;

    private MEEnergyNetwork() {}

    public static void init() {
        if (initialized) return;
        AEApi.instance()
            .registries()
            .gridCache()
            .registerGridCache(IMEEnergyGrid.class, MEEnergyGridCache.class);
        FMLCommonHandler.instance()
            .bus()
            .register(new MEEnergyTickClock());
        initialized = true;
    }

    static boolean isStopping() {
        return stopping;
    }

    static void track(final MEEnergyGridCache cache) {
        CACHES.add(cache);
    }

    static int nextPhase(final int period) {
        final int phase = nextPhase % period;
        nextPhase = (nextPhase + 1) % 20;
        return phase;
    }

    static void track(final MEEnergyNodeSupport host) {
        HOSTS.add(host);
    }

    /** Close indexes first so reentrant AE destruction cannot enroll new payable members. */
    public static void stopServer() {
        stopping = true;
        try {
            for (final MEEnergyGridCache cache : CACHES.toArray(new MEEnergyGridCache[0])) cache.shutdown();
            for (final MEEnergyNodeSupport host : HOSTS.toArray(new MEEnergyNodeSupport[0])) host.stop(true);
        } finally {
            CACHES.clear();
            HOSTS.clear();
            MEEnergyTickClock.reset();
            nextPhase = 0;
            stopping = false;
        }
    }
}
