package org.espetro;

/**
 * 客户端专属事件处理器
 * 所有客户端类引用均使用全限定名，不 import 到顶层避免服务端常量池解析
 * 
 * 通过 DistExecutor 程序化注册，不再用 @Mod.EventBusSubscriber 注解
 * （注解扫描会触发服务端类加载导致 DEDICATED_SERVER 错误）
 */
public class EspetroClient {

    /**
     * 客户端初始化 —— 由 Espetro 主类通过 DistExecutor 调用，仅在 CLIENT 侧执行
     */
    @SuppressWarnings({"unchecked", "removal"})
    public static void init() {
        // 注册 MOD 事件总线（快捷键注册）
        // FMLJavaModLoadingContext.get() 在 Forge 1.20+ 已过时，但 1.20.1 仍可用
        net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext.get().getModEventBus()
            .addListener(EspetroClient::registerKeyBindings);

        // 注册 FORGE 事件总线（Tick + HUD 渲染）
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onClientTick);
        net.minecraftforge.common.MinecraftForge.EVENT_BUS
            .addListener(EspetroClient::onRenderOverlay);
    }

    // ==================== 事件处理方法 ====================

    private static void registerKeyBindings(net.minecraftforge.client.event.RegisterKeyMappingsEvent event) {
        net.minecraft.client.KeyMapping keyTeam = new net.minecraft.client.KeyMapping(
            "key.espetro.team", 75, "key.categories.espetro");
        net.minecraft.client.KeyMapping keyClass = new net.minecraft.client.KeyMapping(
            "key.espetro.class", 74, "key.categories.espetro");
        event.register(keyTeam);
        event.register(keyClass);
        Espetro.KEY_TEAM = keyTeam;
        Espetro.KEY_CLASS = keyClass;
    }

    private static void onClientTick(net.minecraftforge.event.TickEvent.ClientTickEvent event) {
        if (event.phase != net.minecraftforge.event.TickEvent.Phase.END) return;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.player == null) return;

        // K键 - 请求游戏状态后打开对应界面（不直接打开，先请求服务端）
        if (Espetro.KEY_TEAM != null && ((net.minecraft.client.KeyMapping) Espetro.KEY_TEAM).consumeClick()) {
            // 发送请求到服务端，服务端会返回 GameStateResponsePacket
            // 客户端在收到响应后根据状态决定是否打开界面
            org.espetro.network.NetworkManager.requestGameState();
        }
        // J键 - 请求职业选择 (在部署/战斗阶段允许)
        if (Espetro.KEY_CLASS != null && ((net.minecraft.client.KeyMapping) Espetro.KEY_CLASS).consumeClick()) {
            if (org.espetro.client.gui.ClientGameState.canOpenClassSelection()) {
                String playerTeam = org.espetro.client.gui.ClientGameState.getPlayerTeam();
                if (playerTeam == null) {
                    // 未选择队伍，请求游戏状态
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
        if (mc != null && mc.screen == null && mc.level != null) {
            org.espetro.client.gui.TroopCountOverlay.render(event.getGuiGraphics(), mc);
        }
    }
}
