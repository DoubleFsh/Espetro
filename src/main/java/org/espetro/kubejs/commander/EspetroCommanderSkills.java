package org.espetro.kubejs.commander;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.Scriptable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.team.CommanderSkillManager;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class EspetroCommanderSkills {
    public static final String DEFAULT_ARTILLERY_SKILL_ID = "artillery_155";

    private static final Map<String, KubeCommanderSkillDefinition> DEFINITIONS = new LinkedHashMap<>();
    private static final Map<String, Function> SERVER_HANDLERS = new LinkedHashMap<>();

    private EspetroCommanderSkills() {
    }

    public static CommanderSkillBuilder create(String id) {
        return new CommanderSkillBuilder(id);
    }

    public static CommanderSkillBuilder skill(String id) {
        return create(id);
    }

    public static KubeCommanderSkillDefinition register(KubeCommanderSkillDefinition definition) {
        if (definition == null || definition.id().isBlank() || !definition.enabled()) {
            return definition;
        }
        if (definition.id().length() > 128) {
            Espetro.LOGGER.warn("忽略 ID 过长的 KubeJS 指挥官技能: {}", definition.id());
            return definition;
        }
        DEFINITIONS.put(definition.id(), definition);
        Espetro.LOGGER.info("已注册 KubeJS 指挥官技能: {} ({})", definition.id(), definition.displayName());
        return definition;
    }

    public static void registerDefaults() {
        registerBuiltInDefaults();
    }

    public static void clearDefinitions() {
        DEFINITIONS.clear();
    }

    public static void clearHandlers() {
        SERVER_HANDLERS.clear();
    }

    public static boolean on(String skillId, Function callback) {
        if (skillId == null || skillId.isBlank() || callback == null) {
            return false;
        }
        SERVER_HANDLERS.put(skillId.trim(), callback);
        Espetro.LOGGER.info("已注册 KubeJS 指挥官技能回调: {}", skillId.trim());
        return true;
    }

    public static boolean has(String skillId) {
        return getDefinition(skillId) != null;
    }

    @Nullable
    public static KubeCommanderSkillDefinition getDefinition(String skillId) {
        if (skillId == null) {
            return null;
        }
        return DEFINITIONS.get(skillId.trim());
    }

    public static List<KubeCommanderSkillDefinition> getDefinitions() {
        return DEFINITIONS.values().stream()
            .sorted(Comparator.comparing(KubeCommanderSkillDefinition::id))
            .toList();
    }

    public static String[] getSkillIds() {
        return getDefinitions().stream().map(KubeCommanderSkillDefinition::id).toArray(String[]::new);
    }

    public static boolean execute(KubeCommanderSkillDefinition definition, KubeCommanderSkillEvent event) {
        if (definition == null || event == null) {
            return false;
        }

        Function callback = SERVER_HANDLERS.get(definition.id());
        if (callback == null) {
            event.tell("§c指挥官技能 " + definition.displayName() + " 没有 KubeJS server_scripts 回调。");
            Espetro.LOGGER.warn("KubeJS 指挥官技能 {} 没有 server_scripts 回调", definition.id());
            return false;
        }

        try {
            Context cx = Context.enter();
            cx.setApplicationClassLoader(EspetroCommanderSkills.class.getClassLoader());
            Scriptable scope = callback.getParentScope();
            Object eventObject = Context.javaToJS(cx, event, scope);
            Object result = callback.call(cx, scope, scope, new Object[] {eventObject});
            return !Boolean.FALSE.equals(result);
        } catch (Exception e) {
            Espetro.LOGGER.error("执行 KubeJS 指挥官技能失败: {}", definition.id(), e);
            event.tell("§c指挥官技能 KubeJS 回调执行失败: " + e.getMessage());
            return false;
        }
    }

    public static boolean execute(ServerPlayer commander, String skillId) {
        return CommanderSkillManager.getInstance().activateSkill(commander, skillId);
    }

    public static boolean activate(ServerPlayer commander, String skillId) {
        return execute(commander, skillId);
    }

    public static boolean openTacticalMap(ServerPlayer commander, String skillId) {
        return CommanderSkillManager.getInstance().beginArtilleryTargetSelection(commander, skillId);
    }

    public static boolean openTargetMap(ServerPlayer commander, String skillId) {
        return openTacticalMap(commander, skillId);
    }

    public static boolean openArtilleryMap(ServerPlayer commander) {
        return CommanderSkillManager.getInstance().beginArtilleryTargetSelection(commander);
    }

    public static boolean isOnCooldown(ServerPlayer commander, String skillId) {
        return commander != null && isOnCooldown(commander.getUUID(), skillId);
    }

    public static boolean isOnCooldown(UUID commanderId, String skillId) {
        return commanderId != null && CommanderSkillManager.getInstance().isOnCooldown(commanderId, skillId);
    }

    public static int getCooldownSeconds(ServerPlayer commander, String skillId) {
        return commander == null ? 0 : getCooldownSeconds(commander.getUUID(), skillId);
    }

    public static int getCooldownSeconds(UUID commanderId, String skillId) {
        return commanderId == null ? 0
            : CommanderSkillManager.getInstance().getRemainingCooldownSeconds(commanderId, skillId);
    }

    public static Map<String, Integer> getCooldowns(ServerPlayer commander) {
        return commander == null ? Map.of()
            : CommanderSkillManager.getInstance().getCooldownData(commander.getUUID());
    }

    public static CommanderSkillManager.SkillStatus getStatus(ServerPlayer commander, String skillId) {
        return CommanderSkillManager.getInstance().getSkillStatus(commander, skillId);
    }

    public static boolean canUse(ServerPlayer commander, String skillId) {
        return getStatus(commander, skillId).canUse();
    }

    public static KubeCommanderSkillEvent event(KubeCommanderSkillDefinition definition,
                                                ServerPlayer commander,
                                                String team) {
        return new KubeCommanderSkillEvent(definition, commander, team);
    }

    public static KubeCommanderSkillEvent targetEvent(KubeCommanderSkillDefinition definition,
                                                      CommanderSkillManager.ArtillerySupportRequest request,
                                                      ServerPlayer commander,
                                                      ServerLevel level,
                                                      BlockPos blockPos) {
        return new KubeCommanderSkillEvent(definition, request, commander, level, blockPos);
    }

    public static List<CommanderSkillManager.SkillView> getSkillViews() {
        List<CommanderSkillManager.SkillView> views = new ArrayList<>();
        for (KubeCommanderSkillDefinition definition : getDefinitions()) {
            views.add(new CommanderSkillManager.SkillView(
                definition.id(),
                definition.displayName(),
                definition.description(),
                definition.stats().isBlank()
                    ? "§8KubeJS | 冷却: " + definition.cooldownSeconds() + "秒"
                    : definition.stats(),
                definition.icon()
            ));
        }
        return views;
    }

    private static void registerBuiltInDefaults() {
        create("drone_detection")
            .displayName("无人机侦测")
            .description("短时间高亮指挥官附近敌方玩家")
            .stats("§8高亮半径: 100格 | 持续: 10秒 | 冷却: 60秒")
            .icon("espetro:textures/gui/commander_skills/drone_detection.png")
            .activate()
            .cooldownSeconds(60)
            .register();

        create("vehicle_supply_station")
            .displayName("载具补给站")
            .description("在指挥官当前位置部署载具补给站")
            .stats("§8生成载具补给实体和方块 | 冷却: 120秒")
            .icon("espetro:textures/gui/commander_skills/vehicle_supply_station.png")
            .activate()
            .cooldownSeconds(120)
            .register();

        create(DEFAULT_ARTILLERY_SKILL_ID)
            .displayName("155火炮支援")
            .description("打开 ESPoints 战术地图选择炮击坐标，再交给 KubeJS 执行火力效果")
            .stats("§8ESPoints地图选点 | KubeJS两批实体炮击 | 冷却: 180秒")
            .icon("espetro:textures/gui/commander_skills/artillery_155.png")
            .targetMap()
            .cooldownSeconds(180)
            .register();
    }
}
