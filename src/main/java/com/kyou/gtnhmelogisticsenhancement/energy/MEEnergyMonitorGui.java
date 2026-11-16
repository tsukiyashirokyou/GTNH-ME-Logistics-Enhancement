package com.kyou.gtnhmelogisticsenhancement.energy;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketBuffer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import com.cleanroommc.modularui.api.IGuiHolder;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.AbstractUIFactory;
import com.cleanroommc.modularui.factory.GuiManager;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.IntValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.StringSyncValue;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;

import appeng.api.config.SecurityPermissions;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.ISecurityGrid;
import appeng.tile.networking.TileController;

/** Controller-local, permission-checked, server-authoritative MUI2 telemetry. */
public final class MEEnergyMonitorGui extends AbstractUIFactory<PosGuiData> {

    private static final MEEnergyMonitorGui FACTORY = new MEEnergyMonitorGui();
    private static final String PREFIX = "gtnhmelogisticsenhancement.monitor.";
    private static boolean registered;

    private MEEnergyMonitorGui() {
        super("gtnhme:eu_monitor");
    }

    public static void init() {
        if (!registered) {
            GuiManager.registerFactory(FACTORY);
            registered = true;
        }
    }

    public static boolean open(final EntityPlayer player, final int x, final int y, final int z) {
        final PosGuiData data = new PosGuiData(player, x, y, z);
        if (!(player instanceof EntityPlayerMP) || controller(data) == null) return false;
        GuiManager.open(FACTORY, data, (EntityPlayerMP) player);
        return true;
    }

    /** Check range and loaded chunks before any tile lookup, including crafted factory-open requests. */
    static TileEntity controller(final PosGuiData data) {
        final EntityPlayer player = data.getPlayer();
        final ItemStack held = player.getHeldItem();
        if (data.getWorld().isRemote || player.isDead
            || data.getSquaredDistance(player) > 64
            || held == null
            || held.getItem() != GTNHMELogisticsEnhancementMod.meEnergyMonitor
            || data.getY() < 0
            || data.getY() >= data.getWorld()
                .getHeight()
            || !data.getWorld()
                .getChunkProvider()
                .chunkExists(data.getX() >> 4, data.getZ() >> 4))
            return null;
        final TileEntity tile = data.getTileEntity();
        if (!(tile instanceof TileController) || tile.isInvalid()) return null;
        final IGridNode node = node(tile);
        if (node == null || node.getGrid() == null) return null;
        final ISecurityGrid security = node.getGrid()
            .getCache(ISecurityGrid.class);
        return security != null && security.hasPermission(player, SecurityPermissions.BUILD) ? tile : null;
    }

    private static IGridNode node(final TileEntity tile) {
        return ((IActionHost) tile).getActionableNode();
    }

    @Override
    public IGuiHolder<PosGuiData> getGuiHolder(final PosGuiData data) {
        return MEEnergyMonitorGui::build;
    }

    @Override
    public boolean canInteractWith(final EntityPlayer player, final PosGuiData data) {
        return player == data.getPlayer() && controller(data) != null;
    }

    @Override
    public void writeGuiData(final PosGuiData data, final PacketBuffer buffer) {
        buffer.writeVarIntToBuffer(data.getX());
        buffer.writeVarIntToBuffer(data.getY());
        buffer.writeVarIntToBuffer(data.getZ());
    }

    @Override
    public PosGuiData readGuiData(final EntityPlayer player, final PacketBuffer buffer) {
        return new PosGuiData(
            player,
            buffer.readVarIntFromBuffer(),
            buffer.readVarIntFromBuffer(),
            buffer.readVarIntFromBuffer());
    }

    private static ModularPanel build(final PosGuiData data, final PanelSyncManager sync, final UISettings settings) {
        final Session session = sync.isClient() ? null : new Session(data);
        settings.canInteractWith(player -> sync.isClient() || player == data.getPlayer() && session.valid());
        if (session != null) {
            sync.addOpenListener(player -> session.start());
            sync.addCloseListener(player -> session.close());
            sync.onServerTick(() -> { if (!session.valid()) session.close(); });
        }
        final int[] selection = { 0 };
        final IntSyncValue color = new IntSyncValue(
            () -> selection[0],
            value -> { if (value >= 0 && value < 17 && (sync.isClient() || session.valid())) selection[0] = value; })
                .allowC2S();
        sync.syncValue("color", color);
        final IntValue page = new IntValue(0);
        final IntSyncValue status = new IntSyncValue(() -> {
            final MEEnergyMonitor monitor = session == null ? null : session.monitor();
            if (monitor == null || monitor.flowEnd() < 0) return 0;
            if (monitor.stockStart() < 0) return 1;
            return monitor.updating() ? 2 : 3;
        });
        sync.syncValue("status", status);
        final ModularPanel panel = ModularPanel.defaultPanel("meEUMonitor", 388, 210);
        panel.child(
            IKey.lang(PREFIX + "name")
                .asWidget()
                .pos(8, 6));
        final CycleButtonWidget colors = new CycleButtonWidget().stateCount(17)
            .value(color)
            .size(184, 18)
            .pos(8, 22);
        for (int index = 0; index < 17; index++) colors.stateOverlay(index, IKey.lang(PREFIX + "color." + index));
        panel.child(colors);
        final CycleButtonWidget pages = new CycleButtonWidget().stateCount(3)
            .value(page)
            .size(178, 18)
            .pos(200, 22);
        for (int index = 0; index < 3; index++) pages.stateOverlay(index, IKey.lang(PREFIX + "page." + index));
        panel.child(pages);
        panel.child(
            IKey.dynamic(
                () -> text(
                    status.getIntValue() == 0 ? "warming"
                        : status.getIntValue() == 1 ? "no_sample" : status.getIntValue() == 2 ? "pending" : "ready"))
                .asWidget()
                .pos(8, 44)
                .scale(0.7f)
                .maxWidth(370));
        panel.child(
            IKey.dynamic(() -> text("heading." + page.getIntValue()))
                .asWidget()
                .pos(8, 59)
                .scale(0.7f)
                .maxWidth(370));
        for (int tier = MEEnergySpec.MIN_TIER; tier <= MEEnergySpec.MAX_TIER; tier++) {
            final int voltageTier = tier;
            final StringSyncValue value = new StringSyncValue(() -> {
                final MEEnergyMonitor monitor = session == null ? null : session.monitor();
                return monitor == null ? "" : monitor.encodedRow(voltageTier, selection[0]);
            });
            sync.syncValue("tier" + tier, value);
            final Display display = new Display();
            final String tierName = MEEnergySpec.standard(MEEnergySpec.Role.ENERGY, tier)
                .tierName();
            panel.child(
                IKey.dynamic(() -> display.line(tierName, value.getStringValue(), page.getIntValue()))
                    .asWidget()
                    .pos(8, 74 + (tier - 5) * 14)
                    .scale(0.72f)
                    .maxWidth(370)
                    .tooltipDynamic(tooltip -> {
                        final MEEnergyMonitorRow row = display.read(value.getStringValue());
                        tooltip.addLine(text("note"));
                        tooltip.addLine(
                            text("counts", row.onlineSources(), row.sources(), row.onlineTargets(), row.targets()));
                        for (int amount = 0; amount < 9; amount++) tooltip.addLine(
                            text(
                                "amount." + amount,
                                row.amount(amount)
                                    .toString()));
                        tooltip.addLine(
                            text(
                                "loss",
                                row.amount(7)
                                    .subtract(row.amount(8))
                                    .toString()));
                    }));
        }
        return panel;
    }

    private static String line(final String tier, final MEEnergyMonitorRow row, final int page) {
        if (page == 1) return text(
            "row.stock",
            tier,
            compact(row.amount(0), 1),
            compact(row.amount(1), 1),
            compact(row.amount(2), 1),
            compact(row.amount(3), 1));
        if (page == 2) return text(
            "row.limit",
            tier,
            compact(row.amount(4), 1),
            compact(row.amount(5), 1),
            compact(row.amount(6), 1));
        return text(
            "row.flow",
            tier,
            row.onlineSources(),
            row.sources(),
            row.onlineTargets(),
            row.targets(),
            compact(row.amount(7), 20),
            compact(row.amount(8), 20),
            compact(
                row.amount(7)
                    .subtract(row.amount(8)),
                20));
    }

    static String compact(final BigInteger value, final int divisor) {
        BigDecimal number = new BigDecimal(value).divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
        final String[] units = { "", "k", "M", "G", "T", "P", "E", "Z", "Y", "R", "Q" };
        int unit = 0;
        while (number.compareTo(BigDecimal.valueOf(1000)) >= 0 && unit < units.length - 1) {
            number = number.movePointLeft(3);
            unit++;
        }
        return number.setScale(2, RoundingMode.HALF_UP)
            .stripTrailingZeros()
            .toPlainString() + units[unit];
    }

    private static String text(final String key, final Object... args) {
        return StatCollector.translateToLocalFormatted(PREFIX + key, args);
    }

    private static final class Display {

        private String encoded;
        private MEEnergyMonitorRow row = MEEnergyMonitorRow.EMPTY;
        private int page = -1;
        private String line;

        String line(final String tier, final String value, final int selectedPage) {
            if (this.line == null || this.page != selectedPage || !java.util.Objects.equals(this.encoded, value)) {
                this.line = MEEnergyMonitorGui.line(tier, read(value), selectedPage);
                this.page = selectedPage;
            }
            return this.line;
        }

        MEEnergyMonitorRow read(final String value) {
            if (!java.util.Objects.equals(this.encoded, value)) {
                this.encoded = value;
                this.row = MEEnergyMonitorRow.decode(value);
                this.line = null;
            }
            return this.row;
        }
    }

    private static final class Session {

        private final PosGuiData data;
        private final World world;
        private final TileEntity tile;
        private final IGridNode node;
        private final IGrid grid;
        private final MEEnergyGridCache cache;
        private MEEnergyMonitor.Watch watch;
        private boolean closed;

        Session(final PosGuiData data) {
            this.data = data;
            this.world = data.getWorld();
            this.tile = controller(data);
            this.node = this.tile == null ? null : node(this.tile);
            this.grid = this.node == null ? null : this.node.getGrid();
            final IMEEnergyGrid energy = this.grid == null ? null : this.grid.getCache(IMEEnergyGrid.class);
            this.cache = energy instanceof MEEnergyGridCache ? (MEEnergyGridCache) energy : null;
        }

        boolean valid() {
            return !this.closed && this.data.getPlayer().worldObj == this.world
                && this.cache != null
                && controller(this.data) == this.tile
                && node(this.tile) == this.node
                && this.node.getGrid() == this.grid
                && this.grid.getCache(IMEEnergyGrid.class) == this.cache
                && (this.watch == null || this.watch.touch());
        }

        void start() {
            if (valid() && this.watch == null) this.watch = this.cache.watch();
        }

        MEEnergyMonitor monitor() {
            return this.closed || this.watch == null ? null : this.watch.monitor();
        }

        void close() {
            this.closed = true;
            if (this.watch != null) this.watch.close();
        }
    }
}
