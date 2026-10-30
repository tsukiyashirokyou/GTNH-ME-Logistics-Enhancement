package com.kyou.gtnhmelogisticsenhancement.binding;

import java.util.UUID;

import net.minecraft.nbt.NBTTagCompound;

/** Mod-owned source-reference schema, independent of AE memory cards and wireless-kit formats. */
public final class BindingToolNbt {

    public static final String SOURCE_KEY = "metransferdriveBindingSource";
    private static final String DEVICE_KEY = "bindingDeviceId";

    private BindingToolNbt() {}

    public static BindingToolLogic.SourceReference readSource(final NBTTagCompound root) {
        if (root == null || !root.hasKey(SOURCE_KEY)) {
            return null;
        }
        if (!root.hasKey(SOURCE_KEY, 10)) {
            throw new IllegalArgumentException("Invalid binding source tag");
        }
        final NBTTagCompound data = root.getCompoundTag(SOURCE_KEY);
        if (!data.hasKey("version", 3) || data.getInteger("version") != 1
            || !data.hasKey("dimension", 3)
            || !data.hasKey("x", 3)
            || !data.hasKey("y", 3)
            || !data.hasKey("z", 3)
            || !data.hasKey("deviceId", 8)) {
            throw new IllegalArgumentException("Incomplete or unsupported binding source");
        }
        return new BindingToolLogic.SourceReference(
            new BindingToolLogic.Position(
                data.getInteger("dimension"),
                data.getInteger("x"),
                data.getInteger("y"),
                data.getInteger("z")),
            uuid(data.getString("deviceId")));
    }

    public static void writeSource(final NBTTagCompound root, final BindingToolLogic.SourceReference source) {
        final NBTTagCompound data = new NBTTagCompound();
        data.setInteger("version", 1);
        data.setInteger("dimension", source.position.dimension);
        data.setInteger("x", source.position.x);
        data.setInteger("y", source.position.y);
        data.setInteger("z", source.position.z);
        data.setString("deviceId", source.deviceId.toString());
        root.setTag(SOURCE_KEY, data);
    }

    public static UUID readDeviceIdentity(final NBTTagCompound data, final UUID newDeviceIdentity) {
        try {
            return data.hasKey(DEVICE_KEY, 8) ? uuid(data.getString(DEVICE_KEY)) : newDeviceIdentity;
        } catch (final IllegalArgumentException invalid) {
            // An invalid identity must not reuse a different device's binding identity.
            return newDeviceIdentity;
        }
    }

    public static void writeDeviceIdentity(final NBTTagCompound data, final UUID identity) {
        data.setString(DEVICE_KEY, identity.toString());
    }

    private static UUID uuid(final String text) {
        final UUID value = UUID.fromString(text);
        if (!value.toString()
            .equalsIgnoreCase(text)) {
            throw new IllegalArgumentException("Expected canonical UUID");
        }
        return value;
    }
}
