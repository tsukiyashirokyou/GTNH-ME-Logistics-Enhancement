package com.kyou.metransferdrive.item;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.kyou.metransferdrive.METransferDriveMod;
import com.kyou.metransferdrive.binding.BindingToolLogic;
import com.kyou.metransferdrive.binding.BindingToolNbt;
import com.kyou.metransferdrive.tile.TileMETransferDrive;
import com.kyou.metransferdrive.transfer.TransferTarget;

import appeng.api.networking.IGridNode;
import appeng.api.networking.security.PlayerSource;
import appeng.me.helpers.AENetworkProxy;
import appeng.tile.misc.TileInterface;
import appeng.util.Platform;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Only source selection and target registration; device GUI owns all routing and target management. */
public final class ItemNetworkBindingTool extends Item {

    private static final String MESSAGE = "metransferdrive.binding.";

    public ItemNetworkBindingTool() {
        this.setUnlocalizedName("meNetworkBindingTool");
        this.setTextureName(METransferDriveMod.MODID + ":me_network_binding_tool");
        this.setCreativeTab(CreativeTabs.tabTools);
        this.setMaxStackSize(1);
    }

    @Override
    public boolean onItemUse(final ItemStack stack, final EntityPlayer player, final World world, final int x,
        final int y, final int z, final int side, final float hitX, final float hitY, final float hitZ) {
        if (!BindingToolLogic.handleOnServer(world.isRemote, player.isSneaking())) {
            return BindingToolLogic.consumeClientUse(world.isRemote, player.isSneaking());
        }
        try {
            BindingToolLogic.SourceReference selected;
            try {
                selected = BindingToolNbt.readSource(stack.getTagCompound());
            } catch (final IllegalArgumentException invalid) {
                // A new drive selection can replace damaged data; targets never use a guessed source.
                selected = null;
            }
            final BindingToolLogic.Result result = BindingToolLogic.use(
                true,
                selected,
                new BindingToolLogic.Position(world.provider.dimensionId, x, y, z),
                new ServerContext(world, player, stack, side));
            if (result.selection != null) {
                if (!stack.hasTagCompound()) {
                    stack.setTagCompound(new NBTTagCompound());
                }
                BindingToolNbt.writeSource(stack.getTagCompound(), result.selection);
                player.inventory.markDirty();
            }
            player.addChatMessage(
                new ChatComponentTranslation(
                    MESSAGE + result.outcome.name()
                        .toLowerCase(Locale.ROOT)));
        } catch (final RuntimeException failure) {
            METransferDriveMod.LOG.warn("ME network binding failed at {},{},{}", x, y, z, failure);
            player.addChatMessage(new ChatComponentTranslation(MESSAGE + "api_failure"));
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(final ItemStack stack, final EntityPlayer player, final List lines,
        final boolean advanced) {
        lines.add(StatCollector.translateToLocal(MESSAGE + "hint"));
        try {
            final BindingToolLogic.SourceReference source = BindingToolNbt.readSource(stack.getTagCompound());
            if (source != null) {
                lines.add(
                    StatCollector.translateToLocalFormatted(
                        MESSAGE + "source",
                        source.position.dimension,
                        source.position.x,
                        source.position.y,
                        source.position.z));
            } else {
                lines.add(StatCollector.translateToLocal(MESSAGE + "no_source"));
            }
        } catch (final IllegalArgumentException invalid) {
            lines.add(StatCollector.translateToLocal(MESSAGE + "invalid_source_data"));
        }
    }

    private static final class ServerContext implements BindingToolLogic.Context {

        private final World world;
        private final EntityPlayer player;
        private final ItemStack tool;
        private final int side;

        ServerContext(final World world, final EntityPlayer player, final ItemStack tool, final int side) {
            this.world = world;
            this.player = player;
            this.tool = tool;
            this.side = side;
        }

        @Override
        public boolean isServer() {
            return !this.world.isRemote;
        }

        @Override
        public int dimension() {
            return this.world.provider.dimensionId;
        }

        @Override
        public boolean isLoaded(final BindingToolLogic.Position position) {
            return position.dimension == this.dimension() && this.world.getChunkProvider()
                .chunkExists(position.x >> 4, position.z >> 4);
        }

        private TileEntity tile(final BindingToolLogic.Position position) {
            return this.isLoaded(position) ? this.world.getTileEntity(position.x, position.y, position.z) : null;
        }

        @Override
        public BindingToolLogic.Source sourceAt(final BindingToolLogic.Position position) {
            final TileEntity tile = this.tile(position);
            if (!(tile instanceof TileMETransferDrive) || tile.isInvalid()) {
                return null;
            }
            final TileMETransferDrive drive = (TileMETransferDrive) tile;
            return new BindingToolLogic.Source() {

                @Override
                public UUID identity() {
                    return drive.getBindingIdentity();
                }

                @Override
                public boolean isLocked() {
                    return drive.isTransferConfigurationLocked();
                }

                @Override
                public boolean canConfigure() {
                    return allowed(position, drive.getProxy());
                }

                @Override
                public Object grid() {
                    final IGridNode node = drive.getActionableNode();
                    return node == null ? null : node.getGrid();
                }

                @Override
                public List<TransferTarget> targets() {
                    return drive.getTransferTargets();
                }

                @Override
                public UUID register(final TransferTarget target) {
                    return drive.registerTransferTarget(target);
                }
            };
        }

        @Override
        public boolean isInterface(final BindingToolLogic.Position position) {
            final TileEntity tile = this.tile(position);
            return tile != null && tile.getClass() == TileInterface.class && !tile.isInvalid();
        }

        @Override
        public boolean canUseInterface(final BindingToolLogic.Position position) {
            final TileEntity tile = this.tile(position);
            return this.isInterface(position) && this.allowed(position, ((TileInterface) tile).getProxy());
        }

        @Override
        public Object interfaceGrid(final BindingToolLogic.Position position) {
            if (!this.isInterface(position)) {
                return null;
            }
            final IGridNode node = ((TileInterface) this.tile(position)).getGridNode(ForgeDirection.UNKNOWN);
            return node == null ? null : node.getGrid();
        }

        private boolean allowed(final BindingToolLogic.Position position, final AENetworkProxy proxy) {
            return this.player.canPlayerEdit(position.x, position.y, position.z, this.side, this.tool)
                && this.world.canMineBlock(this.player, position.x, position.y, position.z)
                && Platform.canAccess(proxy, new PlayerSource(this.player, null));
        }
    }
}
