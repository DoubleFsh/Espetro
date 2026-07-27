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
    }

    // ==================== 事件处理方法 ====================

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

    private static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null) return;

        net.minecraft.client.KeyMapping radialKey =
            Espetro.KEY_RADIAL instanceof net.minecraft.client.KeyMapping key ? key : null;
        org.espetro.client.gui.AuraTipRadialController.tick(mc, radialKey);
        org.espetro.client.gui.TutorialOverlay.tick();
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
        // J键 - 请求职业选择 (在部署/战斗阶段允许)
        if (Espetro.KEY_CLASS != null && ((net.minecraft.client.KeyMapping) Espetro.KEY_CLASS).consumeClick()) {
            // 不允许快捷键覆盖当前阶段强制显示的选择/重部署界面。
            if (mc.screen == null
                && org.espetro.client.gui.ClientGameState.canOpenClassSelection()) {
                String playerTeam = org.espetro.client.gui.ClientGameState.getPlayerTeam();
                if (playerTeam == null) {
                    org.espetro.network.NetworkManager.requestGameState();
                } else {
                    String factionId = org.espetro.client.gui.ClientGameState.getPlayerFactionId();
                    org.espetro.network.NetworkManager.requestClassSelection(factionId);
                }
            }
        }
    }

    private static void onRenderOverlay(net.minecraftforge.client.event.RenderGuiOverlayEvent.Post event) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.level != null) {
            if (mc.screen == null) {
                org.espetro.client.gui.MutilHudOverlay.render(
                    event.getGuiGraphics(), mc, event.getPartialTick());
            }
            // 教程 HUD 始终叠在最上层（含预览 Screen 打开时）
            org.espetro.client.gui.TutorialHudOverlay.render(
                event.getGuiGraphics(), mc, event.getPartialTick());
        }
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
