package org.espetro.network;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeployPointSyncPacketTest {

    @Test
    void roundTripsHabStatusAndPosition() {
        UUID id = UUID.randomUUID();
        var item = new UnifiedDeployScreenPacket.BastionItem(
            id, "HAB 1", "12, 64, -9",
            UnifiedDeployScreenPacket.BastionItem.TYPE_HAB,
            "HAB 可部署", 0L, 0, 123456L, 30);
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            new DeployPointSyncPacket(List.of(item)).write(buffer);
            DeployPointSyncPacket decoded = DeployPointSyncPacket.read(buffer);

            assertEquals(1, decoded.getDeployPoints().size());
            var actual = decoded.getDeployPoints().get(0);
            assertEquals(id, actual.id);
            assertEquals("12, 64, -9", actual.pos);
            assertEquals("HAB 可部署", actual.status);
            assertEquals(123456L, actual.habAvailableAtEpochMs);
        } finally {
            buffer.release();
        }
    }
}
