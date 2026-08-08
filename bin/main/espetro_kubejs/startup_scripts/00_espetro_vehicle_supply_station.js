// Espetro 默认技能注册：载具补给站（指挥官 + 小队长）。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
// usableBy 声明可用对象：commander / squad_leader。
EspetroCommanderSkills.create('vehicle_supply_station')
  .displayName('载具补给站')
  .description('获得可放置的载具补给站物品，由使用者自行摆放')
  .stats('§8补给载具 | 冷却: 120秒')
  .icon('espetro:textures/gui/commander_skills/vehicle_supply_station.png')
  .usableBy('commander', 'squad_leader')
  .activate()
  .cooldownSeconds(120)
  .register()
