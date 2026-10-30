package com.kyou.gtnhmelogisticsenhancement.distributed;

import static appeng.util.item.AEFluidStackType.FLUID_STACK_TYPE;
import static org.junit.Assert.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.common.util.ForgeDirection;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import com.glodblock.github.common.item.ItemBasicFluidStorageCell;
import com.glodblock.github.common.item.ItemFluidExtremeStorageCell;
import com.glodblock.github.common.item.ItemMultiFluidStorageCell;
import com.glodblock.github.common.storage.CellType;

import appeng.api.config.Actionable;
import appeng.api.storage.data.IAEFluidStack;
import appeng.me.storage.CellInventory;
import appeng.me.storage.FluidCellInventoryHandler;
import appeng.util.item.AEFluidStack;
import gregtech.api.interfaces.IOutputHatchTransaction;
import gregtech.api.interfaces.IOutputTransaction;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.implementations.MTEHatchOutput;
import gregtech.api.util.FluidEjectionHelper;
import gregtech.api.util.GTUtility.FluidId;
import gregtech.api.util.OutputHatchWrapper;

/** Native GT/AE2FC/MUI classes with registry-only bootstrap. AE destinations are explicitly simulated. */
public class DistributedHatchFixture {

    private static final Fluid FLUID = new Fluid("distributed_hatch_test");
    private static ItemBasicFluidStorageCell basic;
    private static final Item RECIPE_HATCH = new Item();
    private static final Item RECIPE_CARD = new Item();

    public static void registries() throws Exception {
        DistributedBusFixture.registries();
        FluidRegistry.registerFluid(FLUID);
        basic = new ItemBasicFluidStorageCell(CellType.Cell1kPart, 0, 1);
        Item.itemRegistry.addObject(31030, "gtnhmelogisticsenhancement:fluid_card_test", basic);
        Item.itemRegistry.addObject(31031, "gtnhmelogisticsenhancement:recipe_hatch_test", RECIPE_HATCH);
        Item.itemRegistry.addObject(31032, "gtnhmelogisticsenhancement:fluid_recipe_card_test", RECIPE_CARD);
        final Item material = appeng.api.AEApi.instance()
            .definitions()
            .materials()
            .cardInverter()
            .maybeStack(1)
            .get()
            .getItem();
        if (Item.getIdFromItem(material) < 0)
            Item.itemRegistry.addObject(31033, "appliedenergistics2:hatch_test_material", material);
    }

    private static NBTTagCompound tag(final int variant) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("identity", variant);
        return tag;
    }

    private static FluidStack fluid(final int variant, final int amount) {
        return new FluidStack(FLUID, amount, tag(variant));
    }

    private static FluidId id(final int variant) {
        return FluidId.create(fluid(variant, 1));
    }

    private static MTEDistributedMEOutputHatch hatch() {
        return new MTEDistributedMEOutputHatch("test.distributed_fluid", 4, new String[0], new ITexture[0][][]);
    }

    private static ItemStack card() {
        return new ItemStack(basic);
    }

    private static void receive(final DistributedFluidHatchState state, final int variant, final long quantity) {
        final PartitionStore<FluidId>.Transaction transaction = state.store()
            .transaction(true);
        assertTrue(transaction.offer(id(variant), quantity));
        transaction.commit();
    }

    private static void actual(final MTEDistributedMEOutputHatch hatch, final int variant, final int quantity) {
        final FluidEjectionHelper helper = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(1, helper.ejectFluids(Collections.singletonList(fluid(variant, quantity)), 1));
        helper.commit();
    }

    private static int check(final MTEDistributedMEOutputHatch hatch, final FluidStack... outputs) {
        return new FluidEjectionHelper(Collections.singletonList(hatch), true, true)
            .ejectFluids(Arrays.asList(outputs), 1);
    }

    public void hatchesOwnIndependentStateAndUseStandardParent() {
        final MTEDistributedMEOutputHatch first = hatch(),
            second = (MTEDistributedMEOutputHatch) first.newMetaEntity(null);
        assertEquals(
            MTEHatchOutput.class,
            first.getClass()
                .getSuperclass());
        assertNotSame(first.state(), second.state());
        assertNotSame(
            first.state()
                .store(),
            second.state()
                .store());
        assertNotSame(
            first.state()
                .output(),
            second.state()
                .output());
        assertEquals(1, first.getRealInventory().length);
        actual(first, 0, 2000);
        assertEquals(
            0,
            second.state()
                .store()
                .identityCount());
        assertFalse(appeng.api.storage.ICellProvider.class.isAssignableFrom(first.getClass()));
        assertFalse(appeng.api.storage.ICellContainer.class.isAssignableFrom(first.getClass()));
    }

    public void defaultConfigurationAndBounds() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        assertEquals(
            4,
            hatch.state()
                .store()
                .partitions());
        assertEquals(
            128000,
            hatch.state()
                .store()
                .capacity());
        assertEquals(
            32000,
            hatch.state()
                .store()
                .quota());
        assertEquals(
            40,
            hatch.state()
                .period());
        for (final int n : new int[] { 1, 4, 16 }) {
            hatch.configurePartitions(n);
            assertEquals(
                128000 / n,
                hatch.state()
                    .store()
                    .quota());
        }
        assertThrows(IllegalArgumentException.class, () -> hatch.configurePartitions(0));
        assertThrows(IllegalArgumentException.class, () -> hatch.configurePartitions(17));
        assertThrows(IllegalArgumentException.class, () -> hatch.configurePeriod(39));
        hatch.configurePeriod(Integer.MAX_VALUE);
        assertEquals(
            Integer.MAX_VALUE,
            hatch.state()
                .period());
        assertFalse(hatch.canConfigure(null));
    }

    public void singleTankContainerAndNeighbourPathsAreDisabled() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        assertFalse(hatch.doesFillContainers());
        assertFalse(hatch.doesEmptyContainers());
        assertFalse(hatch.canTankBeFilled());
        assertFalse(hatch.canTankBeEmptied());
        assertFalse(hatch.isLiquidInput(ForgeDirection.UNKNOWN));
        assertFalse(hatch.isLiquidOutput(ForgeDirection.UNKNOWN));
        assertEquals(0, hatch.getCapacity());
        assertEquals(0, hatch.getTankInfo(ForgeDirection.UNKNOWN).length);
        assertNull(hatch.drain(100, true));
        assertNull(hatch.getFluid());
        assertNull(hatch.getFillableStack());
        assertThrows(IllegalStateException.class, () -> hatch.setFillableStack(fluid(0, 1)));
        assertFalse(hatch.acceptsConfigCopy());
        assertFalse(hatch.setFilter(fluid(0, 1)));
        hatch.setMode((byte) 9);
        hatch.lockFluid(true);
        assertFalse(hatch.isFluidLocked());
        hatch.onPreTick(null, 1); // Must not invoke inherited container conversion, even without a world.
        assertFalse(hatch.allowPutStack(null, 0, ForgeDirection.NORTH, card()));
        assertFalse(hatch.allowPullStack(null, 0, ForgeDirection.NORTH, card()));
        assertFalse(hatch.shouldDropItemAt(0));
        actual(hatch, 0, 1);
        assertNull(hatch.mFluid);
        assertFalse(hatch.isEmptyAndAcceptsAnyFluid());
        assertTrue(hatch.canStoreFluid(fluid(1, 1)));
        assertEquals(
            1,
            hatch.state()
                .store()
                .identityCount());
    }

    public void recipeCheckIsStrictAndWholeBatchIsSoft() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.state()
            .store()
            .configure(4, 1600);
        actual(hatch, 0, 350);
        assertEquals(1, check(hatch, fluid(0, 1000)));
        assertEquals(
            350,
            hatch.state()
                .store()
                .quantity(id(0)));
        actual(hatch, 0, 1000);
        assertEquals(
            1350,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(0, check(hatch, fluid(0, 1)));
        assertEquals(1, check(hatch, fluid(1, 100), fluid(2, 100)));
    }

    public void sharedN1ProtectedRacesRetainBothIdentities() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.configurePartitions(1);
        assertEquals(1, check(hatch, fluid(0, 1000)));
        assertEquals(1, check(hatch, fluid(1, 1000)));
        actual(hatch, 0, 1000);
        actual(hatch, 1, 1000);
        assertEquals(
            1000,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            1000,
            hatch.state()
                .store()
                .quantity(id(1)));
        assertEquals(
            1,
            hatch.state()
                .store()
                .overflowIdentityCount());
        assertEquals(0, check(hatch, fluid(2, 1)));
    }

    public void sharedQuotaProtectedRaceMerges2350() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.state()
            .store()
            .configure(1, 400);
        actual(hatch, 0, 350);
        assertEquals(1, check(hatch, fluid(0, 1000)));
        assertEquals(1, check(hatch, fluid(0, 1000)));
        actual(hatch, 0, 1000);
        actual(hatch, 0, 1000);
        assertEquals(
            2350,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            1,
            hatch.state()
                .store()
                .identityCount());
    }

    public void nativeHelperMergesDuplicatesAndIntMaxSegments() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        final FluidEjectionHelper helper = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(3, helper.ejectFluids(Arrays.asList(fluid(0, Integer.MAX_VALUE), fluid(0, Integer.MAX_VALUE)), 3));
        helper.commit();
        assertEquals(
            6L * Integer.MAX_VALUE,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            1,
            hatch.state()
                .store()
                .identityCount());
    }

    public void fourVoidingModesPropagateFluidProtection() {
        // VOID_NONE, VOID_ITEM, VOID_FLUID, VOID_ALL: pinned GT's real helper receives these fluid flags.
        for (final boolean protect : new boolean[] { true, true, false, false }) {
            final MTEDistributedMEOutputHatch hatch = hatch();
            hatch.state()
                .store()
                .configure(4, 1600);
            actual(hatch, 0, 1000);
            final FluidEjectionHelper helper = new FluidEjectionHelper(Collections.singletonList(hatch), protect);
            assertEquals(1, helper.ejectFluids(Arrays.asList(fluid(0, 10), fluid(1, 100), fluid(2, 200)), 1));
            helper.commit();
            assertEquals(
                protect ? 1010 : 1000,
                hatch.state()
                    .store()
                    .quantity(id(0)));
            assertEquals(
                100,
                hatch.state()
                    .store()
                    .quantity(id(1)));
            assertEquals(
                200,
                hatch.state()
                    .store()
                    .quantity(id(2)));
        }
    }

    public void directFillAndStoreSimulationNeverGrantEmergency() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.configurePartitions(1);
        assertEquals(100, hatch.fill(fluid(0, 100), false));
        assertEquals(
            0,
            hatch.state()
                .store()
                .identityCount());
        assertEquals(100, hatch.fill(fluid(0, 100), true));
        assertEquals(0, hatch.fill(fluid(1, 100), true));
        final FluidStack refused = fluid(1, 100);
        assertFalse(hatch.storePartial(refused, false));
        assertEquals(100, refused.amount);
        assertEquals(
            100,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            0,
            hatch.state()
                .store()
                .overflowIdentityCount());
    }

    public void layerWrapperRejectsOtherLayersEvenProtected() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        final OutputHatchWrapper layer = new OutputHatchWrapper(hatch, id(0)::equals);
        assertSame(hatch, layer.unwrap());
        assertTrue(layer.isFiltered());
        assertFalse(layer.isFilteredToFluid(id(1)));
        final FluidEjectionHelper helper = new FluidEjectionHelper(Collections.singletonList(layer), true);
        assertEquals(0, helper.ejectFluids(Arrays.asList(fluid(0, 100), fluid(1, 200)), 1));
        helper.commit();
        assertEquals(
            100,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            0,
            hatch.state()
                .store()
                .quantity(id(1)));
        final FluidStack wrong = fluid(1, 100);
        assertFalse(layer.storePartial(wrong, false));
        assertEquals(100, wrong.amount);
    }

    public void layerWrapperForwardsFlagsAndIntChunks() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.configurePartitions(1);
        actual(hatch, 1, 1);
        final OutputHatchWrapper layer = new OutputHatchWrapper(hatch, id(0)::equals);
        assertEquals(
            0,
            new FluidEjectionHelper(Collections.singletonList(layer), true, true)
                .ejectFluids(Collections.singletonList(fluid(0, 10)), 1));
        final FluidEjectionHelper actual = new FluidEjectionHelper(Collections.singletonList(layer), true);
        assertEquals(3, actual.ejectFluids(Collections.singletonList(fluid(0, Integer.MAX_VALUE)), 3));
        actual.commit();
        assertEquals(
            3L * Integer.MAX_VALUE,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            1,
            hatch.state()
                .store()
                .quantity(id(1)));
    }

    public void finiteNativeFluidCellsUse2048WithoutUnitConversion() {
        assertEquals(2048, FLUID_STACK_TYPE.getAmountPerByte());
        final FluidCellCapacityPolicy basicPolicy = FluidCellCapacityPolicy.inspect(card());
        assertTrue(basicPolicy.valid());
        assertEquals(1, basicPolicy.types());
        assertEquals((1024L - 8) * 2048, basicPolicy.capacity(4));
        final ItemStack multi = new ItemStack(new ItemMultiFluidStorageCell(CellType.Cell1kPart, 0, 1));
        final FluidCellCapacityPolicy multiPolicy = FluidCellCapacityPolicy.inspect(multi);
        assertTrue(multiPolicy.valid());
        assertEquals(5, multiPolicy.types());
        assertEquals((1024L - 5 * 8) * 2048, multiPolicy.capacity(16));
        final ItemStack extreme = new ItemStack(new ItemFluidExtremeStorageCell("hatch_test", 1000000000L, 1, 8, 1));
        assertTrue(
            FluidCellCapacityPolicy.inspect(extreme)
                .valid());
        assertEquals(
            (1000000000L - 8) * 2048,
            FluidCellCapacityPolicy.inspect(extreme)
                .capacity(16));
    }

    public void effectiveQuotasUseNativeFluidTypeLimits() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        state.updateCard(card());
        assertEquals(
            1,
            state.store()
                .effectivePartitions());
        assertEquals(
            2080768,
            state.store()
                .quota());
        final ItemStack multi = new ItemStack(new ItemMultiFluidStorageCell(CellType.Cell1kPart, 0, 1));
        state.updateCard(multi);
        assertEquals(
            4,
            state.store()
                .effectivePartitions());
        assertEquals(
            507904,
            state.store()
                .quota());
        state.setPartitions(16);
        assertEquals(
            5,
            state.store()
                .effectivePartitions());
        assertEquals(
            2015232L / 5,
            state.store()
                .quota());
        final DistributedFluidHatchState restored = DistributedFluidHatchState.read(state.write(), multi);
        assertEquals(
            state.store()
                .quota(),
            restored.store()
                .quota());
        state.updateCard(null);
        assertEquals(
            16,
            state.store()
                .effectivePartitions());
        assertEquals(
            128000 / 16,
            state.store()
                .quota());
    }

    public void fluidOreUpgradeKeepsNativePreciseAndInverterFilters() {
        final ItemStack card = card();
        basic.getConfigAEInventory(card)
            .putAEStackInSlot(0, AEFluidStack.create(new FluidStack(FLUID, 1)));
        basic.getUpgradesInventory(card)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardOreFilter()
                    .maybeStack(1)
                    .get());
        final ItemStack before = card.copy();
        final FluidCellCapacityPolicy policy = FluidCellCapacityPolicy.inspect(card);
        assertTrue(policy.valid());
        final FluidId configured = FluidId.create(new FluidStack(FLUID, 1));
        final FluidId rejected = FluidId.create(new FluidStack(FluidRegistry.WATER, 1));
        assertTrue(policy.accepts(configured));
        assertFalse(policy.accepts(rejected));
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        final ItemStack inverted = card.copy();
        basic.getUpgradesInventory(inverted)
            .setInventorySlotContents(
                1,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardInverter()
                    .maybeStack(1)
                    .get());
        final ItemStack invertedBefore = inverted.copy();
        final FluidCellCapacityPolicy blacklist = FluidCellCapacityPolicy.inspect(inverted);
        assertTrue(blacklist.valid());
        assertFalse(blacklist.accepts(configured));
        assertTrue(blacklist.accepts(rejected));
        assertTrue(ItemStack.areItemStacksEqual(invertedBefore, inverted));
    }

    public void preexistingCardContentsRemainUntouched() {
        final ItemStack card = card();
        final FluidCellInventoryHandler handler = (FluidCellInventoryHandler) CellInventory
            .getCell(card, null, FLUID_STACK_TYPE);
        assertNull(handler.injectItems(AEFluidStack.create(fluid(0, 12345)), Actionable.MODULATE, null));
        final ItemStack before = card.copy();
        final FluidCellCapacityPolicy policy = FluidCellCapacityPolicy.inspect(card);
        assertTrue(policy.valid());
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        state.updateCard(card);
        assertEquals(
            0,
            state.store()
                .identityCount());
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        assertEquals(
            (1024L - 8) * 2048,
            state.store()
                .capacity());
    }

    public void nativeFiltersInverterAndRestrictionsUseCopies() {
        final ItemStack card = card();
        basic.setCellRestriction(card, new appeng.helpers.ICellRestriction.CellRestrictionData((byte) 1, 50000));
        basic.getConfigAEInventory(card)
            .putAEStackInSlot(0, AEFluidStack.create(fluid(0, 1)));
        final ItemStack before = card.copy();
        final FluidCellCapacityPolicy policy = FluidCellCapacityPolicy.inspect(card);
        assertTrue(policy.valid());
        assertEquals(1, policy.types());
        assertEquals(50000, policy.capacity(4));
        final FluidId other = FluidId.create(new FluidStack(FluidRegistry.WATER, 1));
        assertTrue(policy.accepts(id(0)));
        assertFalse(policy.accepts(other));
        assertTrue(ItemStack.areItemStacksEqual(before, card));
        final ItemStack inverted = card.copy();
        basic.getUpgradesInventory(inverted)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardInverter()
                    .maybeStack(1)
                    .get());
        final ItemStack invertedBefore = inverted.copy();
        assertFalse(
            FluidCellCapacityPolicy.inspect(inverted)
                .accepts(id(0)));
        assertTrue(
            FluidCellCapacityPolicy.inspect(inverted)
                .accepts(other));
        assertTrue(ItemStack.areItemStacksEqual(invertedBefore, inverted));
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        state.updateCard(card);
        final DistributedFluidHatchState loaded = DistributedFluidHatchState.read(state.write(), card);
        assertFalse(
            loaded.output()
                .storePartial(new FluidStack(FluidRegistry.WATER, 10), true));
        assertTrue(
            loaded.output()
                .storePartial(fluid(0, 10), true));
    }

    public void nonFiniteForeignAndDamagedCardsAreRejected() {
        for (final ItemStack invalid : new ItemStack[] { new ItemStack(RECIPE_CARD),
            new ItemStack(new com.glodblock.github.common.item.ItemCreativeFluidStorageCell()),
            new ItemStack(new com.glodblock.github.common.item.ItemFluidVoidStorageCell()),
            new ItemStack(new com.glodblock.github.common.item.ItemInfinityWaterStorageCell()),
            new ItemStack(new ItemBasicFluidStorageCell(CellType.Cell1kPart, 0, 1) {}), new ItemStack(basic, 2) })
            assertFalse(
                FluidCellCapacityPolicy.inspect(invalid)
                    .valid());
        final ItemStack overflow = card();
        basic.getUpgradesInventory(overflow)
            .setInventorySlotContents(
                0,
                appeng.api.AEApi.instance()
                    .definitions()
                    .materials()
                    .cardVoidOverflow()
                    .maybeStack(1)
                    .get());
        assertFalse(
            FluidCellCapacityPolicy.inspect(overflow)
                .valid());
        final ItemStack damaged = card();
        final ItemStack invalidTypeRestriction = card();
        basic.setCellRestriction(
            invalidTypeRestriction,
            new appeng.helpers.ICellRestriction.CellRestrictionData((byte) 2, 50000));
        assertFalse(
            FluidCellCapacityPolicy.inspect(invalidTypeRestriction)
                .valid());
        damaged.setTagCompound(new NBTTagCompound());
        damaged.getTagCompound()
            .setLong("fc", -1);
        assertFalse(
            FluidCellCapacityPolicy.inspect(damaged)
                .valid());
        final MTEDistributedMEOutputHatch hatch = hatch();
        assertTrue(hatch.isItemValidForSlot(0, card()));
        assertFalse(hatch.isItemValidForSlot(0, new ItemStack(RECIPE_CARD)));
    }

    public void shrinkingPolicyRetainsOldStockAndAllowsDrain() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 1000000);
        receive(state, 1, 2000000);
        final ItemStack card = card();
        basic.getConfigAEInventory(card)
            .putAEStackInSlot(0, AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1)));
        state.updateCard(card);
        state.setPartitions(1);
        state.updateCard(null);
        assertEquals(
            BigInteger.valueOf(3000000),
            state.store()
                .totalQuantity());
        state.updateCard(card);
        assertFalse(
            state.output()
                .storePartial(fluid(0, 1), true));
        assertTrue(state.flush((identity, amount) -> null, () -> {}));
        assertEquals(
            0,
            state.store()
                .identityCount());
        assertTrue(
            state.output()
                .storePartial(new FluidStack(FluidRegistry.WATER, 1), true));
    }

    @SuppressWarnings("unchecked")
    private static appeng.api.storage.IMEInventory<IAEFluidStack> destination(
        final java.util.function.Function<IAEFluidStack, IAEFluidStack> insert) {
        return (appeng.api.storage.IMEInventory<IAEFluidStack>) java.lang.reflect.Proxy.newProxyInstance(
            DistributedHatchFixture.class.getClassLoader(),
            new Class<?>[] { appeng.api.storage.IMEInventory.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("injectItems")) return insert.apply((IAEFluidStack) args[0]);
                if (method.getName()
                    .equals("getStackType")) return FLUID_STACK_TYPE;
                if (method.getName()
                    .equals("getChannel")) return appeng.api.storage.StorageChannel.FLUIDS;
                throw new UnsupportedOperationException(method.getName());
            });
    }

    private static appeng.api.networking.energy.IEnergySource energy(final double available, final double[] spent) {
        return (appeng.api.networking.energy.IEnergySource) java.lang.reflect.Proxy.newProxyInstance(
            DistributedHatchFixture.class.getClassLoader(),
            new Class<?>[] { appeng.api.networking.energy.IEnergySource.class },
            (proxy, method, args) -> {
                final double amount = Math.min(available, (Double) args[0]);
                if (args[1] == Actionable.MODULATE) spent[0] += amount;
                return amount;
            });
    }

    private static final class GridPower {

        double stored = 1000, spent;
        int simulations;
        Runnable duringSimulation;

        final appeng.api.networking.energy.IEnergyGrid grid = (appeng.api.networking.energy.IEnergyGrid) java.lang.reflect.Proxy
            .newProxyInstance(
                DistributedHatchFixture.class.getClassLoader(),
                new Class<?>[] { appeng.api.networking.energy.IEnergyGrid.class },
                (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getStoredPower":
                            return this.stored;
                        case "getMaxStoredPower":
                            return 1000.0;
                        case "getIdlePowerUsage":
                            return 0.0;
                        case "getHasInfiniteStore":
                            return false;
                        case "extractAEPower":
                            if (args[1] == Actionable.SIMULATE) {
                                this.simulations++;
                                if (this.duringSimulation != null) this.duringSimulation.run();
                            }
                            final appeng.api.config.PowerMultiplier multiplier = (appeng.api.config.PowerMultiplier) args[2];
                            final double physical = Math.min(this.stored, multiplier.multiply((Double) args[0]));
                            if (args[1] == Actionable.MODULATE) {
                                this.stored -= physical;
                                this.spent += physical;
                            }
                            return multiplier.divide(physical);
                        default:
                            throw new AssertionError("Unexpected energy call: " + method);
                    }
                });
    }

    @SuppressWarnings("unchecked")
    private static <T extends appeng.api.storage.data.IAEStack<T>> appeng.api.storage.IMEInventory<T> energyDestination(
        final appeng.api.storage.data.IAEStackType<T> type, final java.util.function.Function<T, T> receive) {
        return (appeng.api.storage.IMEInventory<T>) java.lang.reflect.Proxy.newProxyInstance(
            DistributedHatchFixture.class.getClassLoader(),
            new Class<?>[] { appeng.api.storage.IMEInventory.class },
            (proxy, method, args) -> {
                if (method.getName()
                    .equals("injectItems")) return receive.apply((T) args[0]);
                if (method.getName()
                    .equals("getStackType")) return type;
                throw new AssertionError("Unexpected inventory call: " + method);
            });
    }

    public void distributedEnergySharesBudgetBetweenItemsAndFluids() {
        DistributedOutputEnergy.clear();
        final Object shared = new Object();
        final GridPower power = new GridPower();
        final appeng.api.networking.security.MachineSource source = new appeng.api.networking.security.MachineSource(
            hatch());
        final appeng.api.storage.data.IAEItemStack items = appeng.util.item.AEItemStack
            .create(new ItemStack(RECIPE_HATCH))
            .setStackSize(Integer.MAX_VALUE);
        final appeng.api.storage.data.IAEItemStack itemLeft = DistributedOutputEnergy.insert(
            shared,
            power.grid,
            () -> true,
            energyDestination(appeng.util.item.AEItemStackType.ITEM_STACK_TYPE, input -> {
                assertEquals(100, input.getStackSize());
                return null;
            }),
            items,
            appeng.util.item.AEItemStackType.ITEM_STACK_TYPE,
            source);
        assertEquals(Integer.MAX_VALUE - 100L, itemLeft.getStackSize());
        final IAEFluidStack fluids = AEFluidStack.create(new FluidStack(FLUID, 1000000));
        final IAEFluidStack fluidLeft = DistributedOutputEnergy
            .insert(shared, power.grid, () -> true, energyDestination(FLUID_STACK_TYPE, input -> {
                fail("Shared quota already spent");
                return null;
            }), fluids, FLUID_STACK_TYPE, source);
        assertEquals(1000000, fluidLeft.getStackSize());
        assertEquals(100, power.spent, 0);
        assertEquals(900, power.stored, 0);
    }

    public void distributedEnergyChargesActualReceiptsAndPreservesStock() {
        final double previousMultiplier = appeng.api.config.PowerMultiplier.CONFIG.multiply(1);
        try {
            for (final double multiplier : new double[] { 1, 2.5 }) {
                appeng.api.config.PowerMultiplier.CONFIG.multiplier = multiplier;
                final long authorized = (long) (100 / multiplier) * 1000;
                for (final long accepted : new long[] { 0, 1501, authorized }) {
                    DistributedOutputEnergy.clear();
                    final GridPower power = new GridPower();
                    final Object shared = new Object();
                    final DistributedFluidHatchState state = new DistributedFluidHatchState();
                    receive(state, 0, 1000000);
                    final appeng.api.storage.IMEInventory<IAEFluidStack> monitor = energyDestination(
                        FLUID_STACK_TYPE,
                        input -> {
                            assertEquals(authorized, input.getStackSize());
                            return accepted == authorized ? null
                                : input.copy()
                                    .setStackSize(authorized - accepted);
                        });
                    state.flush((identity, amount) -> {
                        final IAEFluidStack result = DistributedOutputEnergy.insert(
                            shared,
                            power.grid,
                            () -> true,
                            monitor,
                            AEFluidStack.create(new FluidStack(FLUID, amount)),
                            FLUID_STACK_TYPE,
                            new appeng.api.networking.security.MachineSource(hatch()));
                        return result == null ? null
                            : new DistributedOutputState.Remainder<>(identity, result.getStackSize());
                    }, () -> {});
                    assertEquals(
                        1000000 - accepted,
                        state.store()
                            .quantity(id(0)));
                    assertEquals(((accepted + 999) / 1000) * multiplier, power.spent, 0);
                    assertFalse(state.locked());
                    assertNotNull(
                        DistributedOutputEnergy
                            .insert(shared, power.grid, () -> true, energyDestination(FLUID_STACK_TYPE, input -> {
                                fail("No gross-quota recycling");
                                return null;
                            }),
                                AEFluidStack.create(new FluidStack(FLUID, 1000000)),
                                FLUID_STACK_TYPE,
                                new appeng.api.networking.security.MachineSource(hatch())));
                }
            }
        } finally {
            appeng.api.config.PowerMultiplier.CONFIG.multiplier = previousMultiplier;
            DistributedOutputEnergy.clear();
        }
    }

    public void distributedEnergyChecksTopologyAndSynchronousReentry() {
        DistributedOutputEnergy.clear();
        final GridPower power = new GridPower();
        final Object shared = new Object();
        final boolean[] valid = { true };
        power.duringSimulation = () -> { if (power.simulations == 2) valid[0] = false; };
        final IAEFluidStack offered = AEFluidStack.create(new FluidStack(FLUID, 1000000));
        final appeng.api.networking.security.MachineSource source = new appeng.api.networking.security.MachineSource(
            hatch());
        final IAEFluidStack left = DistributedOutputEnergy
            .insert(shared, power.grid, () -> valid[0], energyDestination(FLUID_STACK_TYPE, input -> {
                fail("Grid changed during native SIM");
                return null;
            }), offered, FLUID_STACK_TYPE, source);
        assertEquals(1000000, left.getStackSize());
        assertEquals(0, power.spent, 0);
        DistributedOutputEnergy.clear();
        power.duringSimulation = null;
        DistributedOutputEnergy.insert(shared, power.grid, () -> true, energyDestination(FLUID_STACK_TYPE, input -> {
            final IAEFluidStack nested = DistributedOutputEnergy
                .insert(shared, power.grid, () -> true, energyDestination(FLUID_STACK_TYPE, ignored -> {
                    fail("Reentry exceeded quota");
                    return null;
                }), offered, FLUID_STACK_TYPE, source);
            assertEquals(offered.getStackSize(), nested.getStackSize());
            return null;
        }), offered, FLUID_STACK_TYPE, source);
        assertEquals(100, power.spent, 0);
    }

    public void distributedEnergyRenewsOnServerTickAndRetainsUncertainFlight() {
        DistributedOutputEnergy.clear();
        final GridPower power = new GridPower();
        final Object shared = new Object();
        final IAEFluidStack offered = AEFluidStack.create(new FluidStack(FLUID, 1000000));
        final appeng.api.networking.security.MachineSource source = new appeng.api.networking.security.MachineSource(
            hatch());
        DistributedOutputEnergy.insert(
            shared,
            power.grid,
            () -> true,
            energyDestination(FLUID_STACK_TYPE, input -> null),
            offered,
            FLUID_STACK_TYPE,
            source);
        final DistributedOutputEnergy.ServerTicks ticks = new DistributedOutputEnergy.ServerTicks();
        ticks.tick(
            new cpw.mods.fml.common.gameevent.TickEvent.ServerTickEvent(
                cpw.mods.fml.common.gameevent.TickEvent.Phase.START));
        DistributedOutputEnergy.insert(shared, power.grid, () -> true, energyDestination(FLUID_STACK_TYPE, input -> {
            assertEquals(90000, input.getStackSize());
            return null;
        }), offered, FLUID_STACK_TYPE, source);
        assertEquals(190, power.spent, 0);
        DistributedOutputEnergy.clear();
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 1000000);
        state.flush((identity, amount) -> {
            DistributedOutputEnergy.insert(
                shared,
                power.grid,
                () -> true,
                energyDestination(FLUID_STACK_TYPE, input -> { throw new IllegalStateException("Unknown delivery"); }),
                offered,
                FLUID_STACK_TYPE,
                source);
            fail("Must propagate unknown result to flight isolation");
            return null;
        }, () -> {});
        assertTrue(state.locked());
        assertTrue(
            DistributedFluidHatchState.read(state.write(), null)
                .locked());
        assertEquals(
            1000000,
            state.store()
                .quantity(id(0)));
    }

    public void nativePoweredInsertUsesFluidEnergyUnits() {
        for (final int available : new int[] { 0, 2, 100 }) for (final int capacity : new int[] { 0, 1501, 100000 }) {
            final double[] spent = { 0 };
            final CheckedStorageDestination<IAEFluidStack> destination = new CheckedStorageDestination<>(
                destination(input -> {
                    final long accepted = Math.min(capacity, input.getStackSize());
                    return accepted == input.getStackSize() ? null
                        : input.copy()
                            .setStackSize(input.getStackSize() - accepted);
                }),
                FLUID_STACK_TYPE);
            final IAEFluidStack remaining = appeng.util.Platform.poweredInsert(
                energy(available, spent),
                destination,
                AEFluidStack.create(new FluidStack(FLUID, 100000)),
                new appeng.api.networking.security.MachineSource(hatch()));
            final long accepted = Math.min(available * 1000L, capacity);
            assertEquals((accepted + 999) / 1000, spent[0], 0);
            assertEquals(100000 - accepted, remaining == null ? 0 : remaining.getStackSize());
        }
    }

    public void rawInvalidReceiptsAndFluidNbtChangesAreIsolated() {
        for (final long left : new long[] { -1, 101, Long.MAX_VALUE }) {
            final CheckedStorageDestination<IAEFluidStack> checked = new CheckedStorageDestination<>(
                destination(
                    input -> input.copy()
                        .setStackSize(left)),
                FLUID_STACK_TYPE);
            assertThrows(
                IllegalStateException.class,
                () -> checked.injectItems(AEFluidStack.create(fluid(0, 100)), Actionable.MODULATE, null));
        }
        assertNull(DistributedFluidBridge.offer(id(0), 100));
        assertNotNull(DistributedFluidBridge.offer(FluidId.create(new FluidStack(FLUID, 1)), 100));
        final IAEFluidStack first = AEFluidStack.create(fluid(0, 100));
        final IAEFluidStack wrong = AEFluidStack.create(new FluidStack(FluidRegistry.WATER, 1));
        assertFalse(first.isSameType(wrong));
        assertNotEquals(
            first.copy()
                .setStackSize(1),
            wrong);
        final CheckedStorageDestination<IAEFluidStack> checked = new CheckedStorageDestination<>(
            destination(input -> wrong),
            FLUID_STACK_TYPE);
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 100);
        state.flush((identity, amount) -> {
            checked.injectItems(AEFluidStack.create(fluid(0, amount)), Actionable.MODULATE, null);
            return null;
        }, () -> {});
        assertTrue(state.locked());
        assertEquals(
            100,
            state.store()
                .quantity(id(0)));
        assertTrue(
            state.write()
                .hasKey("flight"));
    }

    public void partialRefusalAndRecoveryConserveQuantities() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 100);
        assertFalse(
            state.flush((identity, amount) -> new DistributedOutputState.Remainder<>(identity, amount), () -> {}));
        assertEquals(
            100,
            state.store()
                .quantity(id(0)));
        assertFalse(state.locked());
        assertTrue(state.flush((identity, amount) -> new DistributedOutputState.Remainder<>(identity, 30), () -> {}));
        assertEquals(
            30,
            state.store()
                .quantity(id(0)));
        assertFalse(state.locked());
        assertTrue(state.flush((identity, amount) -> {
            assertEquals(30, amount);
            return null;
        }, () -> {}));
        assertEquals(
            0,
            state.store()
                .identityCount());
        assertEquals(
            BigInteger.ZERO,
            state.store()
                .totalQuantity());
    }

    public void fortyTickTimerAndIntBridgeRemainBounded() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        for (int tick = 0; tick < 400; tick++) assertFalse(state.tick());
        receive(state, 0, (long) Integer.MAX_VALUE + 42);
        for (int tick = 1; tick < 40; tick++) assertFalse(state.tick());
        assertTrue(state.tick());
        state.flush((identity, amount) -> {
            assertEquals(Integer.MAX_VALUE, amount);
            return null;
        }, () -> {});
        assertEquals(
            42,
            state.store()
                .quantity(id(0)));
        for (int tick = 1; tick < 40; tick++) assertFalse(state.tick());
        assertTrue(state.tick());
        state.flush((identity, amount) -> {
            assertEquals(42, amount);
            return null;
        }, () -> {});
        assertEquals(
            0,
            state.store()
                .identityCount());
    }

    public void allEmergencyIdentitiesRotateAndCursorSurvivesReload() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        for (int identity = 0; identity < 5000; identity++) receive(state, identity, 100);
        final Set<FluidId> visited = new HashSet<>();
        for (int cycle = 0; cycle < 313; cycle++) {
            final AtomicInteger calls = new AtomicInteger();
            state.flush((identity, amount) -> {
                calls.incrementAndGet();
                visited.add(identity);
                return new DistributedOutputState.Remainder<>(identity, amount);
            }, () -> {});
            assertEquals(cycle == 312 ? 8 : 16, calls.get());
        }
        assertEquals(5000, visited.size());
        final DistributedFluidHatchState restored = DistributedFluidHatchState.read(state.write(), null);
        assertEquals(
            BigInteger.valueOf(500000),
            restored.store()
                .totalQuantity());
        assertEquals(
            id(0),
            restored.store()
                .nextOutputBatch(1)
                .get(0).identity);
    }

    public void uncertainOutputJournalNeverReplaysAfterReload() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 100);
        state.flush(
            (identity, amount) -> { throw new IllegalStateException("unknown external side effect"); },
            () -> {});
        final DistributedFluidHatchState restored = DistributedFluidHatchState.read(state.write(), null);
        assertTrue(restored.locked());
        assertTrue(
            restored.write()
                .hasKey("flight"));
        assertFalse(restored.flush((identity, amount) -> {
            fail("uncertain delivery must not replay");
            return null;
        }, () -> {}));
        assertEquals(
            100,
            restored.store()
                .quantity(id(0)));
    }

    public void synchronousRemovalAndTransactionsAreIsolated() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        actual(hatch, 0, 100);
        final IOutputHatchTransaction input = hatch.createTransaction();
        final NBTTagCompound[] drop = { null };
        hatch.state()
            .flush((identity, amount) -> {
                assertThrows(IllegalStateException.class, () -> input.storePartial(id(1), fluid(1, 1), 0, 0));
                assertThrows(IllegalStateException.class, () -> hatch.configurePeriod(40));
                drop[0] = new NBTTagCompound();
                hatch.setItemNBT(drop[0]);
                return null;
            }, () -> {});
        assertTrue(
            hatch.state()
                .locked());
        assertEquals(
            100,
            hatch.state()
                .store()
                .quantity(id(0)));
        final MTEDistributedMEOutputHatch placed = hatch();
        placed.loadNBTData(drop[0]);
        assertTrue(
            placed.state()
                .locked());
        assertEquals(
            100,
            placed.state()
                .store()
                .quantity(id(0)));
    }

    public void machineItemPreservesCardAndAllFluidStock() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        final ItemStack card = card();
        ((FluidCellInventoryHandler) CellInventory.getCell(card, null, FLUID_STACK_TYPE))
            .injectItems(AEFluidStack.create(fluid(8, 1234)), Actionable.MODULATE, null);
        hatch.getRealInventory()[0] = card;
        hatch.onContentsChanged(0);
        hatch.configurePartitions(1);
        hatch.configurePeriod(80);
        receive(hatch.state(), 0, Long.MAX_VALUE - 1);
        receive(hatch.state(), 1, Long.MAX_VALUE - 2);
        final BigInteger total = hatch.state()
            .store()
            .totalQuantity();
        final ItemStack before = card.copy();
        final NBTTagCompound saved = new NBTTagCompound();
        hatch.setItemNBT(saved);
        assertTrue(
            hatch.state()
                .locked());
        assertFalse(hatch.shouldDropItemAt(0));
        final MTEDistributedMEOutputHatch placed = hatch();
        // Mirrors GT's real order: standard Inventory restoration precedes MTE.loadNBTData.
        placed.getRealInventory()[0] = ItemStack.loadItemStackFromNBT(
            saved.getTagList("Inventory", 10)
                .getCompoundTagAt(0));
        placed.loadNBTData(saved);
        assertEquals(
            total,
            placed.state()
                .store()
                .totalQuantity());
        assertEquals(
            2,
            placed.state()
                .store()
                .identityCount());
        assertEquals(
            1,
            placed.state()
                .store()
                .partitions());
        assertEquals(
            80,
            placed.state()
                .period());
        assertTrue(ItemStack.areItemStacksEqual(before, placed.getRealInventory()[0]));
        assertFalse(
            placed.state()
                .locked());
        final NBTTagCompound world = new NBTTagCompound();
        placed.saveNBTData(world);
        assertEquals(
            saved.getCompoundTag("distributedFluidHatch")
                .getCompoundTag("store")
                .getTag("entries"),
            world.getCompoundTag("distributedFluidHatch")
                .getCompoundTag("store")
                .getTag("entries"));
        assertEquals(
            saved.getCompoundTag("distributedFluidHatch")
                .getCompoundTag("store")
                .getLong("capacity"),
            world.getCompoundTag("distributedFluidHatch")
                .getCompoundTag("store")
                .getLong("capacity"));
        assertFalse(world.hasKey("mFluid"));
    }

    public void corruptNbtAndForeignSingleTankRemainEvidence() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 100);
        final NBTTagCompound raw = state.write();
        raw.getCompoundTag("store")
            .getTagList("entries", 10)
            .getCompoundTagAt(0)
            .setLong("quantity", -1);
        final DistributedFluidHatchState broken = DistributedFluidHatchState.read(raw, null);
        assertTrue(broken.locked());
        assertEquals(
            raw,
            broken.write()
                .getTag("quarantine"));
        final NBTTagCompound legacy = new NBTTagCompound();
        legacy.setTag("mFluid", fluid(0, 123).writeToNBT(new NBTTagCompound()));
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.loadNBTData(legacy);
        assertTrue(
            hatch.state()
                .locked());
        assertNull(hatch.mFluid);
        assertEquals(
            0,
            hatch.state()
                .store()
                .identityCount());
        final NBTTagCompound saved = new NBTTagCompound();
        hatch.saveNBTData(saved);
        assertEquals(legacy.getTag("mFluid"), saved.getTag("mFluid"));
        final MTEDistributedMEOutputHatch again = hatch();
        again.loadNBTData(saved);
        assertTrue(
            again.state()
                .locked());
    }

    public void nodeAndBrokenInventoryEvidenceSurviveReload() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        actual(hatch, 0, 100);
        final NBTTagCompound tag = new NBTTagCompound();
        hatch.saveNBTData(tag);
        final NBTTagCompound node = new NBTTagCompound();
        node.setInteger("player", 42);
        tag.setTag("distributedAE", node);
        final net.minecraft.nbt.NBTTagList inventory = new net.minecraft.nbt.NBTTagList();
        final NBTTagCompound missing = new NBTTagCompound();
        missing.setString("id", "missing:fluid_card");
        missing.setByte("Count", (byte) 1);
        missing.setInteger("IntSlot", 0);
        inventory.appendTag(missing);
        tag.setTag("Inventory", inventory);
        hatch.loadNBTData(tag);
        assertTrue(
            hatch.state()
                .locked());
        assertEquals(
            100,
            hatch.state()
                .store()
                .quantity(id(0)));
        final NBTTagCompound saved = new NBTTagCompound();
        hatch.saveNBTData(saved);
        assertEquals(inventory, saved.getTag("distributedCardEvidence"));
        assertEquals(node, saved.getTag("distributedAE"));
        final MTEDistributedMEOutputHatch invalidNode = hatch();
        tag.setString("distributedAE", "not a node");
        invalidNode.loadNBTData(tag);
        final NBTTagCompound evidence = new NBTTagCompound();
        invalidNode.saveNBTData(evidence);
        assertTrue(
            invalidNode.state()
                .locked());
        assertEquals(tag.getTag("distributedAE"), evidence.getTag("distributedNodeEvidence"));
    }

    public void realMuiPanelIsReadOnlyAndServerValidated() throws Exception {
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync = new com.cleanroommc.modularui.value.sync.PanelSyncManager(
            new com.cleanroommc.modularui.value.sync.ModularSyncManager(false),
            true);
        final MTEDistributedMEOutputHatch hatch = hatch();
        final com.cleanroommc.modularui.screen.ModularPanel panel = hatch
            .buildUI(null, sync, new com.cleanroommc.modularui.screen.UISettings());
        final java.util.ArrayDeque<com.cleanroommc.modularui.api.widget.IWidget> pending = new java.util.ArrayDeque<>();
        pending.add(panel);
        int cardSlots = 0, readOnly = 0, tanks = 0;
        while (!pending.isEmpty()) {
            final com.cleanroommc.modularui.api.widget.IWidget widget = pending.removeFirst();
            if (widget instanceof com.cleanroommc.modularui.widgets.slot.ItemSlot) cardSlots++;
            if (widget.getClass() == com.cleanroommc.modularui.widget.Widget.class) readOnly++;
            if (widget.getClass()
                .getName()
                .contains("FluidSlot")) tanks++;
            pending.addAll(widget.getChildren());
        }
        assertEquals(37, cardSlots);
        assertEquals(32, readOnly);
        assertEquals(0, tanks);
        assertTrue(hatch.forceUseMui2());
        assertFalse(hatch.canConfigure(null));
        final com.cleanroommc.modularui.value.sync.SyncHandler<?> view = sync.findSyncHandlerNullable("busView", 0);
        assertNotNull(view);
        assertFalse(view.isAllowC2S());
        assertThrows(
            java.io.IOException.class,
            () -> view.readOnServer(0, new net.minecraft.network.PacketBuffer(io.netty.buffer.Unpooled.buffer())));
    }

    public void capacityCardSlotUsesFluidPolicyAndLockedState() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync = new com.cleanroommc.modularui.value.sync.PanelSyncManager(
            new com.cleanroommc.modularui.value.sync.ModularSyncManager(true),
            true);
        final ItemStack itemCard = new ItemStack(
            new appeng.items.storage.ItemBasicStorageCell(appeng.items.materials.MaterialType.Cell1kPart, 1));
        DistributedBusFixture.assertCapacityCardSlot(
            hatch,
            hatch.buildUI(null, sync, new com.cleanroommc.modularui.screen.UISettings()),
            card(),
            itemCard,
            new ItemStack(RECIPE_CARD));
    }

    public void guiReusesIdentityIconsAndClearsEmptyPositions() throws Exception {
        final MTEDistributedMEOutputHatch hatch = hatch();
        hatch.configurePartitions(1);
        receive(hatch.state(), 0, 100);
        receive(hatch.state(), 1, 200);
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync = new com.cleanroommc.modularui.value.sync.PanelSyncManager(
            new com.cleanroommc.modularui.value.sync.ModularSyncManager(false),
            true);
        final AtomicInteger copies = new AtomicInteger();
        DistributedOutputGui
            .build(hatch, null, sync, new com.cleanroommc.modularui.screen.UISettings(), true, identity -> {
                copies.incrementAndGet();
                return DistributedOutputGui.DisplayIcon.fluid(identity.getFluidStack());
            });
        final Object view = sync.findSyncHandlerNullable("busView", 0);
        final java.lang.reflect.Method refresh = view.getClass()
            .getDeclaredMethod("refreshIcons", PartitionStore.class);
        refresh.setAccessible(true);
        final java.lang.reflect.Field iconsField = view.getClass()
            .getDeclaredField("icons");
        final java.lang.reflect.Field identitiesField = view.getClass()
            .getDeclaredField("iconIdentities");
        final java.lang.reflect.Field quantitiesField = view.getClass()
            .getDeclaredField("quantities");
        iconsField.setAccessible(true);
        identitiesField.setAccessible(true);
        quantitiesField.setAccessible(true);
        final Object[] icons = (Object[]) iconsField.get(view);
        final Object[] identities = (Object[]) identitiesField.get(view);
        final long[] quantities = (long[]) quantitiesField.get(view);
        final PartitionStore<FluidId> store = hatch.state()
            .store();
        refresh.invoke(view, store);
        assertEquals(2, copies.get());
        final Object normalIcon = icons[0], emergencyIcon = icons[16];
        for (int update = 0; update < 10; update++) {
            receive(hatch.state(), 0, 1);
            refresh.invoke(view, store);
            assertSame(normalIcon, icons[0]);
            assertSame(emergencyIcon, icons[16]);
            assertEquals(101 + update, quantities[0]);
        }
        assertEquals(2, copies.get());
        store.remove(id(0), 110);
        refresh.invoke(view, store);
        assertEquals(3, copies.get());
        assertEquals(200, quantities[0]);
        assertNull(icons[16]);
        assertNull(identities[16]);
        store.remove(id(1), 200);
        refresh.invoke(view, store);
        for (int position = 0; position < 32; position++) {
            assertNull(icons[position]);
            assertNull(identities[position]);
            assertEquals(0, quantities[position]);
        }
        receive(hatch.state(), 1, 1);
        refresh.invoke(view, store);
        assertEquals(4, copies.get());
        assertEquals(1, quantities[0]);
    }

    public void safeHatchRecipeRejectsPayloadsAndCreatesFreshOutput() {
        final net.minecraft.inventory.InventoryCrafting grid = new net.minecraft.inventory.InventoryCrafting(
            new net.minecraft.inventory.Container() {

                @Override
                public boolean canInteractWith(final net.minecraft.entity.player.EntityPlayer player) {
                    return true;
                }
            },
            2,
            2);
        final SafeDistributedBusRecipe recipe = new SafeDistributedBusRecipe(
            new ItemStack(RECIPE_HATCH, 1, 1),
            new ItemStack(RECIPE_HATCH),
            new ItemStack(RECIPE_CARD));
        grid.setInventorySlotContents(0, new ItemStack(RECIPE_HATCH));
        grid.setInventorySlotContents(1, new ItemStack(RECIPE_CARD));
        assertTrue(recipe.matches(grid, null));
        assertFalse(
            recipe.getCraftingResult(grid)
                .hasTagCompound());
        for (final String key : new String[] { "mFluid", "cachedStack", "Inventory", "capacityCell", "unknown" }) {
            final ItemStack occupied = new ItemStack(RECIPE_HATCH);
            occupied.setTagCompound(new NBTTagCompound());
            occupied.getTagCompound()
                .setTag(key, fluid(0, 1).writeToNBT(new NBTTagCompound()));
            grid.setInventorySlotContents(0, occupied);
            assertFalse(recipe.matches(grid, null));
        }
        grid.setInventorySlotContents(0, new ItemStack(RECIPE_HATCH));
        final ItemStack card = new ItemStack(RECIPE_CARD);
        card.setTagCompound(new NBTTagCompound());
        card.getTagCompound()
            .setString("unknown", "payload");
        grid.setInventorySlotContents(1, card);
        assertFalse(recipe.matches(grid, null));
    }

    public void fixedFluidIdConflictNeverOverwrites() {
        for (final gregtech.api.enums.MetaTileEntityIDs id : gregtech.api.enums.MetaTileEntityIDs.values())
            assertNotEquals(DistributedBusRegistration.FLUID_HATCH_ID, id.ID);
        final gregtech.api.interfaces.metatileentity.IMetaTileEntity previous = gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.FLUID_HATCH_ID];
        final MTEDistributedMEOutputHatch occupied = hatch();
        final Object busBefore = gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID];
        try {
            gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.FLUID_HATCH_ID] = occupied;
            assertThrows(IllegalStateException.class, DistributedBusRegistration::register);
            assertSame(occupied, gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.FLUID_HATCH_ID]);
            assertSame(busBefore, gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.ITEM_BUS_ID]);
        } finally {
            gregtech.api.GregTechAPI.METATILEENTITIES[DistributedBusRegistration.FLUID_HATCH_ID] = previous;
        }
    }

    public void realOrdinaryHatchAndDistributedHatchKeepSeparateTransactions() {
        final MTEHatchOutput ordinary = new MTEHatchOutput("test.ordinary", 4, new String[0], new ITexture[0][][]);
        final MTEDistributedMEOutputHatch distributed = hatch();
        final FluidEjectionHelper helper = new FluidEjectionHelper(Arrays.asList(ordinary, distributed), true);
        assertEquals(1, helper.ejectFluids(Collections.singletonList(fluid(0, 200000)), 1));
        helper.commit();
        assertEquals(128000, ordinary.getFluidAmount());
        assertEquals(
            72000,
            distributed.state()
                .store()
                .quantity(id(0)));
        assertNull(distributed.mFluid);
    }

    public void unrepresentableNbtNeverDispatchesAndDoesNotStarveOtherFluids() {
        final DistributedFluidHatchState state = new DistributedFluidHatchState();
        receive(state, 0, 100);
        final FluidId plain = FluidId.create(new FluidStack(FLUID, 1));
        final PartitionStore<FluidId>.Transaction input = state.store()
            .transaction(true);
        assertTrue(input.offer(plain, 200));
        input.commit();
        final AtomicInteger dispatched = new AtomicInteger();
        assertTrue(state.flush((identity, amount) -> {
            final IAEFluidStack offered = DistributedFluidBridge.offer(identity, amount);
            if (offered == null) return new DistributedOutputState.Remainder<>(identity, amount);
            dispatched.incrementAndGet();
            assertEquals(plain, identity);
            assertEquals(200, offered.getStackSize());
            return null;
        }, () -> {}));
        assertEquals(1, dispatched.get());
        assertEquals(
            100,
            state.store()
                .quantity(id(0)));
        assertEquals(
            0,
            state.store()
                .quantity(plain));
        assertFalse(state.locked());
        final DistributedFluidHatchState saved = DistributedFluidHatchState.read(state.write(), null);
        assertEquals(
            id(0),
            saved.store()
                .entries()
                .get(0).identity);
        assertEquals(
            100,
            saved.store()
                .quantity(id(0)));
    }

    public void readonlyFluidViewPreservesLongCountsAndDisplayNbt() throws Exception {
        final com.cleanroommc.modularui.value.sync.PanelSyncManager sync = new com.cleanroommc.modularui.value.sync.PanelSyncManager(
            new com.cleanroommc.modularui.value.sync.ModularSyncManager(true),
            true);
        hatch().buildUI(null, sync, new com.cleanroommc.modularui.screen.UISettings());
        final com.cleanroommc.modularui.value.sync.SyncHandler<?> view = sync.findSyncHandlerNullable("busView", 0);
        final net.minecraft.network.PacketBuffer buffer = new net.minecraft.network.PacketBuffer(
            io.netty.buffer.Unpooled.buffer());
        buffer.writeInt(4);
        buffer.writeLong(128000);
        buffer.writeLong(32000);
        buffer.writeStringToBuffer(Long.toString(Long.MAX_VALUE));
        buffer.writeStringToBuffer("0");
        buffer.writeStringToBuffer("");
        buffer.writeInt(0);
        buffer.writeInt(0);
        buffer.writeInt(1);
        buffer.writeBoolean(true);
        buffer.writeBoolean(true);
        buffer.writeBoolean(false);
        buffer.writeBoolean(true);
        for (int slot = 0; slot < 32; slot++) {
            buffer.writeNBTTagCompoundToBuffer(slot == 0 ? fluid(7, 1).writeToNBT(new NBTTagCompound()) : null);
            buffer.writeLong(slot == 0 ? Long.MAX_VALUE : 0);
        }
        view.readOnClient(0, buffer);
        final java.lang.reflect.Field quantities = view.getClass()
            .getDeclaredField("quantities");
        quantities.setAccessible(true);
        assertEquals(Long.MAX_VALUE, ((long[]) quantities.get(view))[0]);
        final java.lang.reflect.Field icons = view.getClass()
            .getDeclaredField("icons");
        icons.setAccessible(true);
        final Object icon = ((Object[]) icons.get(view))[0];
        final java.lang.reflect.Field fluid = icon.getClass()
            .getDeclaredField("fluid");
        fluid.setAccessible(true);
        assertEquals(id(7), FluidId.create((FluidStack) fluid.get(icon)));
        assertEquals(0, buffer.readableBytes());
        assertFalse(view.isAllowC2S());
    }

    public void staleCommitRevalidatesNCapacityAndFilter() {
        final MTEDistributedMEOutputHatch hatch = hatch();
        final IOutputHatchTransaction stale = hatch.createTransaction();
        ((IOutputTransaction.IProtectOutputAware) stale).setProtectOutput(true);
        assertTrue(stale.storePartial(id(0), fluid(0, 1000), 0, 0));
        hatch.configurePartitions(1);
        hatch.state()
            .store()
            .configure(1, 0);
        hatch.state()
            .store()
            .setAdmissionFilter(identity -> false);
        actual(hatch, 1, 100);
        stale.commit();
        assertEquals(
            1000,
            hatch.state()
                .store()
                .quantity(id(0)));
        assertEquals(
            100,
            hatch.state()
                .store()
                .quantity(id(1)));
        assertThrows(IllegalStateException.class, stale::commit);
        assertEquals(0, check(hatch, fluid(0, 1)));
    }
}
