package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.espetro.team.GamePhase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CurrentMapPacketTest {

    @Test
    void gamePhaseRoundTripsCurrentMapFolder() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new GamePhaseSyncPacket(GamePhase.ROUND_END, "desert_assault", "RAAS").write(buffer);
            GamePhaseSyncPacket decoded = GamePhaseSyncPacket.read(buffer);
            assertEquals("ROUND_END", decoded.getPhaseName());
            assertEquals("desert_assault", decoded.getMapFolder());
            assertEquals("RAAS", decoded.getObjectiveMode());
        } finally {
            buffer.release();
        }
    }

    @Test
    void gameStateRoundTripsCurrentMapFolder() {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new GameStateResponsePacket(
                "DEPLOYING", "ATTACK", "army", "ATTACK", 120,
                "urban_front", "AAS").write(buffer);
            GameStateResponsePacket decoded = GameStateResponsePacket.read(buffer);
            assertEquals("urban_front", decoded.getMapFolder());
            assertEquals(120, decoded.getTimeRemaining());
            assertEquals("AAS", decoded.getObjectiveMode());
        } finally {
            buffer.release();
        }
    }
}
