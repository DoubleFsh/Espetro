// Espetro 默认指挥官技能注册脚本：载具补给站。
// 本文件由 Espetro 首次启动时写入；可按 KubeJS startup_scripts 规则修改。
EspetroCommanderSkills.create('vehicle_supply_station')
  .displayName('载具补给站')
  .description('在指挥官当前位置部署载具补给站')
  .stats('§8生成载具补给实体和方块 | 冷却: 120秒')
  .activate()
  .cooldownSeconds(120)
  .register()
