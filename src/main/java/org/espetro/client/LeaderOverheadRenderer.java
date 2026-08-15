package org.espetro.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.espetro.client.gui.ClientGameState;
import org.espetro.client.gui.ClientTacticalState;
import org.joml.Matrix4f;

/**
 * 头顶标识渲染器（指挥官/队长/火力组长）。
 * 仿 vanilla EntityRenderer.renderNameTag 的 PoseStack 推栈方式，
 * 修复此前自建 Matrix4f 导致的位移/旋转顺序错误。
 */
public final class LeaderOverheadRenderer {

    /** 原标识半宽为 0.28；PNG 画框按需求缩小为原来的 1/2。 */
    private static final float HALF = 0.14f;
    private static final float NUMBER_MAX_SCALE = 0.018f;
    private static final float NUMBER_MAX_WIDTH = HALF * 1.45f;
    private static final float NUMBER_FORWARD_OFFSET = 0.02f;

    private static final ResourceLocation COMMANDER_TEX = texture("commander.png");
    private static final ResourceLocation SQUAD_LEADER_TEX = texture("squad_leader.png");
    private static final ResourceLocation SELF_SQUAD_LEADER_TEX = texture("self_squad_leader.png");
    private static final ResourceLocation FIRETEAM_B_TEX = texture("fireteam_b.png");
    private static final ResourceLocation FIRETEAM_C_TEX = texture("fireteam_c.png");

    private static final double R_CMD = 200.0;
    private static final double R_SL_OTHER = 50.0;
    private static final double R_FT = 50.0;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !ClientGameState.getCurrentPhase().isMatchActive()) return;
        if (mc.options.hideGui || mc.player.isSpectator()) return;

        PoseStack ps = event.getPoseStack();
        Camera camera = event.getCamera();
        MultiBufferSource.BufferSource buf = mc.renderBuffers().bufferSource();
        float pt = event.getPartialTick();

        java.util.Map<Integer, VehicleEntry> veh = new java.util.HashMap<>();

        for (Player p : mc.level.players()) {
            if (p == mc.player || p.isSpectator() || p.isInvisible() || !p.isAlive()) continue;
            OverheadInfo info = resolve(p);
            if (info == null) continue;
            double distSq = camera.getPosition().distanceToSqr(p.getX(), p.getY(), p.getZ());
            if (!inRange(info, distSq)) continue;

            Entity rv = p.getVehicle();
            if (rv != null) {
                int vid = rv.getId();
                VehicleEntry e = veh.get(vid);
                int nr = info.rankOrdinal();
                if (e == null || nr < e.rank || (nr == e.rank && info.squadId < e.info.squadId))
                    veh.put(vid, new VehicleEntry(info, nr));
            } else {
                render(ps, buf, p, pt, info, camera);
            }
        }
        for (var e : veh.entrySet()) {
            Entity v = mc.level.getEntity(e.getKey());
            if (v != null) render(ps, buf, v, pt, e.getValue().info, camera);
        }
        buf.endBatch();
    }

    private static OverheadInfo resolve(Player p) {
        String n = p.getName().getString();
        ClientTacticalState.MarkerInfo m = ClientTacticalState.getMarker(n);
        if (m == null) return null;
        boolean cmd = m.commander() || ClientTacticalState.isCommander(n);
        int sid = m.squadId();
        int displayId = m.displayId();
        byte ft = m.fireteam();
        if (cmd) return new OverheadInfo(sid, displayId, ft, T.COMMANDER);
        if (m.leader() && sid > 0) return new OverheadInfo(sid, displayId, ft, T.SQUAD_LEADER);
        if (m.fireteamLeader() && (ft == 1 || ft == 2)) return new OverheadInfo(sid, displayId, ft, T.FIRETEAM_LEADER);
        return null;
    }

    private static boolean inRange(OverheadInfo info, double distSq) {
        return switch (info.type) {
            case COMMANDER -> distSq <= R_CMD * R_CMD;
            case SQUAD_LEADER -> {
                if (info.squadId == ClientTacticalState.getMySquadId()) yield true;
                yield distSq <= R_SL_OTHER * R_SL_OTHER;
            }
            case FIRETEAM_LEADER -> {
                if (info.squadId != ClientTacticalState.getMySquadId()) yield false;
                if (ClientTacticalState.isLocalSquadLeader(Minecraft.getInstance().player.getName().getString())) yield true;
                if (info.fireteam != ClientTacticalState.getMyFireteam()) yield false;
                yield distSq <= R_FT * R_FT;
            }
        };
    }

    /** 仿 vanilla renderNameTag：push → translate → mulPose(cameraOrientation) → scale → draw → pop */
    private static void render(PoseStack ps, MultiBufferSource.BufferSource buf,
                               Entity entity, float pt, OverheadInfo info, Camera camera) {
        double x = entity.xo + (entity.getX() - entity.xo) * pt;
        double y = entity.yo + (entity.getY() - entity.yo) * pt + entity.getBbHeight() + 1.0;
        double z = entity.zo + (entity.getZ() - entity.zo) * pt;

        ResourceLocation icon = textureFor(info);
        ps.pushPose();
        ps.translate(x - camera.getPosition().x, y - camera.getPosition().y, z - camera.getPosition().z);
        ps.mulPose(camera.rotation());

        // == PNG 画框 ==
        ps.pushPose();
        // 保持正向缩放以维持顶点绕序；通过 UV 水平翻转修正镜像，避免被背面剔除。
        RenderType iconLayer = RenderType.textSeeThrough(icon);
        ps.scale(HALF, HALF, 1f);
        quadFlippedX(buf.getBuffer(iconLayer), ps.last().pose(), 0xFFFFFFFF);
        ps.popPose();
        // 先提交不透明图标，再把数字写入字体缓冲，确保数字最终覆盖在图标上。
        buf.endBatch(iconLayer);

        // == 小队编号：居中叠在队长画框前方，按位数缩放且始终略小于画框 ==
        if (info.type == T.SQUAD_LEADER) {
            renderSquadNumber(ps, buf, info.displayId);
        }
        ps.popPose();
    }

    private static ResourceLocation textureFor(OverheadInfo info) {
        return switch (info.type) {
            case COMMANDER -> COMMANDER_TEX;
            case SQUAD_LEADER -> info.squadId == ClientTacticalState.getMySquadId()
                ? SELF_SQUAD_LEADER_TEX : SQUAD_LEADER_TEX;
            case FIRETEAM_LEADER -> info.fireteam == 1 ? FIRETEAM_B_TEX : FIRETEAM_C_TEX;
        };
    }

    private static void renderSquadNumber(PoseStack ps, MultiBufferSource.BufferSource buf, int squadId) {
        String label = String.valueOf(squadId);
        var font = Minecraft.getInstance().font;
        int textWidth = Math.max(1, font.width(label));
        float scale = Math.min(NUMBER_MAX_SCALE, NUMBER_MAX_WIDTH / textWidth);

        ps.pushPose();
        ps.translate(0f, 0f, NUMBER_FORWARD_OFFSET);
        // 字体坐标 X/Y 都要反转，和 vanilla 名牌渲染保持一致。
        ps.scale(-scale, -scale, scale);
        font.drawInBatch(label,
            -textWidth / 2f,
            -font.lineHeight / 2f,
            0xFFFFFFFF, false, ps.last().pose(), buf,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        ps.popPose();
    }

    private static void quadFlippedX(VertexConsumer vc, Matrix4f m, int rgba) {
        int r = (rgba >> 16) & 0xFF, g = (rgba >> 8) & 0xFF, b = rgba & 0xFF, a = (rgba >> 24) & 0xFF;
        int light = 0xF000F0;
        vc.vertex(m, -1f,  1f, 0f).color(r, g, b, a).uv(1, 0).uv2(light).endVertex();
        vc.vertex(m,  1f,  1f, 0f).color(r, g, b, a).uv(0, 0).uv2(light).endVertex();
        vc.vertex(m,  1f, -1f, 0f).color(r, g, b, a).uv(0, 1).uv2(light).endVertex();
        vc.vertex(m, -1f, -1f, 0f).color(r, g, b, a).uv(1, 1).uv2(light).endVertex();
    }

    private static ResourceLocation texture(String fileName) {
        return ResourceLocation.fromNamespaceAndPath(
            "espetro", "textures/gui/overhead/" + fileName);
    }

    private enum T { COMMANDER, SQUAD_LEADER, FIRETEAM_LEADER }

    private static class OverheadInfo {
        final int squadId; final int displayId; final byte fireteam; final T type;
        OverheadInfo(int s, int d, byte f, T t) { squadId = s; displayId = d; fireteam = f; type = t; }
        int rankOrdinal() { return type == T.COMMANDER ? 0 : type == T.SQUAD_LEADER ? 1 : 2; }
    }

    private static class VehicleEntry {
        final OverheadInfo info; final int rank;
        VehicleEntry(OverheadInfo i, int r) { info = i; rank = r; }
    }
}
