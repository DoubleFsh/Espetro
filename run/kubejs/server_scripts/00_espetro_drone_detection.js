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
