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
