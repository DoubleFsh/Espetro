// Espetro 默认指挥官技能实现脚本：载具补给站。
// 本文件由 Espetro 首次启动时写入；效果使用 KubeJS 封装的原版对象方法实现。

EspetroCommanderSkills.on('vehicle_supply_station', event => {
  const team = event.team()
  const level = event.level()
  const x = event.blockX()
  const y = event.blockY()
  const z = event.blockZ()

  if (team == null || String(team).length === 0) {
    event.tell('§c你不属于任何阵营，无法部署载具补给站！')
    return false
  }

  const barrel = level.getBlock(x + 2, y, z)
  if (!espetroSupplyIsReplaceable(barrel)) {
    event.tell('§c载具补给站方块位置已有方块: ' + (x + 2) + ', ' + y + ', ' + z)
    return false
  }
  barrel.set(Utils.id('minecraft', 'barrel'))

  const spawned = espetroSupplySummonEntity(level, 'minecraft:armor_stand', x + 0.5, y, z + 0.5, team)
  if (!spawned) {
    barrel.set(Utils.id('minecraft', 'air'))
    event.tell('§c载具补给站实体创建失败。')
    return false
  }
  event.tell('§a载具补给站已部署！位置: ' + x + ', ' + y + ', ' + z)
  console.info('[Espetro] vehicle_supply_station deployed through KubeJS wrappers')
  return true
})

function espetroSupplySummonEntity(level, id, x, y, z, team) {
  var tags = [
    'espetro_vehicle_supply_station',
    'espetro_vehicle_supply_station_team_' + team,
    'espetro_team_' + team,
    'espetro_commander_skill'
  ]
  var command = 'summon ' + id + ' '
    + espetroSupplyCommandNumber(x) + ' '
    + espetroSupplyCommandNumber(y) + ' '
    + espetroSupplyCommandNumber(z) + ' '
    + "{CustomName:'{\"text\":\"载具补给站\"}',CustomNameVisible:1b,Tags:" + espetroSupplyNbtStringList(tags) + '}'
  var result = level.runCommandSilent(command)
  if (result <= 0) {
    console.warn('[Espetro] vehicle_supply_station summon command failed: ' + command)
  }
  return result > 0
}

function espetroSupplyNbtStringList(values) {
  var result = []
  for (var i = 0; i < values.length; i++) {
    result.push(espetroSupplyNbtString(values[i]))
  }
  return '[' + result.join(',') + ']'
}

function espetroSupplyNbtString(value) {
  return '"' + String(value).replace(/\\/g, '\\\\').replace(/"/g, '\\"') + '"'
}

function espetroSupplyCommandNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number.toFixed(3) : '0.000'
}

function espetroSupplyIsReplaceable(block) {
  const id = String(block.getId())
  return id === 'minecraft:air' || id === 'minecraft:cave_air' || id === 'minecraft:void_air'
}
