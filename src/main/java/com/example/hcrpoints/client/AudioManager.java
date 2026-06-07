package com.example.hcrpoints.client;

import com.example.hcrpoints.util.ModLogger;
import net.minecraft.client.Minecraft;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 音频管理器类
 * 用于处理背水一战背景音乐的播放和停止
 */
@OnlyIn(Dist.CLIENT)
@Mod.EventBusSubscriber(modid = "hcrpoints", value = Dist.CLIENT)
public class AudioManager {
    // 单例实例
    private static AudioManager INSTANCE;
    
    // 音频文件路径
    private static final String AUDIO_FOLDER_NAME = "fightBGM";
    
    // 支持的音频格式
    private static final String[] SUPPORTED_FORMATS = {"wav", "mp3", "ogg", "flac"};
    
    // 音频播放状态
    private boolean isAudioPlaying = false;
    
    // 音频播放实例
    private Clip audioClip;
    
    // 随机数生成器
    private Random random;
    
    // 上次播放请求状态
    private boolean lastPlayRequest = false;
    
    /**
     * 私有构造函数，防止外部实例化
     */
    private AudioManager() {
        // 初始化随机数生成器
        random = new Random();
        ModLogger.info("AudioManager已初始化");
    }
    
    /**
     * 获取单例实例
     * @return 音频管理器实例
     */
    public static AudioManager getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AudioManager();
        }
        return INSTANCE;
    }
    
    /**
     * 处理背水一战音频播放请求
     * @param playAudio 是否播放音频
     */
    public void handleLowReinforcementAudio(boolean playAudio) {
        ModLogger.info("收到音频播放请求: " + playAudio + ", 当前播放状态: " + isAudioPlaying);
        
        // 检查行动是否正在运行
        boolean isOperationRunning = isOperationModeRunning();
        ModLogger.info("当前行动状态: " + isOperationRunning);
        
        // 只有当行动正在运行时，才响应自动播放请求
        if (isOperationRunning) {
            lastPlayRequest = playAudio;
            
            // 检查游戏状态，决定是否播放音频
            if (playAudio) {
                if (isGameWorldLoaded()) {
                    startAudio();
                } else {
                    ModLogger.info("游戏世界未加载，跳过音频播放");
                }
            } else {
                stopAudio();
            }
        } else {
            // 行动未运行，停止音频
            ModLogger.info("行动未运行，自动停止音频");
            lastPlayRequest = false;
            stopAudio();
        }
    }
    
    /**
     * 检查行动模式是否正在运行
     * @return 行动模式是否正在运行
     */
    private boolean isOperationModeRunning() {
        try {
            // 通过反射获取CapturePointManager的operationModeRunning状态
            Class<?> capturePointManagerClass = Class.forName("com.example.hcrpoints.capturepoint.CapturePointManager");
            Method getInstanceMethod = capturePointManagerClass.getMethod("getInstance");
            Object instance = getInstanceMethod.invoke(null);
            
            // 检查行动是否正在运行
            Field operationModeRunningField = capturePointManagerClass.getDeclaredField("operationModeRunning");
            operationModeRunningField.setAccessible(true);
            return operationModeRunningField.getBoolean(instance);
        } catch (Exception e) {
            ModLogger.error("获取行动状态时发生异常: " + e.getMessage());
            // 发生异常时，默认认为行动未运行
            return false;
        }
    }
    
    /**
     * 检查游戏世界是否加载（不是标题界面）
     * @return 游戏世界是否加载
     */
    private boolean isGameWorldLoaded() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level != null && minecraft.player != null;
    }
    
    /**
     * 开始播放音频
     */
    private void startAudio() {
        if (isAudioPlaying) {
            ModLogger.info("音频已经在播放，跳过启动");
            return;
        }
        
        try {
            // 再次检查游戏世界是否加载
            if (!isGameWorldLoaded()) {
                ModLogger.info("游戏世界未加载，无法播放音频");
                return;
            }
            
            // 获取音频文件夹
            File audioFolder = getAudioFolder();
            ModLogger.info("音频文件夹路径: " + audioFolder.getAbsolutePath());
            ModLogger.info("音频文件夹是否存在: " + audioFolder.exists());
            
            // 获取所有支持的音频文件
            List<File> audioFiles = getAudioFiles(audioFolder);
            ModLogger.info("找到音频文件数量: " + audioFiles.size());
            
            if (audioFiles.isEmpty()) {
                ModLogger.error("音频文件夹中没有找到支持的音频文件！");
                ModLogger.error("支持的格式: " + String.join(", ", SUPPORTED_FORMATS));
                ModLogger.error("音频文件夹路径: " + audioFolder.getAbsolutePath());
                return;
            }
            
            // 随机选择一个音频文件
            File selectedAudio = audioFiles.get(random.nextInt(audioFiles.size()));
            ModLogger.info("随机选择的音频文件: " + selectedAudio.getName());
            ModLogger.info("音频文件路径: " + selectedAudio.getAbsolutePath());
            ModLogger.info("音频文件大小: " + selectedAudio.length() + " 字节");
            
            // 创建音频输入流
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(selectedAudio);
            ModLogger.info("成功创建音频输入流");
            
            // 创建并打开音频剪辑
            audioClip = AudioSystem.getClip();
            audioClip.open(audioStream);
            ModLogger.info("成功打开音频剪辑");
            
            // 开始播放
            audioClip.start();
            audioClip.loop(Clip.LOOP_CONTINUOUSLY); // 循环播放
            
            isAudioPlaying = true;
            ModLogger.info("背水一战音频开始播放");
            ModLogger.info("音频格式: " + audioStream.getFormat());
            
        } catch (Exception e) {
            ModLogger.error("音频播放异常: " + e.getMessage());
            ModLogger.error("完整错误信息:");
            e.printStackTrace();
        }
    }
    
    /**
     * 停止播放音频
     */
    private void stopAudio() {
        ModLogger.info("尝试停止音频播放，当前状态: " + isAudioPlaying);
        if (!isAudioPlaying || audioClip == null) {
            ModLogger.info("音频已经停止或未初始化，跳过停止操作");
            return;
        }
        
        try {
            // 停止播放
            audioClip.stop();
            audioClip.close();
            
            isAudioPlaying = false;
            audioClip = null;
            
            ModLogger.info("背水一战音频已停止");
            
        } catch (Exception e) {
            ModLogger.error("停止音频播放时发生异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 客户端Tick事件处理，检查游戏状态变化
     * @param event 客户端Tick事件
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        
        AudioManager manager = getInstance();
        Minecraft minecraft = Minecraft.getInstance();
        
        // 检查行动是否正在运行
        boolean isOperationRunning = manager.isOperationModeRunning();
        
        // 检查游戏世界是否从加载变为未加载
        if (manager.isAudioPlaying && (minecraft.level == null || minecraft.player == null)) {
            ModLogger.info("检测到游戏世界未加载，自动停止音频");
            manager.stopAudio();
        } 
        // 检查行动是否从运行变为未运行
        else if (manager.isAudioPlaying && !isOperationRunning) {
            ModLogger.info("检测到行动已结束，自动停止音频");
            manager.stopAudio();
            manager.lastPlayRequest = false;
        }
        // 检查游戏世界是否从未加载变为加载，且有播放请求和行动正在运行
        else if (!manager.isAudioPlaying && manager.lastPlayRequest && minecraft.level != null && minecraft.player != null && isOperationRunning) {
            ModLogger.info("检测到游戏世界已加载且有播放请求，尝试播放音频");
            manager.startAudio();
        }
    }
    
    /**
     * 获取音频文件夹
     * @return 音频文件夹
     */
    private File getAudioFolder() {
        // 获取Minecraft游戏目录
        File minecraftDir = Minecraft.getInstance().gameDirectory;
        
        // 构造音频文件夹路径
        Path audioFolderPath = Paths.get(minecraftDir.getAbsolutePath(), AUDIO_FOLDER_NAME);
        
        // 确保音频文件夹存在
        File audioFolder = audioFolderPath.toFile();
        if (!audioFolder.exists()) {
            boolean created = audioFolder.mkdirs();
            if (created) {
                ModLogger.info("音频文件夹已创建: " + audioFolderPath);
            } else {
                ModLogger.error("无法创建音频文件夹: " + audioFolderPath);
            }
        }
        
        return audioFolder;
    }
    
    /**
     * 获取音频文件夹中的所有支持的音频文件
     * @param audioFolder 音频文件夹
     * @return 音频文件列表
     */
    private List<File> getAudioFiles(File audioFolder) {
        List<File> audioFiles = new ArrayList<>();
        
        if (!audioFolder.exists() || !audioFolder.isDirectory()) {
            return audioFiles;
        }
        
        // 获取文件夹中的所有文件
        File[] files = audioFolder.listFiles();
        if (files == null) {
            ModLogger.error("无法读取音频文件夹内容！");
            return audioFiles;
        }
        
        // 筛选支持的音频文件
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName().toLowerCase();
                for (String format : SUPPORTED_FORMATS) {
                    if (fileName.endsWith("." + format)) {
                        audioFiles.add(file);
                        ModLogger.info("找到支持的音频文件: " + file.getName());
                        break;
                    }
                }
            }
        }
        
        return audioFiles;
    }
    
    /**
     * 获取音频文件的完整路径（兼容旧版逻辑）
     * @return 音频文件的完整路径
     */
    public static Path getAudioFilePath() {
        // 获取Minecraft游戏目录
        File minecraftDir = Minecraft.getInstance().gameDirectory;
        
        // 构造音频文件夹路径
        Path audioFolderPath = Paths.get(minecraftDir.getAbsolutePath(), AUDIO_FOLDER_NAME);
        
        // 确保音频文件夹存在
        File audioFolder = audioFolderPath.toFile();
        if (!audioFolder.exists()) {
            boolean created = audioFolder.mkdirs();
            if (created) {
                ModLogger.info("音频文件夹已创建: " + audioFolderPath);
            } else {
                ModLogger.error("无法创建音频文件夹: " + audioFolderPath);
            }
        }
        
        // 返回默认音频文件路径（兼容旧版）
        return audioFolderPath.resolve("lastStandBGM.wav");
    }
    
    /**
     * 检查音频文件是否存在（兼容旧版逻辑）
     * @return 音频文件是否存在
     */
    public static boolean isAudioFileExists() {
        // 检查是否有任何支持的音频文件
        File audioFolder = new File(Minecraft.getInstance().gameDirectory, AUDIO_FOLDER_NAME);
        List<File> audioFiles = getAudioFilesStatic(audioFolder);
        return !audioFiles.isEmpty();
    }
    
    /**
     * 静态方法：获取音频文件列表
     * @param audioFolder 音频文件夹
     * @return 音频文件列表
     */
    private static List<File> getAudioFilesStatic(File audioFolder) {
        List<File> audioFiles = new ArrayList<>();
        
        if (!audioFolder.exists() || !audioFolder.isDirectory()) {
            return audioFiles;
        }
        
        File[] files = audioFolder.listFiles();
        if (files == null) {
            return audioFiles;
        }
        
        for (File file : files) {
            if (file.isFile()) {
                String fileName = file.getName().toLowerCase();
                for (String format : SUPPORTED_FORMATS) {
                    if (fileName.endsWith("." + format)) {
                        audioFiles.add(file);
                        break;
                    }
                }
            }
        }
        
        return audioFiles;
    }
    
    /**
     * 清理资源
     */
    public void cleanup() {
        ModLogger.info("清理AudioManager资源");
        // 停止音频播放
        if (isAudioPlaying) {
            stopAudio();
        }
    }
}