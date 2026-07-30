// Espetro 默认技能注册：155火炮支援（仅指挥官）。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
// usableBy 仅 commander 时小队长看不到也无法使用。
EspetroCommanderSkills.create('artillery_155')
  .displayName('155火炮支援')
  .description('在战术地图上选择炮击位置')
  .stats('§8两轮炮击 | 冷却: 180秒')
  .icon('espetro:textures/gui/commander_skills/artillery_155.png')
  .usableBy('commander')
  .targetMap()
  .cooldownSeconds(180)
  .register()
