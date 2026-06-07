package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 统一部署/复活主界面包（S→C）
 * 集成：职业选择、复活点选择、载具部署、小队选择
 * 触发时机：部署阶段开始、玩家死亡复活
 */
public class UnifiedDeployScreenPacket {

    // === 职业选择数据 ===
    private final String factionId;
    private final String factionName;
    private final String factionDescription;
    private final String factionIcon;
    private final List<ClassInfo> classes;
    private final Map<String, Integer> classCounts;

    // === 复活点选择数据 ===
    private final boolean hasDeployPoint;
    private final String deployPointPos;
    private final List<BastionItem> bastions;

    // === 载具部署数据（仅指挥官） ===
    private final boolean isCommander;
    private final List<VehicleInfo> vehicles;

    // === 小队选择数据 ===
    private final List<SquadInfo> squads;
    private final int mySquadId;

    // === 通用 ===
    private final int deployTimeRemaining;
    private final String team;

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team) {
        this.factionId = factionId;
        this.factionName = factionName;
        this.factionDescription = factionDescription;
        this.factionIcon = factionIcon;
        this.classes = classes != null ? classes : new ArrayList<>();
        this.classCounts = classCounts != null ? classCounts : new HashMap<>();
        this.hasDeployPoint = hasDeployPoint;
        this.deployPointPos = deployPointPos;
        this.bastions = bastions != null ? bastions : new ArrayList<>();
        this.isCommander = isCommander;
        this.vehicles = vehicles != null ? vehicles : new ArrayList<>();
        this.squads = squads != null ? squads : new ArrayList<>();
        this.mySquadId = mySquadId;
        this.deployTimeRemaining = deployTimeRemaining;
        this.team = team;
    }

    public UnifiedDeployScreenPacket(FriendlyByteBuf buf) {
        this.factionId = buf.readUtf();
        this.factionName = buf.readUtf();
        this.factionDescription = buf.readUtf();
        this.factionIcon = buf.readUtf();

        int classSize = buf.readVarInt();
        this.classes = new ArrayList<>();
        for (int i = 0; i < classSize; i++) {
            this.classes.add(new ClassInfo(buf));
        }

        int countSize = buf.readVarInt();
        this.classCounts = new HashMap<>();
        for (int i = 0; i < countSize; i++) {
            this.classCounts.put(buf.readUtf(), buf.readVarInt());
        }

        this.hasDeployPoint = buf.readBoolean();
        this.deployPointPos = buf.readUtf();

        int bastionSize = buf.readVarInt();
        this.bastions = new ArrayList<>();
        for (int i = 0; i < bastionSize; i++) {
            this.bastions.add(new BastionItem(buf));
        }

        this.isCommander = buf.readBoolean();

        int vehicleSize = buf.readVarInt();
        this.vehicles = new ArrayList<>();
        for (int i = 0; i < vehicleSize; i++) {
            this.vehicles.add(new VehicleInfo(buf));
        }

        int squadSize = buf.readVarInt();
        this.squads = new ArrayList<>();
        for (int i = 0; i < squadSize; i++) {
            this.squads.add(new SquadInfo(buf));
        }

        this.mySquadId = buf.readVarInt();
        this.deployTimeRemaining = buf.readVarInt();
        this.team = buf.readUtf();
    }

    public static UnifiedDeployScreenPacket read(FriendlyByteBuf buf) {
        return new UnifiedDeployScreenPacket(buf);
    }

    public void write(FriendlyByteBuf buf) {
        buf.writeUtf(factionId);
        buf.writeUtf(factionName);
        buf.writeUtf(factionDescription);
        buf.writeUtf(factionIcon);

        buf.writeVarInt(classes.size());
        for (ClassInfo c : classes) c.write(buf);

        buf.writeVarInt(classCounts.size());
        for (Map.Entry<String, Integer> e : classCounts.entrySet()) {
            buf.writeUtf(e.getKey());
            buf.writeVarInt(e.getValue());
        }

        buf.writeBoolean(hasDeployPoint);
        buf.writeUtf(deployPointPos);

        buf.writeVarInt(bastions.size());
        for (BastionItem b : bastions) b.write(buf);

        buf.writeBoolean(isCommander);

        buf.writeVarInt(vehicles.size());
        for (VehicleInfo v : vehicles) v.write(buf);

        buf.writeVarInt(squads.size());
        for (SquadInfo s : squads) s.write(buf);

        buf.writeVarInt(mySquadId);
        buf.writeVarInt(deployTimeRemaining);
        buf.writeUtf(team);
    }

    public void handle(Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            try {
                Class.forName("org.espetro.client.ClientPacketHandlers")
                    .getMethod("handleUnifiedDeployScreen", UnifiedDeployScreenPacket.class)
                    .invoke(null, this);
            } catch (Exception e) {
                org.espetro.Espetro.LOGGER.error("Failed to handle UnifiedDeployScreenPacket", e);
            }
        });
        ctx.get().setPacketHandled(true);
    }

    // ============ Getters ============
    public String getFactionId() { return factionId; }
    public String getFactionName() { return factionName; }
    public String getFactionDescription() { return factionDescription; }
    public String getFactionIcon() { return factionIcon; }
    public List<ClassInfo> getClasses() { return classes; }
    public Map<String, Integer> getClassCounts() { return classCounts; }
    public boolean hasDeployPoint() { return hasDeployPoint; }
    public String getDeployPointPos() { return deployPointPos; }
    public List<BastionItem> getBastions() { return bastions; }
    public boolean isCommander() { return isCommander; }
    public List<VehicleInfo> getVehicles() { return vehicles; }
    public List<SquadInfo> getSquads() { return squads; }
    public int getMySquadId() { return mySquadId; }
    public int getDeployTimeRemaining() { return deployTimeRemaining; }
    public String getTeam() { return team; }

    // ============ Inner Classes ============

    public static class ClassInfo {
        public final String classId;
        public final String name;
        public final String description;
        public final String role;
        public final int maxPlayers;
        public final int currentCount;
        public final int troopValue;
        public final int healthBonus;
        public final float speedBonus;

        public ClassInfo(String classId, String name, String description, String role,
                         int maxPlayers, int currentCount, int troopValue, int healthBonus, float speedBonus) {
            this.classId = classId;
            this.name = name;
            this.description = description;
            this.role = role;
            this.maxPlayers = maxPlayers;
            this.currentCount = currentCount;
            this.troopValue = troopValue;
            this.healthBonus = healthBonus;
            this.speedBonus = speedBonus;
        }

        public ClassInfo(FriendlyByteBuf buf) {
            this.classId = buf.readUtf();
            this.name = buf.readUtf();
            this.description = buf.readUtf();
            this.role = buf.readUtf();
            this.maxPlayers = buf.readVarInt();
            this.currentCount = buf.readVarInt();
            this.troopValue = buf.readVarInt();
            this.healthBonus = buf.readVarInt();
            this.speedBonus = buf.readFloat();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(classId);
            buf.writeUtf(name);
            buf.writeUtf(description);
            buf.writeUtf(role);
            buf.writeVarInt(maxPlayers);
            buf.writeVarInt(currentCount);
            buf.writeVarInt(troopValue);
            buf.writeVarInt(healthBonus);
            buf.writeFloat(speedBonus);
        }
    }

    public static class BastionItem {
        public final java.util.UUID id;
        public final String name;
        public final String pos;

        public BastionItem(java.util.UUID id, String name, String pos) {
            this.id = id;
            this.name = name;
            this.pos = pos;
        }

        public BastionItem(FriendlyByteBuf buf) {
            this.id = buf.readUUID();
            this.name = buf.readUtf();
            this.pos = buf.readUtf();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeUtf(pos);
        }
    }

    public static class VehicleInfo {
        public final String type;
        public final String displayName;
        public final int max;
        public final int current;
        public final int cooldownRemaining;
        public final int respawnMinutes;

        public VehicleInfo(String type, String displayName, int max, int current, int cooldownRemaining, int respawnMinutes) {
            this.type = type;
            this.displayName = displayName;
            this.max = max;
            this.current = current;
            this.cooldownRemaining = cooldownRemaining;
            this.respawnMinutes = respawnMinutes;
        }

        public VehicleInfo(FriendlyByteBuf buf) {
            this.type = buf.readUtf();
            this.displayName = buf.readUtf();
            this.max = buf.readVarInt();
            this.current = buf.readVarInt();
            this.cooldownRemaining = buf.readVarInt();
            this.respawnMinutes = buf.readVarInt();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(type);
            buf.writeUtf(displayName);
            buf.writeVarInt(max);
            buf.writeVarInt(current);
            buf.writeVarInt(cooldownRemaining);
            buf.writeVarInt(respawnMinutes);
        }
    }

    public static class SquadInfo {
        public final int id;
        public final String name;
        public final int memberCount;
        public final int maxMembers;
        public final boolean isLocked;

        public SquadInfo(int id, String name, int memberCount, int maxMembers, boolean isLocked) {
            this.id = id;
            this.name = name;
            this.memberCount = memberCount;
            this.maxMembers = maxMembers;
            this.isLocked = isLocked;
        }

        public SquadInfo(FriendlyByteBuf buf) {
            this.id = buf.readVarInt();
            this.name = buf.readUtf();
            this.memberCount = buf.readVarInt();
            this.maxMembers = buf.readVarInt();
            this.isLocked = buf.readBoolean();
        }

        public void write(FriendlyByteBuf buf)    {
            buf.writeVarInt(id);
            buf.writeUtf(name);
            buf.writeVarInt(memberCount);
            buf.writeVarInt(maxMembers);
            buf.writeBoolean(isLocked);
        }
    }
}
