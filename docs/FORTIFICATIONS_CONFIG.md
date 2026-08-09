# 工事与载具补给配置

权威文件是游戏实例根目录的 `config/espetro/fortifications.json`。服务端启动时读取并冻结；
修改后必须完整重启。客户端只收到当前角色可用的目录，放置范围、资源、角色、距离和施工操作
始终由服务端复核。

## 完整示例

```json
{
  "vehicle_service": {
    "main_base_radius": 40.0,
    "station_radius": 20.0,
    "transfer_amount": 100,
    "transfer_interval_ticks": 20
  },
  "builtin_construction": {
    "radio": {
      "required_progress": 600,
      "build_per_hit": 30,
      "remove_per_hit": 5
    },
    "hab": {
      "required_progress": 200,
      "build_per_hit": 5,
      "remove_per_hit": 5
    }
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
      "required_progress": 100,
      "build_per_hit": 5,
      "remove_per_hit": 5,
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
      "required_progress": 100,
      "build_per_hit": 5,
      "remove_per_hit": 5,
      "require_radio_range": true,
      "usable_by": ["commander", "squad_leader", "fireteam_leader"]
    },
    {
      "id": "sandbag_wall",
      "display_name": "沙袋掩体墙",
      "icon": "superbwarfare:textures/block/sandbag.png",
      "place_type": "structure",
      "construction_cost": 100,
      "ammunition_cost": 0,
      "required_progress": 100,
      "build_per_hit": 5,
      "remove_per_hit": 5,
      "require_radio_range": true,
      "usable_by": ["commander", "squad_leader", "fireteam_leader"],
      "blocks": [
        {"offset": [-1, 0, 0], "block_id": "superbwarfare:sandbag"},
        {"offset": [0, 0, 0], "block_id": "superbwarfare:sandbag"},
        {"offset": [1, 0, 0], "block_id": "superbwarfare:sandbag"},
        {"offset": [-1, 1, 0], "block_id": "superbwarfare:sandbag"},
        {"offset": [0, 1, 0], "block_id": "superbwarfare:sandbag"},
        {"offset": [1, 1, 0], "block_id": "superbwarfare:sandbag"}
      ]
    }
  ]
}
```

## 工事字段

| 字段 | 说明 |
|---|---|
| `id` | 唯一 ID，只允许小写字母、数字、点、下划线和连字符，最多 64 字符。 |
| `display_name` | 轮盘和施工进度 HUD 的名称。 |
| `icon` | AuraTip 轮盘纹理资源 ID。 |
| `place_type` | `block`、`entity` 或 `structure`。 |
| `block_id` | `block` 类型最终放置的方块。 |
| `entity_id` | `entity` 类型最终生成的实体。 |
| `fallback_block_id` | 实体类型不可用时的后备方块；实体和后备方块都无效则隐藏该工事。 |
| `blocks` | `structure` 类型的方块列表；`offset` 为相对锚点 `[x,y,z]`。 |
| `construction_cost` | 确认施工范围时一次性扣除的 Radio 建材。 |
| `ammunition_cost` | 确认施工范围时一次性扣除的 Radio 弹药。 |
| `required_progress` | 建成所需总进度，范围 1–1,000,000。 |
| `build_per_hit` | 工兵铲每 5 tick 左键增加的进度。 |
| `remove_per_hit` | 工兵铲每 5 tick 右键减少的进度。 |
| `require_radio_range` | 为 `true` 时锚点必须位于同阵营 Radio 范围内。 |
| `usable_by` | 可选 `commander`、`squad_leader`、`fireteam_leader`。 |

结构配置最多 256 个方块，每个坐标轴的相对偏移范围为 -32 到 32，同一结构内不允许重复
偏移。沙袋墙以锚点为中心，按玩家面向方向旋转，最终为 3 格长、2 格高。

`builtin_construction` 单独控制 Radio 和 HAB 的施工速度。Radio 本身不消耗资源；HAB 仍使用
`logistics.json` 的 `hab_construction_cost`。默认按每 5 tick 一次操作计算：Radio 修建约 5 秒、
拆除约 30 秒；HAB 修建和拆除各约 10 秒。

## 游戏内施工流程

1. 指挥官、小队长或火力组长从 Alt 的“建造工事”选择目标。
2. 客户端只绘制逐方块线框：黄色表示可放置，红色表示被方块或生物占据。薄雪层可直接替换。
3. 左键确认；任意右键取消预览。确认后才扣除资源。
4. 最低一层占地会生成 `espetro:onbuilding`。任意存活的非旁观玩家都可面向施工块，按住铁铲
   左键修建、右键拆除；铁铲不损耗耐久。
5. 数字阶段模型随进度从 1 到 6 层逐步显示。达到上限后替换为最终方块或实体，并在此时才
   注册 Radio、HAB、载具补给站及 ESPoints 地图标记。
6. 建成后可以继续用铁铲右键降低完整度；只有进度归零时整座工事才消失。爆炸或外部方块
   损坏按结构方块数量扣除完整度，随后左键可按进度逐步恢复缺失方块。

施工状态使用位置/实体索引，不扫描世界、不强制加载区块。每位施工玩家最多每 5 tick 发送
一次操作包；HUD 只在服务端进度事件到达时更新状态。

## 载具补给范围

`vehicle_service.main_base_radius` 是原部署点装卸半径，`station_radius` 是已建成载具补给站的
服务半径；`transfer_amount` 与 `transfer_interval_ticks` 控制每次及每隔多久转移物资。
