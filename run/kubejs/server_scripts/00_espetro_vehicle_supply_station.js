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
