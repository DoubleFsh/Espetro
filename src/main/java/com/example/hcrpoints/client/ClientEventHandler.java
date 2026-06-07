package com.example.hcrpoints.client;

import com.example.hcrpoints.client.gui.CapturePointDetailsScreen;
import com.example.hcrpoints.hud.TacticalMapHUD;
import com.example.hcrpoints.util.ModLogger;
import com.example.hcrpoints.client.gui.MDRenderScreen;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import com.example.hcrpoints.HCRPointsMod;
import org.lwjgl.glfw.GLFW;

@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = HCRPointsMod.MOD_ID, value = Dist.CLIENT)
public class ClientEventHandler {
    private static final String KEY_CATEGORY = "key.category.hcrpoints";
    private static final String KEY_OPEN_GUI = "key.hcrpoints.open_gui";
    private static final String KEY_TACTICAL_MAP = "key.hcrpoints.tactical_map";
    private static final String KEY_MAP_CONFIG = "key.hcrpoints.map_config";
    private static final String KEY_OPEN_MD_READER = "key.hcrpoints.open_md_reader";
    
    public static final KeyMapping OPEN_GUI_KEY = new KeyMapping(
        KEY_OPEN_GUI,
        GLFW.GLFW_KEY_O,
        KEY_CATEGORY
    );
    
    public static final KeyMapping TACTICAL_MAP_KEY = new KeyMapping(
        KEY_TACTICAL_MAP,
        GLFW.GLFW_KEY_V,
        KEY_CATEGORY
    );
    
    // 添加新的按键绑定：X键 打开地图配置界面
    public static final KeyMapping MAP_CONFIG_KEY = new KeyMapping(
        KEY_MAP_CONFIG,
        GLFW.GLFW_KEY_X,
        KEY_CATEGORY
    );
    
    // 添加新的按键绑定：无默认键 打开MD文件阅读器
    public static final KeyMapping OPEN_MD_READER_KEY = new KeyMapping(
        KEY_OPEN_MD_READER,
        GLFW.GLFW_KEY_UNKNOWN,
        KEY_CATEGORY
    );
    
    private static boolean wasGuiKeyPressed = false;
    private static boolean wasTacticalMapKeyPressed = false;
    private static boolean wasMapRangeIncreasePressed = false;
    private static boolean wasMapRangeDecreasePressed = false;
    private static boolean wasMapConfigKeyPressed = false;
    private static boolean wasMdReaderKeyPressed = false;
    
    /**
     * 客户端初始化事件，用于创建音频文件夹
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 创建fightBGM文件夹
        AudioManager.getAudioFilePath();
        ModLogger.info("已检查并确保fightBGM文件夹存在");
    }
    
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            Minecraft mc = Minecraft.getInstance();
            
            // 处理打开GUI按键
            boolean isGuiKeyPressed = OPEN_GUI_KEY.isDown();
            if (isGuiKeyPressed && !wasGuiKeyPressed && mc.player != null) {
                mc.setScreen(new CapturePointDetailsScreen());
            }
            wasGuiKeyPressed = isGuiKeyPressed;
            
            // 处理战术地图按键
            boolean isTacticalMapPressed = TACTICAL_MAP_KEY.isDown();
            if (isTacticalMapPressed && !wasTacticalMapKeyPressed && mc.player != null) {
                TacticalMapHUD.getInstance().toggleMapVisibility();
            }
            wasTacticalMapKeyPressed = isTacticalMapPressed;

            if (mc.player != null && mc.screen == null && TacticalMapHUD.getInstance().isMapVisible()) {
                long window = mc.getWindow().getWindow();
                
                boolean isMapRangeIncreasePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
                if (isMapRangeIncreasePressed && !wasMapRangeIncreasePressed) {
                    TacticalMapHUD.getInstance().increaseRenderRange();
                }
                wasMapRangeIncreasePressed = isMapRangeIncreasePressed;
                
                boolean isMapRangeDecreasePressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_B) == GLFW.GLFW_PRESS;
                if (isMapRangeDecreasePressed && !wasMapRangeDecreasePressed) {
                    TacticalMapHUD.getInstance().decreaseRenderRange();
                }
                wasMapRangeDecreasePressed = isMapRangeDecreasePressed;
            } else {
                wasMapRangeIncreasePressed = false;
                wasMapRangeDecreasePressed = false;
            }
            
            // 处理地图配置界面按键（X键）
            boolean isMapConfigPressed = MAP_CONFIG_KEY.isDown();
            if (isMapConfigPressed && !wasMapConfigKeyPressed && mc.player != null) {
                mc.setScreen(new com.example.hcrpoints.client.gui.TacticalMapConfigScreen(mc.screen));
            }
            wasMapConfigKeyPressed = isMapConfigPressed;
            
            // 处理MD文件阅读器按键
            boolean isMdReaderPressed = OPEN_MD_READER_KEY.isDown();
            if (isMdReaderPressed && !wasMdReaderKeyPressed && mc.player != null) {
                mc.setScreen(new MDRenderScreen());
            }
            wasMdReaderKeyPressed = isMdReaderPressed;
        }
    }
}
