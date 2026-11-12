package com.kyou.gtnhmelogisticsenhancement;

import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import appeng.api.AEApi;
import appeng.api.definitions.IDefinitions;
import appeng.api.definitions.IItemDefinition;
import appeng.api.util.AEColor;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.util.GTModHandler;

/** Independent Forge recipes; no replacement or removal of upstream recipes. */
public final class RecipeLoader {

    private RecipeLoader() {}

    public static void register() {
        final IDefinitions definitions = AEApi.instance()
            .definitions();
        final ItemStack meter = GTModHandler.getIC2Item("ecMeter", 1);
        if (meter == null) throw new IllegalStateException("Required IC2 EU meter (ecMeter) is unavailable");
        GameRegistry.addRecipe(
            monitorRecipe(
                new ItemStack(GTNHMELogisticsEnhancementMod.meEnergyMonitor),
                stack(
                    definitions.items()
                        .memoryCard()),
                meter));
        GameRegistry.addRecipe(
            driveRecipe(
                new ItemStack(GTNHMELogisticsEnhancementMod.meTransferDrive),
                stack(
                    definitions.blocks()
                        .drive()),
                stack(
                    definitions.blocks()
                        .iOPort())));
        // GTNH 2.9.0: CoreMod 2.9.99's Wireless Kit uses plateTitanium.
        // AE definitions supply the pinned material/part metadata, including default Fluix glass cable.
        GameRegistry.addRecipe(
            toolRecipe(
                new ItemStack(GTNHMELogisticsEnhancementMod.meNetworkBindingTool),
                stack(
                    definitions.materials()
                        .wireless()),
                "plateTitanium",
                stack(
                    definitions.materials()
                        .engProcessor()),
                stack(
                    definitions.blocks()
                        .iface()),
                stack(
                    definitions.materials()
                        .purifiedFluixCrystal()),
                definitions.parts()
                    .cableGlass()
                    .stack(AEColor.Transparent, 1)));
    }

    private static ItemStack stack(final IItemDefinition definition) {
        if (!definition.maybeStack(1)
            .isPresent()) {
            throw new IllegalStateException("A required AE2 recipe ingredient is disabled");
        }
        return definition.maybeStack(1)
            .get();
    }

    public static ShapedOreRecipe driveRecipe(final ItemStack output, final ItemStack drive, final ItemStack port) {
        return new ShapedOreRecipe(output, "DDD", "DID", "DDD", 'D', drive, 'I', port).setMirrored(false);
    }

    public static ShapelessOreRecipe monitorRecipe(final ItemStack output, final ItemStack card,
        final ItemStack meter) {
        final ItemStack fresh = new ItemStack(output.getItem(), 1, output.getItemDamage());
        final ItemStack oneCard = card.copy(), oneMeter = meter.copy();
        oneCard.stackSize = oneMeter.stackSize = 1;
        return new ShapelessOreRecipe(fresh, oneCard, oneMeter);
    }

    public static ShapedOreRecipe toolRecipe(final ItemStack output, final ItemStack receiver,
        final Object titaniumPlate, final ItemStack processor, final ItemStack blockInterface,
        final ItemStack pureFluix, final ItemStack glassCable) {
        // The caller supplies a fresh ItemStack; never copy a player's temporarily selected source NBT.
        return new ShapedOreRecipe(
            output,
            "RTR",
            "EME",
            "FCF",
            'R',
            receiver,
            'T',
            titaniumPlate,
            'E',
            processor,
            'M',
            blockInterface,
            'F',
            pureFluix,
            'C',
            glassCable).setMirrored(false);
    }
}
