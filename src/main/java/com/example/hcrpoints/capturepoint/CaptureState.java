package com.example.hcrpoints.capturepoint;

/**
 * 据点核心状态枚举
 */
public enum CaptureState {
    /**
     * 中立状态
     */
    NEUTRAL,
    
    /**
     * 争夺中（升旗）
     */
    CAPTURING_FLAG,
    
    /**
     * 争夺中（争夺）
     */
    CONTESTED,
    
    /**
     * 争夺中（升旗争夺）
     */
    CAPTURING_CONTESTED,
    
    /**
     * 争夺中（降旗）
     */
    CAPTURING_DOWN,
    
    /**
     * 已占领
     */
    CAPTURED
}