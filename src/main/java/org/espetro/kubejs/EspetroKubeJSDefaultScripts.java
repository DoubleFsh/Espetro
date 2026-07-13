package org.espetro.kubejs;

import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

public final class EspetroKubeJSDefaultScripts {
    private static final String LEGACY_STARTUP_SCRIPT = "kubejs/startup_scripts/00_espetro_commander_skills.js";
    private static final String LEGACY_SERVER_SCRIPT = "kubejs/server_scripts/00_espetro_commander_skills.js";
    private static final String LEGACY_STARTUP_MARKER = "// Espetro 默认指挥官技能注册脚本。";
    private static final String LEGACY_SERVER_MARKER = "// Espetro 默认指挥官技能实现脚本。";

    private EspetroKubeJSDefaultScripts() {
    }

    public static void ensureDefaultScripts() {
        Path gameDir = FMLPaths.GAMEDIR.get();
        migrateLegacyDefaultScript(gameDir.resolve(LEGACY_STARTUP_SCRIPT), LEGACY_STARTUP_MARKER);
        migrateLegacyDefaultScript(gameDir.resolve(LEGACY_SERVER_SCRIPT), LEGACY_SERVER_MARKER);

        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_drone_detection.js"),
            DEFAULT_DRONE_DETECTION_STARTUP_SCRIPT, "// Espetro 默认指挥官技能注册脚本：无人机侦测。");
        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_vehicle_supply_station.js"),
            DEFAULT_VEHICLE_SUPPLY_STATION_STARTUP_SCRIPT, "// Espetro 默认指挥官技能注册脚本：载具补给站。");
        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_artillery_155.js"),
            DEFAULT_ARTILLERY_155_STARTUP_SCRIPT, "// Espetro 默认指挥官技能注册脚本：155火炮支援。");

        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_drone_detection.js"),
            DEFAULT_DRONE_DETECTION_SERVER_SCRIPT, "// Espetro 默认指挥官技能实现脚本：无人机侦测。");
        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_vehicle_supply_station.js"),
            DEFAULT_VEHICLE_SUPPLY_STATION_SERVER_SCRIPT, "// Espetro 默认指挥官技能实现脚本：载具补给站。");
        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_artillery_155.js"),
            DEFAULT_ARTILLERY_155_SERVER_SCRIPT, "// Espetro 默认指挥官技能实现脚本：155火炮支援。");
    }

    private static void migrateLegacyDefaultScript(Path path, String marker) {
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }

            String current = Files.readString(path, StandardCharsets.UTF_8);
            if (!current.startsWith(marker)) {
                return;
            }

            Path disabledPath = path.resolveSibling(path.getFileName() + ".disabled");
            Files.move(path, disabledPath, StandardCopyOption.REPLACE_EXISTING);
            Espetro.LOGGER.info("已停用旧版合并 KubeJS 指挥官技能脚本: {} -> {}", path, disabledPath);
        } catch (IOException e) {
            Espetro.LOGGER.warn("无法停用旧版合并 KubeJS 指挥官技能脚本: {}", path, e);
        }
    }

    private static void writeDefaultScript(Path path, String source, String marker) {
        try {
            if (Files.isRegularFile(path)) {
                String current = Files.readString(path, StandardCharsets.UTF_8);
                if (current.startsWith(marker) && !current.equals(source)) {
                    Files.writeString(path, source, StandardCharsets.UTF_8);
                    Espetro.LOGGER.info("已更新默认 KubeJS 指挥官技能脚本: {}", path);
                }
                return;
            }
            Files.createDirectories(path.getParent());
            Files.writeString(path, source, StandardCharsets.UTF_8);
            Espetro.LOGGER.info("已写入默认 KubeJS 指挥官技能脚本: {}", path);
        } catch (IOException e) {
            Espetro.LOGGER.warn("无法写入默认 KubeJS 指挥官技能脚本: {}", path, e);
        }
    }

    private static final String DEFAULT_DRONE_DETECTION_STARTUP_SCRIPT = """
        // Espetro 默认指挥官技能注册脚本：无人机侦测。
        // 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
        EspetroCommanderSkills.create('drone_detection')
          .displayName('无人机侦测')
          .description('短时间高亮指挥官附近敌方玩家')
          .stats('§8高亮半径: 100格 | 持续: 10秒 | 冷却: 60秒')
          .activate()
          .cooldownSeconds(60)
          .register()
        """;

    private static final String DEFAULT_VEHICLE_SUPPLY_STATION_STARTUP_SCRIPT = """
        // Espetro 默认指挥官技能注册脚本：载具补给站。
        // 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
        EspetroCommanderSkills.create('vehicle_supply_station')
          .displayName('载具补给站')
          .description('在指挥官当前位置部署载具补给站')
          .stats('§8生成载具补给实体和方块 | 冷却: 120秒')
          .activate()
          .cooldownSeconds(120)
          .register()
        """;

    private static final String DEFAULT_ARTILLERY_155_STARTUP_SCRIPT = """
        // Espetro 默认指挥官技能注册脚本：155火炮支援。
        // 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
        EspetroCommanderSkills.create('artillery_155')
          .displayName('155火炮支援')
          .description('打开 ESPoints 战术地图选择炮击坐标，再交给 KubeJS 执行火力效果')
          .stats('§8ESPoints地图选点 | KubeJS两批实体炮击 | 冷却: 180秒')
          .targetMap()
          .cooldownSeconds(180)
          .register()
        """;

    private static final String DEFAULT_DRONE_DETECTION_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：无人机侦测。
        // 本文件由 Espetro 首次启动时写入；效果尽量使用 KubeJS 封装的原版对象方法实现。
        var EspetroDroneMobEffects = Java.loadClass('net.minecraft.world.effect.MobEffects')

        EspetroCommanderSkills.on('drone_detection', event => {
          const range = 100.0
          const durationSeconds = 10
          const commander = event.commander()
          const commanderTeam = event.team()
          const level = event.level()

          if (commanderTeam == null || String(commanderTeam).length === 0) {
            event.tell('§c你不属于任何阵营，无法发动无人机侦测！')
            return false
          }

          let count = 0
          const players = level.getPlayers()
          for (let i = 0; i < players.size(); i++) {
            const target = players.get(i)
            if (String(target.getUUID()) === String(commander.getUUID())) continue
            const targetTeam = Espetro.getPlayerTeam(target)
            if (targetTeam == null || String(targetTeam) === String(commanderTeam)) continue
            if (target.getDistance(commander.getX(), commander.getY(), commander.getZ()) > range) continue

            target.getPotionEffects().add(EspetroDroneMobEffects.GLOWING, durationSeconds * 20, 0, false, false)
            count++
          }

          console.info('[Espetro] drone_detection highlighted enemies through KubeJS wrappers within ' + range + ' blocks: ' + count)
          event.tell('§a无人机侦测已执行，范围内高亮敌方玩家: ' + count)
          return true
        })
        """;

    private static final String DEFAULT_VEHICLE_SUPPLY_STATION_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：载具补给站。
        // 本文件由 Espetro 首次启动时写入；效果尽量使用 KubeJS 封装的原版对象方法实现。
        var EspetroSupplyBuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
        var EspetroSupplyResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
        var EspetroSupplyComponent = Java.loadClass('net.minecraft.network.chat.Component')
        var EspetroSupplyUUID = Java.loadClass('java.util.UUID')
        var EspetroSupplySoundEvents = Java.loadClass('net.minecraft.sounds.SoundEvents')

        EspetroCommanderSkills.on('vehicle_supply_station', event => {
          const commander = event.commander()
          const team = event.team()
          const level = event.level()
          const x = event.blockX()
          const y = event.blockY()
          const z = event.blockZ()
          const stationId = EspetroSupplyUUID.randomUUID()

          if (team == null || String(team).length === 0) {
            event.tell('§c你不属于任何阵营，无法部署载具补给站！')
            return false
          }

          const barrel = level.getBlock(x + 2, y, z)
          if (!espetroSupplyIsReplaceable(barrel)) {
            event.tell('§c载具补给站方块位置已有方块: ' + (x + 2) + ', ' + y + ', ' + z)
            return false
          }
          barrel.set(espetroSupplyId('minecraft:barrel'))

          const entityType = espetroSupplyEntityType('minecraft:armor_stand')
          if (entityType == null) {
            barrel.set(espetroSupplyId('minecraft:air'))
            event.tell('§c载具补给站实体类型无效。')
            return false
          }

          const entity = level.createEntity(entityType)
          if (entity == null) {
            barrel.set(espetroSupplyId('minecraft:air'))
            event.tell('§c载具补给站实体创建失败。')
            return false
          }

          entity.setPositionAndRotation(x + 0.5, y, z + 0.5, 0, 0)
          entity.setCustomName(EspetroSupplyComponent.literal('载具补给站'))
          entity.setCustomNameVisible(true)
          entity.addTag('espetro_vehicle_supply_station')
          entity.addTag('espetro_vehicle_supply_station_team_' + team)
          entity.addTag('espetro_vehicle_supply_station_id_' + stationId)
          entity.addTag('espetro_team_' + team)
          entity.addTag('espetro_commander_skill')

          const data = entity.getPersistentData()
          data.putString('espetro_vehicle_supply_station_team', String(team))
          data.putUUID('espetro_vehicle_supply_station_id', stationId)
          data.putInt('espetro_vehicle_supply_station_x', x)
          data.putInt('espetro_vehicle_supply_station_y', y)
          data.putInt('espetro_vehicle_supply_station_z', z)
          data.putString('espetro_vehicle_supply_station_block_id', 'minecraft:barrel')
          data.putInt('espetro_vehicle_supply_station_block_x', x + 2)
          data.putInt('espetro_vehicle_supply_station_block_y', y)
          data.putInt('espetro_vehicle_supply_station_block_z', z)

          entity.spawn()
          commander.playSound(EspetroSupplySoundEvents.ITEM_PICKUP, 0.8, 1.0)
          event.tell('§a载具补给站已部署！位置: ' + x + ', ' + y + ', ' + z)
          console.info('[Espetro] vehicle_supply_station deployed through KubeJS wrappers')
          return true
        })

        function espetroSupplyId(id) {
          const normalized = String(id).indexOf(':') >= 0 ? String(id) : 'minecraft:' + id
          return EspetroSupplyResourceLocation.tryParse(normalized)
        }

        function espetroSupplyEntityType(id) {
          const location = espetroSupplyId(id)
          if (location == null || !EspetroSupplyBuiltInRegistries.ENTITY_TYPE.containsKey(location)) return null
          return EspetroSupplyBuiltInRegistries.ENTITY_TYPE.get(location)
        }

        function espetroSupplyIsReplaceable(block) {
          const id = String(block.getId())
          return id === 'minecraft:air' || id === 'minecraft:cave_air' || id === 'minecraft:void_air'
        }
        """;

    private static final String DEFAULT_ARTILLERY_155_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：155火炮支援。
        // 本文件由 Espetro 首次启动时写入；火炮效果完全由 KubeJS server_scripts 实现。
        var EspetroArtilleryBuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
        var EspetroArtilleryResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
        var EspetroArtilleryTagParser = Java.loadClass('net.minecraft.nbt.TagParser')
        var EspetroArtilleryTasks = []
        var EspetroArtilleryTick = 0

        ServerEvents.tick(event => {
          EspetroArtilleryTick++
          if (EspetroArtilleryTasks.length === 0) return

          for (let i = EspetroArtilleryTasks.length - 1; i >= 0; i--) {
            const task = EspetroArtilleryTasks[i]
            if (task.tick <= EspetroArtilleryTick) {
              try {
                task.run()
              } catch (error) {
                console.error('[Espetro] artillery_155 scheduled task failed: ' + error)
              }
              EspetroArtilleryTasks.splice(i, 1)
            }
          }
        })

        function espetroScheduleArtilleryTask(delayTicks, task) {
          EspetroArtilleryTasks.push({
            tick: EspetroArtilleryTick + Math.max(0, Math.floor(delayTicks)),
            run: task
          })
        }

        EspetroCommanderSkills.on('artillery_155', event => {
          const fired = espetroFirePureKubeArtillery(event, {
            entity: 'minecraft:tnt',
            nbt: '{Fuse:260s}',
            targetY: event.y(),
            impactRadius: 90,
            launchHeight: 600,
            clampSpawnYToBuildHeight: true,
            sourceDistance: 160,
            sourceRange: 70,
            velocity: 3.5,
            firstBatchShots: 2,
            firstBatchIntervalTicks: 20 * 20,
            secondBatchDelayTicks: 2 * 20 * 20,
            secondBatchWaves: 6,
            secondBatchIntervalTicks: 4 * 20,
            secondBatchEntitiesPerWave: 4,
            approachYawDegrees: Math.random() * 360
          })
          console.info('[Espetro] artillery_155 queued through KubeJS wrappers: ' + fired)
          return fired
        })

        function espetroFirePureKubeArtillery(event, config) {
          if (!event.hasTarget()) {
            event.tell('§c155火炮支援缺少战术地图目标坐标。')
            return false
          }

          const cfg = espetroNormalizeArtilleryConfig(event, config)
          if (cfg.entityType == null) {
            event.tell('§c155火炮支援实体 ID 无效: ' + cfg.entity)
            return false
          }

          for (let i = 0; i < cfg.firstBatchShots; i++) {
            espetroQueueArtilleryWave(event, cfg, i * cfg.firstBatchIntervalTicks, 1)
          }

          for (let wave = 0; wave < cfg.secondBatchWaves; wave++) {
            espetroQueueArtilleryWave(
              event,
              cfg,
              cfg.secondBatchDelayTicks + wave * cfg.secondBatchIntervalTicks,
              cfg.secondBatchEntitiesPerWave
            )
          }

          const total = cfg.firstBatchShots + cfg.secondBatchWaves * cfg.secondBatchEntitiesPerWave
          if (total > 0) {
            event.tell('§a155火炮支援已排定，预计发射实体: ' + total)
          }
          return total > 0
        }

        function espetroNormalizeArtilleryConfig(event, config) {
          const firstInterval = espetroIntConfig(config, 'firstBatchIntervalTicks', 20 * 20)
          const firstShots = espetroIntConfig(config, 'firstBatchShots', 2)
          const entity = espetroStringConfig(config, 'entity', espetroStringConfig(config, 'entityId', 'minecraft:tnt'))
          const level = event.level()
          const targetY = espetroNumberConfig(config, 'targetY', event.y())
          const launchHeight = espetroNumberConfig(config, 'launchHeight', 600)
          const minSpawnY = espetroNumberConfig(config, 'minSpawnY', level.getMinBuildHeight() + 2)
          const maxSpawnY = espetroNumberConfig(config, 'maxSpawnY', level.getMaxBuildHeight() - 2)
          return {
            level: level,
            commander: event.commander(),
            skillId: event.skillId(),
            entity: entity,
            entityType: espetroArtilleryEntityType(entity),
            nbt: espetroStringConfig(config, 'nbt', ''),
            centerX: event.x(),
            centerZ: event.z(),
            targetY: targetY,
            impactRadius: Math.max(0, espetroNumberConfig(config, 'impactRadius', espetroNumberConfig(config, 'radius', 80))),
            launchHeight: launchHeight,
            minSpawnY: Math.min(minSpawnY, maxSpawnY),
            maxSpawnY: Math.max(minSpawnY, maxSpawnY),
            clampSpawnYToBuildHeight: espetroBooleanConfig(config, 'clampSpawnYToBuildHeight', true),
            sourceDistance: Math.max(0, espetroNumberConfig(config, 'sourceDistance', 260)),
            sourceRange: Math.max(0, espetroNumberConfig(config, 'sourceRange', espetroNumberConfig(config, 'sourceSpread', 90))),
            velocity: Math.max(0, espetroNumberConfig(config, 'velocity', 3.2)),
            inaccuracy: Math.max(0, espetroNumberConfig(config, 'inaccuracy', 0)),
            approachYawDegrees: espetroNumberConfig(config, 'approachYawDegrees', Math.random() * 360),
            firstBatchShots: Math.max(0, firstShots),
            firstBatchIntervalTicks: Math.max(0, firstInterval),
            secondBatchDelayTicks: Math.max(0, espetroIntConfig(config, 'secondBatchDelayTicks', firstShots * firstInterval)),
            secondBatchWaves: Math.max(0, espetroIntConfig(config, 'secondBatchWaves', espetroIntConfig(config, 'secondBatchTimes', 6))),
            secondBatchIntervalTicks: Math.max(0, espetroIntConfig(config, 'secondBatchIntervalTicks', 4 * 20)),
            secondBatchEntitiesPerWave: Math.max(0, espetroIntConfig(config, 'secondBatchEntitiesPerWave', espetroIntConfig(config, 'secondBatchEntitiesPerShot', 4)))
          }
        }

        function espetroQueueArtilleryWave(event, cfg, delayTicks, count) {
          for (let i = 0; i < count; i++) {
            const target = espetroRandomPointInCircle(cfg.centerX, cfg.centerZ, cfg.impactRadius)
            const source = espetroRandomSourcePoint(target[0], target[1], cfg)
            const targetX = target[0] + espetroRandomSigned(cfg.inaccuracy)
            const targetZ = target[1] + espetroRandomSigned(cfg.inaccuracy)
            let spawnY = cfg.targetY + cfg.launchHeight
            if (cfg.clampSpawnYToBuildHeight) {
              spawnY = espetroClamp(spawnY, cfg.minSpawnY, cfg.maxSpawnY)
            }
            const motion = espetroMotionToward(source[0], spawnY, source[1], targetX, cfg.targetY, targetZ, cfg.velocity)
            const rotation = espetroRotationFromMotion(motion)
            espetroScheduleArtilleryTask(delayTicks, () => {
              espetroSpawnArtilleryEntity(cfg, source[0], spawnY, source[1], targetX, cfg.targetY, targetZ, motion, rotation)
            })
          }
        }

        function espetroSpawnArtilleryEntity(cfg, spawnX, spawnY, spawnZ, targetX, targetY, targetZ, motion, rotation) {
          espetroLoadChunk(cfg.level, spawnX, spawnZ)
          espetroLoadChunk(cfg.level, targetX, targetZ)

          const entity = cfg.level.createEntity(cfg.entityType)
          if (entity == null) {
            console.warn('[Espetro] artillery_155 could not create entity: ' + cfg.entity)
            return false
          }

          const nbt = espetroParseNbt(cfg.nbt)
          if (nbt != null) {
            entity.mergeNbt(nbt)
          }

          entity.setPositionAndRotation(spawnX, spawnY, spawnZ, rotation[0], rotation[1])
          entity.setMotionX(motion[0])
          entity.setMotionY(motion[1])
          entity.setMotionZ(motion[2])
          entity.addTag('espetro_commander_skill')
          entity.addTag('espetro_artillery_155')

          const data = entity.getPersistentData()
          data.putString('espetro_commander_skill', String(cfg.skillId))
          data.putDouble('espetro_artillery_target_x', targetX)
          data.putDouble('espetro_artillery_target_y', targetY)
          data.putDouble('espetro_artillery_target_z', targetZ)

          entity.spawn()
          entity.setMotionX(motion[0])
          entity.setMotionY(motion[1])
          entity.setMotionZ(motion[2])
          return true
        }

        function espetroArtilleryEntityType(id) {
          const location = espetroArtilleryId(id)
          if (location == null || !EspetroArtilleryBuiltInRegistries.ENTITY_TYPE.containsKey(location)) return null
          return EspetroArtilleryBuiltInRegistries.ENTITY_TYPE.get(location)
        }

        function espetroArtilleryId(id) {
          const normalized = String(id).indexOf(':') >= 0 ? String(id) : 'minecraft:' + id
          return EspetroArtilleryResourceLocation.tryParse(normalized)
        }

        function espetroParseNbt(nbt) {
          const trimmed = (nbt || '').trim()
          if (trimmed.length === 0) return null
          try {
            return EspetroArtilleryTagParser.parseTag(trimmed)
          } catch (error) {
            console.warn('[Espetro] artillery_155 ignored invalid entity NBT: ' + error)
            return null
          }
        }

        function espetroLoadChunk(level, x, z) {
          level.getChunk(Math.floor(x / 16), Math.floor(z / 16))
        }

        function espetroRandomPointInCircle(centerX, centerZ, radius) {
          const angle = Math.random() * Math.PI * 2
          const distance = Math.sqrt(Math.random()) * Math.max(0, radius)
          return [
            centerX + Math.cos(angle) * distance,
            centerZ + Math.sin(angle) * distance
          ]
        }

        function espetroRandomSourcePoint(targetX, targetZ, cfg) {
          const radians = cfg.approachYawDegrees * Math.PI / 180
          const directionX = Math.cos(radians)
          const directionZ = Math.sin(radians)
          const centerX = targetX - directionX * cfg.sourceDistance
          const centerZ = targetZ - directionZ * cfg.sourceDistance
          const offset = espetroRandomPointInCircle(0, 0, cfg.sourceRange)
          return [centerX + offset[0], centerZ + offset[1]]
        }

        function espetroMotionToward(spawnX, spawnY, spawnZ, targetX, targetY, targetZ, velocity) {
          const dx = targetX - spawnX
          const dy = targetY - spawnY
          const dz = targetZ - spawnZ
          const length = Math.sqrt(dx * dx + dy * dy + dz * dz)
          if (length <= 0.000001 || velocity <= 0) return [0, 0, 0]
          return [dx / length * velocity, dy / length * velocity, dz / length * velocity]
        }

        function espetroRotationFromMotion(motion) {
          const horizontal = Math.sqrt(motion[0] * motion[0] + motion[2] * motion[2])
          const yaw = Math.atan2(motion[2], motion[0]) * 180 / Math.PI - 90
          const pitch = -(Math.atan2(motion[1], horizontal) * 180 / Math.PI)
          return [yaw, pitch]
        }

        function espetroStringConfig(config, key, fallback) {
          const value = config && config[key]
          return value === undefined || value === null ? fallback : String(value)
        }

        function espetroNumberConfig(config, key, fallback) {
          const value = Number(config && config[key])
          return Number.isFinite(value) ? value : fallback
        }

        function espetroBooleanConfig(config, key, fallback) {
          const value = config && config[key]
          if (value === undefined || value === null) return fallback
          return value === true || String(value).toLowerCase() === 'true'
        }

        function espetroIntConfig(config, key, fallback) {
          return Math.round(espetroNumberConfig(config, key, fallback))
        }

        function espetroRandomSigned(value) {
          return value <= 0 ? 0 : (Math.random() * 2 - 1) * value
        }

        function espetroClamp(value, min, max) {
          return Math.max(min, Math.min(max, value))
        }

        function espetroFiniteNumber(value) {
          return Number.isFinite(value) ? value : 0
        }
        """;
}
