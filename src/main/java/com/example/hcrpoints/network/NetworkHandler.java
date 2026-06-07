package com.example.hcrpoints.network;

import com.example.hcrpoints.HCRPointsMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络处理器类
 */
public class NetworkHandler {
    private static final String PROTOCOL_VERSION = "1";
    
    public static final SimpleChannel INSTANCE = NetworkRegistry.newSimpleChannel(
        ResourceLocation.fromNamespaceAndPath(HCRPointsMod.MOD_ID, "main"),
        () -> PROTOCOL_VERSION,
        PROTOCOL_VERSION::equals,
        PROTOCOL_VERSION::equals
    );
    
    private static int packetId = 0;
    
    /**
     * 获取下一个包ID
     * @return 包ID
     */
    private static int nextPacketId() {
        return packetId++;
    }
    
    /**
     * 注册网络消息
     */
    public static void registerMessages() {
        INSTANCE.messageBuilder(SyncCapturePointsMessage.class, nextPacketId())
            .encoder(SyncCapturePointsMessage::encode)
            .decoder(SyncCapturePointsMessage::decode)
            .consumerMainThread(SyncCapturePointsMessage::handle)
            .add();
        
        INSTANCE.messageBuilder(SyncConfigMessage.class, nextPacketId())
            .encoder(SyncConfigMessage::encode)
            .decoder(SyncConfigMessage::decode)
            .consumerMainThread(SyncConfigMessage::handle)
            .add();
        
        INSTANCE.messageBuilder(ShowMessagePopupMessage.class, nextPacketId())
            .encoder(ShowMessagePopupMessage::encode)
            .decoder(ShowMessagePopupMessage::decode)
            .consumerMainThread(ShowMessagePopupMessage::handle)
            .add();
        
        INSTANCE.messageBuilder(SyncOperationModeMessage.class, nextPacketId())
            .encoder(SyncOperationModeMessage::encode)
            .decoder(SyncOperationModeMessage::decode)
            .consumerMainThread(SyncOperationModeMessage::handle)
            .add();
        
        INSTANCE.messageBuilder(SyncPlayerPositionsMessage.class, nextPacketId())
            .encoder(SyncPlayerPositionsMessage::encode)
            .decoder(SyncPlayerPositionsMessage::decode)
            .consumerMainThread(SyncPlayerPositionsMessage::handle)
            .add();
            
        INSTANCE.messageBuilder(PlayLowReinforcementAudioMessage.class, nextPacketId())
            .encoder(PlayLowReinforcementAudioMessage::encode)
            .decoder(PlayLowReinforcementAudioMessage::decode)
            .consumerMainThread(PlayLowReinforcementAudioMessage::handle)
            .add();
        
        INSTANCE.messageBuilder(SyncMapPlayerDisplayMessage.class, nextPacketId())
            .encoder(SyncMapPlayerDisplayMessage::encode)
            .decoder(SyncMapPlayerDisplayMessage::decode)
            .consumerMainThread(SyncMapPlayerDisplayMessage::handle)
            .add();
    }
}