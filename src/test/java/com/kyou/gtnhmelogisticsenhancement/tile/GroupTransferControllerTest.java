package com.kyou.gtnhmelogisticsenhancement.tile;

import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.TYPES;
import static com.kyou.gtnhmelogisticsenhancement.tile.CellHandlerCacheTest.stack;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Iterator;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.junit.After;
import org.junit.Test;

import com.kyou.gtnhmelogisticsenhancement.transfer.AETransferController;
import com.kyou.gtnhmelogisticsenhancement.transfer.RouteGraph;
import com.kyou.gtnhmelogisticsenhancement.transfer.RoutedTransfer;
import com.kyou.gtnhmelogisticsenhancement.transfer.RoutingCycle;
import com.kyou.gtnhmelogisticsenhancement.transfer.TargetRegistry;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferScheduler;
import com.kyou.gtnhmelogisticsenhancement.transfer.TransferTransaction;

import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEStack;

/** Uses the production tile/controller APIs; grid publication and native cell identities use the existing doubles. */
public class GroupTransferControllerTest {

    @After
    public void clearScheduler() {
        TransferScheduler.clear();
    }

    @Test
    public void groupPeriodsRoundTripThroughTheControllerAndLegacyAccessOnlyChangesGroupZero() throws Exception {
        final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
        final AETransferController controller = controller(drive);
        final long revision = controller.getConfigurationRevision();
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            controller.setPeriodSeconds(group, group + 2);
        }
        assertEquals(revision + DriveSlotLayout.GROUP_COUNT, controller.getConfigurationRevision());
        controller.setPeriodSeconds(1);
        assertEquals(1, controller.getPeriodSeconds());
        for (int group = 1; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(group + 2, controller.getPeriodSeconds(group));
        }
        final NBTTagCompound data = new NBTTagCompound();
        controller.writeToNBT(data);
        final AETransferController restored = controller(new CellHandlerCacheTest.TestDrive());
        restored.readFromNBT(data);
        assertEquals(1, restored.getPeriodSeconds(0));
        for (int group = 1; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(group + 2, restored.getPeriodSeconds(group));
        }
        final NBTTagCompound legacy = new NBTTagCompound();
        legacy.setInteger("transferPeriodSeconds", 17);
        restored.readFromNBT(legacy);
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            assertEquals(17, restored.getPeriodSeconds(group));
        }
    }

    @Test
    public void changingAnotherGroupThroughTheControllerPreservesTheExistingCountdown() throws Exception {
        final AETransferController controller = controller(new CellHandlerCacheTest.TestDrive());
        controller.setPeriodSeconds(0, 1);
        for (int tick = 0; tick < 19; tick++) {
            controller.tick();
        }
        controller.setPeriodSeconds(1, 2);
        controller.tick();
        final Object schedule = field("schedule").get(controller);
        final Method next = schedule.getClass()
            .getDeclaredMethod("nextReadyGroup");
        next.setAccessible(true);
        assertEquals(0, next.invoke(schedule));
        controller.setPeriodSeconds(1, 3);
        assertFalse(field("active").getBoolean(controller));
    }

    @Test
    public void allGroupCursorsRetainTheirOwnSourceTypeAndProofWhileProofAgeKeepsAdvancing() throws Exception {
        final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
        final AETransferController controller = controller(drive);
        final Object[] candidates = new Object[DriveSlotLayout.GROUP_COUNT];
        final Object[] types = new Object[DriveSlotLayout.GROUP_COUNT];
        final Object[] routed = new Object[DriveSlotLayout.GROUP_COUNT];
        final Object[] graphs = new Object[DriveSlotLayout.GROUP_COUNT];
        final RoutingCycle<Object> cycle = new RoutingCycle<>(new TargetRegistry(), Object::new, target -> null);
        cycle.prepare(1);
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            controller.setPeriodSeconds(group, 1);
            drive.install(DriveSlotLayout.slot(group, 0), TYPES[group % TYPES.length], group + 100);
        }
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            final int slot = DriveSlotLayout.slot(group, 0);
            invoke(controller, "selectGroup", new Class<?>[] { int.class }, group);
            final IAEStack<?> candidate = stack(TYPES[group % TYPES.length], group + 100);
            candidates[group] = candidate;
            types[group] = Collections.singletonList(candidate)
                .iterator();
            routed[group] = new RoutedTransfer<>(candidate, group + 100, cycle, group);
            final RouteGraph<Object> graph = new RouteGraph<>(
                new Object(),
                Collections.emptyList(),
                new RouteGraph.Topology<Object>() {

                    @Override
                    public long generation() {
                        return 0;
                    }

                    @Override
                    public Iterator<RouteGraph.Device<Object>> devices(final Object grid) {
                        return Collections.emptyIterator();
                    }
                });
            graph.step(1);
            assertTrue(graph.isComplete());
            graphs[group] = graph;
            field("nextSlot").setInt(controller, slot + 1);
            field("sourceSlot").setInt(controller, slot);
            field("sourceCell").set(controller, drive.getStackInSlot(slot));
            field("sourceInventory").set(controller, drive.getNativeCellHandler(slot));
            field("candidate").set(controller, candidates[group]);
            field("types").set(controller, types[group]);
            field("routed").set(controller, routed[group]);
            field("graph").set(controller, graph);
            field("graphType").set(controller, TYPES[group % TYPES.length]);
            field("graphAge").setInt(controller, 0);
            invoke(controller, "saveGroupCursor", new Class<?>[0]);
        }
        field("currentGroup").setInt(controller, -1);
        for (int tick = 0; tick < 20; tick++) {
            controller.tick();
        }
        for (int round = 0; round < 2; round++) {
            for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
                invoke(controller, "selectGroup", new Class<?>[] { int.class }, group);
                final int slot = DriveSlotLayout.slot(group, 0);
                assertEquals(slot + 1, field("nextSlot").getInt(controller));
                assertEquals(slot, field("sourceSlot").getInt(controller));
                assertSame(drive.getStackInSlot(slot), field("sourceCell").get(controller));
                assertSame(drive.getNativeCellHandler(slot), field("sourceInventory").get(controller));
                assertSame(candidates[group], field("candidate").get(controller));
                assertSame(types[group], field("types").get(controller));
                assertSame(routed[group], field("routed").get(controller));
                assertSame(graphs[group], field("graph").get(controller));
                assertSame(TYPES[group % TYPES.length], field("graphType").get(controller));
                assertEquals(20, field("graphAge").getInt(controller));
            }
        }
        field("currentGroup").setInt(controller, -1);
        controller.setPeriodSeconds(0, 5);
        final Object[] cursors = (Object[]) field("groupCursors").get(controller);
        assertNull(cursors[0]);
        for (int group = 1; group < cursors.length; group++) {
            assertTrue(cursors[group] != null);
        }
        final Object schedule = field("schedule").get(controller);
        final Method next = schedule.getClass()
            .getDeclaredMethod("nextReadyGroup");
        next.setAccessible(true);
        assertEquals(1, next.invoke(schedule));
        invoke(controller, "selectGroup", new Class<?>[] { int.class }, 1);
        assertSame(graphs[1], field("graph").get(controller));
        assertEquals(20, field("graphAge").getInt(controller));
    }

    @Test
    public void pendingRecoveryRetainsTheOriginalSourceAndOnlyItsGroupCanTriggerARetry() throws Exception {
        final CellHandlerCacheTest.TestDrive drive = new CellHandlerCacheTest.TestDrive();
        final AETransferController controller = controller(drive);
        final int sourceGroup = DriveSlotLayout.GROUP_COUNT - 1;
        final int slot = DriveSlotLayout.slot(sourceGroup, 0);
        controller.setPeriodSeconds(0, 1);
        controller.setPeriodSeconds(sourceGroup, 3);
        drive.install(slot, TYPES[0], 100);
        final ItemStack originalCell = drive.getStackInSlot(slot);
        final IMEInventory<?> originalInventory = drive.getNativeCellHandler(slot);
        field("sourceSlot").setInt(controller, slot);
        field("sourceCell").set(controller, originalCell);
        field("sourceInventory").set(controller, originalInventory);
        @SuppressWarnings("unchecked")
        final TransferTransaction<IAEStack<?>> transaction = (TransferTransaction<IAEStack<?>>) field("transaction")
            .get(controller);
        transaction.move(stack(TYPES[0], 1), 1, new TransferTransaction.Source() {

            @Override
            public long extract(final long amount) {
                return amount;
            }

            @Override
            public long restore(final long amount) {
                return 0;
            }
        }, new TransferTransaction.Destination() {

            @Override
            public long simulate(final long amount) {
                return amount;
            }

            @Override
            public long insert(final long amount) {
                return 0;
            }
        }, amount -> amount);
        assertEquals(TransferTransaction.Phase.RECOVERABLE, transaction.getPhase());
        controller.suspend();
        for (int tick = 0; tick < 59; tick++) {
            controller.tick();
            assertFalse(field("recoveryDue").getBoolean(controller));
        }
        controller.tick();
        assertTrue(field("recoveryDue").getBoolean(controller));
        assertEquals(slot, field("sourceSlot").getInt(controller));
        assertSame(originalCell, field("sourceCell").get(controller));
        assertSame(originalInventory, field("sourceInventory").get(controller));
        assertEquals(TransferTransaction.Phase.RECOVERABLE, transaction.getPhase());
    }

    private static AETransferController controller(final TileMETransferDrive drive) throws Exception {
        final Field transfer = TileMETransferDrive.class.getDeclaredField("transfer");
        transfer.setAccessible(true);
        return (AETransferController) transfer.get(drive);
    }

    private static Field field(final String name) throws Exception {
        final Field field = AETransferController.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Object invoke(final AETransferController controller, final String name, final Class<?>[] types,
        final Object... arguments) throws Exception {
        final Method method = AETransferController.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(controller, arguments);
    }
}
