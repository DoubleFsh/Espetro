package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassEquipmentZones;
import org.espetro.team.ClassSelectManager;
import org.espetro.bastion.BastionManager;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;

import java.util.function.Supplier;

/**
 * 编制/职业选择数据包
 * 在部署阶段用于指挥官选择编制，战斗阶段用于玩家选择职业
 */
public class ClassSelectPacket {

    public enum Source {
        /** J 键统一部署界面：只允许等待选点或位于本方原部署点。 */
        DEPLOY_SCREEN,
        /** 右键己方 Radio 打开的 AuraTip 职业轮盘。 */
        RADIO
    }

    private final String teamOrFaction; // ATTACK/DEFEND 或 factionId
    private final String classId;
    private final String variantId;
    private final Source source;
    private final BlockPos sourcePos;

    public ClassSelectPacket(String teamOrFaction, String classId) {
        this(teamOrFaction, classId, "", Source.DEPLOY_SCREEN, BlockPos.ZERO);
    }

    public ClassSelectPacket(String teamOrFaction, String classId, String variantId) {
        this(teamOrFaction, classId, variantId, Source.DEPLOY_SCREEN, BlockPos.ZERO);
    }

    public ClassSelectPacket(String teamOrFaction, String classId, String variantId,
                             Source source, BlockPos sourcePos) {
        this.teamOrFaction = teamOrFaction;
        this.classId = classId;
        this.variantId = variantId != null ? variantId : "";
        this.source = source != null ? source : Source.DEPLOY_SCREEN;
        this.sourcePos = sourcePos != null ? sourcePos.immutable() : BlockPos.ZERO;
    }

    public static ClassSelectPacket fromRadio(String teamOrFaction, String classId,
                                               String variantId, BlockPos radioPos) {
        return new ClassSelectPacket(
            teamOrFaction, classId, variantId, Source.RADIO, radioPos);
    }

    public static ClassSelectPacket read(FriendlyByteBuf buf) {
        String teamOrFaction = buf.readUtf();
        String classId = buf.readUtf();
        String variantId = buf.readUtf();
        Source source;
        try {
            source = Source.valueOf(buf.readUtf());
        } catch (IllegalArgumentException ignored) {
            source = Source.DEPLOY_SCREEN;
        }
        BlockPos sourcePos = buf.readBlockPos();
        return new ClassSelectPacket(teamOrFaction, classId, variantId, source, sourcePos);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(teamOrFaction);
        buf.writeUtf(classId);
        buf.writeUtf(variantId);
        buf.writeUtf(source.name());
        buf.writeBlockPos(sourcePos);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            // 检查是否在编制选择阶段
            if (ClassSelectManager.getInstance().isSelectingActive()) {
                // 当前选择阵营的所有玩家均可投票，服务端负责验证队伍和候选池。
                boolean success = ClassSelectManager.getInstance().selectClass(player, classId);
                if (success) {
                    Espetro.LOGGER.info("玩家 {} 投票编制 {}", player.getName().getString(), classId);
                }
                return;
            }

            // 否则作为职业选择处理（在战斗/部署阶段）
            ClassCountManager countManager = ClassCountManager.getInstance();
            GamePhase phase = GameStateManager.getInstance().getCurrentPhase();
            if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE) {
                denyOutOfRange(player);
                return;
            }

            String currentClass = countManager.getPlayerClass(player.getUUID());
            String currentVariant = countManager.getPlayerVariant(player.getUUID());

            /*
             * 入口必须由服务端重新验证，客户端传入的 source 只决定校验路径：
             * - 部署界面：仍在选择部署点，或已落地但位于本方原部署点；
             * - Radio：点击位置仍是附近己方有效 Radio。
             * 不再把普通 HAB、个人上次部署点或前哨当作 J 键换职区。
             */
            boolean allowed = source == Source.RADIO
                ? RadioRadialPacket.isFriendlyRadioNearby(player, sourcePos)
                : BastionManager.getInstance().isWaitingForBastion(player.getUUID())
                    || ClassEquipmentZones.isPlayerNearOriginalSpawn(player);
            if (!allowed) {
                denyOutOfRange(player);
                return;
            }

            ClassCountManager.SelectionResult selection =
                countManager.selectClassVariant(player, classId, variantId);
            if (selection != ClassCountManager.SelectionResult.SUCCESS) {
                String message = ClassCountManager.messageFor(selection, player.getUUID());
                if (message.isEmpty()) {
                    message = "§c当前无法选择该职业装备变体。";
                }
                NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
                    new ClassCountSyncPacket(message, true));
                return;
            }

            boolean sameSelection = classId.equals(currentClass)
                && countManager.getPlayerVariant(player.getUUID()).equals(currentVariant);
            String actualFactionId = countManager.getPlayerFaction(player.getUUID());
            String selectedVariantId = countManager.getPlayerVariant(player.getUUID());
            /*
             * 死亡/中途加入的等待部署状态只预留职业名额，不在高空旁观位发装。
             * 真正落地统一由 GameStateManager.onMidGameDeployComplete 发放一次。
             */
            boolean awaitingDeployment =
                BastionManager.getInstance().isWaitingForBastion(player.getUUID())
                    || player.isSpectator();
            // 已落地换职必发装；同职再点仅在背包被清空时补发，避免误点刷弹药。
            if (!awaitingDeployment
                && (!sameSelection || ClassEquipment.needsLoadout(player))) {
                ClassEquipment.equipPlayer(player, actualFactionId, classId, selectedVariantId);
            }

            // 部署入口统一在 Alt 轮盘，不再发放鱼竿/木棍
            org.espetro.team.TeamPackManager.getInstance().syncTeamPackItem(player);

            String team = countManager.getEffectivePlayerTeam(player.getUUID());
            String factionId = countManager.getPlayerFaction(player.getUUID());
            // Small count + squad packets replace the old full deploy-screen
            // fan-out (which repeated every ItemStack preview for every player).
            NetworkManager.broadcastClassCounts(team,
                factionId != null ? factionId : teamOrFaction);
            NetworkManager.syncSquadsToTeam(team);
        });
        ctx.get().setPacketHandled(true);
    }

    private static void denyOutOfRange(ServerPlayer player) {
        String message = ClassCountManager.messageFor(
            ClassCountManager.SelectionResult.OUT_OF_RANGE, player.getUUID());
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new ClassCountSyncPacket(message, true));
        player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
    }
}
