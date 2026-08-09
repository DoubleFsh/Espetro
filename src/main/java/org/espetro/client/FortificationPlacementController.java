package org.espetro.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.espetro.bastion.OnBuildingBlock;
import org.espetro.network.FortificationPlacementPacket;
import org.espetro.network.FortificationPreviewPacket;
import org.espetro.network.NetworkManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Local-only moving outline and bounded engineer-shovel input controller. */
public final class FortificationPlacementController {
    private static final double REACH = 6.0D;
    private static final int WORK_INTERVAL_TICKS = 5;
    private static Preview preview;
    private static BlockPos anchor;
    private static Direction facing = Direction.NORTH;
    private static List<AABB> boxes = List.of();
    private static boolean valid;
    private static long lastWorkTick = Long.MIN_VALUE / 2;

    private FortificationPlacementController() {
    }

    private record Preview(UUID token, String name, List<FortificationPreviewPacket.Offset> offsets) {
    }

    public static void begin(FortificationPreviewPacket packet) {
        preview = new Preview(packet.token(), packet.displayName(), packet.occupiedOffsets());
        anchor = null;
        boxes = List.of();
        valid = false;
    }

    public static void clear() {
        preview = null;
        anchor = null;
        boxes = List.of();
        valid = false;
    }

    public static boolean isPreviewing() {
        return preview != null;
    }

    public static void tick(Minecraft minecraft) {
        if (minecraft == null || minecraft.player == null || minecraft.level == null) {
            clear();
            return;
        }
        if (preview != null) {
            updateOutline(minecraft);
            return;
        }
        if (minecraft.screen != null
            || minecraft.player.getMainHandItem().getItem() != Items.IRON_SHOVEL) return;
        boolean build = minecraft.options.keyAttack.isDown();
        boolean remove = minecraft.options.keyUse.isDown();
        if (!build && !remove) return;
        long now = minecraft.level.getGameTime();
        if (now - lastWorkTick < WORK_INTERVAL_TICKS) return;
        HitResult hit = minecraft.hitResult;
        lastWorkTick = now;
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            NetworkManager.sendFortificationWork(blockHit.getBlockPos(), build && !remove);
        } else if (hit instanceof EntityHitResult entityHit
            && org.espetro.vehicle.VehicleManager.isMappedSupplyStation(entityHit.getEntity())) {
            NetworkManager.sendFortificationEntityWork(entityHit.getEntity().getUUID(), build && !remove);
        }
    }

    public static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.screen != null) return;
        if (preview != null) {
            if (event.isUseItem()) {
                NetworkManager.sendFortificationPlacement(FortificationPlacementPacket.Action.CANCEL,
                    preview.token, BlockPos.ZERO, Direction.NORTH);
                clear();
                event.setCanceled(true);
                event.setSwingHand(false);
                return;
            }
            if (!event.isAttack()) return;
            event.setCanceled(true);
            event.setSwingHand(false);
            if (!valid || anchor == null) {
                mc.player.displayClientMessage(Component.literal("§c红色范围无法放置工事。"), true);
                return;
            }
            NetworkManager.sendFortificationPlacement(FortificationPlacementPacket.Action.CONFIRM,
                preview.token, anchor, facing);
            clear();
            return;
        }
        if (mc.player.getMainHandItem().getItem() != Items.IRON_SHOVEL) return;
        if (mc.hitResult instanceof EntityHitResult entityHit
            && org.espetro.vehicle.VehicleManager.isMappedSupplyStation(entityHit.getEntity())) {
            event.setCanceled(true);
            event.setSwingHand(false);
            if (event.isAttack() || event.isUseItem()) {
                NetworkManager.sendFortificationEntityWork(entityHit.getEntity().getUUID(), event.isAttack());
                lastWorkTick = mc.level.getGameTime();
            }
            return;
        }
        if (!(mc.hitResult instanceof BlockHitResult hit) || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
        if (mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof OnBuildingBlock) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
        if (event.isAttack() || event.isUseItem()) {
            NetworkManager.sendFortificationWork(hit.getBlockPos(), event.isAttack());
            lastWorkTick = mc.level.getGameTime();
        }
    }

    private static void updateOutline(Minecraft mc) {
        Vec3 eye = mc.player.getEyePosition(1.0F);
        Vec3 end = eye.add(mc.player.getLookAngle().scale(REACH));
        BlockHitResult hit = mc.level.clip(new ClipContext(eye, end,
            ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc.player));
        if (hit.getType() == HitResult.Type.MISS) {
            anchor = null;
            boxes = List.of();
            valid = false;
            return;
        }
        anchor = mc.level.getBlockState(hit.getBlockPos()).is(Blocks.SNOW)
            ? hit.getBlockPos() : hit.getBlockPos().relative(hit.getDirection());
        facing = mc.player.getDirection();
        Direction right = facing.getClockWise();
        List<AABB> next = new ArrayList<>(preview.offsets.size());
        boolean clear = true;
        for (FortificationPreviewPacket.Offset offset : preview.offsets) {
            int dx = right.getStepX() * offset.x() + facing.getStepX() * offset.z();
            int dz = right.getStepZ() * offset.x() + facing.getStepZ() * offset.z();
            BlockPos pos = anchor.offset(dx, offset.y(), dz);
            next.add(new AABB(pos).inflate(0.002D));
            var state = mc.level.getBlockState(pos);
            if (!state.isAir() && !state.is(Blocks.SNOW)) clear = false;
            if (!mc.level.getEntities((Entity) null, new AABB(pos), entity -> entity != mc.player
                && entity instanceof LivingEntity && entity.isAlive()).isEmpty()) clear = false;
        }
        boxes = List.copyOf(next);
        valid = clear;
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS
            || preview == null || boxes.isEmpty()) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.options.hideGui) return;
        PoseStack pose = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        Vec3 camera = event.getCamera().getPosition();
        float red = valid ? 1.0F : 1.0F;
        float green = valid ? 0.85F : 0.15F;
        float blue = valid ? 0.1F : 0.12F;
        pose.pushPose();
        pose.translate(-camera.x, -camera.y, -camera.z);
        for (AABB box : boxes) LevelRenderer.renderLineBox(pose, lines, box, red, green, blue, 1.0F);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }
}
