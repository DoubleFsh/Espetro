package org.espetro.team;

public enum CommanderSkillType {

    DRONE_DETECTION("drone_detection", "无人机侦测", "释放无人机，使周围一定范围内的敌方玩家高亮显示"),
    VEHICLE_SUPPLY_STATION("vehicle_supply_station", "载具补给站", "在当前位置放置可配置的载具补给物资"),
    ARTILLERY_155("artillery_155", "155火炮支援", "打开战术地图选择炮击坐标，交由 KubeJS 回调执行火力效果");

    private final String id;
    private final String displayName;
    private final String description;

    CommanderSkillType(String id, String displayName, String description) {
        this.id = id;
        this.displayName = displayName;
        this.description = description;
    }

    public String getId() {
        return id;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    public static CommanderSkillType fromId(String id) {
        for (CommanderSkillType type : values()) {
            if (type.id.equals(id)) {
                return type;
            }
        }
        return null;
    }
}
