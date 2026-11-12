package com.kyou.gtnhmelogisticsenhancement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.oredict.ShapedOreRecipe;
import net.minecraftforge.oredict.ShapelessOreRecipe;

import org.junit.Test;

/** Checks actual Forge recipe construction; upstream definitions/NEI still require runtime verification. */
public class RecipeLoaderTest {

    @Test
    public void driveUsesEightDrivesAroundOneIoPort() {
        final ItemStack drive = stack(1);
        final ItemStack port = stack(2);
        final ItemStack output = stack(3);
        final ShapedOreRecipe recipe = RecipeLoader.driveRecipe(output, drive, port);
        final Object[] ingredients = recipe.getInput();
        assertEquals(9, ingredients.length);
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(slot == 4 ? 2 : 1, ((ItemStack) ingredients[slot]).getItemDamage());
        }
        assertSame(
            output.getItem(),
            recipe.getRecipeOutput()
                .getItem());
        assertEquals(1, recipe.getRecipeOutput().stackSize);
    }

    @Test
    public void toolUsesTheExactPatternWithTitaniumPlateAndBlockInterfaceAtCenter() {
        // Ordinary JVM tests supply a material stand-in: Forge's vanilla ore bootstrap is not a game test.
        final ShapedOreRecipe recipe = RecipeLoader
            .toolRecipe(stack(0), stack(41), stack(200), stack(24), stack(100), stack(12), stack(16));
        final Object[] ingredients = recipe.getInput();
        assertEquals(9, ingredients.length);
        final int[] metadata = { 41, 200, 41, 24, 100, 24, 12, 16, 12 };
        for (int slot = 0; slot < 9; slot++) {
            assertEquals(metadata[slot], ((ItemStack) ingredients[slot]).getItemDamage());
        }
    }

    @Test
    public void craftedToolsAreFreshAndIndependentWithoutSelectionNbt() {
        final ShapedOreRecipe recipe = RecipeLoader
            .toolRecipe(stack(0), stack(41), stack(200), stack(24), stack(100), stack(12), stack(16));
        final ItemStack first = recipe.getCraftingResult(null);
        final ItemStack second = recipe.getCraftingResult(null);
        assertNotSame(first, second);
        assertFalse(first.hasTagCompound());
        assertFalse(second.hasTagCompound());
        assertEquals(1, first.stackSize);
        first.stackSize = 0;
        assertEquals(1, recipe.getRecipeOutput().stackSize);
    }

    private static ItemStack stack(final int metadata) {
        return new ItemStack(new Item(), 1, metadata);
    }

    @Test
    public void monitorUsesOneCardAndOneMeterWithoutPropagatingTheirNbt() {
        final ItemStack output = stack(72), card = stack(73), meter = stack(74);
        for (final ItemStack item : new ItemStack[] { output, card, meter }) {
            item.stackSize = 4;
            item.setTagCompound(new NBTTagCompound());
            item.getTagCompound()
                .setString("privateData", "not copied");
        }
        final ShapelessOreRecipe recipe = RecipeLoader.monitorRecipe(output, card, meter);
        assertEquals(
            2,
            recipe.getInput()
                .size());
        assertEquals(
            73,
            ((ItemStack) recipe.getInput()
                .get(0)).getItemDamage());
        assertEquals(
            74,
            ((ItemStack) recipe.getInput()
                .get(1)).getItemDamage());
        assertEquals(
            1,
            ((ItemStack) recipe.getInput()
                .get(0)).stackSize);
        assertEquals(
            1,
            ((ItemStack) recipe.getInput()
                .get(1)).stackSize);
        assertEquals(4, card.stackSize);
        final ItemStack first = recipe.getCraftingResult(null), second = recipe.getCraftingResult(null);
        assertSame(output.getItem(), first.getItem());
        assertEquals(1, first.stackSize);
        assertFalse(first.hasTagCompound());
        assertNotSame(first, second);
    }
}
