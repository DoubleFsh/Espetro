package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import org.espetro.tutorial.TutorialManager;

import java.util.function.Supplier;

/**
 * 新手教程操作包（C→S）：下一步 / 跳过全部 / 关闭当前。
 */
public class TutorialActionPacket {

    public static final byte ACTION_NEXT = 0;
    public static final byte ACTION_SKIP_ALL = 1;
    public static final byte ACTION_DISMISS = 2;
    public static final byte ACTION_REOPEN = 3;

    private final byte action;
    private final String stepId;

    public TutorialActionPacket(byte action, String stepId) {
        this.action = action;
        this.stepId = stepId == null ? "" : stepId;
    }

    public static TutorialActionPacket next(String stepId) {
        return new TutorialActionPacket(ACTION_NEXT, stepId);
    }

    public static TutorialActionPacket skipAll() {
        return new TutorialActionPacket(ACTION_SKIP_ALL, "");
    }

    public static TutorialActionPacket dismiss(String stepId) {
        return new TutorialActionPacket(ACTION_DISMISS, stepId);
    }

    public static TutorialActionPacket reopen() {
        return new TutorialActionPacket(ACTION_REOPEN, "");
    }

    public static TutorialActionPacket read(FriendlyByteBuf buf) {
        byte action = buf.readByte();
        String stepId = buf.readUtf();
        return new TutorialActionPacket(action, stepId);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeByte(action);
        buf.writeUtf(stepId);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) {
                return;
            }
            if (action == ACTION_REOPEN) {
                TutorialManager.getInstance().reopen(player);
                return;
            }
            TutorialManager.Action mapped = switch (action) {
                case ACTION_SKIP_ALL -> TutorialManager.Action.SKIP_ALL;
                case ACTION_DISMISS -> TutorialManager.Action.DISMISS;
                default -> TutorialManager.Action.NEXT;
            };
            TutorialManager.getInstance().handleAction(player, mapped, stepId);
        });
        ctx.get().setPacketHandled(true);
    }
}
