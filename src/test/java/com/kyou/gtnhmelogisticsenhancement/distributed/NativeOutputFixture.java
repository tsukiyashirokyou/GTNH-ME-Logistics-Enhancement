package com.kyou.gtnhmelogisticsenhancement.distributed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import net.minecraft.init.Bootstrap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.FluidStack;

import cpw.mods.fml.common.Loader;
import gregtech.api.interfaces.IOutputBus;
import gregtech.api.interfaces.IOutputHatch;
import gregtech.api.util.FluidEjectionHelper;
import gregtech.api.util.GTUtility.FluidId;
import gregtech.api.util.GTUtility.ItemId;
import gregtech.api.util.ItemEjectionHelper;

/** Real pinned GT helpers/interfaces/identities, with a minimal registry fixture, not a running GT controller. */
public class NativeOutputFixture {

    private static final Item ITEM = new Item();
    private static final Fluid FLUID = new Fluid("distributed_test_fluid");

    public static void registries() throws Exception {
        Loader.injectData("7", "10", "99", "99", "1.7.10", "9.05", new File("."), Collections.emptyList());
        Bootstrap.func_151354_b();
        Item.itemRegistry.addObject(31000, "gtnhmelogisticsenhancement:distributed_test", ITEM);
        FluidRegistry.registerFluid(FLUID);
    }

    public void nativeItemRecipeCheckAcceptsWholeOverageWithoutChangingStock() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 350);
        final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(8, check.ejectItems(Collections.singletonList(item(0, 1000)), 8));
        assertEquals(
            350,
            bus.store()
                .quantity(itemId(0)));
    }

    public void nativeItemActualParallelAndDuplicateIdentityCommitWholeBatch() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 350);
        final ItemEjectionHelper output = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(8, output.ejectItems(Arrays.asList(item(0, 400), item(0, 600)), 8));
        output.commit();
        assertEquals(
            8350,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            1,
            bus.store()
                .entries()
                .size());
        final ItemEjectionHelper next = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
        assertEquals(0, next.ejectItems(Collections.singletonList(item(0, 1)), 1));
    }

    public void nativeFluidRecipeCheckAndActualLargeChunksAgree() {
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        final FluidEjectionHelper check = new FluidEjectionHelper(Collections.singletonList(hatch), true, true);
        final FluidStack fluid = fluid(0, Integer.MAX_VALUE);
        assertEquals(3, check.ejectFluids(Arrays.asList(fluid, fluid.copy()), 3));
        assertTrue(
            hatch.store()
                .entries()
                .isEmpty());
        final FluidEjectionHelper output = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(3, output.ejectFluids(Arrays.asList(fluid, fluid.copy()), 3));
        output.commit();
        assertEquals(
            Integer.MAX_VALUE * 6L,
            hatch.store()
                .quantity(FluidId.create(fluid)));
    }

    public void allFourVoidModeFlagPairsUseNativeHelperProtectionAndKeepOtherProducts() {
        // Exact protectItem/protectFluid pairs from the pinned VoidingMode; no client texture enum bootstrap here.
        for (final boolean[] mode : new boolean[][] { { true, true }, { false, true }, { true, false },
            { false, false } }) {
            final PartitionItemOutput bus = new PartitionItemOutput();
            this.items(bus, 0, 1350);
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), mode[0], true);
            assertEquals(
                mode[0] ? 0 : 1,
                check.ejectItems(Arrays.asList(item(0, 1000), item(1, 200), item(2, 300)), 1));
            assertEquals(
                0,
                bus.store()
                    .quantity(itemId(1)));
            // Intentionally force completion even after a zero precheck to expose partial GT commits.
            // A protected controller must not start that rejected recipe; this is not an atomic-delivery claim.
            final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), mode[0]);
            assertEquals(
                mode[0] ? 0 : 1,
                actual.ejectItems(Arrays.asList(item(0, 1000), item(1, 200), item(2, 300)), 1));
            actual.commit();
            assertEquals(
                1350,
                bus.store()
                    .quantity(itemId(0)));
            assertEquals(
                200,
                bus.store()
                    .quantity(itemId(1)));
            assertEquals(
                300,
                bus.store()
                    .quantity(itemId(2)));

            final PartitionFluidOutput hatch = new PartitionFluidOutput();
            this.fluids(hatch, 0, 128000);
            final FluidEjectionHelper fluidCheck = new FluidEjectionHelper(
                Collections.singletonList(hatch),
                mode[1],
                true);
            assertEquals(
                mode[1] ? 0 : 1,
                fluidCheck.ejectFluids(Arrays.asList(fluid(0, 1000), fluid(1, 200), fluid(2, 300)), 1));
            assertEquals(
                0,
                hatch.store()
                    .quantity(FluidId.create(fluid(1, 1))));
            final FluidEjectionHelper fluidActual = new FluidEjectionHelper(Collections.singletonList(hatch), mode[1]);
            assertEquals(
                mode[1] ? 0 : 1,
                fluidActual.ejectFluids(Arrays.asList(fluid(0, 1000), fluid(1, 200), fluid(2, 300)), 1));
            fluidActual.commit();
            assertEquals(
                200,
                hatch.store()
                    .quantity(FluidId.create(fluid(1, 1))));
            assertEquals(
                300,
                hatch.store()
                    .quantity(FluidId.create(fluid(2, 1))));
        }
    }

    public void helpersTryOtherOutputsAfterOnePartitionRejects() {
        final PartitionItemOutput first = new PartitionItemOutput(new PartitionStore<>(1, 400));
        final PartitionItemOutput second = new PartitionItemOutput();
        this.items(first, 0, 1350);
        final ItemEjectionHelper helper = new ItemEjectionHelper(Arrays.<IOutputBus>asList(first, second), true);
        assertEquals(1, helper.ejectItems(Arrays.asList(item(0, 1000), item(1, 200)), 1));
        helper.commit();
        assertEquals(
            1350,
            first.store()
                .quantity(itemId(0)));
        assertEquals(
            1000,
            second.store()
                .quantity(itemId(0)));
        assertEquals(
            200,
            second.store()
                .quantity(itemId(1)));
    }

    public void actualInterfacesPassFlagsAndPreventSimulationCommitAndRepeatedCommit() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final PartitionItemOutput.Transaction simulation = bus.createTransaction();
        simulation.setRecipeCheck(true);
        simulation.setProtectOutput(true);
        assertTrue(simulation.isRecipeCheck());
        assertTrue(simulation.isProtectOutput());
        assertTrue(simulation.storePartial(itemId(0), item(0, 1000), 0, 0));
        simulation.complete(itemId(0));
        assertThrows(IllegalStateException.class, simulation::commit);
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(0)));
        final PartitionItemOutput.Transaction actual = bus.createTransaction();
        actual.storePartial(itemId(0), item(0, 1000), 0, 0);
        actual.complete(itemId(0));
        actual.commit();
        assertThrows(IllegalStateException.class, actual::commit);
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
    }

    public void nativeIdentitiesAndNbtKeepItemMetaAndBothKindsOfTagsSeparate() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final ItemStack first = item(0, 100);
        first.setTagCompound(tag(1));
        final ItemStack second = item(0, 200);
        second.setTagCompound(tag(2));
        bus.storePartial(first);
        bus.storePartial(second);
        this.items(bus, 1, 300);
        assertEquals(
            3,
            bus.store()
                .entries()
                .size());
        final NBTTagCompound saved = PartitionNbt.write(bus.store(), DistributedIdentities.ITEMS);
        assertEquals(
            saved,
            PartitionNbt.write(PartitionNbt.read(saved, DistributedIdentities.ITEMS), DistributedIdentities.ITEMS));

        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        this.fluids(hatch, 0, 100);
        this.fluids(hatch, 1, 200);
        assertEquals(
            2,
            hatch.store()
                .entries()
                .size());
        final NBTTagCompound fluidSaved = PartitionNbt.write(hatch.store(), DistributedIdentities.FLUIDS);
        assertEquals(
            fluidSaved,
            PartitionNbt
                .write(PartitionNbt.read(fluidSaved, DistributedIdentities.FLUIDS), DistributedIdentities.FLUIDS));
    }

    public void callerNbtMutationDoesNotChangeStoredIdentityOrMergePartitions() {
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        final FluidStack stack = fluid(1, 100);
        final FluidId original = FluidId.create(stack);
        assertTrue(hatch.storePartial(stack));
        stack.tag.setInteger("identity", 2);
        assertEquals(
            100,
            hatch.store()
                .quantity(original));
        assertEquals(
            0,
            hatch.store()
                .quantity(FluidId.create(stack)));
    }

    public void rejectedAndSimulatedDirectCallsKeepRealStockAndPreserveRemainders() {
        final IOutputBus bus = new PartitionItemOutput();
        final ItemStack simulated = item(0, 1000);
        assertTrue(bus.storePartial(simulated, true));
        assertEquals(0, simulated.stackSize);
        assertTrue(
            ((PartitionItemOutput) bus).store()
                .entries()
                .isEmpty());
        assertTrue(bus.storePartial(item(0, 1000)));
        final ItemStack denied = item(0, 30);
        assertFalse(bus.storePartial(denied));
        assertEquals(30, denied.stackSize);
        final IOutputHatch hatch = new PartitionFluidOutput();
        final FluidStack simulatedFluid = fluid(0, 128000);
        assertTrue(hatch.storePartial(simulatedFluid, true));
        assertEquals(0, simulatedFluid.amount);
        assertTrue(
            ((PartitionFluidOutput) hatch).store()
                .entries()
                .isEmpty());
    }

    public void twoRealPrechecksCanPassButNativeCompletionCannotGuaranteeSharedDelivery() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        for (int meta = 0; meta < 2; meta++) {
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(1, check.ejectItems(Collections.singletonList(item(meta, 1000)), 1));
        }
        final ItemEjectionHelper first = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, first.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        first.commit();
        final ItemEjectionHelper second = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, second.ejectItems(Collections.singletonList(item(1, 1000)), 1));
        second.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(0)));
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        // Real helpers above demonstrate changed admission; runMachine's array clearing is source-audited,
        // not invoked by this fixture. Future completion integration must retain exactly the second 1000.
    }

    public void nativeHelpersRespectOneAndSixteenPartitionsAndNeverCreateTheNextIdentity() {
        for (final int n : new int[] { 1, 4, 16 }) {
            final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(n, 1600));
            final java.util.List<ItemStack> outputs = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) outputs.add(item(i, 1000));
            final ItemEjectionHelper actual = new ItemEjectionHelper(Collections.singletonList(bus), true);
            assertEquals(1, actual.ejectItems(outputs, 1));
            actual.commit();
            final ItemEjectionHelper extra = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(0, extra.ejectItems(Collections.singletonList(item(n, 1000)), 1));
            assertEquals(
                n,
                bus.store()
                    .entries()
                    .size());
        }
    }

    public void sharedSameIdentityAndFluidPrechecksCannotReserveFutureOutput() {
        final PartitionItemOutput bus = new PartitionItemOutput(new PartitionStore<>(1, 400));
        this.items(bus, 0, 350);
        for (int machine = 0; machine < 2; machine++) {
            final ItemEjectionHelper check = new ItemEjectionHelper(Collections.singletonList(bus), true, true);
            assertEquals(1, check.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        }
        final ItemEjectionHelper first = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(1, first.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        first.commit();
        final ItemEjectionHelper second = new ItemEjectionHelper(Collections.singletonList(bus), true);
        assertEquals(0, second.ejectItems(Collections.singletonList(item(0, 1000)), 1));
        second.commit();
        assertEquals(
            1350,
            bus.store()
                .quantity(itemId(0)));
        final PartitionFluidOutput hatch = new PartitionFluidOutput(new PartitionStore<>(1, 400));
        this.fluids(hatch, 0, 350);
        for (int machine = 0; machine < 2; machine++) {
            final FluidEjectionHelper check = new FluidEjectionHelper(Collections.singletonList(hatch), true, true);
            assertEquals(1, check.ejectFluids(Collections.singletonList(fluid(0, 1000)), 1));
        }
        final FluidEjectionHelper fluidFirst = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(1, fluidFirst.ejectFluids(Collections.singletonList(fluid(0, 1000)), 1));
        fluidFirst.commit();
        final FluidEjectionHelper fluidSecond = new FluidEjectionHelper(Collections.singletonList(hatch), true);
        assertEquals(0, fluidSecond.ejectFluids(Collections.singletonList(fluid(0, 1000)), 1));
        fluidSecond.commit();
        assertEquals(
            1350,
            hatch.store()
                .quantity(FluidId.create(fluid(0, 1))));
    }

    public void failedGtCommitKeepsEvidenceAndCompleteNormalizesQuantityBearingIds() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        final PartitionItemOutput.Transaction first = bus.createTransaction();
        final PartitionItemOutput.Transaction second = bus.createTransaction();
        first.storePartial(itemId(0), item(0, 1000), 0, 0);
        second.storePartial(itemId(1), item(1, 2000), 0, 0);
        first.commit();
        assertThrows(PartitionStore.CommitConflictException.class, second::commit);
        assertEquals(
            Long.valueOf(2000),
            second.pending()
                .get(itemId(1)));
        assertEquals(
            0,
            bus.store()
                .quantity(itemId(1)));
        final PartitionItemOutput.Transaction completed = bus.createTransaction();
        completed.storePartial(itemId(2), item(2, 1000), 0, 0);
        completed.complete(ItemId.createWithStackSize(item(2, 50)));
        assertThrows(IllegalStateException.class, () -> completed.storePartial(itemId(2), item(2, 10), 0, 0));
        completed.commit();
        assertEquals(
            1000,
            bus.store()
                .quantity(itemId(2)));
    }

    public void invalidNativeIdentityNbtCannotTurnIntoDefaultRegistryStock() {
        final PartitionItemOutput bus = new PartitionItemOutput();
        this.items(bus, 0, 1000);
        final NBTTagCompound itemData = PartitionNbt.write(bus.store(), DistributedIdentities.ITEMS);
        itemData.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("identity")
            .setString("item", "gtnhmelogisticsenhancement:missing_identity");
        final PartitionNbt.Loaded<ItemId> itemResult = PartitionNbt.read(itemData, DistributedIdentities.ITEMS);
        assertTrue(itemResult.isolated());
        assertEquals(itemData, itemResult.evidence());
        final PartitionFluidOutput hatch = new PartitionFluidOutput();
        this.fluids(hatch, 0, 1000);
        final NBTTagCompound fluidData = PartitionNbt.write(hatch.store(), DistributedIdentities.FLUIDS);
        fluidData.getTagList("entries", 10)
            .getCompoundTagAt(0)
            .getCompoundTag("identity")
            .setInteger("Amount", 1);
        assertTrue(
            PartitionNbt.read(fluidData, DistributedIdentities.FLUIDS)
                .isolated());
        final PartitionFluidOutput large = new PartitionFluidOutput();
        final FluidEjectionHelper output = new FluidEjectionHelper(Collections.singletonList(large), true);
        output.ejectFluids(Collections.singletonList(fluid(0, Integer.MAX_VALUE)), 3);
        output.commit();
        final NBTTagCompound saved = PartitionNbt.write(large.store(), DistributedIdentities.FLUIDS);
        assertEquals(
            Integer.MAX_VALUE * 3L,
            PartitionNbt.read(saved, DistributedIdentities.FLUIDS)
                .store()
                .quantity(FluidId.create(fluid(0, 1))));
    }

    private void items(final PartitionItemOutput bus, final int meta, final int amount) {
        assertTrue(bus.storePartial(item(meta, amount)));
    }

    private void fluids(final PartitionFluidOutput hatch, final int identity, final int amount) {
        assertTrue(hatch.storePartial(fluid(identity, amount)));
    }

    private static ItemStack item(final int meta, final int amount) {
        return new ItemStack(ITEM, amount, meta);
    }

    private static ItemId itemId(final int meta) {
        return ItemId.create(ITEM, meta, null);
    }

    private static NBTTagCompound tag(final int value) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setInteger("identity", value);
        return tag;
    }

    private static FluidStack fluid(final int identity, final int amount) {
        return new FluidStack(FLUID, amount, tag(identity));
    }
}
