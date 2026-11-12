package com.kyou.gtnhmelogisticsenhancement.energy;

import static org.junit.Assert.*;

import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;
import net.minecraft.world.chunk.IChunkProvider;

import com.cleanroommc.modularui.api.UIFactory;
import com.cleanroommc.modularui.factory.GuiManager;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.ModularSyncManager;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;
import com.kyou.gtnhmelogisticsenhancement.energy.MEEnergySpec.Role;
import com.kyou.gtnhmelogisticsenhancement.item.ItemMEEnergyMonitor;
import com.mojang.authlib.GameProfile;

import appeng.me.helpers.AENetworkProxy;
import appeng.me.helpers.IGridProxyable;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.ITexture;

/** Real native EU fields and production scheduler; controlled AE availability forbids whole-Grid scans. */
public class MEEnergyMonitorFixture extends MEEnergyAENetworkFixture {

    @SuppressWarnings("unchecked")
    public void controllerSessionsEnforceSecurityIdentityAndNeverLoadChunks() throws Exception {
        final Frame frame = new Frame();
        final FakeNode connected = frame.add(hatch(Role.DYNAMO, 5, 4), 0);
        frame.cache.onUpdateTick();
        final MonitorWorld world = new MonitorWorld();
        final TileEntity controller = (TileEntity) Class.forName("appeng.tile.networking.TileController")
            .getDeclaredConstructor()
            .newInstance();
        controller.setWorldObj(world);
        field(AENetworkProxy.class, "node").set(((IGridProxyable) controller).getProxy(), connected.node);
        world.tile = controller;
        final MonitorPlayer player = new MonitorPlayer(world);
        GTNHMELogisticsEnhancementMod.meEnergyMonitor = new ItemMEEnergyMonitor();
        player.inventory.mainInventory[0] = new ItemStack(GTNHMELogisticsEnhancementMod.meEnergyMonitor);
        MEEnergyMonitorGui.init();
        final UIFactory<PosGuiData> factory = (UIFactory<PosGuiData>) GuiManager.getFactory("gtnhme:eu_monitor");
        final PosGuiData data = new PosGuiData(player, 0, 0, 0);
        assertSame(controller, MEEnergyMonitorGui.controller(data));
        final UISettings settings = new UISettings();
        final PanelSyncManager sync = new PanelSyncManager(new ModularSyncManager(false), true);
        factory.createPanel(data, sync, settings);
        assertTrue(settings.canPlayerInteractWithUI(player));
        frame.buildPermission = false;
        assertFalse(settings.canPlayerInteractWithUI(player));
        frame.buildPermission = true;
        assertTrue(settings.canPlayerInteractWithUI(player));
        player.inventory.mainInventory[0] = null;
        assertFalse(settings.canPlayerInteractWithUI(player));
        player.inventory.mainInventory[0] = new ItemStack(GTNHMELogisticsEnhancementMod.meEnergyMonitor);
        world.loaded = false;
        final int reads = world.tileReads;
        assertNull(MEEnergyMonitorGui.controller(data));
        assertEquals(reads, world.tileReads);
        world.loaded = true;
        player.setPosition(100, 0, 0);
        assertNull(MEEnergyMonitorGui.controller(data));
        assertEquals(reads, world.tileReads);
        player.setPosition(0.5, 0.5, 0.5);
        assertNull(MEEnergyMonitorGui.controller(new PosGuiData(player, 0, -1, 0)));
        assertEquals(reads, world.tileReads);
        // A different tile at the same position cannot inherit the old viewing session.
        world.tile = (TileEntity) Class.forName("appeng.tile.networking.TileController")
            .getDeclaredConstructor()
            .newInstance();
        world.tile.setWorldObj(world);
        field(AENetworkProxy.class, "node").set(((IGridProxyable) world.tile).getProxy(), connected.node);
        assertFalse(settings.canPlayerInteractWithUI(player));
        world.tile = controller;
        player.worldObj = new MonitorWorld();
        assertFalse(settings.canPlayerInteractWithUI(player));
        // Client construction never requests server security or an AE node.
        final UISettings client = new UISettings();
        factory.createPanel(data, new PanelSyncManager(new ModularSyncManager(true), true), client);
        assertTrue(client.canPlayerInteractWithUI(player));
    }

    private static final class MonitorWorld extends TrackingWorld {

        TileEntity tile;
        boolean loaded = true;
        int tileReads;
        private final IChunkProvider chunks = (IChunkProvider) Proxy.newProxyInstance(
            IChunkProvider.class.getClassLoader(),
            new Class<?>[] { IChunkProvider.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("chunkExists")) return this.loaded;
                throw new AssertionError("Monitor attempted chunk work: " + method.getName());
            });

        @Override
        public IChunkProvider getChunkProvider() {
            return this.chunks;
        }

        @Override
        public TileEntity getTileEntity(final int x, final int y, final int z) {
            this.tileReads++;
            return this.tile;
        }
    }

    private static final class MonitorPlayer extends EntityPlayer {

        MonitorPlayer(final World world) {
            super(world, new GameProfile(UUID.fromString("d390b64e-629c-4dba-a2a9-a820292a168a"), "Monitor Test"));
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

    public void all162HatchesUseNativeNonLaserVoltagePortsAndMeTraces() throws Exception {
        int checked = 0;
        for (final MEEnergyCatalog.Entry entry : MEEnergyCatalog.entries()) {
            final MEEnergySpec spec = entry.spec();
            final IMEEnergyEndpoint host = hatch(
                spec.role(),
                spec.tier(),
                spec.family() == MEEnergySpec.Family.STANDARD ? 0 : spec.amperage());
            final ITexture port = MEEnergyPresentation.port(spec);
            assertNotNull(port);
            assertNotSame(Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_LASER[spec.tier() + 1], port);
            assertNotSame(Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_LASER[spec.tier() + 1], port);
            for (final String state : new String[] { "getTexturesActive", "getTexturesInactive" }) {
                final ITexture[] layers = (ITexture[]) host.getClass()
                    .getMethod(state, ITexture.class)
                    .invoke(host, new Object[] { null });
                assertEquals(4, layers.length);
                assertSame(port, layers[2]);
                assertNotNull(layers[3]);
            }
            assertEquals(
                spec.amperage() > (spec.role() == Role.ENERGY || spec.tier() < 8 ? 64 : 256),
                MEEnergyPresentation.highPort(spec));
            checked++;
        }
        assertEquals(162, checked);
        assertSame(
            Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_64A[6],
            MEEnergyPresentation.port(MEEnergySpec.multi(Role.DYNAMO, 5, 256)));
        assertTrue(MEEnergyPresentation.highPort(MEEnergySpec.multi(Role.DYNAMO, 5, 256)));
        assertSame(
            Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_4A[6],
            MEEnergyPresentation.port(MEEnergySpec.multi(Role.ENERGY, 5, 4)));
        assertSame(
            Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_256A[9],
            MEEnergyPresentation.port(MEEnergySpec.multi(Role.DYNAMO, 8, 256)));
    }

    public void firstPartialTickIsExcludedAndNoRemainingBudgetMeansNoStockRead() throws Exception {
        final Frame frame = new Frame();
        frame.add(hatch(Role.DYNAMO, 5, 4), 0);
        try (MEEnergyMonitor.Watch watch = frame.cache.watch()) {
            final MEEnergyMonitor monitor = watch.monitor();
            final long start = MEEnergyTickClock.now();
            frame.cache.recordMonitorTransfer(5, 0, 200, 190, start);
            assertEquals(0, monitor.sample(start, 0));
            assertEquals(0, monitor.stockReads());
            advance(1);
            frame.cache.recordMonitorTransfer(5, 0, 20, 19, MEEnergyTickClock.now());
            advance(19);
            monitor.advance(MEEnergyTickClock.now());
            assertEquals(-1, monitor.flowEnd());
            advance(1);
            monitor.advance(MEEnergyTickClock.now());
            assertEquals(start + 21, monitor.flowEnd());
            assertEquals(BigInteger.valueOf(20), monitor.flow(5, 0).gross);
        }
    }

    public void observersShareOneSampleBudgetAndClosingStopsTelemetry() throws Exception {
        final Frame frame = new Frame();
        for (int index = 0; index < 100; index++) frame.add(hatch(Role.DYNAMO, 5, 4), 0);
        frame.cache.onUpdateTick();
        assertNull(field(MEEnergyGridCache.class, "monitor").get(frame.cache));
        final MEEnergyMonitor.Watch first = frame.cache.watch(), second = frame.cache.watch();
        final MEEnergyMonitor monitor = first.monitor();
        assertSame(monitor, second.monitor());
        long before = monitor.stockReads();
        frame.cache.onUpdateTick();
        assertEquals(32, monitor.stockReads() - before);
        frame.cache.onUpdateTick();
        assertEquals(32, monitor.stockReads() - before);
        assertTrue(monitor.updating());
        first.close();
        first.close();
        assertTrue(second.touch());
        for (int tick = 0; tick < 3; tick++) {
            advance(1);
            before = monitor.stockReads();
            frame.cache.onUpdateTick();
            assertTrue(monitor.stockReads() - before <= 32);
            assertTrue(frame.cache.maxTickWork() <= 512);
        }
        assertEquals(
            100,
            monitor.row(5, 0)
                .sources());
        assertEquals(
            100,
            monitor.row(5, 0)
                .onlineSources());
        assertFalse(monitor.updating());
        second.close();
        assertFalse(frame.cache.isMonitoring(monitor));
        before = monitor.stockReads();
        advance(40);
        frame.cache.onUpdateTick();
        assertEquals(before, monitor.stockReads());
        final MEEnergyMonitor.Watch reopened = frame.cache.watch();
        assertNotSame(monitor, reopened.monitor());
        assertEquals(
            -1,
            reopened.monitor()
                .flowEnd());
        advance(100);
        frame.cache.onUpdateTick();
        assertFalse(reopened.touch());
        reopened.close();
    }

    public void stockCountsLimitsAndRawOverCapacityRemainSeparatedByColorAndTier() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4), offline = hatch(Role.DYNAMO, 5, 16),
            target = hatch(Role.ENERGY, 5, 4), white = hatch(Role.DYNAMO, 13, 16777216);
        frame.add(source, 2);
        final FakeNode offlineNode = frame.add(offline, 2);
        offlineNode.channel = false;
        frame.add(target, 2);
        frame.add(white, 16);
        final IMEEnergyEndpoint secondWhite = hatch(Role.DYNAMO, 13, 16777216);
        frame.add(secondWhite, 16);
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity() + 17);
        offline.machine()
            .setEUVar(-1);
        target.machine()
            .setEUVar(
                target.spec()
                    .capacity());
        white.machine()
            .setEUVar(Long.MAX_VALUE);
        secondWhite.machine()
            .setEUVar(Long.MAX_VALUE);
        frame.cache.onUpdateTick();
        try (MEEnergyMonitor.Watch watch = frame.cache.watch()) {
            frame.cache.onUpdateTick();
            final MEEnergyMonitorRow row = watch.monitor()
                .row(5, 2);
            assertEquals(2, row.sources());
            assertEquals(1, row.onlineSources());
            assertEquals(1, row.targets());
            assertEquals(1, row.invalidStocks());
            assertEquals(
                BigInteger.valueOf(
                    source.spec()
                        .capacity() + 17),
                row.amount(0));
            assertEquals(
                BigInteger.valueOf(
                    source.spec()
                        .power()
                        + offline.spec()
                            .power()),
                row.amount(4));
            assertEquals(
                BigInteger.valueOf(
                    source.spec()
                        .power()),
                row.amount(5));
            assertEquals(
                BigInteger.valueOf(
                    target.spec()
                        .power()),
                row.amount(6));
            assertEquals(
                BigInteger.valueOf(Long.MAX_VALUE)
                    .multiply(BigInteger.valueOf(2)),
                watch.monitor()
                    .row(13, 16)
                    .amount(0));
            assertEquals(
                0,
                watch.monitor()
                    .row(5, 0)
                    .sources());
        }
    }

    public void actualCommitsAreCountedOnceWithNativeIntegerLossCredit() throws Exception {
        final Frame frame = new Frame();
        final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 4), target = hatch(Role.ENERGY, 5, 4);
        frame.add(source, 2);
        frame.add(target, 2);
        frame.cache.onUpdateTick();
        source.machine()
            .setEUVar(
                source.spec()
                    .capacity());
        advance(24);
        try (MEEnergyMonitor.Watch watch = frame.cache.watch()) {
            advance(1);
            final long before = source.readEU();
            final MEEnergyGridCache.Member from = frame.cache.first(Role.DYNAMO, 5, 2),
                to = frame.cache.first(Role.ENERGY, 5, 2);
            assertEquals(
                19,
                frame.cache.transfers()
                    .commit(frame.cache, frame.cache.epoch(5), from, to, 19, MEEnergyTickClock.now()));
            assertEquals(
                1,
                frame.cache.transfers()
                    .commit(frame.cache, frame.cache.epoch(5), from, to, 1, MEEnergyTickClock.now()));
            assertEquals(
                0,
                frame.cache.transfers()
                    .commit(frame.cache, frame.cache.epoch(5), from, to, 0, MEEnergyTickClock.now()));
            assertEquals(
                -1,
                watch.monitor()
                    .flowEnd());
            advance(20);
            watch.monitor()
                .advance(MEEnergyTickClock.now());
            final MEEnergyMonitorWindow.Value flow = watch.monitor()
                .flow(5, 2);
            assertEquals(BigInteger.valueOf(before - source.readEU()), flow.gross);
            assertEquals(BigInteger.valueOf(target.readEU()), flow.net);
            assertEquals(BigInteger.valueOf(22), flow.gross);
            assertEquals(BigInteger.valueOf(2), flow.loss());
            assertEquals(
                BigInteger.ZERO,
                watch.monitor()
                    .flow(5, 0).gross);
            assertEquals(
                BigInteger.valueOf(22),
                MEEnergyMonitorRow.decode(
                    watch.monitor()
                        .encodedRow(5, 2))
                    .amount(7));
        }
    }

    public void membershipChangesDiscardPartialSamplesAndReleaseRemovedCursor() throws Exception {
        final Frame frame = new Frame();
        final List<FakeNode> nodes = new ArrayList<>();
        for (int index = 0; index < 80; index++) nodes.add(frame.add(hatch(Role.DYNAMO, 5, 4), 0));
        frame.cache.onUpdateTick();
        try (MEEnergyMonitor.Watch watch = frame.cache.watch()) {
            final MEEnergyMonitor monitor = watch.monitor();
            frame.cache.onUpdateTick();
            assertEquals(0, monitor.stockVersion());
            final FakeNode removed = nodes.get(40);
            frame.cache.removeNode(removed.node, removed.host);
            assertNull(field(MEEnergyMonitor.class, "cursor").get(monitor));
            for (int tick = 0; tick < 4; tick++) {
                advance(1);
                frame.cache.onUpdateTick();
            }
            assertEquals(1, monitor.stockVersion());
            assertEquals(
                79,
                monitor.row(5, 0)
                    .sources());
            final FakeNode recolored = nodes.get(0);
            recolored.host.machine()
                .getBaseMetaTileEntity()
                .setColorization((byte) 0);
            recolored.host.energyState()
                .configurationChanged();
            recolored.host.nodeSupport()
                .configurationChanged();
            assertTrue(monitor.updating());
            for (int tick = 0; tick < 4; tick++) {
                advance(1);
                frame.cache.onUpdateTick();
            }
            assertEquals(
                78,
                monitor.row(5, 0)
                    .sources());
            assertEquals(
                1,
                monitor.row(5, 1)
                    .sources());
        }
    }

    public void observationDoesNotChangeEnergyServiceOrFairness() throws Exception {
        final Frame observed = new Frame(), control = new Frame();
        observed.cache = new MEEnergyGridCache(observed.grid, 10, 3);
        control.cache = new MEEnergyGridCache(control.grid, 10, 3);
        final List<IMEEnergyEndpoint> sources = new ArrayList<>(), targets = new ArrayList<>();
        for (final Frame frame : new Frame[] { observed, control }) {
            for (int index = 0; index < 12; index++) {
                final IMEEnergyEndpoint source = hatch(Role.DYNAMO, 5, 16), target = hatch(Role.ENERGY, 5, 4);
                frame.add(source, index % 3);
                frame.add(target, index % 3);
                source.machine()
                    .setEUVar(
                        source.spec()
                            .capacity());
                target.machine()
                    .setEUVar(
                        target.spec()
                            .capacity());
                sources.add(source);
                targets.add(target);
            }
            frame.cache.onUpdateTick();
        }
        try (MEEnergyMonitor.Watch watch = observed.cache.watch()) {
            int misses = 0;
            for (int tick = 0; tick < 600; tick++) {
                advance(1);
                watch.touch();
                for (int index = 0; index < 24; index++) {
                    final IMEEnergyEndpoint source = sources.get(index), target = targets.get(index);
                    source.machine()
                        .setEUVar(
                            source.spec()
                                .capacity());
                    final long draw = target.spec()
                        .power() * 5
                        / 4;
                    if (target.readEU() < draw) misses++;
                    else target.machine()
                        .setEUVar(target.readEU() - draw);
                    target.nodeSupport()
                        .pollDemandWake(MEEnergyTickClock.now());
                }
                observed.cache.onUpdateTick();
                control.cache.onUpdateTick();
                for (int index = 0; index < 12; index++) {
                    assertEquals(
                        sources.get(index)
                            .readEU(),
                        sources.get(index + 12)
                            .readEU());
                    assertEquals(
                        targets.get(index)
                            .readEU(),
                        targets.get(index + 12)
                            .readEU());
                    assertEquals(
                        sources.get(index)
                            .energyState()
                            .lossAccount()
                            .credit(),
                        sources.get(index + 12)
                            .energyState()
                            .lossAccount()
                            .credit());
                }
            }
            assertEquals(0, misses);
            assertEquals(
                control.cache.transfers()
                    .commits(),
                observed.cache.transfers()
                    .commits());
            assertEquals(
                control.cache.transfers()
                    .reads(),
                observed.cache.transfers()
                    .reads());
            assertTrue(observed.cache.maxTickWork() <= 512);
            assertTrue(
                watch.monitor()
                    .stockReads() > 0);
        }
    }
}
