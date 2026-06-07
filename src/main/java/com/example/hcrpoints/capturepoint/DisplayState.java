package com.example.hcrpoints.capturepoint;

/**
 * 据点显示状态枚举
 */
public enum DisplayState {
    /**
     * 中立
     */
    NEUTRAL,
    
    /**
     * 争夺中（玩家名-升旗）
     */
    CAPTURING_FLAG_SINGLE,
    
    /**
     * 争夺中（多玩家-升旗争夺）
     */
    CAPTURING_CONTESTED_MULTI,
    
    /**
     * 争夺中（多玩家-争夺）
     */
    CONTESTED_MULTI,
    
    /**
     * 争夺中（降旗）
     */
    CAPTURING_DOWN,
    
    /**
     * 已占领（玩家名）
     */
    CAPTURED
}