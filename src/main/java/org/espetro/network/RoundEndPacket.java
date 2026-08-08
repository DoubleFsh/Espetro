package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S→C 回合结算数据包。
 * <p>
 * 结果等级（resultLevel）语义：
 * <ul>
 * <li>5 — 完胜</li>
 * <li>4 — 重大胜利</li>
 * <li>3 — 决定性胜利</li>
 * <li>2 — 险胜</li>
 * <li>1 — 惨烈胜利</li>
 * <li>0 — 平局</li>
 * </ul>
 * 输方客户端将等级对称为对应输出文案（完败 / 重大战败 / … / 功亏一篑）。
 */
public class RoundEndPacket {

    /** ATTACK / DEFEND / DRAW / RESET */
    public final String winner;
    /** RoundEndScreen 展示秒数 */
    public final int displaySeconds;
    /** 赢方 faction.showName（或 name 回退），平局时为 null */
    public final String winnerShowName;
    /** 输方 faction.showName（或 name 回退），赢方视角的"敌方"，平局时为 null */
    public final String loserShowName;
    /** 攻击方剩余票数 */
    public final int attackTickets;
    /** 防守方剩余票数 */
    public final int defendTickets;
    /** 结果等级 5→0（从赢方视角），平局时为 0 */
    public final int resultLevel;
    /** 攻击方是否因超时判负 */
    public final boolean attackerTimeout;

    public RoundEndPacket(String winner, int displaySeconds,
                          String winnerShowName, String loserShowName,
                          int attackTickets, int defendTickets,
                          int resultLevel, boolean attackerTimeout) {
        this.winner = winner == null ? "DRAW" : winner;
        this.displaySeconds = Math.max(1, displaySeconds);
        this.winnerShowName = (winnerShowName == null || winnerShowName.isEmpty()) ? null : winnerShowName;
        this.loserShowName = (loserShowName == null || loserShowName.isEmpty()) ? null : loserShowName;
        this.attackTickets = Math.max(0, attackTickets);
        this.defendTickets = Math.max(0, defendTickets);
        this.resultLevel = Math.max(0, Math.min(5, resultLevel));
        this.attackerTimeout = attackerTimeout;
    }

    public static RoundEndPacket read(FriendlyByteBuf buf) {
        return new RoundEndPacket(
            buf.readUtf(),
            buf.readVarInt(),
            buf.readBoolean() ? buf.readUtf() : null,
            buf.readBoolean() ? buf.readUtf() : null,
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readVarInt(),
            buf.readBoolean()
        );
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(winner);
        buf.writeVarInt(displaySeconds);
        buf.writeBoolean(winnerShowName != null);
        if (winnerShowName != null) buf.writeUtf(winnerShowName);
        buf.writeBoolean(loserShowName != null);
        if (loserShowName != null) buf.writeUtf(loserShowName);
        buf.writeVarInt(attackTickets);
        buf.writeVarInt(defendTickets);
        buf.writeVarInt(resultLevel);
        buf.writeBoolean(attackerTimeout);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleRoundEnd", RoundEndPacket.class)
                    .invoke(null, this);
            } catch (Exception ignored) {
            }
        });
        ctx.get().setPacketHandled(true);
    }
}
