package org.espetro.kubejs;

import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class EspetroKubeJSDefaultScripts {
    private EspetroKubeJSDefaultScripts() {
    }

    public static void ensureDefaultScripts() {
        Path gameDir = FMLPaths.GAMEDIR.get();

        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_drone_detection.js"),
            DEFAULT_DRONE_DETECTION_STARTUP_SCRIPT);
        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_vehicle_supply_station.js"),
            DEFAULT_VEHICLE_SUPPLY_STATION_STARTUP_SCRIPT);
        writeDefaultScript(gameDir.resolve("kubejs/startup_scripts/00_espetro_artillery_155.js"),
            DEFAULT_ARTILLERY_155_STARTUP_SCRIPT);

        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_drone_detection.js"),
            DEFAULT_DRONE_DETECTION_SERVER_SCRIPT);
        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_vehicle_supply_station.js"),
            DEFAULT_VEHICLE_SUPPLY_STATION_SERVER_SCRIPT);
        writeDefaultScript(gameDir.resolve("kubejs/server_scripts/00_espetro_artillery_155.js"),
            DEFAULT_ARTILLERY_155_SERVER_SCRIPT);
    }

    private static void writeDefaultScript(Path path, String source) {
        try {
            if (Files.exists(path)) {
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
          .icon('espetro:textures/gui/commander_skills/drone_detection.png')
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
          .icon('espetro:textures/gui/commander_skills/vehicle_supply_station.png')
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
          .icon('espetro:textures/gui/commander_skills/artillery_155.png')
          .targetMap()
          .cooldownSeconds(180)
          .register()
        """;

    private static final String DEFAULT_DRONE_DETECTION_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：无人机侦测。
        // 本文件由 Espetro 首次启动时写入；效果使用 KubeJS 封装的原版对象方法实现。

        EspetroCommanderSkills.on('drone_detection', event => {
          const range = 100.0
          const durationSeconds = 10
          const commanderId = String(event.commanderId())
          const commanderTeam = event.team()
          const level = event.level()

          if (commanderTeam == null || String(commanderTeam).length === 0) {
            event.tell('§c你不属于任何阵营，无法发动无人机侦测！')
            return false
          }

          var count = 0
          const players = level.getPlayers()
          for (var i = 0; i < players.size(); i++) {
            var target = players.get(i)
            var profile = target.getProfile()
            if (profile != null && String(profile.getId()) === commanderId) continue
            var targetTeam = Espetro.getPlayerTeam(target)
            if (targetTeam == null || String(targetTeam) === String(commanderTeam)) continue
            if (target.getDistance(event.x(), event.y(), event.z()) > range) continue

            target.getPotionEffects().add('minecraft:glowing', durationSeconds * 20, 0, false, false)
            count++
          }

          console.info('[Espetro] drone_detection highlighted enemies through KubeJS wrappers within ' + range + ' blocks: ' + count)
          event.tell('§a无人机侦测已执行，范围内高亮敌方玩家: ' + count)
          return true
        })
        """;

    private static final String DEFAULT_VEHICLE_SUPPLY_STATION_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：载具补给站。
        // 本文件由 Espetro 首次启动时写入；效果使用 KubeJS 原生方法实现。

        EspetroCommanderSkills.on('vehicle_supply_station', event => {
          const team = event.team()
          const level = event.level()
          const commander = event.commander()
          const x = event.blockX()
          const y = event.blockY()
          const z = event.blockZ()

          if (team == null || String(team).length === 0) {
            event.tell('§c你不属于任何阵营，无法部署载具补给站！')
            return false
          }

          const barrelPos = [x + 2, y, z]
          const barrel = level.getBlock(barrelPos[0], barrelPos[1], barrelPos[2])
          const barrelId = String(barrel.getId())
          if (barrelId !== 'minecraft:air' && barrelId !== 'minecraft:cave_air' && barrelId !== 'minecraft:void_air') {
            event.tell('§c载具补给站方块位置已有方块: ' + barrelPos[0] + ', ' + barrelPos[1] + ', ' + barrelPos[2])
            return false
          }

          // 使用 KubeJS 原生方法放置方块
          barrel.set(Utils.id('minecraft', 'barrel'))

          // 使用 KubeJS 原生方法创建实体
          var entityType = Utils.id('minecraft', 'armor_stand')
          var entity = level.createEntity(entityType)
          if (entity == null) {
            barrel.set(Utils.id('minecraft', 'air'))
            event.tell('§c载具补给站实体创建失败。')
            return false
          }

          entity.setPosition(x + 0.5, y, z + 0.5)
          entity.setCustomName(Component.literal('载具补给站'))
          entity.setCustomNameVisible(true)
          entity.addTag('espetro_vehicle_supply_station')
          entity.addTag('espetro_vehicle_supply_station_team_' + team)
          entity.addTag('espetro_team_' + team)
          entity.addTag('espetro_commander_skill')
          entity.spawn()

          event.tell('§a载具补给站已部署！位置: ' + x + ', ' + y + ', ' + z)
          console.info('[Espetro] vehicle_supply_station deployed via KubeJS native API at ' + x + ', ' + y + ', ' + z)
          return true
        })
        """;

    private static final String DEFAULT_ARTILLERY_155_SERVER_SCRIPT = """
        // Espetro 默认指挥官技能实现脚本：155火炮支援。
        // 本文件由 Espetro 首次启动时写入；火炮效果完全由 KubeJS server_scripts 实现。
        var EspetroArtilleryTasks = []
        var EspetroArtilleryTick = 0
        var EspetroArtilleryRandomSeed = 135791357
        var EspetroArtilleryJavaRandom = null
        var EspetroArtilleryEntityId = 'superbwarfare:mortar_shell'
        var EspetroArtilleryScriptVersion = 'rejection-random-xz-20260715-0038'

        console.info('[Espetro] artillery_155 script loaded: ' + EspetroArtilleryScriptVersion)

        try {
          if (typeof Java !== 'undefined') {
            EspetroArtilleryJavaRandom = Java.loadClass('java.util.concurrent.ThreadLocalRandom')
          }
        } catch (error) {
          console.warn('[Espetro] artillery_155 Java random unavailable, using script fallback: ' + error)
          EspetroArtilleryJavaRandom = null
        }

        ServerEvents.tick(event => {
          EspetroArtilleryTick++
          if (EspetroArtilleryTasks.length === 0) return

          for (var i = EspetroArtilleryTasks.length - 1; i >= 0; i--) {
            var task = EspetroArtilleryTasks[i]
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
            entity: EspetroArtilleryEntityId,
            spawnY: 20,
            downwardVelocity: 30 / 20,
            impactRadius: 90,
            firstBatchShots: 2,
            firstBatchIntervalTicks: 50,
            secondBatchDelayTicks: 20 * 20,
            secondBatchWaves: 6,
            secondBatchIntervalTicks: 4 * 20,
            secondBatchEntitiesPerWave: 4,
            debugChat: false
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
          if (cfg.entity == null || cfg.entity.length === 0) {
            event.tell('§c155火炮支援实体 ID 无效: ' + cfg.entity)
            return false
          }
          console.info('[Espetro] artillery_155 target center: '
            + espetroCommandNumber(cfg.centerX) + ', '
            + espetroCommandNumber(cfg.targetY) + ', '
            + espetroCommandNumber(cfg.centerZ))
          espetroDebugTell(cfg, '目标中心: '
            + espetroCommandNumber(cfg.centerX) + ', '
            + espetroCommandNumber(cfg.targetY) + ', '
            + espetroCommandNumber(cfg.centerZ)
            + ' 半径=' + espetroCommandNumber(cfg.impactRadius)
            + ' 生成高度=目标Y+' + espetroCommandNumber(cfg.spawnY))

          for (var i = 0; i < cfg.firstBatchShots; i++) {
            espetroQueueArtilleryWave(event, cfg, i * cfg.firstBatchIntervalTicks, 1)
          }

          for (var wave = 0; wave < cfg.secondBatchWaves; wave++) {
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
            espetroDebugTell(cfg, '排定实体总数: ' + total)
          }
          return total > 0
        }

        function espetroNormalizeArtilleryConfig(event, config) {
          const firstInterval = espetroIntConfig(config, 'firstBatchIntervalTicks', 20 * 20)
          const firstShots = espetroIntConfig(config, 'firstBatchShots', 2)
          const entity = espetroStringConfig(config, 'entity', EspetroArtilleryEntityId)
          return {
            event: event,
            level: event.level(),
            dimensionId: event.dimensionId(),
            commander: event.commander(),
            skillId: event.skillId(),
            entity: entity,
            entityType: espetroArtilleryEntityType(entity),
            centerX: espetroNumberValue(espetroTargetCoordinate(event, 'x', 0), 0),
            centerZ: espetroNumberValue(espetroTargetCoordinate(event, 'z', 0), 0),
            targetY: espetroNumberValue(espetroTargetCoordinate(event, 'y', 0), 0),
            spawnY: espetroNumberConfig(config, 'spawnY', 180),
            downwardVelocity: Math.max(0, espetroNumberConfig(config, 'downwardVelocity', 30 / 20)),
            impactRadius: Math.max(0, espetroNumberConfig(config, 'impactRadius', espetroNumberConfig(config, 'radius', 80))),
            firstBatchShots: Math.max(0, firstShots),
            firstBatchIntervalTicks: Math.max(0, firstInterval),
            secondBatchDelayTicks: Math.max(0, espetroIntConfig(config, 'secondBatchDelayTicks', firstShots * firstInterval)),
            secondBatchWaves: Math.max(0, espetroIntConfig(config, 'secondBatchWaves', espetroIntConfig(config, 'secondBatchTimes', 6))),
            secondBatchIntervalTicks: Math.max(0, espetroIntConfig(config, 'secondBatchIntervalTicks', 4 * 20)),
            secondBatchEntitiesPerWave: Math.max(0, espetroIntConfig(config, 'secondBatchEntitiesPerWave', espetroIntConfig(config, 'secondBatchEntitiesPerShot', 4))),
            debugChat: espetroBooleanConfig(config, 'debugChat', false)
          }
        }

        function espetroQueueArtilleryWave(event, cfg, delayTicks, count) {
          var radius = espetroPlainNumber(cfg.impactRadius, 0)
          for (var i = 0; i < count; i++) {
            espetroQueueArtilleryShot(cfg, delayTicks, radius, i, count)
          }
        }

        function espetroQueueArtilleryShot(cfg, delayTicks, radius, shotIndex, waveCount) {
          var random = espetroRandomPointInCircle(cfg.centerX, cfg.centerZ, radius)
          var spawn = random.point
          var spawnY = espetroArtillerySpawnY(cfg)
          console.info('[Espetro] artillery_155 queued shot index='
            + shotIndex + ' center='
            + espetroCommandNumber(cfg.centerX) + ', '
            + espetroCommandNumber(cfg.centerZ) + ' radius='
            + espetroCommandNumber(radius) + ' spawn='
            + espetroCommandNumber(spawn[0]) + ', '
            + espetroCommandNumber(spawnY) + ', '
            + espetroCommandNumber(spawn[1])
            + ' offset=' + espetroCommandNumber(random.offsetX) + ', '
            + espetroCommandNumber(random.offsetZ)
            + ' distance=' + espetroCommandNumber(random.distance)
            + ' random=' + espetroCommandNumber(random.angleUnit) + '/'
            + espetroCommandNumber(random.distanceUnit))
          espetroDebugTell(cfg, '排队 delay=' + Math.floor(delayTicks)
            + ' tick, 批内 ' + (shotIndex + 1) + '/' + waveCount
            + ' -> ' + espetroCommandNumber(spawn[0])
            + ', ' + espetroCommandNumber(spawnY)
            + ', ' + espetroCommandNumber(spawn[1])
            + ' 偏移=' + espetroCommandNumber(random.offsetX)
            + '/' + espetroCommandNumber(random.offsetZ)
            + ' 距离=' + espetroCommandNumber(random.distance)
            + ' rand=' + espetroCommandNumber(random.angleUnit)
            + '/' + espetroCommandNumber(random.distanceUnit))
          espetroScheduleArtilleryTask(delayTicks, () => {
            espetroSpawnArtilleryEntity(cfg, spawn[0], spawnY, spawn[1])
          })
        }

        // 使用 KubeJS 原生方法生成炮击实体
        function espetroSpawnArtilleryEntity(cfg, spawnX, spawnY, spawnZ) {
          var projectile = cfg.level.createEntity(cfg.entityType)
          if (projectile == null) {
            console.warn('[Espetro] artillery_155 could not create entity: ' + cfg.entity)
            espetroDebugTell(cfg, '§c实体创建失败: ' + cfg.entity)
            return false
          }

          projectile.setPosition(spawnX, spawnY, spawnZ)
          espetroSetArtilleryMotion(projectile, 0, -cfg.downwardVelocity, 0)
          projectile.spawn()

          console.info('[Espetro] artillery_155 spawned ' + cfg.entity + ' at '
            + espetroCommandNumber(spawnX) + ', '
            + espetroCommandNumber(spawnY) + ', '
            + espetroCommandNumber(spawnZ))
          espetroDebugTell(cfg, '生成 ' + cfg.entity + ' 坐标='
            + espetroCommandNumber(spawnX) + ', '
            + espetroCommandNumber(spawnY) + ', '
            + espetroCommandNumber(spawnZ))
          return true
        }

        function espetroSetArtilleryMotion(entity, motionX, motionY, motionZ) {
          try {
            entity.setMotion(motionX, motionY, motionZ)
          } catch (error) {
            try {
              entity.setMotionX(motionX)
              entity.setMotionY(motionY)
              entity.setMotionZ(motionZ)
            } catch (ignored) {
              console.warn('[Espetro] artillery_155 failed to set motion: ' + error)
            }
          }
        }

        function espetroArtilleryEntityType(id) {
          var entityId = espetroResourceId(id)
          if (entityId == null || EspetroArtilleryEntityRegistry == null || !EspetroArtilleryEntityRegistry.hasValue(entityId)) {
            return null
          }
          return EspetroArtilleryEntityRegistry.getValue(entityId)
        }

        function espetroResourceId(id) {
          var value = String(id || '')
          if (value.length === 0) return null
          var separator = value.indexOf(':')
          return separator >= 0
            ? Utils.id(value.substring(0, separator), value.substring(separator + 1))
            : Utils.id('minecraft', value)
        }

        function espetroDebugTell(cfg, message) {
          if (!cfg.debugChat) return
          try {
            cfg.event.tell('§7[Espetro 155调试] ' + message)
          } catch (error) {
            console.warn('[Espetro] artillery_155 debug chat failed: ' + error)
          }
        }

        function espetroArtillerySpawnY(cfg) {
          return espetroPlainNumber(cfg.targetY, 0) + Math.max(1, espetroPlainNumber(cfg.spawnY, 20))
        }

        function espetroRandomPointInCircle(centerX, centerZ, radius) {
          var x = espetroPlainNumber(centerX, 0)
          var z = espetroPlainNumber(centerZ, 0)
          var r = Math.max(0, espetroPlainNumber(radius, 0))
          if (r <= 0) {
            return {
              point: [x, z],
              angleUnit: 0,
              distanceUnit: 0,
              offsetX: 0,
              offsetZ: 0,
              distance: 0
            }
          }

          var offsetX = 0
          var offsetZ = 0
          var randomX = 0
          var randomZ = 0
          var radiusSquared = r * r
          var distanceSquared = 0
          for (var attempt = 0; attempt < 16; attempt++) {
            randomX = espetroRandomUnit()
            randomZ = espetroRandomUnit()
            offsetX = (randomX * 2 - 1) * r
            offsetZ = (randomZ * 2 - 1) * r
            distanceSquared = offsetX * offsetX + offsetZ * offsetZ
            if (distanceSquared <= radiusSquared && distanceSquared > 0.000001) break
          }

          while (distanceSquared > radiusSquared) {
            offsetX = offsetX * 0.5
            offsetZ = offsetZ * 0.5
            distanceSquared = offsetX * offsetX + offsetZ * offsetZ
          }
          if (distanceSquared <= 0.000001) {
            offsetX = (randomX >= 0.5 ? 0.5 : -0.5) * r
            offsetZ = (randomZ >= 0.5 ? 0.25 : -0.25) * r
            distanceSquared = offsetX * offsetX + offsetZ * offsetZ
          }

          return {
            point: [
              espetroPlainNumber(x, 0) + espetroPlainNumber(offsetX, 0),
              espetroPlainNumber(z, 0) + espetroPlainNumber(offsetZ, 0)
            ],
            angleUnit: randomX,
            distanceUnit: randomZ,
            offsetX: offsetX,
            offsetZ: offsetZ,
            distance: espetroSqrt(distanceSquared)
          }
        }

        function espetroSqrt(value) {
          var number = espetroPlainNumber(value, 0)
          if (number <= 0) return 0
          var result = Math.sqrt(number)
          return isFinite(result) ? result : 0
        }

        function espetroRandomUnit() {
          if (EspetroArtilleryJavaRandom != null) {
            try {
              var javaRandom = espetroPlainNumber(EspetroArtilleryJavaRandom.current().nextDouble(), NaN)
              if (isFinite(javaRandom) && javaRandom >= 0 && javaRandom < 1) return javaRandom
            } catch (error) {
              console.warn('[Espetro] artillery_155 Java random failed, using script fallback: ' + error)
              EspetroArtilleryJavaRandom = null
            }
          }
          return espetroFallbackRandomUnit()
        }

        function espetroFallbackRandomUnit() {
          var seed = Math.floor(espetroPlainNumber(EspetroArtilleryRandomSeed, 135791357))
          seed = (seed * 48271) % 2147483647
          if (seed <= 0) seed += 2147483646
          EspetroArtilleryRandomSeed = seed
          return seed / 2147483647
        }

        function espetroCommandNumber(value) {
          var number = espetroPlainNumber(value, NaN)
          return isFinite(number) ? number.toFixed(3) : '0.000'
        }

        function espetroNbtDouble(value) {
          return espetroCommandNumber(value) + 'd'
        }

        function espetroStringConfig(config, key, fallback) {
          const value = config && config[key]
          return value === undefined || value === null ? fallback : String(value)
        }

        function espetroTargetCoordinate(event, axis, fallback) {
          var blockPosValue = null
          try {
            blockPosValue = event.blockPos()
          } catch (error) {
            blockPosValue = null
          }

          const blockPosNumber = espetroBlockPosAxis(blockPosValue, axis, NaN)
          if (isFinite(blockPosNumber)) return blockPosNumber

          const blockMethod = axis === 'x' ? 'blockX' : axis === 'y' ? 'blockY' : 'blockZ'
          const exactMethod = axis
          return espetroNumberValue(espetroCall(event, blockMethod), espetroNumberValue(espetroCall(event, exactMethod), fallback))
        }

        function espetroCall(target, method) {
          try {
            const value = target[method]
            return typeof value === 'function' ? value.call(target) : value
          } catch (error) {
            return null
          }
        }

        function espetroBlockPosAxis(blockPos, axis, fallback) {
          if (blockPos == null) return fallback
          const text = String(blockPos)
          const named = text.match(new RegExp(axis + '=(-?\\\\d+)'))
          if (named != null && named.length > 1) {
            return espetroNumberValue(named[1], fallback)
          }

          const values = text.match(/-?\\\\d+/g)
          if (values == null || values.length < 3) return fallback
          const index = axis === 'x' ? 0 : axis === 'y' ? 1 : 2
          return espetroNumberValue(values[index], fallback)
        }

        function espetroNumberValue(value, fallback) {
          if (value === undefined || value === null) return fallback
          if (typeof value === 'number') return isFinite(value) ? value : fallback
          if (value.doubleValue) return espetroNumberValue(value.doubleValue(), fallback)
          if (value.intValue) return espetroNumberValue(value.intValue(), fallback)
          return espetroPlainNumber(value, fallback)
        }

        function espetroPlainNumber(value, fallback) {
          if (value === undefined || value === null) return fallback
          var number = parseFloat(String(value))
          return isFinite(number) ? number : fallback
        }

        function espetroNumberConfig(config, key, fallback) {
          return espetroNumberValue(config && config[key], fallback)
        }

        function espetroIntConfig(config, key, fallback) {
          return Math.round(espetroNumberConfig(config, key, fallback))
        }

        function espetroBooleanConfig(config, key, fallback) {
          const value = config && config[key]
          if (value === undefined || value === null) return fallback
          return value === true || String(value).toLowerCase() === 'true'
        }
        """;
}
