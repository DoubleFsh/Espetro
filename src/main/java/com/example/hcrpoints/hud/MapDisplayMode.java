package com.example.hcrpoints.hud;

/**
 * 战术地图显示模式枚举
 */
public enum MapDisplayMode {
    /** 按键唤出模式（默认） */
    TOGGLE_KEY,
    
    /** 常显-左下角 */
    ALWAYS_VISIBLE_BOTTOM_LEFT,
    
    /** 常显-左上角 */
    ALWAYS_VISIBLE_TOP_LEFT,
    
    /** 常显-右下角 */
    ALWAYS_VISIBLE_BOTTOM_RIGHT,
    
    /** 常显-右上角 */
    ALWAYS_VISIBLE_TOP_RIGHT
}