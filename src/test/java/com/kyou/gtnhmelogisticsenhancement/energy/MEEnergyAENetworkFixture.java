package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;
import com.mojang.authlib.GameProfile;

import appeng.api.AEApi;
import appeng.api.config.SecurityPermissions;
import appeng.api.networking.GridFlags;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridConnection;
import appeng.api.networking.IGridNode;
import appeng.api.networking.energy.IEnergyGrid;
import appeng.api.networking.events.MENetworkBootingStatusChange;
import appeng.api.networking.events.MENetworkChannelsChanged;
import appeng.api.networking.events.MENetworkPowerStatusChange;
import appeng.api.networking.pathing.IPathingGrid;
import appeng.api.networking.security.ISecurityGrid;
import appeng.api.util.AECableType;
import appeng.api.util.AEColor;
import appeng.core.AEConfig;
import appeng.core.features.AEFeature;
import appeng.core.worlddata.WorldData;
import appeng.me.Grid;
import appeng.me.GridNode;
import appeng.me.GridStorage;
import appeng.me.cache.EnergyGridCache;
import appeng.me.cache.PathGridCache;
import appeng.me.cache.SecurityCache;
import appeng.me.helpers.AENetworkProxy;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.gameevent.TickEvent;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.BaseMetaTileEntity;
import gregtech.common.GTDummyWorld;

/**
 * Native GT hatches/AE proxies and two explicit test layers: controlled Grid availability for indexing,
 * and real GridNode/Grid/PathGridCache/EnergyGridCache/SecurityCache for native lifecycle/channel tests.
 * No transfer service or fake EU balance is installed.
 */
public class MEEnergyAENetworkFixture {

    private static final UUID OWNER = UUID.fromString("6d431cab-b690-4bba-ac3e-9fbfdaedbb3c");
    private static final MEEnergyTickClock CLOCK = new MEEnergyTickClock();
    private static Object worldData;
    private static TrackingWorld nativeWorld;

    public static void registries() throws Exception {
        MEEnergyHatchFixture.registries();
        cpw.mods.fml.common.registry.GameRegistry
            .registerTileEntity(ControlledBase.class, "me_energy_network_base_test");
        AEConfig.instance.featureFlags.add(AEFeature.Channels);
        // Use the real storage/player codecs, without starting a game, compass executor or connected players.
        final File folder = new File("build/test-energy-ae");
        assertTrue(folder.isDirectory() || folder.mkdirs());
        final Configuration configuration = new Configuration(new File(folder, "world.cfg"));
        final Object storage = dataService("StorageData", configuration);
        final Object players = dataService("PlayerData", configuration);
        final Class<?> playerType = Class.forName("appeng.core.worlddata.IWorldPlayerData");
        final Object playerView = Proxy.newProxyInstance(
            playerType.getClassLoader(),
            new Class<?>[] { playerType },
            (proxy, method, args) -> method.getName()
                .equals("getPlayerFromID") ? null : invoke(method, players, args));
        final Field instance = field(WorldData.class, "instance");
        worldData = Proxy.newProxyInstance(
            instance.getType()
                .getClassLoader(),
            new Class<?>[] { instance.getType() },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("storageData")) return storage;
                if (method.getName()
                    .equals("playerData")) return playerView;
                return defaultValue(proxy, method, args);
            });
        instance.set(null, worldData);
        AEApi.instance()
            .registries()
            .gridCache()
            .registerGridCache(IEnergyGrid.class, EnergyGridCache.class);
        AEApi.instance()
            .registries()
            .gridCache()
            .registerGridCache(IPathingGrid.class, PathGridCache.class);
        AEApi.instance()
            .registries()
            .gridCache()
            .registerGridCache(ISecurityGrid.class, SecurityCache.class);
        MEEnergyNetwork.init();
    }

    private static Object dataService(final String name, final Configuration configuration) throws Exception {
        final Class<?> type = Class.forName("appeng.core.worlddata." + name);
        final Constructor<?> constructor = type.getDeclaredConstructor(Configuration.class);
        constructor.setAccessible(true);
        final Object result = constructor.newInstance(configuration);
        final Method start = type.getDeclaredMethod("onWorldStart");
        start.setAccessible(true);
        start.invoke(result);
        return result;
    }

    public static void begin() throws Exception {
        field(WorldData.class, "instance").set(null, worldData);
        MEEnergyNetwork.stopServer();
        nativeWorld = new TrackingWorld();
    }

    public static void end() {
        MEEnergyNetwork.stopServer();
    }

    public static void cleanup() throws Exception {
        end();
        field(WorldData.class, "instance").set(null, null);
    }

    static Field field(final Class<?> type, final String name) throws Exception {
        final Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(final Method method, final Object target, final Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static Object defaultValue(final Object proxy, final Method method, final Object[] args) {
        if (method.getName()
            .equals("equals")) return proxy == args[0];
        if (method.getName()
            .equals("hashCode")) return System.identityHashCode(proxy);
        if (method.getName()
            .equals("toString")) return "Controlled AE test boundary";
        final Class<?> type = method.getReturnType();
        if (type == boolean.class) return false;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == double.class) return 0.0;
        return null;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(final Class<T> type, final InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, handler);
    }

    static IMEEnergyEndpoint hatch(final Role role, final int tier, final int amps) {
        final MEEnergySpec spec = amps == 0 ? MEEnergySpec.standard(role, tier) : MEEnergySpec.multi(role, tier, amps);
        if (amps == 0) return role == Role.DYNAMO
            ? new MTEHatchMEDynamoStandard("test.energy.ae", spec, new String[0], new ITexture[0][][])
            : new MTEHatchMEEnergyStandard("test.energy.ae", spec, new String[0], new ITexture[0][][]);
        return role == Role.DYNAMO
            ? new MTEHatchMEDynamoMulti("test.energy.ae", spec, new String[0], new ITexture[0][][])
            : new MTEHatchMEEnergyMulti("test.energy.ae", spec, new String[0], new ITexture[0][][]);
    }

    static ControlledBase attach(final IMEEnergyEndpoint host) {
        final ControlledBase base = new ControlledBase();
        host.machine()
            .setBaseMetaTileEntity(base);
        base.setOwnerUuid(OWNER);
        base.setOwnerName("Energy Test Owner");
        host.machine()
            .onFirstTick(base); // Null World: stage-2 lifecycle only; AE ready is deliberately deferred.
        return base;
    }

    static void advance(final int ticks) {
        for (int tick = 0; tick < ticks; tick++) CLOCK.tick(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
    }

    static void credit(final IMEEnergyEndpoint host, final int desired) {
        final MEEnergyLossAccount account = host.energyState()
            .lossAccount();
        final long net = (account.credit() - desired + 19) % 19;
        account.record(net, account.grossCost(net));
    }

    private static NBTTagCompound nodeData(final int player) {
        final NBTTagCompound node = new NBTTagCompound();
        node.setInteger("p", player);
        node.setLong("k", -1);
        node.setLong("g", 1234);
        return node;
    }

    /** Explicitly simulated availability; no native pathing or channel count is inferred from these objects. */
    static final class Frame {

        boolean powered = true;
        boolean booting;
        boolean buildPermission = true;
        int securityQueries;
        final IGrid grid;
        MEEnergyGridCache cache;
        final TrackingWorld world = new TrackingWorld();
        final IEnergyGrid power;
        final IPathingGrid path;
        final ISecurityGrid security;

        Frame() {
            this.power = proxy(
                IEnergyGrid.class,
                (p, m, a) -> m.getName()
                    .equals("isNetworkPowered") ? this.powered : defaultValue(p, m, a));
            this.path = proxy(
                IPathingGrid.class,
                (p, m, a) -> m.getName()
                    .equals("isNetworkBooting") ? this.booting : defaultValue(p, m, a));
            this.security = proxy(ISecurityGrid.class, (p, m, a) -> {
                if (m.getName()
                    .equals("hasPermission")) {
                    assertSame(SecurityPermissions.BUILD, a[1]);
                    this.securityQueries++;
                    return this.buildPermission;
                }
                return defaultValue(p, m, a);
            });
            this.grid = proxy(IGrid.class, (p, m, a) -> {
                if (m.getName()
                    .equals("getCache")) {
                    if (a[0] == IEnergyGrid.class) return this.power;
                    if (a[0] == IPathingGrid.class) return this.path;
                    if (a[0] == ISecurityGrid.class) return this.security;
                    if (a[0] == IMEEnergyGrid.class) return this.cache;
                }
                if (m.getName()
                    .equals("getNodes")
                    || m.getName()
                        .equals("getMachines")
                    || m.getName()
                        .equals("getMachinesClasses"))
                    throw new AssertionError("Full Grid traversal");
                return defaultValue(p, m, a);
            });
            this.cache = new MEEnergyGridCache(this.grid, 20); // Preserve the historical phase 3-5 P20 scenarios.
        }

        FakeNode add(final IMEEnergyEndpoint host, final int color) throws Exception {
            ControlledBase base = (ControlledBase) host.machine()
                .getBaseMetaTileEntity();
            if (base == null) base = attach(host);
            base.setWorldObj(this.world);
            base.setColorization((byte) (color - 1));
            final AENetworkProxy nativeProxy = host.getProxy();
            final FakeNode fake = new FakeNode(this, host);
            field(AENetworkProxy.class, "node").set(nativeProxy, fake.node);
            field(MEEnergyNodeSupport.class, "ready").setBoolean(host.nodeSupport(), true);
            this.cache.addNode(fake.node, host);
            host.gridChanged();
            return fake;
        }
    }

    static final class FakeNode {

        Frame frame;
        final IMEEnergyEndpoint host;
        IGridNode node;
        boolean channel = true;
        int activeQueries;
        int gridQueries;
        int updates;
        int destroys;

        FakeNode(final Frame frame, final IMEEnergyEndpoint host) {
            this.frame = frame;
            this.host = host;
            this.node = proxy(IGridNode.class, (p, m, a) -> {
                switch (m.getName()) {
                    case "getMachine":
                        return this.host;
                    case "getGridBlock":
                        return this.host.getProxy();
                    case "getGrid":
                        this.gridQueries++;
                        return this.frame.grid;
                    case "isActive":
                        this.activeQueries++;
                        return this.channel && this.frame.powered && !this.frame.booting;
                    case "meetsChannelRequirements":
                        return this.channel;
                    case "hasFlag":
                        return this.host.getProxy()
                            .hasFlag((GridFlags) a[0]);
                    case "updateState":
                        this.updates++;
                        return null;
                    case "destroy":
                        this.destroys++;
                        this.frame.cache.removeNode(this.node, this.host);
                        return null;
                    case "saveToNBT":
                        ((NBTTagCompound) a[1]).setTag((String) a[0], nodeData(17));
                        return null;
                    default:
                        return defaultValue(p, m, a);
                }
            });
        }
    }

    static IMEEnergyEndpoint nativeHost(final Role role, final int x) {
        final IMEEnergyEndpoint host = hatch(role, 5, 4);
        final ControlledBase base = attach(host);
        base.xCoord = x;
        base.setWorldObj(nativeWorld);
        host.machine()
            .onFirstTick(base);
        assertNotNull(host.getActionableNode());
        assertTrue(host.getActionableNode() instanceof GridNode);
        return host;
    }

    static MEEnergyGridCache cache(final IMEEnergyEndpoint host) {
        return (MEEnergyGridCache) host.getActionableNode()
            .getGrid()
            .getCache(IMEEnergyGrid.class);
    }

    static void nativeTicks(final IMEEnergyEndpoint host, final int count) {
        for (int tick = 0; tick < count; tick++) {
            advance(1);
            final Grid grid = (Grid) host.getActionableNode()
                .getGrid();
            final IEnergyGrid power = grid.getCache(IEnergyGrid.class);
            power.setHasInfiniteStore(true); // Explicit AE maintenance-power fixture.
            grid.update();
        }
    }

    private static void forceNativeActive(final IMEEnergyEndpoint host) throws Exception {
        final GridNode node = (GridNode) host.getActionableNode();
        field(GridNode.class, "usedChannels").setInt(node, 1);
        field(GridNode.class, "lastUsedChannels").setInt(node, 1);
        field(EnergyGridCache.class, "publicHasPower").setBoolean(
            node.getGrid()
                .getCache(IEnergyGrid.class),
            true);
        field(PathGridCache.class, "booting").setBoolean(
            node.getGrid()
                .getCache(IPathingGrid.class),
            false);
        node.getGrid()
            .postEvent(new MENetworkPowerStatusChange());
        host.gridChanged();
        cache(host).onUpdateTick();
        assertTrue(host.isActive());
    }

    public void callbacksAvoidGridTraversalAndDuplicateNodes() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(source, 0);
        final int queries = node.gridQueries;
        for (int face = 0; face < 6; face++) frame.cache.addNode(node.node, source);
        assertEquals(queries, node.gridQueries);
        assertEquals(1, frame.cache.memberCount());
        frame.cache.onUpdateTick();
        source.setConnectsToAllSides(true);
        for (final ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
            assertSame(node.node, source.getGridNode(side));
        frame.cache.onUpdateTick();
        assertEquals(1, frame.cache.memberCount());
        assertEquals(1, frame.cache.activeCount(Role.DYNAMO, 5, 0));
        frame.cache.removeNode(node.node, source);
        frame.cache.removeNode(node.node, source);
        assertEquals(0, frame.cache.memberCount());
        assertNull(frame.cache.first(Role.DYNAMO, 5, 0));
    }

    public void smallAndLargeGroupsKeepStableTicksScanFree() throws Exception {
        for (final int size : new int[] { 1, 32, 400 }) {
            final Frame frame = new Frame();
            final List<FakeNode> nodes = new ArrayList<>();
            final int[][][] expected = new int[2][9][17];
            for (int index = 0; index < size; index++) {
                final Role role = index % 2 == 0 ? Role.DYNAMO : Role.ENERGY;
                final int tier = 5 + index % 9;
                final int color = index % 17;
                nodes.add(frame.add(hatch(role, tier, 4), color));
                expected[role.ordinal()][tier - 5][color]++;
            }
            frame.cache.onUpdateTick();
            while (frame.cache.refreshPending()) {
                advance(1);
                frame.cache.onUpdateTick();
            }
            int count = 0;
            for (final Role role : Role.values())
                for (int tier = 5; tier <= 13; tier++) for (int color = 0; color < 17; color++) {
                    assertEquals(expected[role.ordinal()][tier - 5][color], frame.cache.activeCount(role, tier, color));
                    for (MEEnergyGridCache.Member member = frame.cache.first(role, tier, color); member
                        != null; member = member.next()) {
                        assertTrue(member.isCurrent());
                        assertEquals(
                            role,
                            member.endpoint()
                                .spec()
                                .role());
                        assertEquals(
                            tier,
                            member.endpoint()
                                .spec()
                                .tier());
                        assertEquals(
                            color,
                            member.endpoint()
                                .businessColor());
                        count++;
                    }
                }
            assertEquals(size, count);
            final long refreshes = frame.cache.refreshes();
            int activeQueries = 0;
            for (final FakeNode node : nodes) activeQueries += node.activeQueries;
            for (int tick = 0; tick < 1000; tick++) {
                advance(1);
                frame.cache.onUpdateTick();
            }
            assertEquals(refreshes, frame.cache.refreshes());
            int afterQueries = 0;
            for (final FakeNode node : nodes) afterQueries += node.activeQueries;
            // Phase 4 now takes periodic snapshots; membership itself still never refreshes stable members.
            assertTrue(afterQueries - activeQueries <= 200 * size);
            assertTrue(frame.cache.maxTickWork() <= MEEnergyGridCache.WORK_PER_TICK);
            frame.cache.shutdown();
            assertEquals(0, frame.cache.memberCount());
        }
    }

    public void paintAndRepeatedDirtyEventsCoalesce() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 256);
        final FakeNode node = frame.add(source, 0);
        credit(source, 13);
        source.machine()
            .setEUVar(20000);
        frame.cache.onUpdateTick();
        final long epoch = frame.cache.epoch(5);
        final long refreshes = frame.cache.refreshes();
        final int updates = node.updates;
        final int queries = frame.world.neighborQueries;
        ((BaseMetaTileEntity) source.machine()
            .getBaseMetaTileEntity()).setColorization((byte) 2);
        for (int event = 0; event < 100; event++) source.meChannelsChanged(new MENetworkChannelsChanged());
        assertFalse(frame.cache.isEpochValid(5, epoch));
        frame.cache.onUpdateTick();
        assertEquals(refreshes + 1, frame.cache.refreshes());
        assertEquals(0, frame.cache.activeCount(Role.DYNAMO, 5, 0));
        assertEquals(1, frame.cache.activeCount(Role.DYNAMO, 5, 3));
        assertEquals(
            AEColor.Transparent,
            source.getProxy()
                .getColor());
        assertEquals(updates, node.updates);
        assertEquals(queries, frame.world.neighborQueries);
        assertEquals(20000, source.readEU());
        assertEquals(
            13,
            source.energyState()
                .lossAccount()
                .credit());
    }

    public void statusEventsPauseAtTheirTickWithoutLosingEarnedTokens() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(source, 1);
        frame.cache.onUpdateTick();
        final long rate = source.spec()
            .power();
        advance(19);
        frame.powered = false;
        frame.cache.powerChanged(new MENetworkPowerStatusChange());
        assertEquals(
            rate * 19,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertFalse(
            frame.cache.first(Role.DYNAMO, 5, 1)
                .isCurrent());
        frame.cache.onUpdateTick();
        assertEquals(0, frame.cache.activeCount(Role.DYNAMO, 5, 1));
        advance(1000);
        assertEquals(
            rate * 19,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        frame.powered = true;
        frame.booting = true;
        frame.cache.powerChanged(new MENetworkPowerStatusChange());
        frame.cache.onUpdateTick();
        assertEquals(0, frame.cache.activeCount(Role.DYNAMO, 5, 1));
        frame.booting = false;
        frame.cache.bootChanged(new MENetworkBootingStatusChange(false));
        frame.cache.onUpdateTick();
        advance(1);
        assertEquals(
            rate * 20,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        node.channel = false;
        source.meChannelsChanged(new MENetworkChannelsChanged());
        advance(1000);
        assertEquals(
            rate * 20,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        frame.cache.onUpdateTick();
        assertEquals(0, frame.cache.activeCount(Role.DYNAMO, 5, 1));
    }

    public void migrationAndStorageCallbacksNeverMoveEu() throws Exception {
        final Frame first = new Frame(), second = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = first.add(source, 0);
        source.machine()
            .setEUVar(5000);
        credit(source, 18);
        first.cache.onUpdateTick();
        final MEEnergyGridCache.Member old = first.cache.first(Role.DYNAMO, 5, 0);
        final long epoch = first.cache.epoch(5);
        advance(10);
        assertTrue(
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()) > 0);
        first.cache.removeNode(node.node, source);
        node.frame = second;
        second.cache.addNode(node.node, source);
        source.gridChanged();
        second.cache.onUpdateTick();
        first.cache.removeNode(node.node, source); // Late old-grid callbacks cannot detach the new binding.
        assertEquals(0, first.cache.memberCount());
        assertEquals(1, second.cache.memberCount());
        assertFalse(old.isCurrent());
        assertFalse(first.cache.isEpochValid(5, epoch));
        assertEquals(
            0,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        final GridStorage storage = new GridStorage();
        second.cache.onSplit(storage);
        second.cache.onJoin(storage);
        second.cache.populateGridStorage(storage);
        assertTrue(
            storage.dataObject()
                .hasNoTags());
        assertEquals(5000, source.readEU());
        assertEquals(
            18,
            source.energyState()
                .lossAccount()
                .credit());
    }

    public void unloadRemovalAndSecurityBreakReleaseMembership() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(source, 0);
        source.machine()
            .setEUVar(999);
        credit(source, 7);
        frame.cache.onUpdateTick();
        source.machine()
            .onUnload();
        source.machine()
            .onRemoval();
        assertEquals(1, node.destroys);
        assertEquals(0, frame.cache.memberCount());
        assertNull(source.getActionableNode());
        assertEquals(999, source.readEU());
        assertEquals(
            7,
            source.energyState()
                .lossAccount()
                .credit());
        final IMEEnergyEndpoint broken = hatch(Role.DYNAMO, 5, 4);
        final FakeNode brokenNode = frame.add(broken, 0);
        broken.machine()
            .setEUVar(111);
        frame.cache.onUpdateTick();
        broken.securityBreak();
        broken.securityBreak();
        assertEquals(1, brokenNode.destroys);
        assertEquals(1, frame.world.breaks);
        assertEquals(0, frame.cache.memberCount());
        assertFalse(broken.isActive());
        assertEquals(111, broken.readEU());
    }

    public void foreignNodesAndLateRemovalCannotStealMembership() throws Exception {
        final Frame first = new Frame(), second = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        final FakeNode original = first.add(source, 0);
        first.cache.onUpdateTick();
        final FakeNode foreign = new FakeNode(second, source);
        second.cache.addNode(foreign.node, source);
        assertEquals(0, second.cache.memberCount());
        first.cache.removeNode(original.node, hatch(Role.ENERGY, 5, 4));
        assertEquals(1, first.cache.memberCount());
        assertTrue(
            first.cache.first(Role.DYNAMO, 5, 0)
                .isCurrent());
    }

    public void replacedHostsAndInvalidMetadataAreNeverCurrent() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint original = hatch(Role.ENERGY, 5, 4);
        frame.add(original, 0);
        frame.cache.onUpdateTick();
        final MEEnergyGridCache.Member old = frame.cache.first(Role.ENERGY, 5, 0);
        final IMEEnergyEndpoint replacement = hatch(Role.ENERGY, 5, 4);
        replacement.machine()
            .setBaseMetaTileEntity(
                original.machine()
                    .getBaseMetaTileEntity());
        assertFalse(old.isCurrent());
        original.meChannelsChanged(new MENetworkChannelsChanged());
        frame.cache.onUpdateTick();
        assertEquals(0, frame.cache.activeCount(Role.ENERGY, 5, 0));
        final IMEEnergyEndpoint invalid = hatch(Role.DYNAMO, 5, 4);
        attach(invalid);
        final NBTTagCompound tag = new NBTTagCompound();
        invalid.machine()
            .saveNBTData(tag);
        tag.getCompoundTag(MEEnergyNbt.DATA)
            .setInteger("credit", 19);
        invalid.machine()
            .loadNBTData(tag);
        invalid.energyState()
            .start();
        frame.add(invalid, 0);
        frame.cache.onUpdateTick();
        assertFalse(invalid.isActive());
        assertEquals(0, frame.cache.activeCount(Role.DYNAMO, 5, 0));
    }

    public void nativeHostOnlyChannelEventsReachTheCache() throws Exception {
        final IMEEnergyEndpoint source = nativeHost(Role.DYNAMO, 0);
        forceNativeActive(source);
        final MEEnergyGridCache cache = cache(source);
        final long epoch = cache.epoch(5);
        advance(19);
        final GridNode node = (GridNode) source.getActionableNode();
        field(GridNode.class, "lastUsedChannels").setInt(node, 0);
        field(GridNode.class, "usedChannels").setInt(node, 0);
        node.getGrid()
            .postEventTo(node, new MENetworkChannelsChanged());
        assertFalse(cache.isEpochValid(5, epoch));
        assertFalse(source.isActive());
        assertEquals(
            source.spec()
                .power() * 19,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        advance(100);
        assertEquals(
            source.spec()
                .power() * 19,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        cache.onUpdateTick();
        assertEquals(0, cache.activeCount(Role.DYNAMO, 5, 0));
    }

    public void serverStopClearsIndexesAndTransientReferences() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        frame.add(source, 0);
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(54321);
        credit(source, 13);
        final long epoch = frame.cache.epoch(5);
        advance(20);
        MEEnergyNetwork.stopServer();
        assertEquals(0, MEEnergyTickClock.now());
        assertEquals(0, frame.cache.memberCount());
        assertFalse(frame.cache.isEpochValid(5, epoch));
        assertNull(frame.cache.first(Role.DYNAMO, 5, 0));
        assertNull(source.getActionableNode());
        assertEquals(
            0,
            source.energyState()
                .rateBudget()
                .tokens(0));
        assertEquals(54321, source.readEU());
        assertEquals(
            13,
            source.energyState()
                .lossAccount()
                .credit());
        frame.cache.onUpdateTick();
        MEEnergyNetwork.stopServer();
        assertEquals(0, frame.cache.memberCount());
    }

    public void proxyFlagsPaintAndFacesUseOneNode() throws Exception {
        for (final int amps : new int[] { 0, 4, 256 }) for (final Role role : Role.values()) {
            final IMEEnergyEndpoint host = hatch(role, 5, amps);
            attach(host);
            final AENetworkProxy nativeProxy = host.getProxy();
            assertSame(nativeProxy, host.getProxy());
            assertNull(nativeProxy.getNode());
            assertFalse(nativeProxy.isReady());
            assertTrue(nativeProxy.hasFlag(GridFlags.REQUIRE_CHANNEL));
            assertEquals(
                1,
                nativeProxy.getFlags()
                    .size());
            assertEquals(1.0, nativeProxy.getIdlePowerUsage(), 0.0);
            assertEquals(AEColor.Transparent, nativeProxy.getGridColor());
            assertThrows(IllegalArgumentException.class, () -> nativeProxy.setColor(AEColor.Red));
            final Frame frame = new Frame();
            final FakeNode node = frame.add(host, 3);
            frame.cache.onUpdateTick();
            assertSame(node.node, host.getGridNode(ForgeDirection.DOWN));
            assertNull(host.getGridNode(ForgeDirection.UP));
            host.setConnectsToAllSides(true);
            for (final ForgeDirection side : ForgeDirection.VALID_DIRECTIONS) {
                assertSame(node.node, host.getGridNode(side));
                assertEquals(AECableType.SMART, host.getCableConnectionType(side));
            }
            assertNull(host.getGridNode(ForgeDirection.UNKNOWN));
            assertEquals(AECableType.NONE, host.getCableConnectionType(ForgeDirection.UNKNOWN));
            assertEquals(
                AEColor.Transparent,
                host.getProxy()
                    .getColor());
            assertEquals(1, frame.cache.memberCount());
        }
    }

    public void faceConfigurationAndNodeDataHaveDifferentItemLifetimes() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4);
        frame.add(source, 0);
        source.setConnectsToAllSides(true);
        source.machine()
            .setEUVar(7000);
        credit(source, 13);
        frame.cache.onUpdateTick();
        final NBTTagCompound world = new NBTTagCompound();
        source.machine()
            .saveNBTData(world);
        assertTrue(world.getBoolean(MEEnergyNodeSupport.ALL_SIDES));
        assertTrue(world.hasKey(MEEnergyNodeSupport.NODE_DATA, 10));
        assertEquals(
            17,
            world.getCompoundTag(MEEnergyNodeSupport.NODE_DATA)
                .getInteger("p"));
        final IMEEnergyEndpoint reloaded = hatch(Role.DYNAMO, 5, 4);
        attach(reloaded);
        reloaded.machine()
            .loadNBTData(world);
        assertTrue(reloaded.connectsToAllSides());
        assertEquals(
            13,
            reloaded.energyState()
                .lossAccount()
                .credit());
        assertNull(reloaded.getActionableNode());
        assertTrue(
            reloaded.nodeSupport()
                .diagnostic()
                .isEmpty());
        final NBTTagCompound savedAgain = new NBTTagCompound();
        reloaded.machine()
            .saveNBTData(savedAgain);
        assertEquals(world.getTag(MEEnergyNodeSupport.NODE_DATA), savedAgain.getTag(MEEnergyNodeSupport.NODE_DATA));
        final NBTTagCompound item = (NBTTagCompound) world.copy();
        item.setLong("mStoredEnergy", 7000);
        source.machine()
            .setItemNBT(item);
        assertFalse(item.hasKey(MEEnergyNodeSupport.NODE_DATA));
        assertFalse(item.hasKey("mStoredEnergy"));
        assertFalse(item.hasKey(MEEnergyNbt.DATA));
        assertTrue(item.getBoolean(MEEnergyNodeSupport.ALL_SIDES));
        final IMEEnergyEndpoint placed = hatch(Role.DYNAMO, 5, 4);
        attach(placed);
        placed.machine()
            .loadNBTData(item);
        assertEquals(0, placed.readEU());
        assertEquals(
            0,
            placed.energyState()
                .lossAccount()
                .credit());
        assertTrue(placed.connectsToAllSides());
        assertEquals(7000, source.readEU()); // Exporting item metadata itself is not a remote refund or inventory edit.
    }

    public void invalidNodeAndSideDataRemainIsolated() throws Exception {
        for (int scenario = 0; scenario < 7; scenario++) {
            final IMEEnergyEndpoint host = hatch(Role.DYNAMO, 5, 4);
            final ControlledBase base = attach(host);
            host.machine()
                .setEUVar(1234);
            final NBTTagCompound data = new NBTTagCompound();
            host.machine()
                .saveNBTData(data);
            data.setTag(MEEnergyNodeSupport.NODE_DATA, nodeData(2));
            switch (scenario) {
                case 0:
                    data.setString(MEEnergyNodeSupport.NODE_DATA, "broken");
                    break;
                case 1:
                    data.getCompoundTag(MEEnergyNodeSupport.NODE_DATA)
                        .setLong("p", 2);
                    break;
                case 2:
                    data.getCompoundTag(MEEnergyNodeSupport.NODE_DATA)
                        .removeTag("g");
                    break;
                case 3:
                    data.getCompoundTag(MEEnergyNodeSupport.NODE_DATA)
                        .setInteger("k", 0);
                    break;
                case 4:
                    data.setInteger(MEEnergyNodeSupport.ALL_SIDES, 1);
                    break;
                case 5:
                    data.setByte(MEEnergyNodeSupport.ALL_SIDES, (byte) 2);
                    break;
                case 6:
                    data.setByte(MEEnergyNodeSupport.ALL_SIDES, (byte) -1);
                    break;
                default:
                    throw new AssertionError();
            }
            host.machine()
                .loadNBTData(data);
            base.setWorldObj(nativeWorld);
            host.machine()
                .onFirstTick(base);
            assertFalse(
                host.nodeSupport()
                    .diagnostic()
                    .isEmpty());
            assertFalse(host.isActive());
            assertNull(host.getActionableNode());
            assertEquals(1234, host.readEU());
            final NBTTagCompound saved = new NBTTagCompound();
            host.machine()
                .saveNBTData(saved);
            assertEquals(data.getTag(MEEnergyNodeSupport.NODE_DATA), saved.getTag(MEEnergyNodeSupport.NODE_DATA));
            assertEquals(data.getTag(MEEnergyNodeSupport.ALL_SIDES), saved.getTag(MEEnergyNodeSupport.ALL_SIDES));
        }
    }

    public void nativeOwnerDataIsAppliedBeforeTheFirstGridJoin() throws Exception {
        final IMEEnergyEndpoint source = nativeHost(Role.DYNAMO, 0);
        final int ownerId = WorldData.instance()
            .playerData()
            .getPlayerID(new GameProfile(OWNER, "Energy Test Owner"));
        assertEquals(
            ownerId,
            source.getActionableNode()
                .getPlayerID());
        credit(source, 18);
        final NBTTagCompound world = new NBTTagCompound();
        source.machine()
            .saveNBTData(world);
        assertEquals(
            ownerId,
            world.getCompoundTag(MEEnergyNodeSupport.NODE_DATA)
                .getInteger("p"));
        source.machine()
            .onUnload();
        final IMEEnergyEndpoint reloaded = hatch(Role.DYNAMO, 5, 4);
        final ControlledBase base = attach(reloaded);
        reloaded.machine()
            .loadNBTData(world);
        base.setOwnerUuid(UUID.fromString("4cd2beb9-3fc4-4e20-9bb6-64dc59225ae0"));
        base.setOwnerName("Another Test Player");
        base.setWorldObj(nativeWorld);
        reloaded.machine()
            .onFirstTick(base);
        assertEquals(
            ownerId,
            reloaded.getActionableNode()
                .getPlayerID());
        assertEquals(
            18,
            reloaded.energyState()
                .lossAccount()
                .credit());
        assertEquals(0, reloaded.readEU()); // The fixture restored metadata only; it never manufactures a second
                                            // balance.
    }

    public void nativeEightAndNineChannelNetworksRecoverCorrectly() throws Exception {
        final List<IMEEnergyEndpoint> hosts = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            final IMEEnergyEndpoint host = nativeHost(index % 2 == 0 ? Role.DYNAMO : Role.ENERGY, index);
            hosts.add(host);
            host.setConnectsToAllSides(true);
            if (index > 0) AEApi.instance()
                .createGridConnection(
                    hosts.get(0)
                        .getActionableNode(),
                    host.getActionableNode());
        }
        final IMEEnergyEndpoint first = hosts.get(0);
        nativeTicks(first, 45);
        for (final IMEEnergyEndpoint host : hosts) {
            assertTrue(host.isActive());
            assertSame(
                first.getActionableNode()
                    .getGrid(),
                host.getActionableNode()
                    .getGrid());
            for (final ForgeDirection side : ForgeDirection.VALID_DIRECTIONS)
                assertSame(host.getActionableNode(), host.getGridNode(side));
        }
        assertEquals(8, cache(first).memberCount());
        final IPathingGrid path = first.getActionableNode()
            .getGrid()
            .getCache(IPathingGrid.class);
        assertEquals(8, field(PathGridCache.class, "channelsInUse").getInt(path));
        final IMEEnergyEndpoint ninth = nativeHost(Role.DYNAMO, 9);
        AEApi.instance()
            .createGridConnection(first.getActionableNode(), ninth.getActionableNode());
        nativeTicks(first, 45);
        for (final IMEEnergyEndpoint host : hosts) assertFalse(host.isActive());
        assertFalse(ninth.isActive());
        assertEquals(9, cache(first).memberCount());
        assertEquals(0, cache(first).activeCount(Role.DYNAMO, 5, 0));
        assertEquals(0, cache(first).activeCount(Role.ENERGY, 5, 0));
        ninth.machine()
            .onUnload();
        nativeTicks(first, 45);
        for (final IMEEnergyEndpoint host : hosts) assertTrue(host.isActive());
        assertEquals(8, cache(first).memberCount());
        assertEquals(4, cache(first).activeCount(Role.DYNAMO, 5, 0));
        assertEquals(4, cache(first).activeCount(Role.ENERGY, 5, 0));
    }

    public void nativeSplitAndJoinPreserveEndpointStockAndCredit() throws Exception {
        final IMEEnergyEndpoint source = nativeHost(Role.DYNAMO, 0);
        final IMEEnergyEndpoint target = nativeHost(Role.ENERGY, 1);
        // This lifecycle case isolates stock preservation from the phase-4 transfer service.
        source.machine()
            .getBaseMetaTileEntity()
            .setColorization((byte) 0);
        target.machine()
            .getBaseMetaTileEntity()
            .setColorization((byte) 1);
        final IGridConnection connection = AEApi.instance()
            .createGridConnection(source.getActionableNode(), target.getActionableNode());
        source.machine()
            .setEUVar(33333);
        target.machine()
            .setEUVar(77777);
        credit(source, 13);
        nativeTicks(source, 45);
        final MEEnergyGridCache before = cache(source);
        final long epoch = before.epoch(5);
        connection.destroy();
        assertFalse(before.isEpochValid(5, epoch));
        assertTrue(
            source.getActionableNode()
                .getGrid()
                != target.getActionableNode()
                    .getGrid());
        assertEquals(1, cache(source).memberCount());
        assertEquals(1, cache(target).memberCount());
        if (cache(source) != before) assertEquals(
            0,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertEquals(33333, source.readEU());
        assertEquals(77777, target.readEU());
        assertEquals(
            13,
            source.energyState()
                .lossAccount()
                .credit());
        AEApi.instance()
            .createGridConnection(source.getActionableNode(), target.getActionableNode());
        nativeTicks(source, 45);
        assertSame(
            source.getActionableNode()
                .getGrid(),
            target.getActionableNode()
                .getGrid());
        assertEquals(2, cache(source).memberCount());
        assertEquals(33333, source.readEU());
        assertEquals(77777, target.readEU());
        assertEquals(
            13,
            source.energyState()
                .lossAccount()
                .credit());
    }

    public void configurationChecksOwnerDistanceGtAndAeBuild() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint host = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(host, 0);
        final ControlledBase base = (ControlledBase) host.machine()
            .getBaseMetaTileEntity();
        final Player owner = new Player(frame.world, OWNER, "Energy Test Owner");
        final Player stranger = new Player(
            frame.world,
            UUID.fromString("6f724fd9-efbd-4ad3-963e-88c66d671aad"),
            "Stranger");
        node.channel = false;
        host.meChannelsChanged(new MENetworkChannelsChanged());
        frame.cache.onUpdateTick();
        assertTrue(
            host.nodeSupport()
                .canConfigure(owner));
        assertFalse(
            host.nodeSupport()
                .canConfigure(stranger));
        assertFalse(
            host.nodeSupport()
                .canConfigure(null));
        base.usable = false;
        assertFalse(
            host.nodeSupport()
                .canConfigure(owner));
        base.usable = true;
        owner.setPosition(100, 0, 0);
        assertFalse(
            host.nodeSupport()
                .canConfigure(owner));
        owner.setPosition(0.5, 0.5, 0.5);
        node.channel = true;
        host.meChannelsChanged(new MENetworkChannelsChanged());
        frame.cache.onUpdateTick();
        frame.buildPermission = false;
        assertFalse(
            host.nodeSupport()
                .canConfigure(owner));
        frame.buildPermission = true;
        assertTrue(
            host.nodeSupport()
                .canConfigure(owner));
        assertTrue(
            host.nodeSupport()
                .canConfigure(stranger));
        assertTrue(frame.securityQueries >= 3);
        final boolean allSides = host.connectsToAllSides();
        assertTrue(
            host.machine()
                .onWireCutterRightClick(ForgeDirection.DOWN, ForgeDirection.DOWN, owner, 0, 0, 0, null));
        assertEquals(!allSides, host.connectsToAllSides());
    }

    public void sharedClockRegistersOnceAndSurvivesAeTeardown() throws Exception {
        MEEnergyNetwork.init();
        MEEnergyNetwork.init();
        assertEquals(0, MEEnergyTickClock.now());
        FMLCommonHandler.instance()
            .bus()
            .post(new TickEvent.ServerTickEvent(TickEvent.Phase.START));
        assertEquals(1, MEEnergyTickClock.now());
        FMLCommonHandler.instance()
            .bus()
            .post(new TickEvent.ServerTickEvent(TickEvent.Phase.END));
        assertEquals(1, MEEnergyTickClock.now());
        final Frame frame = new Frame();
        final IMEEnergyEndpoint host = hatch(Role.DYNAMO, 5, 4);
        final FakeNode node = frame.add(host, 0);
        frame.cache.onUpdateTick();
        host.machine()
            .setEUVar(55);
        field(WorldData.class, "instance").set(null, null); // AE's ServerStopped can run before this addon.
        try {
            MEEnergyNetwork.stopServer();
            assertEquals(0, MEEnergyTickClock.now());
            assertNull(host.getActionableNode());
            assertEquals(0, frame.cache.memberCount());
            assertEquals(0, node.destroys); // Do not reconstruct obsolete native graphs after WorldData vanished.
            assertEquals(55, host.readEU());
        } finally {
            field(WorldData.class, "instance").set(null, worldData);
        }
    }

    public void nativePowerAndBootEventsInvalidatePlansImmediately() throws Exception {
        final IMEEnergyEndpoint source = nativeHost(Role.DYNAMO, 0);
        forceNativeActive(source);
        final MEEnergyGridCache cache = cache(source);
        source.machine()
            .setEUVar(4000);
        final Grid grid = (Grid) source.getActionableNode()
            .getGrid();
        long epoch = cache.epoch(5);
        advance(10);
        field(PathGridCache.class, "booting").setBoolean(grid.getCache(IPathingGrid.class), true);
        grid.postEvent(new MENetworkBootingStatusChange(true));
        assertFalse(cache.isEpochValid(5, epoch));
        assertFalse(source.isActive());
        advance(1000);
        assertEquals(
            source.spec()
                .power() * 10,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        field(PathGridCache.class, "booting").setBoolean(grid.getCache(IPathingGrid.class), false);
        grid.postEvent(new MENetworkBootingStatusChange(false));
        cache.onUpdateTick();
        assertTrue(source.isActive());
        epoch = cache.epoch(5);
        advance(1);
        field(EnergyGridCache.class, "publicHasPower").setBoolean(grid.getCache(IEnergyGrid.class), false);
        grid.postEvent(new MENetworkPowerStatusChange());
        assertFalse(cache.isEpochValid(5, epoch));
        assertFalse(source.isActive());
        advance(1000);
        assertEquals(
            source.spec()
                .power() * 11,
            source.energyState()
                .rateBudget()
                .tokens(MEEnergyTickClock.now()));
        assertEquals(4000, source.readEU());
    }

    /** Native dummy terrain plus event/write boundaries; it has no placed blocks or actual chunk loading. */
    public static class TrackingWorld extends GTDummyWorld {

        int breaks;
        int neighborQueries;

        @Override
        public boolean blockExists(final int x, final int y, final int z) {
            this.neighborQueries++;
            return false;
        }

        @Override
        public Chunk getChunkFromBlockCoords(final int x, final int z) {
            return null;
        }

        @Override
        public boolean func_147480_a(final int x, final int y, final int z, final boolean drop) {
            assertTrue(drop);
            this.breaks++;
            return true;
        }
    }

    /** Only the GT/player usability decision is controlled; native EU storage and setters are inherited. */
    static class ControlledBase extends BaseMetaTileEntity {

        boolean usable = true;

        @Override
        public boolean isUseableByPlayer(final EntityPlayer player) {
            return this.usable;
        }
    }

    private static final class Player extends EntityPlayer {

        Player(final World world, final UUID uuid, final String name) {
            super(world, new GameProfile(uuid, name));
            setPosition(0.5, 0.5, 0.5);
        }

        @Override
        public void addChatMessage(final IChatComponent message) {}

        @Override
        public boolean canCommandSenderUseCommand(final int level, final String command) {
            return false;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return new ChunkCoordinates(0, 0, 0);
        }
    }
}
