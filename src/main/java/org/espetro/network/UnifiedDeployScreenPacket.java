package org.espetro.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.UUID;

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
    private final List<SquadCategoryInfo> squadCategories;
    private final int mySquadId;
    private final List<String> commanderNames;
    private final double teammateNameTagDistance;

    // === 通用 ===
    private final int deployTimeRemaining;
    private final String team;
    private final boolean waitingForDeploySelection;
    private final int outpostRedeployCooldownRemaining;
    private final int classSwitchCooldownRemaining;
    /** 当前接收玩家由服务端确认的职业 ID；空串表示尚未选择。 */
    private final String selectedClassId;
    /** true 时客户端应打开面板；false 时仅刷新当前已打开的面板和战术缓存。 */
    private final boolean openScreen;

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team) {
        this(factionId, factionName, factionDescription, factionIcon,
            classes, classCounts, hasDeployPoint, deployPointPos, bastions,
            isCommander, vehicles, squads, mySquadId, deployTimeRemaining, team,
            new ArrayList<>(), 10.0, false, 0, new ArrayList<>(), 0, true);
    }

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team,
            List<String> commanderNames, double teammateNameTagDistance,
            boolean waitingForDeploySelection, int outpostRedeployCooldownRemaining,
            List<SquadCategoryInfo> squadCategories) {
        this(factionId, factionName, factionDescription, factionIcon,
            classes, classCounts, hasDeployPoint, deployPointPos, bastions,
            isCommander, vehicles, squads, mySquadId, deployTimeRemaining, team,
            commanderNames, teammateNameTagDistance, waitingForDeploySelection,
            outpostRedeployCooldownRemaining, squadCategories, 0, true);
    }

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team,
            List<String> commanderNames, double teammateNameTagDistance,
            boolean waitingForDeploySelection, int outpostRedeployCooldownRemaining,
            List<SquadCategoryInfo> squadCategories, int classSwitchCooldownRemaining) {
        this(factionId, factionName, factionDescription, factionIcon,
            classes, classCounts, hasDeployPoint, deployPointPos, bastions,
            isCommander, vehicles, squads, mySquadId, deployTimeRemaining, team,
            commanderNames, teammateNameTagDistance, waitingForDeploySelection,
            outpostRedeployCooldownRemaining, squadCategories, classSwitchCooldownRemaining,
            true);
    }

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team,
            List<String> commanderNames, double teammateNameTagDistance,
            boolean waitingForDeploySelection, int outpostRedeployCooldownRemaining,
            List<SquadCategoryInfo> squadCategories, int classSwitchCooldownRemaining,
            boolean openScreen) {
        this(factionId, factionName, factionDescription, factionIcon,
            classes, classCounts, hasDeployPoint, deployPointPos, bastions,
            isCommander, vehicles, squads, mySquadId, deployTimeRemaining, team,
            commanderNames, teammateNameTagDistance, waitingForDeploySelection,
            outpostRedeployCooldownRemaining, squadCategories, classSwitchCooldownRemaining,
            openScreen, "");
    }

    public UnifiedDeployScreenPacket(
            String factionId, String factionName, String factionDescription, String factionIcon,
            List<ClassInfo> classes, Map<String, Integer> classCounts,
            boolean hasDeployPoint, String deployPointPos, List<BastionItem> bastions,
            boolean isCommander, List<VehicleInfo> vehicles,
            List<SquadInfo> squads, int mySquadId,
            int deployTimeRemaining, String team,
            List<String> commanderNames, double teammateNameTagDistance,
            boolean waitingForDeploySelection, int outpostRedeployCooldownRemaining,
            List<SquadCategoryInfo> squadCategories, int classSwitchCooldownRemaining,
            boolean openScreen, String selectedClassId) {
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
        this.squadCategories = squadCategories != null ? squadCategories : new ArrayList<>();
        this.mySquadId = mySquadId;
        this.deployTimeRemaining = deployTimeRemaining;
        this.team = team;
        this.commanderNames = commanderNames != null ? commanderNames : new ArrayList<>();
        this.teammateNameTagDistance = teammateNameTagDistance;
        this.waitingForDeploySelection = waitingForDeploySelection;
        this.outpostRedeployCooldownRemaining = Math.max(0, outpostRedeployCooldownRemaining);
        this.classSwitchCooldownRemaining = Math.max(0, classSwitchCooldownRemaining);
        this.openScreen = openScreen;
        this.selectedClassId = selectedClassId == null ? "" : selectedClassId;
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
        int categorySize = buf.readVarInt();
        this.squadCategories = new ArrayList<>();
        for (int i = 0; i < categorySize; i++) {
            this.squadCategories.add(new SquadCategoryInfo(buf.readUtf(), buf.readUtf()));
        }

        this.mySquadId = buf.readVarInt();
        this.deployTimeRemaining = buf.readVarInt();
        this.team = buf.readUtf();

        int commanderSize = buf.readVarInt();
        this.commanderNames = new ArrayList<>();
        for (int i = 0; i < commanderSize; i++) {
            this.commanderNames.add(buf.readUtf());
        }
        this.teammateNameTagDistance = buf.readDouble();
        this.waitingForDeploySelection = buf.readBoolean();
        this.outpostRedeployCooldownRemaining = buf.readVarInt();
        this.classSwitchCooldownRemaining = buf.readVarInt();
        this.openScreen = buf.readBoolean();
        this.selectedClassId = buf.readUtf();
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
        buf.writeVarInt(squadCategories.size());
        for (SquadCategoryInfo category : squadCategories) {
            buf.writeUtf(category.id);
            buf.writeUtf(category.displayName);
        }

        buf.writeVarInt(mySquadId);
        buf.writeVarInt(deployTimeRemaining);
        buf.writeUtf(team);
        buf.writeVarInt(commanderNames.size());
        for (String commanderName : commanderNames) {
            buf.writeUtf(commanderName);
        }
        buf.writeDouble(teammateNameTagDistance);
        buf.writeBoolean(waitingForDeploySelection);
        buf.writeVarInt(outpostRedeployCooldownRemaining);
        buf.writeVarInt(classSwitchCooldownRemaining);
        buf.writeBoolean(openScreen);
        buf.writeUtf(selectedClassId);
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
    public Map<String, Map<String, Integer>> getVariantCounts() {
        Map<String, Map<String, Integer>> result = new java.util.HashMap<>();
        for (ClassInfo classInfo : classes) {
            Map<String, Integer> perClass = new java.util.HashMap<>();
            for (VariantInfo variant : classInfo.variants) {
                perClass.put(variant.variantId, variant.currentCount);
            }
            result.put(classInfo.classId, perClass);
        }
        return result;
    }
    public boolean hasDeployPoint() { return hasDeployPoint; }
    public String getDeployPointPos() { return deployPointPos; }
    public List<BastionItem> getBastions() { return bastions; }
    public boolean isCommander() { return isCommander; }
    public List<VehicleInfo> getVehicles() { return vehicles; }
    public List<SquadInfo> getSquads() { return squads; }
    public List<SquadCategoryInfo> getSquadCategories() { return squadCategories; }
    public int getMySquadId() { return mySquadId; }
    public int getDeployTimeRemaining() { return deployTimeRemaining; }
    public String getTeam() { return team; }
    public List<String> getCommanderNames() { return commanderNames; }
    public double getTeammateNameTagDistance() { return teammateNameTagDistance; }
    public boolean isWaitingForDeploySelection() { return waitingForDeploySelection; }
    public int getOutpostRedeployCooldownRemaining() { return outpostRedeployCooldownRemaining; }
    public int getClassSwitchCooldownRemaining() { return classSwitchCooldownRemaining; }
    public boolean shouldOpenScreen() { return openScreen; }
    public String getSelectedClassId() { return selectedClassId; }

    // ============ Inner Classes ============

    public static class ClassInfo {
        public final String classId;
        public final String name;
        public final String description;
        public final String role;
        public final String icon;
        /** 磁盘完整路径 IconImage；可空。 */
        public final String iconImage;
        public final int maxPlayers;
        public final boolean strictCount;
        public final int currentCount;
        public final int troopValue;
        public final int healthBonus;
        public final float speedBonus;
        public final boolean teamCount;
        public final int maxPerSquad;
        /** 选择职业所需的小队最低人数（含自己）。 */
        public final int teammatesNeed;
        public int squadCurrentCount;
        public final List<VariantInfo> variants;
        public final int row;
        public final int unlockPerN;
        public final int unlockMinSquad;
        public final boolean leaderOnly;

        public ClassInfo(String classId, String name, String description, String role, String icon,
                         int maxPlayers, boolean strictCount, int currentCount, int troopValue, int healthBonus, float speedBonus,
                         List<VariantInfo> variants) {
            this(classId, name, description, role, icon, null, maxPlayers, strictCount, currentCount,
                troopValue, healthBonus, speedBonus, false, 0, 0, 0, variants);
        }

        public ClassInfo(String classId, String name, String description, String role, String icon,
                         int maxPlayers, boolean strictCount, int currentCount, int troopValue, int healthBonus, float speedBonus,
                         boolean teamCount, int maxPerSquad, int squadCurrentCount,
                         List<VariantInfo> variants) {
            this(classId, name, description, role, icon, null, maxPlayers, strictCount, currentCount,
                troopValue, healthBonus, speedBonus, teamCount, maxPerSquad, squadCurrentCount, 0, variants);
        }

        public ClassInfo(String classId, String name, String description, String role, String icon, String iconImage,
                         int maxPlayers, boolean strictCount, int currentCount, int troopValue, int healthBonus, float speedBonus,
                         boolean teamCount, int maxPerSquad, int squadCurrentCount,
                         int teammatesNeed,
                         List<VariantInfo> variants) {
            this(classId, name, description, role, icon, iconImage, maxPlayers, strictCount, currentCount,
                troopValue, healthBonus, speedBonus, teamCount, maxPerSquad, squadCurrentCount, teammatesNeed,
                0, 0, 0, false, variants);
        }

        public ClassInfo(String classId, String name, String description, String role, String icon, String iconImage,
                         int maxPlayers, boolean strictCount, int currentCount, int troopValue, int healthBonus, float speedBonus,
                         boolean teamCount, int maxPerSquad, int squadCurrentCount,
                         int teammatesNeed, int row, int unlockPerN, int unlockMinSquad, boolean leaderOnly,
                         List<VariantInfo> variants) {
            this.classId = classId;
            this.name = name;
            this.description = description;
            this.role = role;
            this.icon = icon;
            this.iconImage = iconImage == null ? "" : iconImage;
            this.maxPlayers = maxPlayers;
            this.strictCount = strictCount;
            this.currentCount = currentCount;
            this.troopValue = troopValue;
            this.healthBonus = healthBonus;
            this.speedBonus = speedBonus;
            this.teamCount = teamCount;
            this.maxPerSquad = Math.max(0, maxPerSquad);
            this.squadCurrentCount = Math.max(0, squadCurrentCount);
            this.teammatesNeed = Math.max(0, teammatesNeed);
            this.row = row;
            this.unlockPerN = unlockPerN;
            this.unlockMinSquad = unlockMinSquad;
            this.leaderOnly = leaderOnly;
            this.variants = variants != null ? variants : new ArrayList<>();
        }

        public ClassInfo(FriendlyByteBuf buf) {
            this.classId = buf.readUtf();
            this.name = buf.readUtf();
            this.description = buf.readUtf();
            this.role = buf.readUtf();
            this.icon = buf.readUtf();
            this.iconImage = buf.readUtf();
            this.maxPlayers = buf.readVarInt();
            this.strictCount = buf.readBoolean();
            this.currentCount = buf.readVarInt();
            this.troopValue = buf.readVarInt();
            this.healthBonus = buf.readVarInt();
            this.speedBonus = buf.readFloat();
            this.teamCount = buf.readBoolean();
            this.maxPerSquad = Math.max(0, buf.readVarInt());
            this.squadCurrentCount = Math.max(0, buf.readVarInt());
            this.teammatesNeed = Math.max(0, buf.readVarInt());
            this.row = buf.readVarInt();
            this.unlockPerN = buf.readVarInt();
            this.unlockMinSquad = buf.readVarInt();
            this.leaderOnly = buf.readBoolean();
            int variantCount = buf.readVarInt();
            this.variants = new ArrayList<>(variantCount);
            for (int i = 0; i < variantCount; i++) {
                this.variants.add(new VariantInfo(buf));
            }
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(classId);
            buf.writeUtf(name);
            buf.writeUtf(description);
            buf.writeUtf(role);
            buf.writeUtf(icon == null ? "" : icon);
            buf.writeUtf(iconImage == null ? "" : iconImage);
            buf.writeVarInt(maxPlayers);
            buf.writeBoolean(strictCount);
            buf.writeVarInt(currentCount);
            buf.writeVarInt(troopValue);
            buf.writeVarInt(healthBonus);
            buf.writeFloat(speedBonus);
            buf.writeBoolean(teamCount);
            buf.writeVarInt(maxPerSquad);
            buf.writeVarInt(squadCurrentCount);
            buf.writeVarInt(teammatesNeed);
            buf.writeVarInt(row);
            buf.writeVarInt(unlockPerN);
            buf.writeVarInt(unlockMinSquad);
            buf.writeBoolean(leaderOnly);
            buf.writeVarInt(variants.size());
            for (VariantInfo variant : variants) variant.write(buf);
        }
    }

    public static class VariantInfo {
        public final String variantId;
        public final String name;
        public final String description;
        public final int maxPlayers;
        public int currentCount;
        /** 服务端权威装备预览，客户端无需解析命令即可渲染人物模型。 */
        public final LoadoutPreview preview;

        public VariantInfo(String variantId, String name, String description,
                           int maxPlayers, int currentCount) {
            this(variantId, name, description, maxPlayers, currentCount, LoadoutPreview.empty());
        }

        public VariantInfo(String variantId, String name, String description,
                           int maxPlayers, int currentCount, LoadoutPreview preview) {
            this.variantId = variantId;
            this.name = name;
            this.description = description;
            this.maxPlayers = maxPlayers;
            this.currentCount = currentCount;
            this.preview = preview != null ? preview : LoadoutPreview.empty();
        }

        public VariantInfo(FriendlyByteBuf buf) {
            this.variantId = buf.readUtf();
            this.name = buf.readUtf();
            this.description = buf.readUtf();
            this.maxPlayers = buf.readVarInt();
            this.currentCount = buf.readVarInt();
            this.preview = new LoadoutPreview(buf);
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUtf(variantId);
            buf.writeUtf(name != null ? name : variantId);
            buf.writeUtf(description != null ? description : "");
            buf.writeVarInt(maxPlayers);
            buf.writeVarInt(currentCount);
            preview.write(buf);
        }
    }

    /**
     * 6 槽位装备预览。所有 ItemStack 在构造时被复制，
     * 调用方持有的原始 ItemStack 不会被后续渲染修改。
     * <p>
     * 序列化使用 {@link FriendlyByteBuf#writeItem} / {@link FriendlyByteBuf#readItem}，
     * 空物品、带 NBT 物品、带数量物品均能保持一致。
     */
    public static class LoadoutPreview {
        public final ItemStack head;
        public final ItemStack chest;
        public final ItemStack legs;
        public final ItemStack feet;
        public final ItemStack mainHand;
        public final ItemStack offHand;

        public LoadoutPreview(ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet,
                              ItemStack mainHand, ItemStack offHand) {
            this.head = copySafe(head);
            this.chest = copySafe(chest);
            this.legs = copySafe(legs);
            this.feet = copySafe(feet);
            this.mainHand = copySafe(mainHand);
            this.offHand = copySafe(offHand);
        }

        public static LoadoutPreview empty() {
            return new LoadoutPreview(
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        }

        public LoadoutPreview(FriendlyByteBuf buf) {
            this.head = buf.readItem();
            this.chest = buf.readItem();
            this.legs = buf.readItem();
            this.feet = buf.readItem();
            this.mainHand = buf.readItem();
            this.offHand = buf.readItem();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeItem(head);
            buf.writeItem(chest);
            buf.writeItem(legs);
            buf.writeItem(feet);
            buf.writeItem(mainHand);
            buf.writeItem(offHand);
        }

        public boolean isEmpty() {
            return head.isEmpty() && chest.isEmpty() && legs.isEmpty()
                && feet.isEmpty() && mainHand.isEmpty() && offHand.isEmpty();
        }

        private static ItemStack copySafe(ItemStack stack) {
            return stack == null ? ItemStack.EMPTY : stack.copy();
        }
    }

    public static class BastionItem {
        public static final String TYPE_HAB = "hab";
        public static final String TYPE_RALLY = "rally";
        public static final String TYPE_OUTPOST = "outpost";

        public final java.util.UUID id;
        public final String name;
        public final String pos;
        public final String type;
        public final String status;
        /** 个人波次/冷却就绪时刻（epoch ms）；0 表示无倒计时。 */
        public final long nextWaveAtEpochMs;
        /** 个人冷却总时长（秒），用于 GUI 显示 n/m；0 表示未知。 */
        public final int waveSeconds;
        /** HAB 激活就绪时刻（epoch ms）；0 表示无需等待激活。 */
        public final long habAvailableAtEpochMs;
        /** HAB 激活总时长（秒），用于客户端本地倒计时显示。 */
        public final int habActivationTotalSeconds;

        public BastionItem(java.util.UUID id, String name, String pos) {
            this(id, name, pos,
                id.getMostSignificantBits() == 0L ? TYPE_OUTPOST : TYPE_HAB, "", 0L, 0, 0L, 0);
        }

        public BastionItem(java.util.UUID id, String name, String pos, String type, String status) {
            this(id, name, pos, type, status, 0L, 0, 0L, 0);
        }

        public BastionItem(java.util.UUID id, String name, String pos, String type, String status,
                           long nextWaveAtEpochMs) {
            this(id, name, pos, type, status, nextWaveAtEpochMs, 0, 0L, 0);
        }

        public BastionItem(java.util.UUID id, String name, String pos, String type, String status,
                           long nextWaveAtEpochMs, int waveSeconds) {
            this(id, name, pos, type, status, nextWaveAtEpochMs, waveSeconds, 0L, 0);
        }

        public BastionItem(java.util.UUID id, String name, String pos, String type, String status,
                           long nextWaveAtEpochMs, int waveSeconds,
                           long habAvailableAtEpochMs, int habActivationTotalSeconds) {
            this.id = id;
            this.name = name;
            this.pos = pos;
            this.type = type == null ? TYPE_HAB : type;
            this.status = status == null ? "" : status;
            this.nextWaveAtEpochMs = Math.max(0L, nextWaveAtEpochMs);
            this.waveSeconds = Math.max(0, waveSeconds);
            this.habAvailableAtEpochMs = Math.max(0L, habAvailableAtEpochMs);
            this.habActivationTotalSeconds = Math.max(0, habActivationTotalSeconds);
        }

        public BastionItem(FriendlyByteBuf buf) {
            this.id = buf.readUUID();
            this.name = buf.readUtf();
            this.pos = buf.readUtf();
            this.type = buf.readUtf();
            this.status = buf.readUtf();
            this.nextWaveAtEpochMs = Math.max(0L, buf.readLong());
            this.waveSeconds = Math.max(0, buf.readVarInt());
            this.habAvailableAtEpochMs = Math.max(0L, buf.readLong());
            this.habActivationTotalSeconds = Math.max(0, buf.readVarInt());
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUUID(id);
            buf.writeUtf(name);
            buf.writeUtf(pos);
            buf.writeUtf(type);
            buf.writeUtf(status);
            buf.writeLong(nextWaveAtEpochMs);
            buf.writeVarInt(waveSeconds);
            buf.writeLong(habAvailableAtEpochMs);
            buf.writeVarInt(habActivationTotalSeconds);
        }

        /**
         * 判断是否为前哨基地（用特殊 UUID 标记：MSB=0, LSB=index+1）
         */
        public boolean isOutpost() {
            return TYPE_OUTPOST.equals(type)
                || (id.getMostSignificantBits() == 0L && id.getLeastSignificantBits() > 0L);
        }

        public boolean isRally() {
            return TYPE_RALLY.equals(type);
        }

        /**
         * 获取前哨基地索引（从0开始）
         */
        public int getOutpostIndex() {
            return (int) (id.getLeastSignificantBits() - 1);
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
        public final String leaderName;
        public final String categoryId;
        public final String categoryDisplayName;
        public final List<SquadMemberInfo> members;

        public SquadInfo(int id, String name, int memberCount, int maxMembers, boolean isLocked) {
            this(id, name, memberCount, maxMembers, isLocked, "", "none", "无", new ArrayList<>());
        }

        public SquadInfo(int id, String name, int memberCount, int maxMembers, boolean isLocked,
                         String leaderName, String categoryId, String categoryDisplayName,
                         List<SquadMemberInfo> members) {
            this.id = id;
            this.name = name;
            this.memberCount = memberCount;
            this.maxMembers = maxMembers;
            this.isLocked = isLocked;
            this.leaderName = leaderName == null ? "" : leaderName;
            this.categoryId = categoryId == null ? "none" : categoryId;
            this.categoryDisplayName = categoryDisplayName == null ? "无" : categoryDisplayName;
            this.members = members != null ? members : new ArrayList<>();
        }

        public SquadInfo(FriendlyByteBuf buf) {
            this.id = buf.readVarInt();
            this.name = buf.readUtf();
            this.memberCount = buf.readVarInt();
            this.maxMembers = buf.readVarInt();
            this.isLocked = buf.readBoolean();
            this.leaderName = buf.readUtf();
            this.categoryId = buf.readUtf();
            this.categoryDisplayName = buf.readUtf();

            int memberSize = buf.readVarInt();
            this.members = new ArrayList<>();
            for (int i = 0; i < memberSize; i++) {
                this.members.add(new SquadMemberInfo(buf));
            }
        }

        public void write(FriendlyByteBuf buf)    {
            buf.writeVarInt(id);
            buf.writeUtf(name);
            buf.writeVarInt(memberCount);
            buf.writeVarInt(maxMembers);
            buf.writeBoolean(isLocked);
            buf.writeUtf(leaderName);
            buf.writeUtf(categoryId);
            buf.writeUtf(categoryDisplayName);
            buf.writeVarInt(members.size());
            for (SquadMemberInfo member : members) {
                member.write(buf);
            }
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SquadInfo that)) return false;
            return id == that.id
                && memberCount == that.memberCount
                && maxMembers == that.maxMembers
                && isLocked == that.isLocked
                && Objects.equals(name, that.name)
                && Objects.equals(leaderName, that.leaderName)
                && Objects.equals(categoryId, that.categoryId)
                && Objects.equals(categoryDisplayName, that.categoryDisplayName)
                && Objects.equals(members, that.members);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, name, memberCount, maxMembers, isLocked, leaderName,
                categoryId, categoryDisplayName, members);
        }
    }

    public static class SquadMemberInfo {
        public final UUID uuid;
        public final String playerName;
        public final String className;
        public final boolean leader;
        public final boolean commander;
        /** 0=A, 1=B, 2=C */
        public final byte fireteam;
        public final boolean fireteamLeader;

        public SquadMemberInfo(String playerName, String className, boolean leader) {
            this(new UUID(0L, 0L), playerName, className, leader, false, (byte) 0, leader);
        }

        public SquadMemberInfo(String playerName, String className, boolean leader, boolean commander) {
            this(new UUID(0L, 0L), playerName, className, leader, commander, (byte) 0, leader);
        }

        public SquadMemberInfo(UUID uuid, String playerName, String className,
                               boolean leader, boolean commander) {
            this(uuid, playerName, className, leader, commander, (byte) 0, leader);
        }

        public SquadMemberInfo(UUID uuid, String playerName, String className,
                               boolean leader, boolean commander,
                               byte fireteam, boolean fireteamLeader) {
            this.uuid = uuid == null ? new UUID(0L, 0L) : uuid;
            this.playerName = playerName == null ? "" : playerName;
            this.className = className == null ? "" : className;
            this.leader = leader;
            this.commander = commander;
            this.fireteam = fireteam;
            this.fireteamLeader = fireteamLeader;
        }

        public SquadMemberInfo(FriendlyByteBuf buf) {
            this.uuid = buf.readUUID();
            this.playerName = buf.readUtf();
            this.className = buf.readUtf();
            this.leader = buf.readBoolean();
            this.commander = buf.readBoolean();
            this.fireteam = buf.readByte();
            this.fireteamLeader = buf.readBoolean();
        }

        public void write(FriendlyByteBuf buf) {
            buf.writeUUID(uuid);
            buf.writeUtf(playerName);
            buf.writeUtf(className);
            buf.writeBoolean(leader);
            buf.writeBoolean(commander);
            buf.writeByte(fireteam);
            buf.writeBoolean(fireteamLeader);
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SquadMemberInfo that)) return false;
            return uuid.equals(that.uuid)
                && leader == that.leader
                && commander == that.commander
                && fireteam == that.fireteam
                && fireteamLeader == that.fireteamLeader
                && Objects.equals(playerName, that.playerName)
                && Objects.equals(className, that.className);
        }

        @Override
        public int hashCode() {
            return Objects.hash(uuid, playerName, className, leader, commander, fireteam, fireteamLeader);
        }
    }

    public static class SquadCategoryInfo {
        public final String id;
        public final String displayName;

        public SquadCategoryInfo(String id, String displayName) {
            this.id = id == null ? "none" : id;
            this.displayName = displayName == null ? "无" : displayName;
        }
    }
}
