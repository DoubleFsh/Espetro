// Espetro 默认指挥官技能实现脚本：155火炮支援。
// 本文件由 Espetro 首次启动时写入；火炮效果完全由 KubeJS server_scripts 实现。
var EspetroArtilleryTasks = []
var EspetroArtilleryTick = 0
var EspetroArtilleryEntityId = 'superbwarfare:mortar_shell'
var EspetroArtilleryEntityRegistry = Utils.getRegistry(Utils.id('minecraft', 'entity_type'))

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
    spawnY: 180,
    downwardVelocity: 30 / 20,
    impactRadius: 90,
    firstBatchShots: 2,
    firstBatchIntervalTicks: 20 * 20,
    secondBatchDelayTicks: 2 * 20 * 20,
    secondBatchWaves: 6,
    secondBatchIntervalTicks: 4 * 20,
    secondBatchEntitiesPerWave: 4
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
  console.info('[Espetro] artillery_155 target center: '
    + espetroCommandNumber(cfg.centerX) + ', '
    + espetroCommandNumber(cfg.targetY) + ', '
    + espetroCommandNumber(cfg.centerZ))

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
  }
  return total > 0
}

function espetroNormalizeArtilleryConfig(event, config) {
  const firstInterval = espetroIntConfig(config, 'firstBatchIntervalTicks', 20 * 20)
  const firstShots = espetroIntConfig(config, 'firstBatchShots', 2)
  const entity = EspetroArtilleryEntityId
  return {
    level: event.level(),
    commander: event.commander(),
    skillId: event.skillId(),
    entity: entity,
    entityType: espetroArtilleryEntityType(entity),
    centerX: espetroTargetCoordinate(event, 'x', 0),
    centerZ: espetroTargetCoordinate(event, 'z', 0),
    targetY: espetroTargetCoordinate(event, 'y', 0),
    spawnY: espetroNumberConfig(config, 'spawnY', 180),
    downwardVelocity: Math.max(0, espetroNumberConfig(config, 'downwardVelocity', 30 / 20)),
    impactRadius: Math.max(0, espetroNumberConfig(config, 'impactRadius', espetroNumberConfig(config, 'radius', 80))),
    firstBatchShots: Math.max(0, firstShots),
    firstBatchIntervalTicks: Math.max(0, firstInterval),
    secondBatchDelayTicks: Math.max(0, espetroIntConfig(config, 'secondBatchDelayTicks', firstShots * firstInterval)),
    secondBatchWaves: Math.max(0, espetroIntConfig(config, 'secondBatchWaves', espetroIntConfig(config, 'secondBatchTimes', 6))),
    secondBatchIntervalTicks: Math.max(0, espetroIntConfig(config, 'secondBatchIntervalTicks', 4 * 20)),
    secondBatchEntitiesPerWave: Math.max(0, espetroIntConfig(config, 'secondBatchEntitiesPerWave', espetroIntConfig(config, 'secondBatchEntitiesPerShot', 4)))
  }
}

function espetroQueueArtilleryWave(event, cfg, delayTicks, count) {
  for (var i = 0; i < count; i++) {
    espetroQueueArtilleryShot(cfg, delayTicks)
  }
}

function espetroQueueArtilleryShot(cfg, delayTicks) {
  var spawn = espetroRandomPointInCircle(cfg.centerX, cfg.centerZ, cfg.impactRadius)
  espetroScheduleArtilleryTask(delayTicks, () => {
    espetroSpawnArtilleryEntity(cfg, spawn[0], cfg.spawnY, spawn[1])
  })
}

function espetroSpawnArtilleryEntity(cfg, spawnX, spawnY, spawnZ) {
  var projectile = cfg.level.createEntity(cfg.entityType)
  if (projectile == null) {
    console.warn('[Espetro] artillery_155 could not create entity: ' + cfg.entity)
    return false
  }

  projectile.setPosition(spawnX, spawnY, spawnZ)
  espetroSetArtilleryMotion(projectile, 0, -cfg.downwardVelocity, 0)
  projectile.spawn()
  console.info('[Espetro] artillery_155 spawned ' + cfg.entity + ' at '
    + espetroCommandNumber(spawnX) + ', '
    + espetroCommandNumber(spawnY) + ', '
    + espetroCommandNumber(spawnZ))
  return true
}

function espetroSetArtilleryMotion(entity, motionX, motionY, motionZ) {
  try {
    entity.setMotion(motionX, motionY, motionZ)
  } catch (error) {
    entity.setMotionX(motionX)
    entity.setMotionY(motionY)
    entity.setMotionZ(motionZ)
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

function espetroRandomPointInCircle(centerX, centerZ, radius) {
  const angle = Math.random() * Math.PI * 2
  const distance = Math.sqrt(Math.random()) * Math.max(0, radius)
  return [
    centerX + Math.cos(angle) * distance,
    centerZ + Math.sin(angle) * distance
  ]
}

function espetroCommandNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(3) : '0.000'
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
  if (Number.isFinite(blockPosNumber)) return blockPosNumber

  const blockMethod = axis === 'x' ? 'blockX' : axis === 'y' ? 'blockY' : 'blockZ'
  const exactMethod = axis
  return espetroNumberValue(espetroCall(event, blockMethod), espetroNumberValue(espetroCall(event, exactMethod), fallback))
}

function espetroCall(target, method) {
  try {
    return target[method]()
  } catch (error) {
    return null
  }
}

function espetroBlockPosAxis(blockPos, axis, fallback) {
  if (blockPos == null) return fallback
  const text = String(blockPos)
  const named = text.match(new RegExp(axis + '=(-?\\d+)'))
  if (named != null && named.length > 1) {
    return espetroNumberValue(named[1], fallback)
  }

  const values = text.match(/-?\d+/g)
  if (values == null || values.length < 3) return fallback
  const index = axis === 'x' ? 0 : axis === 'y' ? 1 : 2
  return espetroNumberValue(values[index], fallback)
}

function espetroNumberValue(value, fallback) {
  if (value === undefined || value === null) return fallback
  if (typeof value === 'number') return Number.isFinite(value) ? value : fallback
  if (value.doubleValue) return espetroNumberValue(value.doubleValue(), fallback)
  if (value.intValue) return espetroNumberValue(value.intValue(), fallback)
  const number = Number(String(value))
  return Number.isFinite(number) ? number : fallback
}

function espetroNumberConfig(config, key, fallback) {
  return espetroNumberValue(config && config[key], fallback)
}

function espetroIntConfig(config, key, fallback) {
  return Math.round(espetroNumberConfig(config, key, fallback))
}
