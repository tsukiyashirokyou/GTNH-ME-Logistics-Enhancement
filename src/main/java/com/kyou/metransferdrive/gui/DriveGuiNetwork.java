package com.kyou.metransferdrive.gui;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import com.kyou.metransferdrive.METransferDriveMod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.ByteBufUtils;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;
import io.netty.buffer.ByteBuf;

/** Netty handlers only enqueue. All world, container and GUI access occurs on the respective game thread. */
public final class DriveGuiNetwork {

    private static final ArrayBlockingQueue<Runnable> SERVER_QUEUE = new ArrayBlockingQueue<>(1024);
    private static SimpleNetworkWrapper channel;

    private DriveGuiNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("METransferGui");
        channel.registerMessage(ActionHandler.class, ActionMessage.class, 0, Side.SERVER);
        channel.registerMessage(StateHandler.class, StateMessage.class, 1, Side.CLIENT);
        channel.registerMessage(ClickHandler.class, ClickMessage.class, 2, Side.SERVER);
        FMLCommonHandler.instance()
            .bus()
            .register(new ServerTicks());
    }

    public static void request(final int window, final DriveGuiAction action) {
        final ActionMessage message = new ActionMessage();
        message.window = window;
        message.action = action;
        channel.sendToServer(message);
    }

    public static void click(final ClickMessage message) {
        channel.sendToServer(message);
    }

    public static void clearServerQueue() {
        SERVER_QUEUE.clear();
    }

    public static void send(final EntityPlayerMP player, final int window, final NBTTagCompound state) {
        final StateMessage message = new StateMessage();
        message.window = window;
        message.state = state;
        channel.sendTo(message, player);
    }

    public static final class ServerTicks {

        @SubscribeEvent
        public void tick(final TickEvent.ServerTickEvent event) {
            if (event.phase == TickEvent.Phase.START) {
                for (int count = 0; count < 128; count++) {
                    final Runnable work = SERVER_QUEUE.poll();
                    if (work == null) {
                        break;
                    }
                    work.run();
                }
            }
        }
    }

    public static final class ActionMessage implements IMessage {

        int window;
        DriveGuiAction action;

        @Override
        public void fromBytes(final ByteBuf buffer) {
            this.window = buffer.readInt();
            checkWindow(this.window);
            final int kind = buffer.readUnsignedByte();
            if (kind >= DriveGuiAction.Kind.values().length) {
                throw new IllegalArgumentException("Unknown GUI operation");
            }
            final UUID session = uuid(buffer);
            final long revision = buffer.readLong();
            final int group = buffer.readInt();
            final UUID target = buffer.readBoolean() ? uuid(buffer) : null;
            this.action = new DriveGuiAction(
                DriveGuiAction.Kind.values()[kind],
                session,
                revision,
                group,
                target,
                string(buffer, 256),
                string(buffer, 32));
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Trailing GUI request data");
            }
        }

        @Override
        public void toBytes(final ByteBuf buffer) {
            buffer.writeInt(this.window);
            buffer.writeByte(this.action.kind.ordinal());
            uuid(buffer, this.action.session);
            buffer.writeLong(this.action.revision);
            buffer.writeInt(this.action.group);
            buffer.writeBoolean(this.action.target != null);
            if (this.action.target != null) {
                uuid(buffer, this.action.target);
            }
            string(buffer, this.action.text, 256);
            string(buffer, this.action.number, 32);
        }
    }

    public static final class StateMessage implements IMessage {

        public int window;
        public NBTTagCompound state;

        @Override
        public void fromBytes(final ByteBuf buffer) {
            this.window = buffer.readInt();
            checkWindow(this.window);
            if (buffer.readableBytes() > 32769) {
                throw new IllegalArgumentException("GUI snapshot exceeds native NBT packet limit");
            }
            this.state = ByteBufUtils.readTag(buffer);
            if (buffer.isReadable()) {
                throw new IllegalArgumentException("Trailing GUI snapshot data");
            }
        }

        @Override
        public void toBytes(final ByteBuf buffer) {
            buffer.writeInt(this.window);
            ByteBufUtils.writeTag(buffer, this.state);
        }
    }

    /** No client-supplied ItemStack or NBT: the server alone executes native Container slot semantics. */
    public static final class ClickMessage implements IMessage {

        public int window;
        public UUID session;
        public long pageRevision;
        public long configurationRevision;
        public long sequence;
        public int slot;
        public int button;
        public int mode;

        @Override
        public void fromBytes(final ByteBuf buffer) {
            this.window = buffer.readInt();
            checkWindow(this.window);
            this.session = uuid(buffer);
            this.pageRevision = buffer.readLong();
            this.configurationRevision = buffer.readLong();
            this.sequence = buffer.readLong();
            this.slot = buffer.readInt();
            this.button = buffer.readInt();
            this.mode = buffer.readInt();
            if (buffer.isReadable() || this.pageRevision < 0 || this.sequence <= 0) {
                throw new IllegalArgumentException("Invalid inventory request");
            }
        }

        @Override
        public void toBytes(final ByteBuf buffer) {
            buffer.writeInt(this.window);
            uuid(buffer, this.session);
            buffer.writeLong(this.pageRevision);
            buffer.writeLong(this.configurationRevision);
            buffer.writeLong(this.sequence);
            buffer.writeInt(this.slot);
            buffer.writeInt(this.button);
            buffer.writeInt(this.mode);
        }
    }

    public static final class ClickHandler implements IMessageHandler<ClickMessage, IMessage> {

        @Override
        public IMessage onMessage(final ClickMessage message, final MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_QUEUE.offer(() -> {
                if (player.playerNetServerHandler.netManager.isChannelOpen()
                    && player.openContainer instanceof ContainerTransferDrive
                    && player.openContainer.windowId == message.window) {
                    try {
                        ((ContainerTransferDrive) player.openContainer).handleClick(message);
                    } catch (RuntimeException failure) {
                        METransferDriveMod.LOG.warn("Transfer Drive inventory operation failed; not replayed", failure);
                        player.closeScreen();
                    }
                }
            });
            return null;
        }
    }

    public static final class ActionHandler implements IMessageHandler<ActionMessage, IMessage> {

        @Override
        public IMessage onMessage(final ActionMessage message, final MessageContext context) {
            final EntityPlayerMP player = context.getServerHandler().playerEntity;
            SERVER_QUEUE.offer(() -> {
                if (player.playerNetServerHandler.netManager.isChannelOpen()
                    && player.openContainer instanceof ContainerTransferDrive
                    && player.openContainer.windowId == message.window) {
                    try {
                        ((ContainerTransferDrive) player.openContainer).handle(message.action);
                    } catch (RuntimeException failure) {
                        // Never repeat an operation after an unknown result. Existing device guards remain
                        // authoritative.
                        METransferDriveMod.LOG.warn("Transfer Drive GUI operation failed", failure);
                        player.closeScreen();
                    }
                }
            });
            return null;
        }
    }

    public static final class StateHandler implements IMessageHandler<StateMessage, IMessage> {

        @Override
        public IMessage onMessage(final StateMessage message, final MessageContext context) {
            METransferDriveMod.proxy.receiveGuiState(message);
            return null;
        }
    }

    private static UUID uuid(final ByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    private static void checkWindow(final int window) {
        if (window <= 0 || window > 100) {
            throw new IllegalArgumentException("Invalid container window");
        }
    }

    private static void uuid(final ByteBuf buffer, final UUID id) {
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
    }

    private static String string(final ByteBuf buffer, final int maxBytes) {
        final int length = buffer.readUnsignedShort();
        if (length > maxBytes || length > buffer.readableBytes()) {
            throw new IllegalArgumentException("GUI string exceeds limit");
        }
        final String result = buffer.toString(buffer.readerIndex(), length, StandardCharsets.UTF_8);
        buffer.skipBytes(length);
        return result;
    }

    private static void string(final ByteBuf buffer, final String value, final int maxBytes) {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("GUI string exceeds limit");
        }
        buffer.writeShort(bytes.length);
        buffer.writeBytes(bytes);
    }
}
