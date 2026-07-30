// Espetro 默认指挥官技能实现脚本：Dragonrise 载具补给站。
// 用原版 /give + NBT 发放可放置物品，由指挥官自行摆放。

var EspetroSupplyItemId = 'dragonrise_reforge:ammo_supply_station'
var EspetroSupplyDisplayName = '载具补给站'

EspetroCommanderSkills.on('vehicle_supply_station', event => {
  var team = String(event.team() || '')
  var commander = event.commander()

  if (team.length === 0) {
    event.tell('§c你还没有加入阵营。')
    return false
  }
  if (commander == null) {
    event.tell('§c无法识别指挥官。')
    return false
  }

  var playerName = espetroCommanderUserName(commander, event)
  if (!playerName) {
    event.tell('§c无法解析指挥官名称。')
    return false
  }

  // display.Name 为 JSON 文本组件；物品为 Dragonrise 补给站部署器
  var nbt = '{display:{Name:\'{"text":"' + EspetroSupplyDisplayName + '"}\'}}'
  var cmd = 'give ' + playerName + ' ' + EspetroSupplyItemId + nbt + ' 1'

  try {
    var server = espetroEventServer(event)
    if (server == null) {
      event.tell('§c服务器不可用。')
      return false
    }

    var code = -1
    if (typeof server.runCommandSilent === 'function') {
      code = server.runCommandSilent(cmd)
    } else if (typeof server.runCommand === 'function') {
      code = server.runCommand(cmd)
    } else {
      // Utils.server 回退
      code = Utils.server.runCommandSilent(cmd)
    }

    // runCommandSilent：成功通常 >= 1（给予数量）；失败为 0
    if (code === 0 || code === false) {
      console.error('[Espetro] give failed, cmd=' + cmd + ' code=' + code)
      event.tell('§c发放失败（物品不存在或指令未生效）。')
      return false
    }

    event.tell('§a已获得「' + EspetroSupplyDisplayName + '」。右键方块放置补给站。')
    console.info('[Espetro] vehicle_supply_station give ok: ' + cmd + ' code=' + code)
    return true
  } catch (error) {
    console.error('[Espetro] vehicle_supply_station grant failed: ' + error + ' cmd=' + cmd)
    event.tell('§c发放载具补给站物品失败。')
    return false
  }
})

function espetroEventServer(event) {
  try {
    if (typeof event.server === 'function') return event.server()
  } catch (e) {}
  try {
    if (typeof event.getServer === 'function') return event.getServer()
  } catch (e) {}
  try {
    if (event.server) return event.server
  } catch (e) {}
  try {
    return Utils.server
  } catch (e) {}
  return null
}

function espetroCommanderUserName(commander, event) {
  try {
    if (commander.username) return String(commander.username)
  } catch (e) {}
  try {
    if (typeof commander.getGameProfile === 'function') {
      return String(commander.getGameProfile().getName())
    }
  } catch (e) {}
  try {
    if (typeof commander.getName === 'function') {
      var n = commander.getName()
      if (n && typeof n.getString === 'function') return n.getString()
      return String(n)
    }
  } catch (e) {}
  try {
    if (typeof event.commanderName === 'function') return String(event.commanderName())
  } catch (e) {}
  return ''
}
