// Espetro 默认指挥官技能实现脚本：155 火炮支援。
// 不使用 server.scheduleInTicks：部分 KubeJS/Forge 组合会丢失较长延迟的回调。
// 队列仅在有火炮任务时执行，且每次执行前会核验当前战局，避免跨局落弹。

var EspetroArtilleryEntityId = 'superbwarfare:mortar_shell'
var EspetroArtilleryTasks = []
var EspetroArtilleryTick = 0
var EspetroArtilleryJavaRandom = null
var EspetroArtilleryFallbackSeed = 135791357
var EspetroArtilleryScriptVersion = 'wave-queue-20260729'

try {
  if (typeof Java !== 'undefined') {
    EspetroArtilleryJavaRandom = Java.loadClass('java.util.concurrent.ThreadLocalRandom')
  }
} catch (error) {
  EspetroArtilleryJavaRandom = null
}

console.info('[Espetro] artillery_155 script loaded: ' + EspetroArtilleryScriptVersion)

// 空队列时仅做一次整数递增和一次长度判断；不扫描玩家、实体或区块。
ServerEvents.tick(event => {
  EspetroArtilleryTick++
  if (EspetroArtilleryTasks.length === 0) return

  for (var index = EspetroArtilleryTasks.length - 1; index >= 0; index--) {
    const task = EspetroArtilleryTasks[index]
    if (task.executeAt > EspetroArtilleryTick) continue
    EspetroArtilleryTasks.splice(index, 1)
    try {
      espetroExecuteArtilleryWave(task)
    } catch (error) {
      console.error('[Espetro] artillery_155 wave ' + task.label + ' failed: ' + error)
    }
  }
})

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
    // 与旧版 155 脚本一致：炮弹从目标上空约 180 格处落下。
    spawnHeight: 180,
    downwardVelocity: 1.5,
    impactRadius: 90,
    cancelled: false
  }

  if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(cfg.entity)
      || cfg.sessionId <= 0 || cfg.dimensionId === 'null') {
    event.tell('§c当前战局不可使用火炮支援。')
    return false
  }

  // 两发校射：间隔 20 秒；随后等待 20 秒，再进行六轮、每轮四发的覆盖射击。
  espetroQueueArtilleryWave(cfg, 0, 1, '校射 1/2')
  espetroQueueArtilleryWave(cfg, 20 * 20, 1, '校射 2/2')
  for (var wave = 0; wave < 6; wave++) {
    espetroQueueArtilleryWave(cfg, 2 * 20 * 20 + wave * 4 * 20, 4, '覆盖 ' + (wave + 1) + '/6')
  }

  event.tell('§a火炮支援已确认：两发校射后将实施六轮覆盖，共 26 发。')
  console.info('[Espetro] artillery_155 queued 8 waves / 26 shells in session ' + cfg.sessionId)
  return true
})

function espetroQueueArtilleryWave(cfg, delayTicks, count, label) {
  EspetroArtilleryTasks.push({
    executeAt: EspetroArtilleryTick + Math.max(0, Math.floor(delayTicks)),
    cfg: cfg,
    count: count,
    label: label
  })
}

function espetroExecuteArtilleryWave(task) {
  const level = espetroResolveArtilleryLevel(task.cfg)
  if (level == null) return

  var failures = 0
  for (var i = 0; i < task.count; i++) {
    const point = espetroRandomArtilleryPoint(
      task.cfg.centerX,
      task.cfg.centerZ,
      task.cfg.impactRadius
    )
    if (!espetroSpawnArtilleryShell(
      level,
      task.cfg,
      point[0],
      task.cfg.targetY + task.cfg.spawnHeight,
      point[1]
    )) {
      failures++
    }
  }

  const spawned = task.count - failures
  console.info('[Espetro] artillery_155 ' + task.label + ': ' + spawned + '/' + task.count + ' shells spawned')
  if (failures > 0) {
    console.warn('[Espetro] artillery_155 ' + task.label + ' failed to spawn ' + failures + '/' + task.count + ' shells')
  }
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
  return server == null ? null : server.getLevel(espetroArtilleryResourceId(cfg.dimensionId))
}

function espetroSpawnArtilleryShell(level, cfg, x, y, z) {
  // 保留经过实测可用的命令生成方式，避免不同 Superb Warfare 版本的实体包装 API 差异。
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
    if (offsetX * offsetX + offsetZ * offsetZ <= radiusSquared) return [centerX + offsetX, centerZ + offsetZ]
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
