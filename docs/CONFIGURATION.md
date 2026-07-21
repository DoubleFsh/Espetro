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
    │   ├── logistics.json
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

执行 `/reload` 或 `/espetro reload` 后会重新加载阵营、游戏参数、复活点、兵站、队包、载具和前哨配置。指挥官技能由 KubeJS 脚本注册和实现，按 KubeJS 自身的脚本加载规则生效。JSON 必须是 UTF-8，不支持注释和尾随逗号。

`logistics.json` 的补给方块、方块标签、方块实体 NBT、补给物品和 FOB 库存配置见
[后勤与补给配置](LOGISTICS_CONFIG.md)。

## 构建与模组元数据配置

| `gradle.properties` 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `minecraft_version` | `1.20.1` | 目标游戏版本 |
| `forge_version` | `47.4.20` | Forge 开发版本 |
| `mod_id` | `espetro` | 模组 ID 与数据包命名空间 |
| `mod_version` | `1.0.6-final` | 构建版本 |
| `mod_group_id` | `com.shuai` | Maven group |
| `mutil_version` | `6.3.0` | MUtil 强制依赖版本 |
| `rhino_version` | `2001.2.2-build.17` | Rhino 强制依赖版本，用于 KubeJS JavaScript |
| `architectury_version` | `9.1.12` | Architectury API 强制依赖版本，满足 KubeJS Forge 1.20.1 要求 |
| `espoints_version` | `1.0.6-final` | ESPoints 可选运行时依赖版本 |
| `kubejs_version` | `2001.6.5-build.26` | KubeJS 强制运行依赖版本 |

`META-INF/mods.toml` 声明 MUtil 6.3.0、Rhino `2001.2.2-build.17`、Architectury API `>=9.1.12` 和 KubeJS `>=2001.6.5-build.26` 为强制依赖，并将模组 ID 为 `espoints` 的 ESPoints `>=1.0.6-final` 声明为可选依赖。未安装 ESPoints 时部署界面使用占位地图；如需战术地图或 `155火炮支援` 选点，客户端和服务器必须同时安装 ESPoints。版本升级时应同步修改 Gradle 属性、本地开发依赖和元数据范围。

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
  "stamina": {
    "player_stamina": 100,
    "sprint_cost_per_second": 5,
    "jump_cost": 15,
    "regen_delay_seconds": 4,
    "regen_per_second": 2
  },
  "tutorial": {
    "enabled": false,
    "show_on_join": true,
    "allow_skip": true
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
| `waiting_y` | 200 | 200 | 统一等待部署点的高空 Y 坐标；玩家聚集于主世界 `(0.5, waiting_y, 0.5)`，选择部署点前保持旁观、失明并锁位 |

### `troops`

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `initial_attack` | 280 | 进攻方初始兵力 |
| `initial_defend` | 1200 | 防守方初始兵力 |
| `commander_death_penalty` | 2 | 指挥官阵亡额外兵力惩罚 |

`game.json` 不包含任何指挥官技能配置。技能列表、显示文本、触发方式、冷却、无人机范围、载具补给站布局和火炮参数都由 KubeJS 脚本负责。

## KubeJS 指挥官技能

Espetro 首次启动时会在游戏目录写入默认脚本：

```text
kubejs/startup_scripts/00_espetro_drone_detection.js
kubejs/startup_scripts/00_espetro_vehicle_supply_station.js
kubejs/startup_scripts/00_espetro_artillery_155.js
kubejs/server_scripts/00_espetro_drone_detection.js
kubejs/server_scripts/00_espetro_vehicle_supply_station.js
kubejs/server_scripts/00_espetro_artillery_155.js
```

`startup_scripts` 调用 `EspetroCommanderSkills.create(id).register()` 注册技能元数据；`server_scripts` 调用 `EspetroCommanderSkills.on(id, callback)` 实现实际效果。默认脚本按技能拆分，便于单独维护。默认 `155火炮支援` 打开 ESPoints 战术地图，选点后由 KubeJS 回调用纯 KubeJS tick 调度，并通过 `level.createEntity(...)`、`entity.setPositionAndRotation(...)`、`entity.setMotionX/Y/Z(...)` 和 `entity.spawn()` 从目标上空分两批发射实体。

已有自定义脚本不会被覆盖。旧版 Espetro 默认合并脚本 `00_espetro_commander_skills.js` 如果仍带默认文件头，会在启动时改名为 `00_espetro_commander_skills.js.disabled` 保存，避免与拆分后的脚本重复加载。

详见 [KubeJS 指挥官技能配置与开发文档](COMMANDER_SKILL_SCRIPTS.md)。

### `stamina`

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `player_stamina` | 100 | 玩家初始体力与上限；设为 `-1` 时禁用整个体力系统 |
| `sprint_cost_per_second` | 5 | 持续奔跑时每秒消耗的体力 |
| `jump_cost` | 15 | 每次跳跃消耗的体力 |
| `regen_delay_seconds` | 4 | 最后一次消耗后，开始恢复前等待的秒数 |
| `regen_per_second` | 2 | 恢复期间每秒恢复的体力 |

### `tutorial`

引导式新手教程：不打断正常 8 阶段对局，在进服与关键节点通过 AuraTip 原生提示层展示分步内容，不再向聊天栏重复发送教程正文。关闭当前 AuraTip 会继续队列中的下一步。进度仅本会话有效；「跳过」后本会话不再自动弹出。重进服务器会重新开始；也可用命令手动打开。

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `false` | 总开关。生产服建议关闭；教学服在世界数据包中设为 `true` |
| `show_on_join` | `true` | 进服是否自动推送欢迎与当前阶段步骤 |
| `allow_skip` | `true` | 是否允许使用 `/espetro tutorial skip` 跳过全部教程；关闭当前提示始终可继续下一步 |

教学服示例（世界数据包覆盖 `game.json` 片段）：

```json
"tutorial": {
  "enabled": true,
  "show_on_join": true,
  "allow_skip": true
}
```

玩家命令（无需 OP）：

| 命令 | 说明 |
| --- | --- |
| `/espetro tutorial` | 重新打开欢迎/当前阶段教程 |
| `/espetro tutorial next` | 下一步 |
| `/espetro tutorial dismiss` | 关闭当前步骤（队列中有后续则继续展示） |
| `/espetro tutorial skip` | 跳过本会话全部教程 |
| `/espetro tutorial status` | 查看开关与本会话状态 |

`/reload` 或 `/espetro reload` 后会重新读取 `tutorial` 节点；若 `enabled` 变为 `false`，会清除在线玩家的教程卡片。

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

每个 `data/espetro/factions/<formation_id>.json` 定义一个编制。文件名（不含 `.json`）是编制 ID；
`faction.faction_id` 则是该编制所属的阵营字符串。这两个概念彼此独立。

### 完整示例

```json
{
  "faction": {
    "name": "示例合成旅",
    "description": "用于说明配置格式",
    "icon": "★",
    "selection_image": "espetro:textures/gui/factions/example.png",
    "faction_id": "EXAMPLE_COUNTRY",
    "team": "ATTACK",
    "color": "AA5555"
  },
  "vehicles": {
    "transport": {
      "entity_type": "minecraft:minecart",
      "display_name": "§6运输车",
      "max": 2,
      "respawn_minutes": 5,
      "troop_value": 5,
      "deployment": {
        "ATTACK": { "position": [3028, 9, -3640], "yaw": 90 },
        "DEFEND": { "position": [-2276, 9, 2306], "yaw": 90 }
      }
    }
  },
  "classes": {
    "EXAMPLE_RIFLEMAN": {
      "name": "步枪兵",
      "description": "标准步兵",
      "role": "突击",
      "icon": "rifleman",
      "maxPlayers": 8,
      "healthBonus": 0,
      "speedBonus": 0.0,
      "troopValue": 1,
      "variants": {
        "standard": {
          "name": "标准步枪",
          "description": "通用步枪与盾牌",
          "maxPlayers": 5,
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
            "ammo_cost": 30,
            "items": [
              { "id": "minecraft:bread", "count": 8, "max": 16 }
            ]
          }
        },
        "grenadier": {
          "name": "榴弹手",
          "description": "携带爆炸物的突击配装",
          "maxPlayers": 3,
          "equipment": {
            "head": "minecraft:iron_helmet 1",
            "chest": "minecraft:iron_chestplate 1",
            "mainhand": "minecraft:iron_sword 1"
          },
          "commands": [
            "minecraft:fire_charge 16",
            "minecraft:bread 16"
          ],
          "resupply": {
            "ammo_cost": 60,
            "items": [
              { "id": "minecraft:fire_charge", "count": 4, "max": 16 }
            ]
          }
        }
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
| `selection_image` | String | 可选；编制投票卡片使用的完整资源位置，如 `espetro:textures/gui/factions/plaagf.png`；也接受别名 `selectionImage` |
| `faction_id` | String | **必填**；编制所属阵营，直接使用此处字符串，不需要另行注册 |
| `team` | String | 必须使用 `ATTACK` 或 `DEFEND` |
| `color` | String | 六位 RGB，不带 `#` |

编制图片的源文件分辨率不限。客户端会完整采样整张纹理，并将其拉伸或缩放到与内置编制图片完全相同的固定卡片图片区，不会裁剪；因此宽高比不同的图片可能发生形变。没有配置、路径无效或客户端资源包中不存在时，卡片仍保持相同尺寸，并在编制名下显示“还没配置图片喵”。服务器与客户端需要安装包含该资源的同一模组或资源包。

`faction_id` 使用区分大小写、保留空白的精确字符串比较。某方确定编制后，另一方的候选池会排除所有拥有相同 `faction_id` 的编制，确保攻守双方不属于同一阵营。缺失或空白的 `faction_id` 会导致整个编制拒绝载入。

### `classes.<class_id>`

| 字段 | 类型 | 默认/说明 |
| --- | --- | --- |
| `name`, `description`, `role` | String | GUI 信息 |
| `icon` | String | 可选；`assets/espetro/textures/gui/roles/` 下不带扩展名的职业图标短名 |
| `maxPlayers` | Integer | 职业总人数上限，必须大于 0；按整个 `ATTACK`/`DEFEND` 队伍统计，不按小队拆分 |
| `troopValue` | Integer | 0 或缺失时回退 1；阵亡扣兵力 |
| `healthBonus` | Integer | 默认 0 |
| `speedBonus` | Float | 默认 0 |
| `variants` | Object | 变体 ID 到装备变体的映射；JSON 中的顺序就是二级菜单显示顺序 |

### `classes.<class_id>.variants.<variant_id>`

| 字段 | 类型 | 默认/说明 |
| --- | --- | --- |
| `name` | String | 二级菜单显示名；缺失时使用变体 ID |
| `description` | String | 二级菜单说明 |
| `maxPlayers` | Integer | 该变体人数上限，必须大于 0；同样按整个攻方或守方统计 |
| `commands` | String[] | `/give <player>` 后面的参数，不含命令前缀和玩家名 |
| `equipment` | Object | 直接装备到指定槽位 |
| `wearable_equipment` | Object | `equipment` 的语义别名 |
| `auto_equip_wearables` | Boolean | 默认启用；自动穿戴 `commands` 中的护甲 |
| `resupply.items` | Array | 此变体自己的兵站补给项目 |
| `resupply.ammo_cost` | Integer | 此变体单次有效补给消耗；缺失时使用 `logistics.json` 默认值 |

显式配置 `variants` 时，每个变体都是独立完整配装，装备和补给不会从职业节点或其他变体继承。
所有变体的 `maxPlayers` 之和必须**严格等于**职业的 `maxPlayers`；任何变体无效、人数不相等，都会在启动或 `/reload` 时输出 `[编制拒载]` 警告并拒绝载入整个编制，不会只跳过出错职业。

兼容旧 JSON：若完全没有 `variants`，职业节点原有的 `commands`、`equipment`、`wearable_equipment`、`auto_equip_wearables` 和 `resupply` 会被合成为一个隐式 `default` 变体，人数等于职业上限。若显式配置了 `variants`，它不可为空，职业节点上的这些旧装备字段会被忽略并警告。

玩家点击只有一个变体的职业时会直接选择；两个及以上变体会在光标旁打开二级菜单。二级菜单显示实时人数，可用右上角 `X`、`Esc` 或点击菜单外关闭。

`equipment` 支持槽位：`head`/`helmet`/`armor.head`、`chest`、`legs`、`feet`、`mainhand`、`offhand` 及代码中对应别名。

补给项：

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `id` | 必填 | 物品注册 ID |
| `nbt` | 空 | 可选 SNBT |
| `count` | 16 | 单次补给数量 |
| `max` | 64 | 背包上限 |

只有实际补入至少一种物品时才进入补给冷却并尝试扣除 `ammo_cost`。Radio/FOB
弹药库存大于 0、但不足完整费用时，玩家仍会得到本次补给，并扣除全部剩余弹药，
使库存归零；库存已经为 0 时不发放补给。

### `vehicles.<vehicle_id>`

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `entity_type` | 必填 | 实体注册 ID，可来自其他模组 |
| `display_name` | vehicle ID | 显示名，可含 `§` 颜色码 |
| `max` | 1 | 同类最大部署数量 |
| `respawn_minutes` | 5 | 单辆刷新冷却 |
| `troop_value` | 0 | 载具死亡/被摧毁时扣除的所属队伍兵力 |
| `deployment.ATTACK.position` | 必填 | 该编制被攻方选中时使用的固定坐标 `[x,y,z]` |
| `deployment.ATTACK.yaw` | 0 | 攻方载具朝向 |
| `deployment.DEFEND.position` | 必填 | 该编制被守方选中时使用的固定坐标 `[x,y,z]` |
| `deployment.DEFEND.yaw` | 0 | 守方载具朝向 |

载具部署点只支持在阵营 JSON 中按 `ATTACK`/`DEFEND` 直接指定坐标；同一个编制被哪一方选中，就使用对应队伍下的坐标。不会再按玩家部署点、偏移量、半径或自动落地逻辑推导位置。

## 内置编制

| 文件 ID | 队伍 | `faction_id` | 说明 |
| --- | --- | --- | --- |
| `pla_heavy_brigade` | ATTACK | `PLA` | PLA 重型合成旅 |
| `pla_medium_brigade` | ATTACK | `PLA` | PLA 中型合成旅 |
| `pla_rapid_force` | ATTACK | `PLA` | 快速反应占位编制，当前无职业/载具 |
| `russia_army` | ATTACK | `RUSSIA` | 俄罗斯陆上部队 |
| `russia_logistics` | ATTACK | `RUSSIA` | 俄罗斯后勤编制 |
| `middle_east_militia` | ATTACK | `MIDDLE_EAST_MILITIA` | 中东联合武装 |
| `us_airborne` | DEFEND | `USA` | 美国空降部队 |
| `us_cavalry` | DEFEND | `USA` | 美国骑兵旅 |
| `ukraine_irregular` | DEFEND | `UKRAINE_IRREGULAR` | 乌萨克非正规武装 |

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
/espetro setclass <factionId> <classId> <player>
/espetro setclass <factionId> <classId> variant <variantId> <player>
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
