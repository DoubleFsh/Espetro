package org.espetro.script;

import dev.latvian.mods.rhino.Context;
import dev.latvian.mods.rhino.Function;
import dev.latvian.mods.rhino.ScriptRuntime;
import dev.latvian.mods.rhino.Scriptable;
import dev.latvian.mods.rhino.ScriptableObject;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.Vec3;
import org.espetro.Espetro;
import org.espetro.team.CommanderSkillManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

public class CommanderScriptAPI {
    private final CommanderScriptManager manager;
    private final CommanderScriptEvent event;
    private final Random random = new Random();

    public CommanderScriptAPI(CommanderScriptManager manager, CommanderScriptEvent event) {
        this.manager = manager;
        this.event = event;
    }

    public void log(String message) {
        Espetro.LOGGER.info("[KubeJSCommander:{}] {}", event.getSkillId(), message);
    }

    public void warn(String message) {
        Espetro.LOGGER.warn("[KubeJSCommander:{}] {}", event.getSkillId(), message);
    }

    public void tell(String message) {
        event.tell(message);
    }

    public void command(String command) {
        event.getServer().getCommands().performPrefixedCommand(
            event.getServer().createCommandSourceStack(), command);
    }

    public void schedule(int delayTicks, Function callback) {
        manager.scheduleScriptCallback(Math.max(0, delayTicks), callback, event);
    }

    public int droneDetection() {
        return droneDetection(100.0D, 10);
    }

    public int droneDetection(double range, int durationSeconds) {
        return CommanderSkillManager.getInstance().runDroneDetection(
            event.getCommander(),
            Math.max(0.0D, range),
            Math.max(0, durationSeconds)
        );
    }

    public boolean deployVehicleSupplyStation() {
        return CommanderSkillManager.getInstance().deployVehicleSupplyStation(
            event.getCommander(),
            defaultVehicleSupplyStationPlacements()
        );
    }

    public boolean deployVehicleSupplyStation(Object configObject) {
        List<CommanderSkillManager.VehicleSupplyStationPlacement> placements =
            parseVehicleSupplyStationPlacements(configObject);
        return CommanderSkillManager.getInstance().deployVehicleSupplyStation(event.getCommander(), placements);
    }

    public boolean fireBatched(Object configObject) {
        ScriptConfig config = new ScriptConfig(configObject);
        String entityId = config.string("entity", config.string("entityId", "minecraft:tnt"));
        String nbt = config.string("nbt", "");
        if (!isValidEntityType(entityId)) {
            warn("无效实体 ID: " + entityId);
            return false;
        }

        double targetY = config.number("targetY", event.getY());
        double impactRadius = Math.max(0.0D, config.number("impactRadius", config.number("radius", 80.0D)));
        double launchHeight = config.number("launchHeight", 600.0D);
        double sourceDistance = Math.max(0.0D, config.number("sourceDistance", 260.0D));
        double sourceRange = Math.max(0.0D, config.number("sourceRange", config.number("sourceSpread", 90.0D)));
        double velocity = Math.max(0.0D, config.number("velocity", 3.2D));
        double inaccuracy = Math.max(0.0D, config.number("inaccuracy", 0.0D));
        double approachYaw = config.number("approachYawDegrees", random.nextDouble() * 360.0D);

        int firstBatchShots = Math.max(0, config.integer("firstBatchShots", 2));
        int firstBatchIntervalTicks = Math.max(0, config.integer("firstBatchIntervalTicks", 20 * 20));
        int secondBatchDelayTicks = Math.max(0,
            config.integer("secondBatchDelayTicks", firstBatchShots * firstBatchIntervalTicks));
        int secondBatchWaves = Math.max(0, config.integer("secondBatchWaves",
            config.integer("secondBatchTimes", 6)));
        int secondBatchIntervalTicks = Math.max(0, config.integer("secondBatchIntervalTicks", 4 * 20));
        int secondBatchEntitiesPerWave = Math.max(0, config.integer("secondBatchEntitiesPerWave",
            config.integer("secondBatchEntitiesPerShot", 4)));

        BarragePlan plan = new BarragePlan(entityId, nbt, targetY, impactRadius, launchHeight,
            sourceDistance, sourceRange, velocity, inaccuracy, approachYaw);

        for (int i = 0; i < firstBatchShots; i++) {
            manager.scheduleJavaTask(i * firstBatchIntervalTicks, () -> fireWave(plan, 1));
        }

        for (int wave = 0; wave < secondBatchWaves; wave++) {
            int delay = secondBatchDelayTicks + wave * secondBatchIntervalTicks;
            manager.scheduleJavaTask(delay, () -> fireWave(plan, secondBatchEntitiesPerWave));
        }
        return true;
    }

    public boolean fireEntity(String entityId,
                              double spawnX, double spawnY, double spawnZ,
                              double targetX, double targetY, double targetZ,
                              double velocity) {
        return fireEntity(entityId, "", spawnX, spawnY, spawnZ, targetX, targetY, targetZ, velocity);
    }

    public boolean fireEntity(String entityId,
                              String nbt,
                              double spawnX, double spawnY, double spawnZ,
                              double targetX, double targetY, double targetZ,
                              double velocity) {
        if (!allFinite(spawnX, spawnY, spawnZ, targetX, targetY, targetZ, velocity)) {
            warn("实体发射参数包含非法数字，已跳过");
            return false;
        }

        if (!isValidEntityType(entityId)) {
            warn("无效实体 ID: " + entityId);
            return false;
        }

        ResourceLocation location = ResourceLocation.tryParse(entityId);
        EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(location);
        ServerLevel level = event.getLevel();
        Entity entity = type.create(level);
        if (entity == null) {
            warn("实体无法创建: " + entityId);
            return false;
        }

        applyNbt(entity, nbt);
        entity.setPos(spawnX, spawnY, spawnZ);

        Vec3 delta = new Vec3(targetX - spawnX, targetY - spawnY, targetZ - spawnZ);
        if (delta.lengthSqr() > 1.0E-6D) {
            Vec3 motion = delta.normalize().scale(Math.max(0.0D, velocity));
            entity.setDeltaMovement(motion);
            entity.setYRot((float) (Math.atan2(motion.z, motion.x) * 180.0D / Math.PI) - 90.0F);
            double horizontal = Math.sqrt(motion.x * motion.x + motion.z * motion.z);
            entity.setXRot((float) (-(Math.atan2(motion.y, horizontal) * 180.0D / Math.PI)));
        }

        if (entity instanceof Projectile projectile) {
            projectile.setOwner(event.getCommander());
        }

        level.getChunk(BlockPos.containing(spawnX, 0.0D, spawnZ));
        level.getChunk(BlockPos.containing(targetX, 0.0D, targetZ));

        return level.addFreshEntity(entity);
    }

    public boolean isValidEntityType(String entityId) {
        if (entityId == null || entityId.isBlank()) {
            return false;
        }
        ResourceLocation location = ResourceLocation.tryParse(entityId);
        return location != null && BuiltInRegistries.ENTITY_TYPE.containsKey(location);
    }

    private void fireWave(BarragePlan plan, int count) {
        for (int i = 0; i < count; i++) {
            double[] target = randomPointInCircle(event.getX(), event.getZ(), plan.impactRadius());
            double[] source = randomSourcePoint(target[0], target[1], plan);
            double targetX = target[0] + randomSigned(plan.inaccuracy());
            double targetZ = target[1] + randomSigned(plan.inaccuracy());
            fireEntity(plan.entityId(), plan.nbt(),
                source[0], plan.targetY() + plan.launchHeight(), source[1],
                targetX, plan.targetY(), targetZ, plan.velocity());
        }
    }

    private double[] randomSourcePoint(double targetX, double targetZ, BarragePlan plan) {
        double radians = Math.toRadians(plan.approachYawDegrees());
        double directionX = Math.cos(radians);
        double directionZ = Math.sin(radians);
        double centerX = targetX - directionX * plan.sourceDistance();
        double centerZ = targetZ - directionZ * plan.sourceDistance();
        double[] offset = randomPointInCircle(0.0D, 0.0D, plan.sourceRange());
        return new double[] {centerX + offset[0], centerZ + offset[1]};
    }

    public double[] randomPointInCircle(double centerX, double centerZ, double radius) {
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double distance = Math.sqrt(random.nextDouble()) * Math.max(0.0D, radius);
        return new double[] {
            centerX + Math.cos(angle) * distance,
            centerZ + Math.sin(angle) * distance
        };
    }

    private double randomSigned(double value) {
        return value <= 0.0D ? 0.0D : (random.nextDouble() * 2.0D - 1.0D) * value;
    }

    private boolean allFinite(double... values) {
        for (double value : values) {
            if (!Double.isFinite(value)) {
                return false;
            }
        }
        return true;
    }

    private void applyNbt(Entity entity, String nbt) {
        if (nbt == null || nbt.isBlank()) {
            return;
        }
        try {
            CompoundTag tag = TagParser.parseTag(nbt);
            tag.putString("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
            entity.load(tag);
        } catch (Exception e) {
            warn("实体 NBT 解析失败: " + e.getMessage());
        }
    }

    private List<CommanderSkillManager.VehicleSupplyStationPlacement> parseVehicleSupplyStationPlacements(Object configObject) {
        Object source = new ScriptConfig(configObject).value("entities");
        if (source == null) {
            source = configObject;
        }

        List<CommanderSkillManager.VehicleSupplyStationPlacement> placements = new ArrayList<>();
        if (source instanceof List<?> list) {
            for (Object element : list) {
                addVehicleSupplyStationPlacement(placements, element);
            }
        } else if (source instanceof Scriptable scriptable && isArrayLike(scriptable)) {
            int length = scriptArrayLength(scriptable);
            for (int i = 0; i < length; i++) {
                Object element = ScriptableObject.getProperty(scriptable, i, Context.enter());
                if (element != Scriptable.NOT_FOUND) {
                    addVehicleSupplyStationPlacement(placements, element);
                }
            }
        } else {
            addVehicleSupplyStationPlacement(placements, source);
        }

        return placements.isEmpty() ? defaultVehicleSupplyStationPlacements() : List.copyOf(placements);
    }

    private void addVehicleSupplyStationPlacement(
        List<CommanderSkillManager.VehicleSupplyStationPlacement> placements,
        Object placementObject
    ) {
        if (placementObject == null || placementObject == Scriptable.NOT_FOUND) {
            return;
        }

        ScriptConfig config = new ScriptConfig(placementObject);
        String entityType = config.string("entity",
            config.string("entityId",
                config.string("entity_type",
                    config.string("entity_id", "minecraft:armor_stand"))));
        if (entityType == null || entityType.isBlank()) {
            return;
        }

        String customName = config.string("customName", config.string("custom_name", "载具补给站"));
        int x = config.integer("x", 0);
        int y = config.integer("y", 0);
        int z = config.integer("z", 0);
        float yaw = (float) config.number("yaw", 0.0D);
        CommanderSkillManager.VehicleSupplyStationBlockPlacement block = parseSupplyStationBlock(config);
        placements.add(new CommanderSkillManager.VehicleSupplyStationPlacement(
            entityType, customName, x, y, z, yaw, block));
    }

    private CommanderSkillManager.VehicleSupplyStationBlockPlacement parseSupplyStationBlock(ScriptConfig entityConfig) {
        ScriptConfig blockConfig = new ScriptConfig(entityConfig.value("block"));
        String blockId = blockConfig.string("id",
            blockConfig.string("block_id",
                blockConfig.string("block",
                    entityConfig.string("block_id", "minecraft:barrel"))));

        int x = blockConfig.integer("x", entityConfig.integer("block_x", 2));
        int y = blockConfig.integer("y", entityConfig.integer("block_y", 0));
        int z = blockConfig.integer("z", entityConfig.integer("block_z", 0));

        int[] offset = readIntVector(blockConfig.value("offset"));
        if (offset != null) {
            x = offset[0];
            y = offset[1];
            z = offset[2];
        }

        return new CommanderSkillManager.VehicleSupplyStationBlockPlacement(blockId, x, y, z);
    }

    private List<CommanderSkillManager.VehicleSupplyStationPlacement> defaultVehicleSupplyStationPlacements() {
        return List.of(new CommanderSkillManager.VehicleSupplyStationPlacement(
            "minecraft:armor_stand",
            "载具补给站",
            0, 0, 0, 0.0F,
            new CommanderSkillManager.VehicleSupplyStationBlockPlacement("minecraft:barrel", 2, 0, 0)
        ));
    }

    private int[] readIntVector(Object value) {
        if (value instanceof List<?> list && list.size() >= 3) {
            return new int[] {
                toInt(list.get(0), 0),
                toInt(list.get(1), 0),
                toInt(list.get(2), 0)
            };
        }
        if (value instanceof Scriptable scriptable && scriptArrayLength(scriptable) >= 3) {
            return new int[] {
                toInt(ScriptableObject.getProperty(scriptable, 0, Context.enter()), 0),
                toInt(ScriptableObject.getProperty(scriptable, 1, Context.enter()), 0),
                toInt(ScriptableObject.getProperty(scriptable, 2, Context.enter()), 0)
            };
        }
        return null;
    }

    private boolean isArrayLike(Scriptable scriptable) {
        return scriptArrayLength(scriptable) >= 0;
    }

    private int scriptArrayLength(Scriptable scriptable) {
        Object length = ScriptableObject.getProperty(scriptable, "length", Context.enter());
        if (length == Scriptable.NOT_FOUND) {
            return -1;
        }
        return Math.max(0, toInt(length, 0));
    }

    private int toInt(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || value == Scriptable.NOT_FOUND) {
            return fallback;
        }
        return (int) Math.round(ScriptRuntime.toNumber(Context.enter(), value));
    }

    private record BarragePlan(String entityId,
                               String nbt,
                               double targetY,
                               double impactRadius,
                               double launchHeight,
                               double sourceDistance,
                               double sourceRange,
                               double velocity,
                               double inaccuracy,
                               double approachYawDegrees) {
    }

    private static final class ScriptConfig {
        private final Object object;

        private ScriptConfig(Object object) {
            this.object = object;
        }

        private String string(String key, String fallback) {
            Object value = value(key);
            if (value == null) {
                return fallback;
            }
            return ScriptRuntime.toString(Context.enter(), value);
        }

        private int integer(String key, int fallback) {
            return (int) Math.round(number(key, fallback));
        }

        private double number(String key, double fallback) {
            Object value = value(key);
            if (value == null) {
                return fallback;
            }
            return ScriptRuntime.toNumber(Context.enter(), value);
        }

        private Object value(String key) {
            if (object instanceof Scriptable scriptable) {
                Object value = ScriptableObject.getProperty(scriptable, key, Context.enter());
                return value == Scriptable.NOT_FOUND ? null : value;
            }
            if (object instanceof Map<?, ?> map) {
                return map.get(key);
            }
            return null;
        }
    }
}
