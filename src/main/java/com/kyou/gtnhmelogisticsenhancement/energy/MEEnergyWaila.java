package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.List;
import java.util.Locale;

import net.minecraft.nbt.NBTTagCompound;

/** A small server snapshot; the client must never infer EU stock from its unsynchronized native tile. */
final class MEEnergyWaila {

    static final String DATA_KEY = "gtnhmelogisticsenhancement.me_eu";

    private MEEnergyWaila() {}

    private static String formatNumber(final long eu) {
        return String.format(Locale.ROOT, "%,d", eu);
    }

    static void write(final IMEEnergyEndpoint host, final NBTTagCompound tag) {
        final NBTTagCompound data = new NBTTagCompound();
        data.setLong("stored", host.readEU());
        data.setLong(
            "capacity",
            host.spec()
                .capacity());
        data.setInteger("group", host.businessColor());
        tag.setTag(DATA_KEY, data);
    }

    static void body(final NBTTagCompound tag, final List<String> tooltip) {
        if (!tag.hasKey(DATA_KEY, 10)) return;
        final NBTTagCompound data = tag.getCompoundTag(DATA_KEY);
        if (!data.hasKey("stored", 4) || !data.hasKey("capacity", 4) || !data.hasKey("group", 3)) return;
        tooltip.add(
            MEEnergyPresentation
                .text("waila.buffer", formatNumber(data.getLong("stored")), formatNumber(data.getLong("capacity"))));
        tooltip.add(MEEnergyPresentation.text("waila.group", MEEnergyPresentation.colorName(data.getInteger("group"))));
    }
}
