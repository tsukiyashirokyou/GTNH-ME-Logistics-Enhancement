package com.kyou.metransferdrive.transfer;

import static org.junit.Assert.assertEquals;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class TargetRegistryNbtTest {

    @Test
    public void roundTripKeepsBindingsAliasesPrioritiesAndAllEightRoutes() {
        final TargetRegistry registry = new TargetRegistry();
        final TransferTarget first = target(0, 0);
        final TransferTarget second = target(1, 100);
        registry.register(first);
        registry.register(second);
        registry.setRoute(0, TargetRegistry.Route.automatic());
        registry.setRoute(7, TargetRegistry.Route.specific(first.getId()));
        final TargetRegistry loaded = TargetRegistryNbt.read(TargetRegistryNbt.write(registry));
        assertEquals(
            first.getId(),
            loaded.getTargets()
                .get(0)
                .getId());
        assertEquals(
            second.getId(),
            loaded.byPriority()
                .get(0)
                .getId());
        assertEquals(
            "Alias 1",
            loaded.getTargets()
                .get(1)
                .getDisplayName());
        assertEquals(
            100,
            loaded.getTargets()
                .get(1)
                .getPriority());
        assertEquals(
            TargetRegistry.Mode.AUTOMATIC,
            loaded.getRoute(0)
                .getMode());
        assertEquals(
            TargetRegistry.Mode.UNASSIGNED,
            loaded.getRoute(1)
                .getMode());
        assertEquals(
            first.getId(),
            loaded.getRoute(7)
                .getTargetId());
        assertEquals(TargetRegistryNbt.write(registry), TargetRegistryNbt.write(loaded));
    }

    @Test
    public void legacySingleTargetKeepsItsIdAndIsExplicitlyAssignedToAllGroups() {
        final TransferTarget previous = target(0, 0);
        final TargetRegistry registry = new TargetRegistry();
        registry.register(previous);
        final NBTTagCompound entry = TargetRegistryNbt.write(registry)
            .getTagList("targets", 10)
            .getCompoundTagAt(0);
        entry.removeTag("name");
        entry.removeTag("priority");
        final TargetRegistry migrated = TargetRegistryNbt.legacy(entry);
        assertEquals(
            previous.getId(),
            migrated.getTargets()
                .get(0)
                .getId());
        for (int group = 0; group < 8; group++) {
            assertEquals(
                TargetRegistry.Mode.SPECIFIC,
                migrated.getRoute(group)
                    .getMode());
            assertEquals(
                previous.getId(),
                migrated.getRoute(group)
                    .getTargetId());
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void unknownVersionCannotSilentlyResetRoutes() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        data.setInteger("version", 99);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void danglingSpecificReferenceIsRejectedRatherThanChangedToAutomatic() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        final NBTTagCompound route = data.getTagList("routes", 10)
            .getCompoundTagAt(0);
        route.setString("mode", "SPECIFIC");
        route.setString(
            "targetId",
            UUID.randomUUID()
                .toString());
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void repeatedGroupsAreRejected() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        data.getTagList("routes", 10)
            .getCompoundTagAt(1)
            .setInteger("group", 0);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedTargetListIsNotReadAsAnEmptyRegistry() {
        final NBTTagCompound data = TargetRegistryNbt.write(new TargetRegistry());
        final NBTTagList invalid = new NBTTagList();
        invalid.appendTag(new net.minecraft.nbt.NBTTagString("invalid entry"));
        data.setTag("targets", invalid);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void duplicateInterfaceCoordinatesCannotLoseOneBindingOnLoad() {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(target(0, 0));
        final NBTTagCompound data = TargetRegistryNbt.write(registry);
        final NBTTagList targets = data.getTagList("targets", 10);
        final NBTTagCompound duplicate = (NBTTagCompound) targets.getCompoundTagAt(0)
            .copy();
        duplicate.setString(
            "id",
            UUID.randomUUID()
                .toString());
        targets.appendTag(duplicate);
        TargetRegistryNbt.read(data);
    }

    @Test(expected = IllegalArgumentException.class)
    public void missingCoordinateCannotAccidentallyBindPositionZero() {
        final TargetRegistry registry = new TargetRegistry();
        registry.register(target(0, 0));
        final NBTTagCompound data = TargetRegistryNbt.write(registry);
        data.getTagList("targets", 10)
            .getCompoundTagAt(0)
            .removeTag("x");
        TargetRegistryNbt.read(data);
    }

    private static TransferTarget target(final int x, final int priority) {
        return new TransferTarget(UUID.randomUUID(), 0, x, 64, 0, "Alias " + x, priority);
    }
}
