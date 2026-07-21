// Espetro 默认指挥官技能注册脚本：155火炮支援。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
EspetroCommanderSkills.create('artillery_155')
  .displayName('155火炮支援')
  .description('打开 ESPoints 战术地图选择炮击坐标，再交给 KubeJS 执行火力效果')
  .stats('§8ESPoints地图选点 | KubeJS两批实体炮击 | 冷却: 180秒')
  .icon('espetro:textures/gui/commander_skills/artillery_155.png')
  .targetMap()
  .cooldownSeconds(180)
  .register()
