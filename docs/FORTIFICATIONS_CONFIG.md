# 工事与载具补给配置

权威文件是游戏实例根目录的 `config/espetro/fortifications.json`。服务端在首次 datapack
加载完成后、战场 gate 开放前解析、编译 Structure NBT 并冻结；修改后必须完整重启。
运行中 `/reload` 不会改变已冻结定义。客户端只收到当前角色可用的目录；放置范围、资源、
角色、距离和施工操作始终由服务端复核。

形状真源是原版 Minecraft Structure NBT（`data/<namespace>/structures/<path>.nbt`）。
仓库里的 `data/espetro/structure_sources/fortifications/*.snbt` 只是便于审阅的文本源，
启动时由 `DimensionPackBootstrap` 编译进内存数据包。`.bbmodel`、方块模型 JSON、OBJ
不是运行时输入。

地图差异只能写在该图 `EsConfig/logistics.json` 的
`fortification_overrides.<fortification_id>.<field>`。运行时唯一事实源是
“全局定义 + 当前地图显式 override”。旧 `hab_construction_cost` /
`ammo_crate_construction_cost` 只作一次性迁移输入。

## 完整示例

```json
{
  "schema_version": 2,
  "limits": {
    "max_template_blocks": 4096,
    "max_template_entities": 32,
    "max_template_axis": 64,
    "max_template_nbt_bytes": 2097152,
    "max_passenger_depth": 4
  },
  "vehicle_service": {
    "main_base_radius": 40.0,
    "station_radius": 20.0,
    "transfer_amount": 100,
    "transfer_interval_ticks": 20
  },
  "fortifications": [
    {
      "id": "espetro:radio",
      "legacy_ids": ["builtin_radio"],
      "display_name": "电台",
      "icon": {"texture": "espetro:textures/gui/squad/radio.png"},
      "behavior": "radio",
      "placement": {
        "type": "structure",
        "template": "espetro:fortifications/radio",
        "template_by_team": {},
        "palette_index": 0,
        "origin_offset": [0, 0, 0],
        "pivot": [0, 0, 0],
        "rotation": "player_facing",
        "mirror": "none",
        "air_policy": "reject_non_replaceable",
        "include_entities": true
      },
      "cost": {"construction": 0, "ammunition": 0},
      "construction": {"required_progress": 600, "build_per_hit": 30, "remove_per_hit": 5},
      "durability": {
        "structural_value": 600,
        "repair_per_hit": 30,
        "damageable_structure_entities": [],
        "damage_reduction": {"explosion": 0.9, "projectile": 0.9, "direct_break": 0.0}
      },
      "requirements": {
        "require_radio_range": false,
        "usable_by": ["commander", "squad_leader", "fireteam_leader"]
      }
    }
  ]
}
```

纯实体工事的 `placement`：

```json
{
  "type": "entity",
  "entity_type": "dragonrise_reforge:ammo_supply_station",
  "fallback_template": "espetro:fortifications/vehicle_supply_station_fallback",
  "spawn_offset": [0.5, 0.0, 0.5],
  "yaw": "player_facing",
  "virtual_damageable_part": true,
  "entity_nbt": {}
}
```

`entity_id` 是 `entity_type` 的同义读取字段。

## 字段

| 字段 | 说明 |
|---|---|
| `schema_version` | 必须为 `2`。旧 v1 只做一次性迁移。 |
| `id` | namespaced ID。无 namespace 时规范化为 `espetro:`。 |
| `legacy_ids` | 旧客户端动作/日志/配置别名，例如 `builtin_radio`。 |
| `behavior` | 只能是 `radio`、`hab`、`ammo_crate`、`vehicle_supply_station`、`generic`。 |
| `placement.type` | 严格判别联合：`structure` 或 `entity`，字段不能混用。 |
| `template` / `template_by_team` | 结构模板。HAB 必须用 `attack`/`defend` 映射，不再手拼羊毛。 |
| `origin_offset` / `pivot` | 结构坐标。`world = anchor + R(origin_offset + local - pivot)`。 |
| `air_policy` | 目前只支持 `reject_non_replaceable`。`structure_void` 永不触碰世界。 |
| `required_progress` | 未建成阶段的施工进度。 |
| `structural_value` | 建成后的结构值上限；归零后整座销毁且不能复活。 |
| `damage_reduction` | 减掉的比例，有效伤害 = 原始伤害 × `(1 - reduction)`。 |
| `require_radio_range` | 为 `true` 时锚点必须位于同阵营 Radio 范围内。 |
| `usable_by` | `commander`、`squad_leader`、`fireteam_leader`。 |

Rally 仍是非结构部署点，不进入本文件。

## 内置五项

| ID | 行为 | 形状 |
|---|---|---|
| `espetro:radio` | `radio` | `espetro:fortifications/radio` |
| `espetro:hab` | `hab` | `hab_attack` / `hab_defend` |
| `espetro:ammo_crate` | `ammo_crate` | `espetro:fortifications/ammo_crate` |
| `espetro:vehicle_supply_station` | `vehicle_supply_station` | DragonRise 实体，失败时用 fallback NBT |
| `espetro:sandbag_wall` | `generic` | 原 3×2 沙袋导出为 NBT |

管理员新增工事时，把 NBT 放到存档 datapack 的
`datapacks/<pack>/data/<namespace>/structures/<path>.nbt`，并在启动前启用该包。

## 游戏内施工流程

1. 指挥官、小队长或火力组长从 Alt 的“建造工事”选择目标。
2. 客户端只绘制逐方块线框：黄色可放置，红色被占据。
3. 左键确认后才扣除资源；任一步失败回滚方块、实体和费用。
4. 未建成阶段累计 `required_progress`；完成后提交最终模板/实体，并把当前结构值设为 `structural_value`。
5. 建成后铁铲左键按 `repair_per_hit` 修复，右键/爆炸/弹丸按结构部件扣结构值。
6. 施工状态只存在于当前会话，不写 SavedData。每次服务器启动会隔离当前存档的 `dimensions/espetro`。

## 载具补给范围

`vehicle_service.main_base_radius` 是原部署点装卸半径，`station_radius` 是已建成载具补给站的
服务半径；`transfer_amount` 与 `transfer_interval_ticks` 控制每次及每隔多久转移物资。
