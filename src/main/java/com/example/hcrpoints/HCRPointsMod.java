package com.example.hcrpoints;

import com.example.hcrpoints.capturepoint.CapturePointManager;
import com.example.hcrpoints.command.HCRCommand;
import com.example.hcrpoints.config.ModConfig;
import com.example.hcrpoints.config.TacticalMapConfig;
import com.example.hcrpoints.network.NetworkHandler;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig.Type;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(HCRPointsMod.MOD_ID)
public class HCRPointsMod {
    public static final String MOD_ID = "hcrpoints";
    public static final Logger LOGGER = LogManager.getLogger();
    
    // 单例实例
    private static HCRPointsMod INSTANCE;
    
    // 客户端专用的HUD实例 - 仅在客户端初始化
    @OnlyIn(Dist.CLIENT)
    private static com.example.hcrpoints.hud.CapturePointHUD capturePointHUD;
    @OnlyIn(Dist.CLIENT)
    private static com.example.hcrpoints.hud.AreaInfoHUD areaInfoHUD;
    @OnlyIn(Dist.CLIENT)
    private static com.example.hcrpoints.hud.CurrentCapturePointHUD currentCapturePointHUD;
    @OnlyIn(Dist.CLIENT)
    private static com.example.hcrpoints.hud.ReinforcementsHUD reinforcementsHUD;
    @OnlyIn(Dist.CLIENT)
    private static com.example.hcrpoints.hud.MessagePopup messagePopup;

    public HCRPointsMod(FMLJavaModLoadingContext context) {
        INSTANCE = this;
        IEventBus modEventBus = context.getModEventBus();
        
        // 注册配置
        context.registerConfig(Type.COMMON, ModConfig.SPEC);
        context.registerConfig(Type.CLIENT, TacticalMapConfig.SPEC);
        
        // 注册mod事件
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::clientSetup);
        
        // 注册Forge事件
        MinecraftForge.EVENT_BUS.register(this);
        MinecraftForge.EVENT_BUS.register(CapturePointManager.getInstance());
    }
    
    /**
     * 客户端专用的事件监听器类
     */
    @OnlyIn(Dist.CLIENT)
    @net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = MOD_ID, value = Dist.CLIENT, bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD)
    private static class ClientModEvents {
        @SubscribeEvent
        public static void registerOverlays(final RegisterGuiOverlaysEvent event) {
            // 初始化HUD实例（如果还没有初始化）
            if (capturePointHUD == null) {
                capturePointHUD = new com.example.hcrpoints.hud.CapturePointHUD();
                areaInfoHUD = new com.example.hcrpoints.hud.AreaInfoHUD();
                currentCapturePointHUD = new com.example.hcrpoints.hud.CurrentCapturePointHUD();
                reinforcementsHUD = new com.example.hcrpoints.hud.ReinforcementsHUD();
                messagePopup = com.example.hcrpoints.hud.MessagePopup.getInstance();
            }
            
            // 注册HUD覆盖层
            event.registerBelowAll("capture_point_hud", capturePointHUD);
            event.registerBelowAll("area_info_hud", areaInfoHUD);
            event.registerBelowAll("current_capture_point_hud", currentCapturePointHUD);
            event.registerBelowAll("tactical_map_hud", com.example.hcrpoints.hud.TacticalMapHUD.getInstance());
            event.registerBelowAll("reinforcements_hud", reinforcementsHUD);
            event.registerBelowAll("message_popup", messagePopup);
        }
    }
    
    private void commonSetup(final FMLCommonSetupEvent event) {
        // 初始化网络处理器
        NetworkHandler.registerMessages();
        
        // 移除了游戏规则，改用命令系统控制地图显示玩家位置
    }
    
    private void clientSetup(final FMLClientSetupEvent event) {
        // 初始化客户端专用的HUD实例
        capturePointHUD = new com.example.hcrpoints.hud.CapturePointHUD();
        areaInfoHUD = new com.example.hcrpoints.hud.AreaInfoHUD();
        currentCapturePointHUD = new com.example.hcrpoints.hud.CurrentCapturePointHUD();
        reinforcementsHUD = new com.example.hcrpoints.hud.ReinforcementsHUD();
        messagePopup = com.example.hcrpoints.hud.MessagePopup.getInstance();
        
        // 客户端初始化
        LOGGER.info("HCR Points Mod客户端初始化完成！");
        
        // 生成教程文件
        com.example.hcrpoints.util.TutorialManager.generateTutorialFiles();
        
        // 注册配置界面
        registerConfigScreens();
    }
    
    /**
     * 注册配置界面
     */
    @OnlyIn(Dist.CLIENT)
    private void registerConfigScreens() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> {
                // 返回服务端配置界面
                return new com.example.hcrpoints.client.gui.ServerConfigScreen(parent);
            })
        );
    }
    
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        // 注册命令
        HCRCommand.register(event.getDispatcher());
    }
    
    /**
     * 获取服务器实例
     * @return Minecraft服务器实例
     */
    public static net.minecraft.server.MinecraftServer getServer() {
        // 通过ServerLifecycleHooks获取当前运行的服务器实例
        return net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
    }
    
    /**
     * 检查服务器是否正在运行
     * @return 如果服务器正在运行返回true，否则返回false
     */
    public static boolean isServerRunning() {
        return getServer() != null;
    }
    
    /**
     * 检查是否正在客户端运行
     * @return 如果在客户端运行返回true，否则返回false
     */
    public static boolean isClientRunning() {
        return net.minecraft.client.Minecraft.getInstance() != null;
    }
}