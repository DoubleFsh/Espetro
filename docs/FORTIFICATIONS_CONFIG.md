# 工事与载具补给配置

权威文件是游戏实例根目录的 `config/espetro/fortifications.json`。服务端首次启动会生成
默认文件；修改后必须完整重启。客户端只接收当前角色有权使用的只读工事目录，实际放置、
扣费、阵营、距离和权限始终由服务端校验。

```json
{
  "vehicle_service": {
    "main_base_radius": 40.0,
    "station_radius": 20.0,
    "transfer_amount": 100,
    "transfer_interval_ticks": 20
  },
  "fortifications": [
    {
      "id": "ammo_crate",
      "display_name": "弹药箱",
      "icon": "espetro:textures/gui/squad/ammo_crate.png",
      "place_type": "block",
      "block_id": "minecraft:shulker_box",
      "construction_cost": 100,
      "ammunition_cost": 0,
      "require_radio_range": true,
      "usable_by": ["commander", "squad_leader", "fireteam_leader"]
    },
    {
      "id": "vehicle_supply_station",
      "display_name": "载具补给站",
      "icon": "espetro:textures/gui/commander_skills/vehicle_supply_station.png",
      "place_type": "entity",
      "entity_id": "dragonrise_reforge:ammo_supply_station",
      "fallback_block_id": "minecraft:barrel",
      "construction_cost": 200,
      "ammunition_cost": 0,
      "require_radio_range": true,
      "usable_by": ["commander", "squad_leader", "fireteam_leader"]
    }
  ]
}
```

`place_type` 只能是 `block` 或 `entity`。实体注册 ID 不存在时使用 `fallback_block_id`；两者
都无效则拒绝该条配置。费用可同时包含建材和弹药，并以一次事务扣除，放置失败会退款。
`require_radio_range` 为 `true` 时目标点必须由同阵营 Radio 覆盖。`usable_by` 可选值为
`commander`、`squad_leader`、`fireteam_leader`。

载具字段配置在 `EsFactions/<编制>.json` 的 `vehicles.<类型>` 中：

```json
{
  "fightveh": true,
  "capacity": 500,
  "initial_deploy_delay_seconds": { "attack": 90, "defend": 60 }
}
```

补给载具改用 `"supplyveh": true`。两种标志不要同时启用。`capacity` 是弹药和建材共享容量；
所有手动部署载具初始为空，玩家必须自行装载。编制级
`faction.max_habs_per_radio` 控制单个 Radio 可覆盖的 HAB 数量。
