// Espetro 默认指挥官技能注册脚本：载具补给站。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
EspetroCommanderSkills.create('vehicle_supply_station')
  .displayName('载具补给站')
  .description('在指挥官前方部署载具补给站')
  .stats('§8补给载具 | 冷却: 120秒')
  .icon('espetro:textures/gui/commander_skills/vehicle_supply_station.png')
  .activate()
  .cooldownSeconds(120)
  .register()
