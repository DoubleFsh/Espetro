package com.example.hcrpoints.util;

import com.example.hcrpoints.HCRPointsMod;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 模组日志管理类
 */
public class ModLogger {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String LOG_DIRECTORY = "logs/hcr_mod";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    
    /**
     * 记录信息日志
     * @param message 日志消息
     */
    public static void info(String message) {
        LOGGER.info("[HCR] " + message);
    }
    
    /**
     * 记录警告日志
     * @param message 日志消息
     */
    public static void warn(String message) {
        LOGGER.warn("[HCR] " + message);
    }
    
    /**
     * 记录错误日志
     * @param message 日志消息
     */
    public static void error(String message) {
        LOGGER.error("[HCR] " + message);
    }
    
    /**
     * 记录错误日志（带异常）
     * @param message 日志消息
     * @param throwable 异常对象
     */
    public static void error(String message, Throwable throwable) {
        LOGGER.error("[HCR] " + message, throwable);
    }
    
    /**
     * 将日志写入文件
     * @param level 日志级别
     * @param message 日志消息
     */
    public static void logToFile(String level, String message) {
        try {
            // 创建日志目录
            Path logDir = Paths.get(LOG_DIRECTORY);
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            
            // 创建日志文件路径
            Path logFile = logDir.resolve("hcr_mod.log");
            
            // 格式化日志消息
            String timestamp = LocalDateTime.now().format(DATE_FORMATTER);
            String logEntry = String.format("[%s] [%s] %s%n", timestamp, level, message);
            
            // 写入日志文件
            Files.write(logFile, logEntry.getBytes(), 
                       StandardOpenOption.CREATE, 
                       StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOGGER.error("无法写入日志文件", e);
        }
    }
    
    /**
     * 记录同步错误日志
     * @param message 错误消息
     */
    public static void syncError(String message) {
        error("同步错误: " + message);
        logToFile("SYNC_ERROR", message);
    }
    
    /**
     * 记录解码错误日志
     * @param message 错误消息
     */
    public static void decodeError(String message) {
        error("解码错误: " + message);
        logToFile("DECODE_ERROR", message);
    }
    
    /**
     * 将十六进制颜色代码转换为整数颜色值
     * @param hexColor 十六进制颜色代码（可以带#号，例如：#FF5500或FF5500）
     * @param defaultColor 默认颜色值（当解析失败时使用）
     * @return 整数颜色值（ARGB格式，例如：0xFFFF5500）
     */
    public static int hexToColor(String hexColor, int defaultColor) {
        try {
            // 移除#前缀（如果有）
            String normalizedHex = hexColor.trim().toUpperCase();
            if (normalizedHex.startsWith("#")) {
                normalizedHex = normalizedHex.substring(1);
            }
            // 解析为整数，添加透明度FF（不透明）
            int color = Integer.parseInt(normalizedHex, 16);
            return 0xFF000000 | color; // 添加不透明前缀
        } catch (NumberFormatException e) {
            // 解析失败，使用默认颜色
            warn("无效的颜色代码: " + hexColor + "，使用默认颜色");
            return defaultColor;
        }
    }
}