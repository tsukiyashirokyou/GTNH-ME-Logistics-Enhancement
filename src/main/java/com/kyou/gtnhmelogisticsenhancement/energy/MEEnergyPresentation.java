package com.kyou.gtnhmelogisticsenhancement.energy;

import java.util.Arrays;

import net.minecraft.util.StatCollector;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;

import gregtech.api.enums.Dyes;
import gregtech.api.enums.Textures;
import gregtech.api.interfaces.IIconContainer;
import gregtech.api.interfaces.ITexture;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.render.TextureFactory;

/** Shared appearance and tooltips; never inherits laser optics or controls. */
final class MEEnergyPresentation {

    private static final IIconContainer BADGE = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_badge");
    private static final IIconContainer HIGH_BADGE = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_badge_high");
    private static final IIconContainer ACTIVE_BADGE = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_badge_active");
    private static final IIconContainer ACTIVE_HIGH_BADGE = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_badge_high_active");
    private static final IIconContainer GROUP_FRAME = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_group_frame");
    private static final IIconContainer GROUP_STRIPE = Textures.BlockIcons
        .custom(GTNHMELogisticsEnhancementMod.MODID, "me_eu_group_stripe");
    private static final ITexture GROUP_FRAME_TEXTURE = TextureFactory.of(GROUP_FRAME);
    private static final ITexture[] GROUP_COLORS = new ITexture[16];

    static {
        for (int color = 0; color < GROUP_COLORS.length; color++) GROUP_COLORS[color] = TextureFactory.of(
            GROUP_STRIPE,
            Dyes.get(color)
                .getRGBA());
    }

    private MEEnergyPresentation() {}

    /** Called by the MTE icon hook before GT drains its block-icon loading queue. */
    static void registerIcons() {
        // Invoking this method initializes the badge containers, including on resource reload.
        // Waiting for textures()/description() can create them after the first atlas stitch.
    }

    static ITexture[] textures(final ITexture base, final MEEnergySpec spec, final boolean active) {
        return new ITexture[] { base,
            TextureFactory
                .of(active ? Textures.BlockIcons.OVERLAY_ME_HATCH_ACTIVE : Textures.BlockIcons.OVERLAY_ME_HATCH),
            port(spec), TextureFactory
                .of(highPort(spec) ? active ? ACTIVE_HIGH_BADGE : HIGH_BADGE : active ? ACTIVE_BADGE : BADGE) };
    }

    /** Independent of the controller's casing override and of the purple/cyan ME status lights. */
    static ITexture[] withColor(final ITexture[] layers, final int color) {
        if (color < 0 || color >= GROUP_COLORS.length) return layers;
        final ITexture[] painted = Arrays.copyOf(layers, layers.length + 2);
        painted[layers.length] = GROUP_FRAME_TEXTURE;
        painted[layers.length + 1] = GROUP_COLORS[color];
        return painted;
    }

    static String colorName(final int group) {
        return StatCollector
            .translateToLocal("gtnhmelogisticsenhancement.monitor.color." + (group >= 0 && group <= 16 ? group : 0));
    }

    static boolean highPort(final MEEnergySpec spec) {
        return spec.amperage() > (spec.role() == MEEnergySpec.Role.ENERGY || spec.tier() < 8 ? 64 : 256);
    }

    /** Native non-laser ports retain voltage tint and normal/multi-A direction semantics. */
    static ITexture port(final MEEnergySpec spec) {
        final int tier = spec.tier() + 1;
        final int amps = spec.amperage();
        if (spec.role() == MEEnergySpec.Role.ENERGY) {
            if (amps <= 2) return Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_2A[tier];
            if (amps <= 4) return Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_4A[tier];
            if (amps <= 16) return Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_16A[tier];
            return Textures.BlockIcons.OVERLAYS_ENERGY_IN_MULTI_64A[tier];
        }
        if (amps == 1) return Textures.BlockIcons.OVERLAYS_ENERGY_OUT[tier];
        if (amps <= 4) return Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_4A[tier];
        if (amps <= 16) return Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_16A[tier];
        // The 256A array has UV-coloured placeholders below UV, not actual lower-tier artwork.
        if (amps <= 64 || spec.tier() < 8) return Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_64A[tier];
        return Textures.BlockIcons.OVERLAYS_ENERGY_OUT_MULTI_256A[tier];
    }

    static String text(final String key, final Object... args) {
        final String translationKey = "gtnhmelogisticsenhancement.energy." + key;
        return args.length == 0 ? StatCollector.translateToLocal(translationKey)
            : StatCollector.translateToLocalFormatted(translationKey, args);
    }

    static String name(final MetaTileEntity hatch) {
        return StatCollector.translateToLocal(hatch.getLocalNameKey());
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
