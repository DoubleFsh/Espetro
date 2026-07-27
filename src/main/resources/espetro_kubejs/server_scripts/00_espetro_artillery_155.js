// Espetro 默认指挥官技能实现脚本：155火炮支援。
// 使用 KubeJS 的服务器延迟任务，不注册额外的逐 tick 扫描。

var EspetroArtilleryEntityId = 'superbwarfare:mortar_shell'
var EspetroArtilleryJavaRandom = null
var EspetroArtilleryFallbackSeed = 135791357
var EspetroArtilleryScriptVersion = 'scheduled-waves-20260727'

try {
  if (typeof Java !== 'undefined') {
    EspetroArtilleryJavaRandom = Java.loadClass('java.util.concurrent.ThreadLocalRandom')
  }
} catch (error) {
  EspetroArtilleryJavaRandom = null
}

console.info('[Espetro] artillery_155 script loaded: ' + EspetroArtilleryScriptVersion)

EspetroCommanderSkills.on('artillery_155', event => {
  if (!event.hasTarget()) {
    event.tell('§c请先在战术地图上选择炮击位置。')
    return false
  }

  const cfg = {
    entity: EspetroArtilleryEntityId,
    dimensionId: String(event.dimensionId()),
    sessionId: Number(Espetro.getBattlefieldSessionId()),
    centerX: espetroArtilleryNumber(event.x(), 0),
    targetY: espetroArtilleryNumber(event.y(), 0),
    centerZ: espetroArtilleryNumber(event.z(), 0),
    spawnHeight: 20,
    downwardVelocity: 1.5,
    impactRadius: 90,
    cancelled: false
  }
  const server = event.server()

  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(cfg.entity)) {
    event.tell('§c炮击实体配置无效。')
    return false
  }

  try {
    // 第一轮：两发校射。
    espetroScheduleArtilleryWave(server, cfg, 0, 1)
    espetroScheduleArtilleryWave(server, cfg, 50, 1)

    // 第二轮：20 秒后开始，六轮、每轮四发、间隔四秒。
    for (var wave = 0; wave < 6; wave++) {
      espetroScheduleArtilleryWave(server, cfg, 400 + wave * 80, 4)
    }
  } catch (error) {
    console.error('[Espetro] artillery_155 scheduling failed: ' + error)
    event.tell('§c炮击任务排定失败。')
    return false
  }

  event.tell('§a火炮支援已确认，共 26 发。')
  console.info('[Espetro] artillery_155 scheduled 8 waves / 26 shells in session ' + cfg.sessionId)
  return true
})

function espetroScheduleArtilleryWave(server, cfg, delayTicks, count) {
  server.scheduleInTicks(Math.max(0, Math.floor(delayTicks)), scheduled => {
    const level = espetroResolveArtilleryLevel(cfg)
    if (level == null) return

    var failures = 0
    for (var i = 0; i < count; i++) {
      const point = espetroRandomArtilleryPoint(
        cfg.centerX,
        cfg.centerZ,
        cfg.impactRadius
      )
      if (!espetroSpawnArtilleryShell(
        level,
        cfg,
        point[0],
        cfg.targetY + cfg.spawnHeight,
        point[1]
      )) {
        failures++
      }
    }

    if (failures > 0) {
      console.warn('[Espetro] artillery_155 wave failed to spawn ' + failures + '/' + count + ' shells')
    }
  })
}

function espetroResolveArtilleryLevel(cfg) {
  const phase = String(Espetro.phaseId())
  const activeDimension = Espetro.getActiveBattlefieldDimension()
  const currentSession = Number(Espetro.getBattlefieldSessionId())
  if ((phase !== 'DEPLOYING' && phase !== 'BATTLE')
      || activeDimension == null
      || String(activeDimension) !== cfg.dimensionId
      || currentSession !== cfg.sessionId) {
    if (!cfg.cancelled) {
      cfg.cancelled = true
      console.info('[Espetro] artillery_155 remaining waves cancelled: battlefield changed')
    }
    return null
  }

  const server = Espetro.server()
  if (server == null) return null
  return server.getLevel(espetroArtilleryResourceId(cfg.dimensionId))
}

function espetroSpawnArtilleryShell(level, cfg, x, y, z) {
  const command = 'summon ' + cfg.entity + ' '
    + espetroArtilleryCommandNumber(x) + ' '
    + espetroArtilleryCommandNumber(y) + ' '
    + espetroArtilleryCommandNumber(z)
    + ' {Motion:[0.0d,' + espetroArtilleryCommandNumber(-cfg.downwardVelocity) + 'd,0.0d]}'
  return level.runCommandSilent(command) > 0
}

function espetroRandomArtilleryPoint(centerX, centerZ, radius) {
  var offsetX = 0
  var offsetZ = 0
  const radiusSquared = radius * radius
  for (var attempt = 0; attempt < 16; attempt++) {
    offsetX = (espetroArtilleryRandom() * 2 - 1) * radius
    offsetZ = (espetroArtilleryRandom() * 2 - 1) * radius
    if (offsetX * offsetX + offsetZ * offsetZ <= radiusSquared) {
      return [centerX + offsetX, centerZ + offsetZ]
    }
  }
  return [centerX + offsetX * 0.5, centerZ + offsetZ * 0.5]
}

function espetroArtilleryRandom() {
  if (EspetroArtilleryJavaRandom != null) {
    try {
      return Number(EspetroArtilleryJavaRandom.current().nextDouble())
    } catch (error) {
      EspetroArtilleryJavaRandom = null
    }
  }

  EspetroArtilleryFallbackSeed = (EspetroArtilleryFallbackSeed * 48271) % 2147483647
  if (EspetroArtilleryFallbackSeed <= 0) EspetroArtilleryFallbackSeed += 2147483646
  return EspetroArtilleryFallbackSeed / 2147483647
}

function espetroArtilleryResourceId(id) {
  const value = String(id)
  const separator = value.indexOf(':')
  return separator >= 0
    ? Utils.id(value.substring(0, separator), value.substring(separator + 1))
    : Utils.id('minecraft', value)
}

function espetroArtilleryNumber(value, fallback) {
  const number = Number(value)
  return Number.isFinite(number) ? number : fallback
}

function espetroArtilleryCommandNumber(value) {
  return espetroArtilleryNumber(value, 0).toFixed(3)
}
