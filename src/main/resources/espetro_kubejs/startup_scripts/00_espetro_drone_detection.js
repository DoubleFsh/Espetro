// Espetro 默认指挥官技能注册脚本：无人机侦测。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
EspetroCommanderSkills.create('drone_detection')
  .displayName('无人机侦测')
  .description('短时间标记附近已经部署的敌人')
  .stats('§8范围: 100格 | 持续: 10秒 | 冷却: 60秒')
  .icon('espetro:textures/gui/commander_skills/drone_detection.png')
  .activate()
  .cooldownSeconds(60)
  .register()
