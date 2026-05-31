package org.espetro.team;

/**
 * 职业类型枚举
 * 定义可选择的职业（保留作为兼容性备用）
 * 主要职业数据现在从JSON配置加载
 */
public enum ClassType {
    // 进攻方职业
    RIFLEMAN("步枪兵", "标准突击步枪配置", "ATTACK"),
    MEDIC("医疗兵", "配备医疗用品", "ATTACK"),
    HEAVY("重火力", "轻机枪手", "ATTACK"),
    RECON("侦察兵", "狙击手配置", "ATTACK"),
    SUPPORT("支援兵", "工程师配置", "ATTACK"),

    // 防守方职业
    DEFENDER("防御者", "标准防守配置", "DEFEND"),
    GUARD("守卫", "近战防守", "DEFEND"),
    SNIPER("狙击手", "远程狙击", "DEFEND"),
    ENGINEER("工程师", "工事建造", "DEFEND"),
    MEDIC_DEFEND("战地医护", "防守医疗", "DEFEND");

    private final String defaultDisplayName;
    private final String defaultDescription;
    private final String team;

    ClassType(String displayName, String description, String team) {
        this.defaultDisplayName = displayName;
        this.defaultDescription = description;
        this.team = team;
    }

    /**
     * 获取显示名称
     */
    public String getDisplayName() {
        return defaultDisplayName;
    }

    /**
     * 获取描述
     */
    public String getDescription() {
        return defaultDescription;
    }

    /**
     * 获取队伍
     */
    public String getTeam() {
        return team;
    }

    /**
     * 根据队伍获取可用职业
     */
    public static ClassType[] getClassesForTeam(String team) {
        return java.util.Arrays.stream(values())
            .filter(c -> c.team.equals(team))
            .toArray(ClassType[]::new);
    }

    /**
     * 根据名称获取职业
     */
    public static ClassType fromName(String name) {
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
