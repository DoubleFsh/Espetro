package org.espetro;

/**
 * 客户端专属事件处理器
 * 所有客户端类引用均使用全限定名，不 import 到顶层避免服务端常量池解析
 * 
 * 通过 DistExecutor 程序化注册，不再用 @Mod.EventBusSubscriber 注解
 * （注解扫描会触发服务端类加载导致 DEDICATED_SERVER 错误）
 */
public class EspetroClient {
    private static boolean tutorialExitMouseWasDown;

    /**
     * 客户端初始化 —— 由 Espetro 主类通过 DistExecutor 调用，仅在 CLIENT 侧执行
     */
    @SuppressWarnings({"unchecked", "removal"})
    public static void init() {
        // 注册 MOD 事件总线（快捷键注册）
        // FMLJavaModLoadingContext.get() 在 Forge 1.20+ 已过时，但 1.20.1 仍可用
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
            .addListener(EspetroClient::registerKeyBindings);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
            .addListener(EspetroClient::registerReloadListeners);
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
            .addListener(EspetroClient::onClientSetup);

        // 注册 FORGE 事件总线（Tick + HUD 渲染 + 隐藏玩家名字）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onClientTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.gui.VanillaHudLayout::onClientTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.gui.VanillaHudLayout::onRenderOverlayPre);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onRenderOverlay);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onRenderNameTag);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onLivingJump);

        org.espetro.client.gui.AuraTipRadialController.initialize();
        org.espetro.client.gui.RadioRadialController.initialize();
        org.espetro.client.gui.VehicleWheelController.initialize();
        org.espetro.client.gui.ResupplyRadialController.initialize();
        org.espetro.client.gui.VehicleSupplyHud.register();
        org.espetro.client.gui.FobSupplyHud.register();
        org.espetro.client.gui.SeatSwitchHandler.register();
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.gui.AuraTipAboveScreen::onScreenRenderPost);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.EquipZoneRenderer::onRenderLevel);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onRightClickBlock);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.LeaderOverheadRenderer::onRenderLevelStage);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.FortificationPlacementController::onInteraction);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(org.espetro.client.FortificationPlacementController::render);
    }

    // ==================== 事件处理方法 ====================

    @SuppressWarnings("deprecation")
    private static void onClientSetup(net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent event) {
        event.enqueueWork(() -> net.minecraft.client.renderer.ItemBlockRenderTypes.setRenderLayer(
            org.espetro.bastion.BastionItems.ON_BUILDING_BLOCK,
            net.minecraft.client.renderer.RenderType.cutout()));
    }

    private static void registerKeyBindings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        net.minecraft.client.KeyMapping keyTeam = new net.minecraft.client.KeyMapping(
            "key.espetro.team", 75, "key.categories.espetro");
        net.minecraft.client.KeyMapping keyClass = new net.minecraft.client.KeyMapping(
            "key.espetro.class", 74, "key.categories.espetro");
        net.minecraft.client.KeyMapping keyRadial = new net.minecraft.client.KeyMapping(
            "key.espetro.radial", org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_ALT, "key.categories.espetro");
        event.register(keyTeam);
        event.register(keyClass);
        event.register(keyRadial);
        Espetro.KEY_TEAM = keyTeam;
        Espetro.KEY_CLASS = keyClass;
        Espetro.KEY_RADIAL = keyRadial;
    }

    private static void registerReloadListeners(
            net.minecraftforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((net.minecraft.server.packs.resources.ResourceManagerReloadListener)
            resourceManager -> org.espetro.client.gui.FobSupplyHud.onResourceReload());
    }

    private static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) return;

        net.minecraft.client.KeyMapping radialKey =
            Espetro.KEY_RADIAL instanceof net.minecraft.client.KeyMapping key ? key : null;
        org.espetro.client.gui.AuraTipRadialController.tick(mc, radialKey);
        org.espetro.client.gui.RadioRadialController.tick(mc);
        org.espetro.client.gui.VehicleWheelController.tick(mc);
        org.espetro.client.gui.ResupplyRadialController.tick();
        org.espetro.client.FortificationPlacementController.tick(mc);
        org.espetro.client.gui.TutorialOverlay.tick();
        org.espetro.client.audio.ClientFormationAudioManager.tick(mc);
        // 无 Screen 时左下「退出教程」点击（有 Screen 时由 MutilScreen 处理）
        if (org.espetro.client.gui.TutorialClientController.isActive() && mc.screen == null) {
            boolean down = org.lwjgl.glfw.GLFW.glfwGetMouseButton(
                mc.getWindow().getWindow(), org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT)
                == org.lwjgl.glfw.GLFW.GLFW_PRESS;
            if (down && !tutorialExitMouseWasDown) {
                double scale = mc.getWindow().getGuiScale();
                double mx = mc.mouseHandler.xpos() / scale;
                double my = mc.mouseHandler.ypos() / scale;
                org.espetro.client.gui.TutorialHudOverlay.mouseClicked(mx, my, 0);
            }
            tutorialExitMouseWasDown = down;
        } else {
            tutorialExitMouseWasDown = false;
        }

        if (mc.player == null) return;

        // 客户端立即抑制耗尽后的奔跑，服务端仍会执行权威校验。
        if (org.espetro.client.gui.StaminaOverlay.isExhausted()) {
            mc.player.setSprinting(false);
            mc.options.keySprint.setDown(false);
        }

        // K键 - 请求游戏状态后打开对应界面（不直接打开，先请求服务端）
        if (Espetro.KEY_TEAM != null && ((net.minecraft.client.KeyMapping) Espetro.KEY_TEAM).consumeClick()) {
            if (mc.screen == null) {
                // 发送请求到服务端，服务端会返回 GameStateResponsePacket
                // 客户端在收到响应后根据状态决定是否打开界面
                org.espetro.network.NetworkManager.requestGameState();
            }
        }
        // J键 - 主城打开组队面板，对战中请求职业选择
        if (Espetro.KEY_CLASS != null && ((net.minecraft.client.KeyMapping) Espetro.KEY_CLASS).consumeClick()) {
            if (mc.screen != null) return;
            org.espetro.client.gui.ClientGameState.tryOpenJKeyScreen();
        }
    }

    /** 右键己方 Radio：更换职业已移至弹药箱，此处不再打开轮盘。 */
    private static void onRightClickBlock(net.minecraftforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        if (!event.getLevel().isClientSide()) {
            return;
        }
        net.minecraft.world.level.block.state.BlockState state = event.getLevel().getBlockState(event.getPos());
        if (!(state.getBlock() instanceof org.espetro.bastion.RadioBlock)) {
            return;
        }
        if (org.espetro.client.FortificationPlacementController.isPreviewing()
            || event.getEntity().getMainHandItem().getItem()
                == net.minecraft.world.item.Items.IRON_SHOVEL) {
            return;
        }
    }

    private static void onRenderOverlay(net.minecraftforge.client.event.RenderGuiEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.level == null) {
            return;
        }
        if (mc.screen == null) {
            org.espetro.client.gui.MutilHudOverlay.render(
                event.getGuiGraphics(), mc, event.getPartialTick());
        }
        org.espetro.client.gui.TutorialHudOverlay.render(
            event.getGuiGraphics(), mc, event.getPartialTick());
    }

    private static void onLivingJump(
            net.minecraftforge.event.entity.living.LivingEvent.LivingJumpEvent event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.player == null || event.getEntity() != mc.player
                || !org.espetro.client.gui.StaminaOverlay.isEnabled()) {
            return;
        }

        org.espetro.network.NetworkManager.sendStaminaJump();
    }

    /**
     * 主城显示全员白色名牌；对战阶段按战术规则仅显示队友名牌。
     */
    private static void onRenderNameTag(net.minecraftforge.client.event.RenderNameTagEvent event) {
        org.espetro.client.TeammateNameTagRenderer.onRenderNameTag(event);
    }
}
