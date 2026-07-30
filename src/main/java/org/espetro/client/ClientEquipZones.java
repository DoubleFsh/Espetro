package org.espetro.client;

import org.espetro.network.EquipZoneSyncPacket;

import java.util.ArrayList;
import java.util.List;

/** 本机同阵营换装黄框数据缓存。 */
public final class ClientEquipZones {

    private static final List<EquipZoneSyncPacket.Zone> ZONES = new ArrayList<>();

    private ClientEquipZones() {
    }

    public static void setZones(List<EquipZoneSyncPacket.Zone> zones) {
        ZONES.clear();
        if (zones != null) {
            ZONES.addAll(zones);
        }
    }

    public static void clear() {
        ZONES.clear();
    }

    public static List<EquipZoneSyncPacket.Zone> getZones() {
        return List.copyOf(ZONES);
    }
}
