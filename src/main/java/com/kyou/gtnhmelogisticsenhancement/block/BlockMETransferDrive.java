package com.kyou.gtnhmelogisticsenhancement.block;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.block.Block;
import net.minecraft.block.BlockContainer;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.IIcon;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;

import com.kyou.gtnhmelogisticsenhancement.GTNHMELogisticsEnhancementMod;
import com.kyou.gtnhmelogisticsenhancement.gui.DriveGuiHandler;
import com.kyou.gtnhmelogisticsenhancement.tile.TileMETransferDrive;

import appeng.util.Platform;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** A static drive-style shell with native cell inventory drops. */
public class BlockMETransferDrive extends BlockContainer {

    private IIcon frontIcon;
    private IIcon sideIcon;
    private IIcon topIcon;

    public BlockMETransferDrive() {
        super(Material.iron);
        this.setBlockName("meTransferDrive");
        this.setCreativeTab(CreativeTabs.tabMisc);
        this.setHardness(2.2F);
        this.setResistance(10.0F);
        this.setHarvestLevel("pickaxe", 0);
        this.setStepSound(soundTypeMetal);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int metadata) {
        return new TileMETransferDrive();
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (player.isSneaking()) {
            return false;
        }
        if (!world.isRemote) {
            player.openGui(GTNHMELogisticsEnhancementMod.instance, DriveGuiHandler.MACHINE, world, x, y, z);
        }
        return true;
    }

    @Override
    public void breakBlock(final World world, final int x, final int y, final int z, final Block oldBlock,
        final int oldMetadata) {
        final TileEntity tile = world.getTileEntity(x, y, z);
        if (!world.isRemote && !world.restoringBlockSnapshots && tile instanceof TileMETransferDrive) {
            final TileMETransferDrive drive = (TileMETransferDrive) tile;
            if (drive.dropItems()) {
                final List<ItemStack> drops = new ArrayList<>();
                drive.getDrops(world, x, y, z, drops);
                Platform.spawnDrops(world, x, y, z, drops);
            }
        }
        super.breakBlock(world, x, y, z, oldBlock, oldMetadata);
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int direction = MathHelper.floor_double(placer.rotationYaw * 4.0D / 360.0D + 0.5D) & 3;
        int front = new int[] { 2, 5, 3, 4 }[direction];
        world.setBlockMetadataWithNotify(x, y, z, front, 2);
        if (!world.isRemote) {
            final TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof TileMETransferDrive) {
                final TileMETransferDrive drive = (TileMETransferDrive) tile;
                if (placer instanceof EntityPlayer) {
                    drive.getProxy()
                        .setOwner((EntityPlayer) placer);
                }
                drive.restoreRecoveryItem(stack);
            }
        }
    }

    @Override
    public ArrayList<ItemStack> getDrops(final World world, final int x, final int y, final int z, final int metadata,
        final int fortune) {
        final TileEntity tile = world.getTileEntity(x, y, z);
        if (tile instanceof TileMETransferDrive && ((TileMETransferDrive) tile).hasTransferRecovery()) {
            // breakBlock emits one packed device, replacing the normal block and separate cell drops.
            return new ArrayList<>();
        }
        return super.getDrops(world, x, y, z, metadata, fortune);
    }

    @Override
    public boolean removedByPlayer(final World world, final EntityPlayer player, final int x, final int y, final int z,
        final boolean willHarvest) {
        return willHarvest || super.removedByPlayer(world, player, x, y, z, false);
    }

    @Override
    public void harvestBlock(final World world, final EntityPlayer player, final int x, final int y, final int z,
        final int metadata) {
        super.harvestBlock(world, player, x, y, z, metadata);
        world.setBlockToAir(x, y, z);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        String base = GTNHMELogisticsEnhancementMod.MODID + ":me_transfer_drive_";
        this.frontIcon = register.registerIcon(base + "front");
        this.sideIcon = register.registerIcon(base + "side");
        this.topIcon = register.registerIcon(base + "top");
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int side, int metadata) {
        if (side == 0 || side == 1) {
            return this.topIcon;
        }
        if (side == metadata || (metadata == 0 && side == 3)) {
            return this.frontIcon;
        }
        return this.sideIcon;
    }
}
