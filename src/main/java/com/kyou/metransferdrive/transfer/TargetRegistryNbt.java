package com.kyou.metransferdrive.transfer;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.kyou.metransferdrive.tile.DriveSlotLayout;

/** Versioned configuration only; runtime topology and routing progress must never be replayed from NBT. */
final class TargetRegistryNbt {

    private TargetRegistryNbt() {}

    static NBTTagCompound write(final TargetRegistry registry) {
        final NBTTagCompound data = new NBTTagCompound();
        data.setInteger("version", 1);
        final NBTTagList targets = new NBTTagList();
        for (final TransferTarget target : registry.getTargets()) {
            final NBTTagCompound entry = new NBTTagCompound();
            entry.setString(
                "id",
                target.getId()
                    .toString());
            entry.setInteger("dimension", target.getDimension());
            entry.setInteger("x", target.getX());
            entry.setInteger("y", target.getY());
            entry.setInteger("z", target.getZ());
            entry.setString("name", target.getDisplayName());
            entry.setInteger("priority", target.getPriority());
            targets.appendTag(entry);
        }
        data.setTag("targets", targets);
        final NBTTagList routes = new NBTTagList();
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            final TargetRegistry.Route route = registry.getRoute(group);
            final NBTTagCompound entry = new NBTTagCompound();
            entry.setInteger("group", group);
            entry.setString(
                "mode",
                route.getMode()
                    .name());
            if (route.getTargetId() != null) {
                entry.setString(
                    "targetId",
                    route.getTargetId()
                        .toString());
            }
            routes.appendTag(entry);
        }
        data.setTag("routes", routes);
        return data;
    }

    static TargetRegistry read(final NBTTagCompound data) {
        require(data.hasKey("version", 3) && data.getInteger("version") == 1);
        require(data.hasKey("targets", 9) && data.hasKey("routes", 9));
        final TargetRegistry registry = new TargetRegistry();
        final NBTTagList targets = data.getTagList("targets", 10);
        final NBTTagList routes = data.getTagList("routes", 10);
        // A wrong list element type must not silently become an empty target list.
        require(
            data.getTag("targets")
                .equals(targets)
                && data.getTag("routes")
                    .equals(routes));
        require(targets.tagCount() <= TargetRegistry.MAX_TARGETS && routes.tagCount() == DriveSlotLayout.GROUP_COUNT);
        final Set<UUID> ids = new HashSet<>();
        for (int index = 0; index < targets.tagCount(); index++) {
            final NBTTagCompound entry = targets.getCompoundTagAt(index);
            require(entry.hasKey("name", 8) && entry.hasKey("priority", 3));
            final TransferTarget target = locator(entry)
                .withMetadata(entry.getString("name"), entry.getInteger("priority"));
            require(ids.add(target.getId()));
            require(
                registry.register(target)
                    .equals(target.getId()));
        }
        final Set<Integer> groups = new HashSet<>();
        for (int index = 0; index < routes.tagCount(); index++) {
            final NBTTagCompound entry = routes.getCompoundTagAt(index);
            require(entry.hasKey("group", 3) && entry.hasKey("mode", 8));
            final int group = entry.getInteger("group");
            require(group >= 0 && group < DriveSlotLayout.GROUP_COUNT && groups.add(group));
            final TargetRegistry.Mode mode = TargetRegistry.Mode.valueOf(entry.getString("mode"));
            final TargetRegistry.Route route;
            switch (mode) {
                case AUTOMATIC:
                    route = TargetRegistry.Route.automatic();
                    break;
                case SPECIFIC:
                    require(entry.hasKey("targetId", 8));
                    route = TargetRegistry.Route.specific(UUID.fromString(entry.getString("targetId")));
                    break;
                default:
                    route = TargetRegistry.Route.unassigned();
                    break;
            }
            require(mode == TargetRegistry.Mode.SPECIFIC || !entry.hasKey("targetId"));
            registry.setRoute(group, route);
        }
        return registry;
    }

    static TargetRegistry legacy(final NBTTagCompound entry) {
        final TargetRegistry registry = new TargetRegistry();
        final UUID id = registry.register(locator(entry));
        for (int group = 0; group < DriveSlotLayout.GROUP_COUNT; group++) {
            registry.setRoute(group, TargetRegistry.Route.specific(id));
        }
        return registry;
    }

    private static TransferTarget locator(final NBTTagCompound entry) {
        require(
            entry.hasKey("id", 8) && entry.hasKey("dimension", 3)
                && entry.hasKey("x", 3)
                && entry.hasKey("y", 3)
                && entry.hasKey("z", 3));
        return new TransferTarget(
            UUID.fromString(entry.getString("id")),
            entry.getInteger("dimension"),
            entry.getInteger("x"),
            entry.getInteger("y"),
            entry.getInteger("z"));
    }

    private static void require(final boolean valid) {
        if (!valid) {
            throw new IllegalArgumentException("Invalid transfer routing configuration");
        }
    }
}
