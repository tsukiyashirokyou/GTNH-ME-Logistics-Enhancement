package com.kyou.gtnhmelogisticsenhancement.energy;

import net.minecraft.util.StatCollector;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;

import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.render.TextureFactory;

/** Shared appearance and tooltips; never inherits laser optics or controls. */
final class MEEnergyPresentation {

    private static final IIconContainer ENERGY = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_energy");
    private static final IIconContainer DYNAMO = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_dynamo");

    private MEEnergyPresentation() {}

    static ITexture[] textures(final ITexture base, final MEEnergySpec.Role role, final boolean active) {
        return new ITexture[] { base,
            TextureFactory
                .of(active ? Textures.BlockIcons.OVERLAY_ME_HATCH_ACTIVE : Textures.BlockIcons.OVERLAY_ME_HATCH),
            TextureFactory.of(role == MEEnergySpec.Role.ENERGY ? ENERGY : DYNAMO) };
    }

    static String text(final String key, final Object... args) {
        return StatCollector.translateToLocalFormatted("gtnhmelogisticsenhancement.energy." + key, args);
    }

    static String[] description(final MEEnergySpec spec) {
        return new String[] { text(spec.role() == MEEnergySpec.Role.ENERGY ? "input_note" : "output_note"),
            text("spec", spec.tierName(), spec.voltage(), spec.amperage()), text("capacity", spec.capacity()),
            text(
                "family." + spec.family()
                    .name()),
            text("connection_note"), text("loss_note"), text("paint_note"), text("faces_note"), text("offline_note"),
            text("removal_note") };
    }
}
