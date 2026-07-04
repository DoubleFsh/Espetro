package org.espetro.team;

public enum CommanderSkillType {

    DRONE_DETECTION("drone_detection", "无人机侦测", "释放无人机，使周围一定范围内的敌方玩家高亮显示");

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