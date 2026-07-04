# Espetro 配置文档

## 数据包覆盖规则

Espetro 使用 `EspetroDataResources` 读取 `data/espetro`。同一路径存在多个资源时，优先选择世界数据包等外部资源，找不到时回退到模组内置 JSON。

推荐服务器目录：

```text
world/datapacks/espetro_server_config/
├── pack.mcmeta
└── data/espetro/
    ├── config/
    │   ├── game.json
    │   ├── spawn_points.json
    │   ├── bastion.json
    │   ├── team_pack.json
    │   └── outposts.json
    └── factions/
        ├── my_attack_faction.json
        └── my_defend_faction.json
```

`pack.mcmeta`：

```json
{
  "pack": {
    "pack_format": 15,
    "description": "Espetro server configuration"
  }
}
```

执行 `/reload` 或 `/espetro reload` 后会重新加载阵营、游戏参数、复活点、兵站、队包、载具和前哨配置。JSON 必须是 UTF-8，不支持注释和尾随逗号。

## 构建与模组元数据配置

| `gradle.properties` 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `minecraft_version` | `1.20.1` | 目标游戏版本 |
| `forge_version` | `47.4.20` | Forge 开发版本 |
| `mod_id` | `espetro` | 模组 ID 与数据包命名空间 |
| `mod_version` | `1.0.2-final` | 构建版本 |
| `mod_group_id` | `com.shuai` | Maven group |

`META-INF/mods.toml` 声明 MUtil 6.3.0 为强制依赖，并将模组 ID 为 `espoints` 的 HCR AAD `>=1.0.4-final` 声明为可选依赖。未安装 HCR AAD 时部署界面使用占位地图；如需战术地图，客户端和服务器必须同时安装。版本升级时应同步修改 `hcr_aad_version`、本地开发依赖和元数据范围。

资源 JSON：

- `assets/espetro/lang/zh_cn.json`、`en_us.json` 是翻译键值表；应保持键集合一致。
- `assets/espetro/models/item/*.json` 是物品模型配置。
- `pack.mcmeta` 是内置资源包元数据；Minecraft 1.20.1 使用 `pack_format: 15`。
- 这些资源不参与服务端战术规则热重载。

## `game.json`

当前内置配置：

```json
{
  "game": {
    "required_players": 1,
    "deploy_timeout_seconds": 3,
    "deploy_warning_seconds": 0,
    "defend_commander_vote_seconds": 1,
    "attack_commander_vote_seconds": 1,
    "defend_faction_select_seconds": 1,
    "attack_faction_select_seconds": 1,
    "faction_pool_size": 6,
    "respawn_invincibility_ticks": 60,
    "teammate_name_tag_distance": 10.0,
    "waiting_y": 200
  },
  "troops": {
    "initial_attack": 280,
    "initial_defend": 1200,
    "commander_death_penalty": 2
  },
  "commander_skills": {
    "drone_detection_range": 100.0,
    "drone_detection_duration_seconds": 10,
    "drone_detection_cooldown_seconds": 60
  },
  "stamina": {
    "player_stamina": 100,
    "sprint_cost_per_second": 5,
    "jump_cost": 15,
    "regen_delay_seconds": 4,
    "regen_per_second": 2
  }
}
```

### `game`

| 字段 | 内置值 | 缺失/无文件回退 | 单位与说明 |
| --- | ---: | ---: | --- |
| `required_players` | 1 | 20 | 自动开始所需玩家数 |
| `deploy_timeout_seconds` | 3 | 240 | 部署阶段总时间，秒 |
| `deploy_warning_seconds` | 0 | 30 | 部署结束警告，秒 |
| `defend_commander_vote_seconds` | 1 | 20 | 守方指挥官投票，秒 |
| `attack_commander_vote_seconds` | 1 | 20 | 攻方指挥官投票，秒 |
| `defend_faction_select_seconds` | 1 | 30 | 守方编制选择，秒 |
| `attack_faction_select_seconds` | 1 | 30 | 攻方编制选择，秒 |
| `faction_pool_size` | 6 | 6 | 每次编制候选池数量 |
| `respawn_invincibility_ticks` | 60 | 60 | 复活保护 tick，20 tick = 1 秒 |
| `teammate_name_tag_distance` | 10.0 | 10.0 | 队友名牌距离，方块 |
| `waiting_y` | 200 | 200 | 等待状态传送高度 |

### `troops`

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `initial_attack` | 280 | 进攻方初始兵力 |
| `initial_defend` | 1200 | 防守方初始兵力 |
| `commander_death_penalty` | 2 | 指挥官阵亡额外兵力惩罚 |

### `commander_skills`

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `drone_detection_range` | 100.0 | 无人机侦测半径 |
| `drone_detection_duration_seconds` | 10 | 高亮持续时间 |
| `drone_detection_cooldown_seconds` | 60 | 技能冷却 |

### `stamina`

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `player_stamina` | 100 | 玩家初始体力与上限；设为 `-1` 时禁用整个体力系统 |
| `sprint_cost_per_second` | 5 | 持续奔跑时每秒消耗的体力 |
| `jump_cost` | 15 | 每次跳跃消耗的体力 |
| `regen_delay_seconds` | 4 | 最后一次消耗后，开始恢复前等待的秒数 |
| `regen_per_second` | 2 | 恢复期间每秒恢复的体力 |

持续奔跑会在开始时结算一次消耗，之后每秒结算一次；跳跃按次结算。停止消耗后经过 `regen_delay_seconds`，立即恢复一次，之后每秒按 `regen_per_second` 恢复。

体力耗尽时，服务端会禁止玩家奔跑和跳跃。体力未满时，客户端 HUD 会在准星下方显示一条 `40×2` 像素、无数字的细白线，线条长度表示剩余体力；体力回满后自动隐藏。修改数据包配置后可使用 `/reload` 热重载。

除 `player_stamina: -1` 这一禁用值外，生产配置应使用非负数，并确保阶段时间合理。

## `spawn_points.json`

```json
{
  "spawnPoints": {
    "ATTACK": { "x": 3020, "y": 9, "z": -3640, "yaw": 0 },
    "DEFEND": { "x": -2284, "y": 9, "z": 2306, "yaw": 180 }
  }
}
```

| 字段 | 类型 | 缺失默认 |
| --- | --- | --- |
| `x`, `z` | Double | 0 |
| `y` | Double | 65 |
| `yaw` | Float | ATTACK 0，DEFEND 180 |

文件不存在时完整回退为 ATTACK `(100.5,65,0.5,0)`、DEFEND `(-100.5,65,0.5,180)`。命令 `/espetro spawnpoint here <ATTACK|DEFEND>` 只修改内存，不写回 JSON。

## `bastion.json`

```json
{
  "bastion": {
    "cooldown_seconds": 800,
    "required_planks": 0,
    "armor_stand_health": 5,
    "destroy_troop_penalty": 20
  }
}
```

| 字段 | 内置值 | 无文件回退 | 说明 |
| --- | ---: | ---: | --- |
| `cooldown_seconds` | 800 | 800 | 玩家建造兵站冷却 |
| `required_planks` | 0 | 640 | 建造所需任意木板数；0 为不消耗 |
| `armor_stand_health` | 5 | 5 | 核心生命值，解析时至少为 1 |
| `destroy_troop_penalty` | 20 | 20 | 兵站被摧毁时本方扣兵力 |

弹药补给冷却固定为 5 分钟，不在此 JSON 中配置。

## `team_pack.json`

```json
{
  "team_pack": {
    "cooldown_seconds": 300
  }
}
```

`cooldown_seconds` 是每个小队部署队包的冷却，最小为 0。队包只允许小队队长放置，使用带 Espetro NBT 标记的信标物品。

## `outposts.json`

```json
{
  "redeploy_cooldown_seconds": 60,
  "outposts": [
    {
      "name": "前哨基地A",
      "x": -2284,
      "y": 9,
      "z": 2306,
      "yaw": 180
    }
  ]
}
```

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `redeploy_cooldown_seconds` | 60 | 重新部署冷却，至少 0 |
| `outposts` | 空 | 前哨数组；空时功能不启用 |
| `name` | `前哨` | 显示名 |
| `x`, `z` | 0 | 传送坐标 |
| `y` | 64 | 传送高度 |
| `yaw` | 0 | 朝向 |

前哨只在 `DEPLOYING` 阶段对防守方可用，进入 `BATTLE` 后停用。

## 阵营/编制 JSON

每个 `data/espetro/factions/<faction_id>.json` 定义一个编制。文件名（不含 `.json`）就是 `faction_id`。

### 完整示例

```json
{
  "faction": {
    "name": "示例合成旅",
    "description": "用于说明配置格式",
    "icon": "★",
    "team": "ATTACK",
    "color": "AA5555"
  },
  "vehicles": {
    "transport": {
      "entity_type": "minecraft:minecart",
      "display_name": "§6运输车",
      "max": 2,
      "respawn_minutes": 5,
      "deployment": {
        "mode": "deploy_point",
        "offset": [8, 0, 0],
        "radius": 5,
        "yaw": 90,
        "snap_to_ground": true,
        "vertical_scan": 6
      }
    }
  },
  "classes": {
    "EXAMPLE_RIFLEMAN": {
      "name": "步枪兵",
      "description": "标准步兵",
      "role": "突击",
      "maxPlayers": 8,
      "healthBonus": 0,
      "speedBonus": 0.0,
      "troopValue": 1,
      "auto_equip_wearables": true,
      "equipment": {
        "head": "minecraft:iron_helmet 1",
        "chest": "minecraft:iron_chestplate 1",
        "mainhand": "minecraft:iron_sword 1"
      },
      "commands": [
        "minecraft:shield 1",
        "minecraft:bread 16"
      ],
      "resupply": {
        "items": [
          { "id": "minecraft:bread", "count": 8, "max": 16 }
        ]
      }
    }
  }
}
```

### `faction`

| 字段 | 类型 | 说明 |
| --- | --- | --- |
| `name` | String | 玩家可见名称 |
| `description` | String | 编制描述 |
| `icon` | String | GUI 图标文本/Emoji |
| `team` | String | 必须使用 `ATTACK` 或 `DEFEND` |
| `color` | String | 六位 RGB，不带 `#` |

### `classes.<class_id>`

| 字段 | 类型 | 默认/说明 |
| --- | --- | --- |
| `name`, `description`, `role` | String | GUI 信息 |
| `maxPlayers` | Integer | 0 或缺失时回退 5 |
| `troopValue` | Integer | 0 或缺失时回退 1；阵亡扣兵力 |
| `healthBonus` | Integer | 默认 0 |
| `speedBonus` | Float | 默认 0 |
| `commands` | String[] | `/give <player>` 后面的参数，不含命令前缀和玩家名 |
| `equipment` | Object | 直接通过 `/item replace` 装入槽位 |
| `wearable_equipment` | Object | `equipment` 的语义别名 |
| `auto_equip_wearables` | Boolean | 默认启用；自动穿戴 `commands` 中的护甲 |
| `resupply.items` | Array | 兵站补给项目 |

`equipment` 支持槽位：`head`/`helmet`/`armor.head`、`chest`、`legs`、`feet`、`mainhand`、`offhand` 及代码中对应别名。

补给项：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `id` | 必填 | 物品注册 ID |
| `nbt` | 空 | 可选 SNBT |
| `count` | 16 | 单次补给数量 |
| `max` | 64 | 背包上限 |

### `vehicles.<vehicle_id>`

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `entity_type` | 必填 | 实体注册 ID，可来自其他模组 |
| `display_name` | vehicle ID | 显示名，可含 `§` 颜色码 |
| `max` | 1 | 同类最大部署数量 |
| `respawn_minutes` | 5 | 单辆刷新冷却 |
| `deployment.mode` | `deploy_point` | `deploy_point`、`fixed` 或 `absolute` |
| `deployment.absolute` | 空 | 固定坐标 `[x,y,z]` |
| `deployment.offset` | `[0,0,0]` | 相对当前部署点偏移 |
| `deployment.radius` | 6 | 周围落点搜索半径，至少 0 |
| `deployment.yaw` | 0 | 朝向 |
| `deployment.snap_to_ground` | `true` | 是否寻找地面 |
| `deployment.vertical_scan` | 6 | 上下扫描距离，至少 1 |

旧式兼容字段 `position`、`offset`、`radius`、`yaw` 可直接写在车辆对象下；新配置应使用 `deployment`。

## 内置编制

| 文件 ID | 队伍 | 说明 |
| --- | --- | --- |
| `pla_heavy_brigade` | ATTACK | PLA 重型合成旅 |
| `pla_medium_brigade` | ATTACK | PLA 中型合成旅 |
| `pla_rapid_force` | ATTACK | 快速反应占位编制，当前无职业/载具 |
| `russia_army` | ATTACK | 俄罗斯陆上部队 |
| `russia_logistics` | ATTACK | 俄罗斯后勤编制 |
| `middle_east_militia` | ATTACK | 中东联合武装 |
| `us_airborne` | DEFEND | 美国空降部队 |
| `us_cavalry` | DEFEND | 美国骑兵旅 |
| `ukraine_irregular` | DEFEND | 乌萨克非正规武装 |

## 旧配置与生成文件

- `config/espetro/factions/<id>.json` 只由 `FactionConfigLoader` 作为旧式队伍判断回退读取；完整编制应放数据包路径。
- 当前源码没有注册 Espetro Forge TOML。已有 `config/espetro-common.toml` 是旧构建遗留，不控制现行业务逻辑。
- `assets/espetro/lang/*.json` 是翻译资源，不应作为服务器业务配置。

## 配置命令

```text
/espetro reload
/espetro reset
/espetro start
/espetro status
/espetro teams
/espetro factions
/espetro spawnpoint
/espetro spawnpoint here <ATTACK|DEFEND>
/espetro setclass <player> <classId>
/espetro troops
/espetro troops set <ATTACK|DEFEND> <value>
/espetro troops add <ATTACK|DEFEND> <value>
/vehicle list
/vehicle spawn <vehicleId>
/bastion list
/bastion select <uuid>
/bastion deploy
/outpost list
/outpost deploy <index>
/outpost redeploy
```

管理员配置命令通常要求权限等级 2；载具部署要求当前指挥官身份。
