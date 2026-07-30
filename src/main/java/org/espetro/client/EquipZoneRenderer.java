package org.espetro.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.espetro.client.gui.ClientGameState;
import org.espetro.network.EquipZoneSyncPacket;
import org.espetro.team.GamePhase;

/**
 * 同阵营换装范围黄色线框（仅本方原部署点）。
 */
public final class EquipZoneRenderer {

    private EquipZoneRenderer() {
    }

    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        String team = ClientGameState.getPlayerTeam();
        if (team == null || team.isBlank()) {
            return;
        }
        GamePhase phase = ClientGameState.getCurrentPhase();
        if (phase != GamePhase.DEPLOYING && phase != GamePhase.BATTLE
            && phase != GamePhase.FACTION_REVEAL) {
            // 部署/战斗外通常不需要换装框
            if (ClientEquipZones.getZones().isEmpty()) {
                return;
            }
        }
        var zones = ClientEquipZones.getZones();
        if (zones.isEmpty()) {
            return;
        }

        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        double camX = event.getCamera().getPosition().x;
        double camY = event.getCamera().getPosition().y;
        double camZ = event.getCamera().getPosition().z;

        // 金黄不透明线
        float r = 1.0f;
        float g = 0.85f;
        float b = 0.1f;
        float a = 1.0f;

        pose.pushPose();
        pose.translate(-camX, -camY, -camZ);
        for (EquipZoneSyncPacket.Zone zone : zones) {
            double range = zone.range() > 0 ? zone.range() : 6.0;
            // closerThan(center, range) 近似 AABB：中心 ± range（方块坐标语义）
            AABB box = new AABB(
                zone.x() - range, zone.y() - 0.05, zone.z() - range,
                zone.x() + range, zone.y() + range * 2.0, zone.z() + range);
            LevelRenderer.renderLineBox(pose, lines, box, r, g, b, a);
        }
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
