// Espetro 默认指挥官技能实现脚本：无人机侦测。

EspetroCommanderSkills.on('drone_detection', event => {
  const range = 100.0
  const durationSeconds = 10
  const commanderId = String(event.commanderId())
  const commanderTeam = String(event.team() || '')
  const level = event.level()

  if (commanderTeam.length === 0) {
    event.tell('§c你还没有加入阵营。')
    return false
  }

  var count = 0
  const players = level.getPlayers()
  for (var i = 0; i < players.size(); i++) {
    var target = players.get(i)
    var profile = target.getProfile()
    if (profile != null && String(profile.getId()) === commanderId) continue
    if (!Espetro.isPlayerDeployed(target)) continue

    var targetTeam = Espetro.getPlayerTeam(target)
    if (targetTeam == null || String(targetTeam) === commanderTeam) continue
    if (target.getDistance(event.x(), event.y(), event.z()) > range) continue

    target.getPotionEffects().add('minecraft:glowing', durationSeconds * 20, 0, false, false)
    count++
  }

  console.info('[Espetro] drone_detection highlighted deployed enemies: ' + count)
  event.tell('§a侦测完成，发现敌人: ' + count)
  return true
})
