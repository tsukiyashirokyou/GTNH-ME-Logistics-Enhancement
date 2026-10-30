package com.kyou.gtnhmelogisticsenhancement.distributed;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.function.Function;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.StatCollector;
import net.minecraftforge.fluids.FluidStack;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.FluidDrawable;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.ItemDrawable;
import com.cleanroommc.modularui.factory.PosGuiData;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.UISettings;
import com.cleanroommc.modularui.value.sync.DoubleSyncValue;
import com.cleanroommc.modularui.value.sync.IntSyncValue;
import com.cleanroommc.modularui.value.sync.InteractionSyncHandler;
import com.cleanroommc.modularui.value.sync.PanelSyncManager;
import com.cleanroommc.modularui.value.sync.SyncHandler;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.slot.ItemSlot;
import com.cleanroommc.modularui.widgets.slot.ModularSlot;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;

/** Shared MUI2 construction, following GT's own common-side GUI factories. No net.minecraft.client references. */
public final class DistributedOutputGui {

    private DistributedOutputGui() {}

    public static <K> ModularPanel build(final DistributedOutputHost<K> bus, final PosGuiData data,
        final PanelSyncManager sync, final UISettings settings, final boolean fluid,
        final Function<K, DisplayIcon> icons) {
        settings.canInteractWith(bus::canConfigure);
        final ModularPanel panel = ModularPanel
            .defaultPanel(fluid ? "distributedFluidHatch" : "distributedItemBus", 306, 316)
            .bindPlayerInventory();
        final ViewSync<K> view = new ViewSync<>(bus, sync, fluid, icons);
        sync.syncValue("busView", view);
        final IntSyncValue partitions = new IntSyncValue(
            () -> bus.state()
                .store()
                .partitions(),
            value -> {
                if (authorized(bus, sync) && !bus.state()
                    .locked() && value >= 1 && value <= 16) bus.configurePartitions(value);
            }).allowC2S();
        final DoubleSyncValue period = new DoubleSyncValue(
            () -> bus.state()
                .periodSeconds(),
            value -> {
                if (authorized(bus, sync) && !bus.state()
                    .locked() && value >= 2 && value <= DistributedOutputState.MAX_PERIOD_SECONDS)
                    bus.configurePeriod(DistributedOutputState.ticksFromSeconds(value));
            }).allowC2S();
        panel.child(
            IKey.lang(view.prefix + "name")
                .asWidget()
                .pos(8, 6));
        panel.child(new ItemSlot().slot(new ModularSlot(bus.machine().inventoryHandler, 0) {

            @Override
            public boolean isItemValid(final ItemStack stack) {
                return authorized(bus, sync) && !bus.state()
                    .locked() && bus.acceptsCapacityCard(stack) && super.isItemValid(stack);
            }

            @Override
            public boolean canTakeStack(final EntityPlayer player) {
                return authorized(bus, sync) && !bus.state()
                    .locked() && super.canTakeStack(player);
            }
        }.singletonSlotGroup())
            .pos(8, 23)
            .addTooltipLine(view.text("card_note")));
        panel.child(
            IKey.lang(view.prefix + "partitions")
                .asWidget()
                .pos(34, 24));
        panel.child(
            new TextFieldWidget().value(partitions)
                .numbersInt(1, 16)
                .acceptsExpressions(false)
                .setMaxLength(2)
                .size(24, 14)
                .pos(97, 24));
        panel.child(
            IKey.lang(view.prefix + "period")
                .asWidget()
                .pos(134, 24));
        panel.child(
            new TextFieldWidget().value(period)
                .numbersDouble(2, DistributedOutputState.MAX_PERIOD_SECONDS)
                .acceptsExpressions(false)
                .setMaxLength(12)
                .size(72, 14)
                .pos(216, 24));
        panel.child(
            IKey.dynamic(() -> view.text("network", view.active, view.powered, view.booting))
                .asWidget()
                .pos(8, 47));
        panel.child(
            IKey.dynamic(() -> view.text("capacity", view.total, view.capacity, view.quota))
                .asWidget()
                .pos(8, 60)
                .scale(0.7f)
                .maxWidth(288));
        panel.child(
            IKey.lang(view.prefix + "normal")
                .asWidget()
                .pos(8, 75));
        panel.child(
            IKey.dynamic(() -> view.text("emergency", view.emergencyCount, view.emergencyTotal))
                .asWidget()
                .pos(122, 75)
                .scale(0.7f)
                .maxWidth(176));
        for (int slot = 0; slot < 16; slot++) {
            addReadOnly(panel, view, slot, 8 + slot % 4 * 26, 90 + slot / 4 * 25);
            addReadOnly(panel, view, 16 + slot, 128 + slot % 4 * 26, 90 + slot / 4 * 25);
        }
        panel.child(
            new ButtonWidget<>().size(20, 16)
                .pos(239, 94)
                .child(
                    IKey.str("<")
                        .asWidget()
                        .center())
                .syncHandler(
                    new InteractionSyncHandler()
                        .setOnMousePressed(mouse -> { if (authorized(bus, sync)) view.movePage(-1); })));
        panel.child(
            new ButtonWidget<>().size(20, 16)
                .pos(265, 94)
                .child(
                    IKey.str(">")
                        .asWidget()
                        .center())
                .syncHandler(
                    new InteractionSyncHandler()
                        .setOnMousePressed(mouse -> { if (authorized(bus, sync)) view.movePage(1); })));
        panel.child(
            IKey.dynamic(() -> view.text("page", view.page + 1, view.pages))
                .asWidget()
                .pos(239, 116));
        panel.child(
            IKey.dynamic(() -> view.text("card_state", view.validCard))
                .asWidget()
                .pos(8, 194));
        panel.child(
            IKey.dynamic(
                () -> view.text(
                    "diagnostic",
                    view.fault.isEmpty() ? view.text("ok")
                        : "UNSUPPORTED_FLUID_NBT".equals(view.fault) ? view.text("unsupported_nbt") : view.fault))
                .asWidget()
                .pos(8, 207)
                .scale(0.7f)
                .maxWidth(288));
        return panel;
    }

    private static boolean authorized(final DistributedOutputHost<?> bus, final PanelSyncManager sync) {
        return sync.isClient()
            || bus.canConfigure(sync.getPlayer()) && sync.getPlayer().openContainer == sync.getContainer();
    }

    private static <K> void addReadOnly(final ModularPanel panel, final ViewSync<K> view, final int index, final int x,
        final int y) {
        final ItemDrawable icon = new ItemDrawable();
        final FluidDrawable fluidIcon = new FluidDrawable();
        panel.child(
            new Widget<>().size(18)
                .pos(x, y)
                .background(GuiTextures.SLOT_ITEM, icon, fluidIcon)
                .onUpdateListener(widget -> {
                    final DisplayIcon display = view.icons[index];
                    icon.setItem(display == null ? null : display.item);
                    fluidIcon.setFluid(display == null ? null : display.fluid);
                })
                .tooltipDynamic(tooltip -> {
                    final DisplayIcon stack = view.icons[index];
                    tooltip.addLine(
                        stack == null ? view.text(index < 16 && index >= view.partitions ? "disabled" : "empty")
                            : stack.name());
                    if (stack != null) {
                        tooltip.addLine(view.text("quantity", Long.toString(view.quantities[index])));
                        tooltip.addLine(view.text("quota", Long.toString(view.quota)));
                        if (view.quantities[index] >= view.quota) tooltip.addLine(view.text("over_quota"));
                        if (index < 16 && index >= view.partitions) tooltip.addLine(view.text("legacy"));
                    }
                }));
        panel.child(
            IKey.dynamic(
                () -> view.quantities[index] == 0 ? index < 16 && index >= view.partitions ? "x" : ""
                    : (index < 16 && index >= view.partitions ? "*" : "") + Long.toString(view.quantities[index]))
                .asWidget()
                .pos(x, y + 18)
                .scale(0.5f));
    }

    /** S2C only, <=32 single-item icons, no stock editing endpoint and no full emergency/card-NBT synchronization. */
    private static final class ViewSync<K> extends SyncHandler<ViewSync<K>> {

        private final DistributedOutputHost<K> bus;
        private final PanelSyncManager panel;
        private final DisplayIcon[] icons = new DisplayIcon[32];
        private final Object[] iconIdentities = new Object[32];
        private final Function<K, DisplayIcon> iconsFactory;
        private final boolean fluid;
        private final String prefix;
        private final long[] quantities = new long[32];
        private String total = "0";
        private String emergencyTotal = "0";
        private String fault = "";
        private long capacity;
        private long quota;
        private int partitions = 4;
        private int emergencyCount;
        private int page;
        private int pages = 1;
        private int ticks;
        private long lastCache = -1;
        private long lastConfig = -1;
        private boolean force = true;
        private boolean active;
        private boolean powered;
        private boolean booting;
        private boolean validCard;

        private ViewSync(final DistributedOutputHost<K> bus, final PanelSyncManager panel, final boolean fluid,
            final Function<K, DisplayIcon> factory) {
            this.bus = bus;
            this.panel = panel;
            this.fluid = fluid;
            this.iconsFactory = factory;
            this.prefix = "gtnhmelogisticsenhancement." + (fluid ? "hatch." : "bus.");
        }

        private String text(final String key, final Object... args) {
            return StatCollector.translateToLocalFormatted(this.prefix + key, args);
        }

        private void movePage(final int delta) {
            // InteractionSyncHandler also invokes the callback on the client. Never edit machine state there.
            if (this.panel.isClient()) return;
            this.page = Math.max(
                0,
                Math.min(
                    Math.max(
                        0,
                        (this.bus.state()
                            .store()
                            .emergencyIdentityCount() - 1) / 16),
                    this.page + delta));
            this.force = true;
        }

        @Override
        public void detectAndSendChanges(final boolean init) {
            if (!init && !this.force && ++this.ticks < 10) return;
            this.ticks = 0;
            if (!authorized(this.bus, this.panel)) return;
            final PartitionStore<K> store = this.bus.state()
                .store();
            final boolean active = this.bus.isActive(), powered = this.bus.isPowered(), booting = this.bus.isBooting();
            final String fault = this.bus.diagnostic();
            if (!init && !this.force
                && this.lastCache == store.cacheVersion()
                && this.lastConfig == store.configurationVersion()
                && this.active == active
                && this.powered == powered
                && this.booting == booting
                && this.fault.equals(fault)) return;
            this.active = active;
            this.powered = powered;
            this.booting = booting;
            this.validCard = this.bus.state()
                .validCard();
            this.fault = fault;
            this.lastCache = store.cacheVersion();
            this.lastConfig = store.configurationVersion();
            this.partitions = store.partitions();
            this.capacity = store.capacity();
            this.quota = store.quota();
            this.total = store.totalQuantity()
                .toString();
            this.emergencyTotal = store.emergencyQuantity()
                .toString();
            this.emergencyCount = store.emergencyIdentityCount();
            this.pages = Math.max(1, (this.emergencyCount - 1) / 16 + 1);
            this.page = Math.min(this.page, this.pages - 1);
            this.refreshIcons(store);
            this.force = false;
            this.syncToClient(0, buffer -> {
                buffer.writeInt(this.partitions);
                buffer.writeLong(this.capacity);
                buffer.writeLong(this.quota);
                buffer.writeStringToBuffer(this.total);
                buffer.writeStringToBuffer(this.emergencyTotal);
                buffer.writeStringToBuffer(this.fault);
                buffer.writeInt(this.emergencyCount);
                buffer.writeInt(this.page);
                buffer.writeInt(this.pages);
                buffer.writeBoolean(this.active);
                buffer.writeBoolean(this.powered);
                buffer.writeBoolean(this.booting);
                buffer.writeBoolean(this.validCard);
                for (int index = 0; index < 32; index++) {
                    DisplayIcon.write(buffer, this.icons[index], this.fluid);
                    buffer.writeLong(this.quantities[index]);
                }
            });
        }

        /** Fixed-size view reuse only. Quantity changes do not require recopying identity NBT. */
        private void refreshIcons(final PartitionStore<K> store) {
            Arrays.fill(this.quantities, 0);
            for (final PartitionStore.Entry<K> entry : store.normalEntries()) this.setIcon(entry.slot, entry);
            final List<PartitionStore.Entry<K>> emergency = store.emergencyPage(this.page * 16, 16);
            for (int index = 0; index < emergency.size(); index++) this.setIcon(16 + index, emergency.get(index));
            for (int index = 0; index < this.icons.length; index++) {
                if (this.quantities[index] == 0) {
                    this.icons[index] = null;
                    this.iconIdentities[index] = null;
                }
            }
        }

        private void setIcon(final int index, final PartitionStore.Entry<K> entry) {
            if (!entry.identity.equals(this.iconIdentities[index])) {
                this.icons[index] = this.iconsFactory.apply(entry.identity);
                this.iconIdentities[index] = entry.identity;
            }
            this.quantities[index] = entry.quantity;
        }

        @Override
        public void readOnClient(final int id, final PacketBuffer buffer) throws IOException {
            this.partitions = buffer.readInt();
            this.capacity = buffer.readLong();
            this.quota = buffer.readLong();
            this.total = buffer.readStringFromBuffer(64);
            this.emergencyTotal = buffer.readStringFromBuffer(64);
            this.fault = buffer.readStringFromBuffer(128);
            this.emergencyCount = buffer.readInt();
            this.page = buffer.readInt();
            this.pages = buffer.readInt();
            this.active = buffer.readBoolean();
            this.powered = buffer.readBoolean();
            this.booting = buffer.readBoolean();
            this.validCard = buffer.readBoolean();
            for (int index = 0; index < 32; index++) {
                this.icons[index] = DisplayIcon.read(buffer, this.fluid);
                this.quantities[index] = buffer.readLong();
            }
        }

        @Override
        public void readOnServer(final int id, final PacketBuffer buffer) throws IOException {
            throw new IOException("Partition stock is read only");
        }
    }

    /** Display-only copies. Neither icon is an actual output slot/tank or a second inventory. */
    public static final class DisplayIcon {

        private final ItemStack item;
        private final FluidStack fluid;

        private DisplayIcon(final ItemStack item, final FluidStack fluid) {
            this.item = item;
            this.fluid = fluid;
        }

        public static DisplayIcon item(final ItemStack original) {
            final ItemStack icon = original.copy();
            icon.stackSize = 1;
            if (oversized(icon.getTagCompound())) icon.setTagCompound(null);
            return new DisplayIcon(icon, null);
        }

        public static DisplayIcon fluid(final FluidStack original) {
            final FluidStack icon = original.copy();
            icon.amount = 1;
            if (oversized(icon.tag)) icon.tag = null;
            return new DisplayIcon(null, icon);
        }

        private static boolean oversized(final NBTTagCompound tag) {
            try {
                return tag != null && CompressedStreamTools.compress(tag).length > 1024;
            } catch (IOException invalid) {
                return true;
            }
        }

        private String name() {
            return this.item == null ? this.fluid.getLocalizedName() : this.item.getDisplayName();
        }

        private static void write(final PacketBuffer buffer, final DisplayIcon icon, final boolean fluid)
            throws IOException {
            if (fluid)
                buffer.writeNBTTagCompoundToBuffer(icon == null ? null : icon.fluid.writeToNBT(new NBTTagCompound()));
            else buffer.writeItemStackToBuffer(icon == null ? null : icon.item);
        }

        private static DisplayIcon read(final PacketBuffer buffer, final boolean fluid) throws IOException {
            if (fluid) {
                final NBTTagCompound tag = buffer.readNBTTagCompoundFromBuffer();
                final FluidStack icon = tag == null ? null : FluidStack.loadFluidStackFromNBT(tag);
                return icon == null ? null : new DisplayIcon(null, icon);
            }
            final ItemStack icon = buffer.readItemStackFromBuffer();
            return icon == null ? null : new DisplayIcon(icon, null);
        }
    }
}
