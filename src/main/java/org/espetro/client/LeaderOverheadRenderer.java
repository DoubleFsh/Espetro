package org.espetro.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
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

    private static final int TEX_SIZE = 32;
    private static final float HALF = 0.28f;

    private static ResourceLocation CIRCLE_TEX;
    private static ResourceLocation STAR_TEX;

    private static final int CC = 0xFFFFD700;    // 指挥官金黄
    private static final int SC_OWN = 0xFF00FF00; // 己队绿
    private static final int SC_OTHER = 0xFF4488FF; // 他队蓝
    private static final int FT_B = 0xFFB06CFF;    // B 组紫
    private static final int FT_C = 0xFF2EE6D6;    // C 组青

    private static final double R_CMD = 200.0;
    private static final double R_SL_OTHER = 50.0;
    private static final double R_FT = 50.0;

    public static void onRenderLevelStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !ClientGameState.getCurrentPhase().isMatchActive()) return;
        if (mc.options.hideGui || mc.player.isSpectator()) return;

        ensureTextures(mc);

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
        byte ft = m.fireteam();
        if (cmd) return new OverheadInfo(sid, ft, T.COMMANDER);
        if (m.leader() && sid > 0) return new OverheadInfo(sid, ft, T.SQUAD_LEADER);
        if (m.fireteamLeader() && (ft == 1 || ft == 2)) return new OverheadInfo(sid, ft, T.FIRETEAM_LEADER);
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

        int color = switch (info.type) {
            case COMMANDER -> CC;
            case SQUAD_LEADER -> info.squadId == ClientTacticalState.getMySquadId() ? SC_OWN : SC_OTHER;
            case FIRETEAM_LEADER -> info.fireteam == 1 ? FT_B : FT_C;
        };

        // == 圆形 ==
        ps.pushPose();
        ps.translate(x - camera.getPosition().x, y - camera.getPosition().y, z - camera.getPosition().z);
        ps.mulPose(camera.rotation());
        ps.scale(HALF, HALF, 1f);
        quad(buf.getBuffer(RenderType.textSeeThrough(CIRCLE_TEX)), ps.last().pose(), color);

        // == 指挥官星 ==
        if (info.type == T.COMMANDER) {
            ps.pushPose();
            ps.translate(0f, 0f, 0.002f);
            ps.scale(0.55f, 0.55f, 1f);
            quad(buf.getBuffer(RenderType.textSeeThrough(STAR_TEX)), ps.last().pose(), 0xFFFFFFFF);
            ps.popPose();
        }
        ps.popPose();

        // == 文字标签 ==
        String label = switch (info.type) {
            case COMMANDER -> "\u2605";
            case SQUAD_LEADER -> String.valueOf(info.squadId);
            case FIRETEAM_LEADER -> info.fireteam == 1 ? "B" : "C";
        };
        float ly = (float)(y + 0.4 - camera.getPosition().y);
        ps.pushPose();
        ps.translate(x - camera.getPosition().x, ly, z - camera.getPosition().z);
        ps.mulPose(camera.rotation());
        ps.scale(0.025f, -0.025f, 0.025f);
        Matrix4f tm = ps.last().pose();
        Minecraft.getInstance().font.drawInBatch(label,
            -Minecraft.getInstance().font.width(label) / 2f,
            -Minecraft.getInstance().font.lineHeight / 2f,
            0xFFFFFFFF, false, tm, buf,
            net.minecraft.client.gui.Font.DisplayMode.SEE_THROUGH, 0, 0xF000F0);
        ps.popPose();
    }

    private static void quad(VertexConsumer vc, Matrix4f m, int rgba) {
        int r = (rgba >> 16) & 0xFF, g = (rgba >> 8) & 0xFF, b = rgba & 0xFF, a = (rgba >> 24) & 0xFF;
        int light = 0xF000F0;
        vc.vertex(m, -1f,  1f, 0f).color(r, g, b, a).uv(0, 0).uv2(light).endVertex();
        vc.vertex(m,  1f,  1f, 0f).color(r, g, b, a).uv(1, 0).uv2(light).endVertex();
        vc.vertex(m,  1f, -1f, 0f).color(r, g, b, a).uv(1, 1).uv2(light).endVertex();
        vc.vertex(m, -1f, -1f, 0f).color(r, g, b, a).uv(0, 1).uv2(light).endVertex();
    }

    // ==== 纹理 ====

    private static void ensureTextures(Minecraft mc) {
        if (CIRCLE_TEX == null) CIRCLE_TEX = circleTex(mc);
        if (STAR_TEX == null) STAR_TEX = starTex(mc);
    }

    private static ResourceLocation circleTex(Minecraft mc) {
        NativeImage img = new NativeImage(TEX_SIZE, TEX_SIZE, false);
        float cx = (TEX_SIZE - 1) / 2f, cy = cx, r = cx - 1;
        for (int y = 0; y < TEX_SIZE; y++)
            for (int x = 0; x < TEX_SIZE; x++)
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r)
                    img.setPixelRGBA(x, y, 0xFFFFFFFF);
        DynamicTexture t = new DynamicTexture(img);
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("espetro", "leader_circle");
        mc.getTextureManager().register(rl, t);
        return rl;
    }

    private static ResourceLocation starTex(Minecraft mc) {
        NativeImage img = new NativeImage(TEX_SIZE, TEX_SIZE, false);
        float cx = TEX_SIZE / 2f, cy = cx, or = TEX_SIZE * 0.40f, ir = or * 0.38f;
        int[] px = new int[10], py = new int[10];
        for (int i = 0; i < 10; i++) {
            double a = Math.toRadians(-90 + i * 36), r = (i % 2 == 0) ? or : ir;
            px[i] = (int)(cx + r * Math.cos(a)); py[i] = (int)(cy + r * Math.sin(a));
        }
        int minY = TEX_SIZE, maxY = 0;
        for (int i = 0; i < 10; i++) { minY = Math.min(minY, py[i]); maxY = Math.max(maxY, py[i]); }
        for (int sy = minY; sy <= maxY; sy++) {
            java.util.List<Integer> xs = new java.util.ArrayList<>();
            for (int i = 0; i < 10; i++) {
                int j = (i + 1) % 10, y0 = py[i], y1 = py[j];
                if ((y0 <= sy && y1 > sy) || (y1 <= sy && y0 > sy))
                    xs.add(px[i] + (sy - y0) * (px[j] - px[i]) / (y1 - y0));
            }
            xs.sort(Integer::compareTo);
            for (int k = 0; k + 1 < xs.size(); k += 2)
                for (int fx = Math.max(0, Math.min(TEX_SIZE - 1, xs.get(k)));
                     fx <= Math.max(0, Math.min(TEX_SIZE - 1, xs.get(k + 1))); fx++)
                    img.setPixelRGBA(fx, sy, 0xFF000000);
        }
        DynamicTexture t = new DynamicTexture(img);
        ResourceLocation rl = ResourceLocation.fromNamespaceAndPath("espetro", "leader_star");
        mc.getTextureManager().register(rl, t);
        return rl;
    }

    private enum T { COMMANDER, SQUAD_LEADER, FIRETEAM_LEADER }

    private static class OverheadInfo {
        final int squadId; final byte fireteam; final T type;
        OverheadInfo(int s, byte f, T t) { squadId = s; fireteam = f; type = t; }
        int rankOrdinal() { return type == T.COMMANDER ? 0 : type == T.SQUAD_LEADER ? 1 : 2; }
    }

    private static class VehicleEntry {
        final OverheadInfo info; final int rank;
        VehicleEntry(OverheadInfo i, int r) { info = i; rank = r; }
    }
}
