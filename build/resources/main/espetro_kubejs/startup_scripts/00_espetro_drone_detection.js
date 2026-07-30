// Espetro 默认技能注册：无人机侦测（指挥官 + 小队长）。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
// usableBy 声明可用对象：commander / squad_leader（同义 leader、队长）。
EspetroCommanderSkills.create('drone_detection')
  .displayName('无人机侦测')
  .description('短时间标记附近已经部署的敌人')
  .stats('§8范围: 100格 | 持续: 10秒 | 冷却: 60秒')
  .icon('espetro:textures/gui/commander_skills/drone_detection.png')
  .usableBy('commander', 'squad_leader')
  .activate()
  .cooldownSeconds(60)
  .register()
