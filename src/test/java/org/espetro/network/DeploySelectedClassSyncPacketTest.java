package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeploySelectedClassSyncPacketTest {

    @Test
    void unifiedDeployPacketRoundTripsRecipientSelectedClass() {
        UnifiedDeployScreenPacket original = new UnifiedDeployScreenPacket(
            "us_army", "US Army", "", "",
            List.of(), Map.of(), false, "", List.of(),
            false, List.of(), List.of(), 3,
            120, "ATTACK", List.of(), 10.0,
            true, 0, List.of(), 0, true, "rifleman");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(buffer);
            UnifiedDeployScreenPacket decoded = UnifiedDeployScreenPacket.read(buffer);

            assertEquals("rifleman", decoded.getSelectedClassId());
        } finally {
            buffer.release();
        }
    }

    @Test
    void squadSyncPacketRoundTripsRecipientSelectedClass() {
        SquadSyncPacket original = new SquadSyncPacket(
            "DEFEND", List.of(), 7, List.of(), 10.0, "medic");
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.write(buffer);
            SquadSyncPacket decoded = SquadSyncPacket.read(buffer);

            assertEquals("medic", decoded.getSelectedClassId());
        } finally {
            buffer.release();
        }
    }
}
