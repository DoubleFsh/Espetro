# Espetro 配置文档

## 启动冻结与目录结构

地图、编制及游戏设置不再从存档 datapack 读取。它们直接位于游戏实例或服务端根目录，并且只在客户端/服务端启动阶段读取一次：

**运行时权威：** 阶段秒数、兵力、复活点、兵站/前哨/后勤/队包等**地图级**参数在战场激活时由该图 `EsWorld/<map>/EsConfig/` 快照应用；**不再**从 `data/espetro/config` datapack 读取。编制仅来自根目录 `EsFactions/`。

```text
<游戏或服务端根目录>/
├── EsDimensions.json
├── EsFactions/
│   ├── my_attack_faction.json
│   └── my_defend_faction.json
└── EsWorld/
    └── my_map/
        ├── level.dat
        ├── region/
        ├── entities/
        ├── poi/
        ├── data/
        └── EsConfig/
            ├── game.json
            ├── spawn_points.json
            ├── bastion.json
            ├── logistics.json
            ├── team_pack.json
            ├── outposts.json
            ├── SquadTypes.json
            ├── VehSpawn.json
            ├── TacticalMap.json
            ├── CapturePoints.json
            └── map.png              # 可选战术地图底图
```

`level.dat` 与至少一个非空 `region/*.mca` 是有效地图模板的必要条件。维度生成器直接从该地图自己的 `level.dat` 读取，因此超平坦、噪声世界等模板不会共享一份写死的生成器。服务端在 `ServerAboutToStartEvent`、原版创建各个 `ServerLevel` 之前，把所有有效模板的 `region/entities/poi/data/EsConfig` 复制到当前存档，作为每张地图的首局副本。地图使用完毕后会卸载并删除该副本；后续再次选中同一地图时，才重新从只读模板复制并挂载新的 `ServerLevel`。因此每局地形破坏只存在于当前存档副本中，`EsWorld` 原件始终不变。

首次启动会从 JAR 导出 `test_flat` 超平坦地图、地图侧 JSON 和十份示例编制。只要 `EsFactions/` 已有任意 JSON，导出器就不会向该目录新增、覆盖或删除任何文件；只有空目录或全新安装才会生成示例编制。`/reload` 与 `/espetro reload` 明确不会重读上述文件；修改后必须完整重启。

JSON 必须是 UTF-8，不支持注释和尾随逗号。指挥官技能仍由 KubeJS 脚本按 KubeJS 自身规则加载。

### `EsDimensions.json`

```json
{
  "_comment": "dimension_id 可省略，推荐让系统稳定生成并避免冲突；修改后必须重启。",
  "map_vote_seconds": 30,
  "dimensions": [
    {
      "name": "我的战场",
      "map": "my_map"
    }
  ]
}
```

- `name`：投票与界面显示名。
- `map`：`EsWorld/` 下的安全单层目录名。
- `dimension_id`：可选的 `<namespace>:<path>`；省略时稳定生成 `espetro:<map>`。禁止使用 `minecraft`、`forge`、`espetro` 作为手工 namespace，也禁止重复 ID。
- `map_vote_seconds`：全局地图投票时长，最少 5 秒。

缺失地图、非法目录、无效 `level.dat`、缺少区块或任一必需 `EsConfig` 时，该维度会输出明确错误并拒绝注册。

`logistics.json` 的补给方块、方块标签、方块实体 NBT、补给物品和 FOB 库存配置见
[后勤与补给配置](LOGISTICS_CONFIG.md)。

## ESPoints 地图扩展

每张地图必须在自己的 `EsConfig/` 下提供 `TacticalMap.json` 和
`CapturePoints.json`。Espetro 在启动时解析、校验并冻结它们；地图激活后通过只读快照交给
ESPoints。ESPoints 不会自行扫描存档、服务器全局配置或数据包，也不会写入
`EsWorld/<地图>/EsConfig/`。

### `TacticalMap.json`

```json
{
  "topLeftX": -512,
  "topLeftZ": -512,
  "bottomRightX": 512,
  "bottomRightZ": 512,
  "initialRange": 512,
  "minimumRange": 64,
  "backgroundImage": "map.png",
  "backgroundImageWidth": 1024,
  "backgroundImageHeight": 1024,
  "showGrid": true,
  "showLabels": true,
  "tacticalMarkerDurationSeconds": 120,
  "tacticalMarkerFadeSeconds": 120
}
```

| 字段 | 说明 |
| --- | --- |
| `topLeftX/topLeftZ` | 战术地图西北角世界坐标 |
| `bottomRightX/bottomRightZ` | 战术地图东南角世界坐标，必须分别大于左上角 |
| `initialRange` | 打开地图时显示的世界范围，必须大于 0 |
| `minimumRange` | 最大放大时的最小范围，必须大于 0 且不超过 `initialRange` |
| `backgroundImage` | 可留空；非空时只能引用同一 `EsConfig/` 内的相对 PNG 文件 |
| `backgroundImageWidth/Height` | 底图原始尺寸提示；不用底图时可为 0 |
| `showGrid/showLabels` | 是否显示网格和名称 |
| `tacticalMarkerDurationSeconds` | 标点完整显示时长 |
| `tacticalMarkerFadeSeconds` | 标点淡出时长 |

底图最大 16 MiB。绝对路径、`..`、跨目录路径、非 PNG 文件、符号链接和伪造 PNG 都会使整张地图在启动时被拒绝。底图内容由服务端从冻结快照分片同步给客户端。

### `CapturePoints.json`

```json
{
  "totalBatches": 2,
  "endBehavior": "terminate",
  "teamReinforcements": {
    "ATTACK": 280,
    "DEFEND": 1200
  },
  "plannedPoints": [
    {
      "name": "A",
      "batch": 1,
      "pos1": {"x": -24, "y": 60, "z": -24},
      "pos2": {"x": 24, "y": 72, "z": 24}
    },
    {
      "name": "B",
      "batch": 2,
      "pos1": {"x": 104, "y": 60, "z": -24},
      "pos2": {"x": 152, "y": 72, "z": 24}
    }
  ]
}
```

- `totalBatches` 必须至少为 1。
- `endBehavior` 只能为 `terminate` 或 `loop`。
- `teamReinforcements.ATTACK/DEFEND` 都必须为正整数。
- `plannedPoints` 必须是数组；据点名只能是单个 `A-Z` 字母且不能重复。
- `batch` 必须落在 `1..totalBatches`，每批最多 7 个据点；`pos1` 与 `pos2` 是占领区域的两个不同角点。
- 部署阶段开始时 ESPoints 根据这个冻结快照创建第一批据点。切换地图或强制结束时，据点、标点、底图和缓存会一起清空。
- 管理命令的“保存配置”只会导出到 `config/espoints/exports/`，不会修改地图模板。

## 构建与模组元数据配置

| `gradle.properties` 字段 | 当前值 | 说明 |
| --- | --- | --- |
| `minecraft_version` | `1.20.1` | 目标游戏版本 |
| `forge_version` | `47.4.20` | Forge 开发版本 |
| `mod_id` | `espetro` | 模组 ID 与数据包命名空间 |
| `mod_version` | `1.1.0-alpha` | 构建版本 |
| `mod_group_id` | `com.shuai` | Maven group |
| `mutil_version` | `6.3.0` | MUtil 强制依赖版本 |
| `oelib_version` | `0.2.4` | AuraTip 运行时强制依赖版本 |
| `auratip_version` | `1.1.1-beta` | 战术轮盘强制依赖版本 |
| `rhino_version` | `2001.2.2-build.17` | Rhino 强制依赖版本，用于 KubeJS JavaScript |
| `architectury_version` | `9.1.12` | Architectury API 强制依赖版本，满足 KubeJS Forge 1.20.1 要求 |
| `espoints_version` | `1.1.0-final` | ESPoints 可选运行时依赖版本 |
| `kubejs_version` | `2001.6.5-build.26` | KubeJS 强制运行依赖版本 |

`META-INF/mods.toml` 声明 MUtil、OELib、AuraTip、Rhino、Architectury API 和 KubeJS 为强制依赖，并将模组 ID 为 `espoints` 的 ESPoints `>=1.1.0-final` 声明为可选依赖。普通 GUI 使用 MUtil，战术轮盘使用 AuraTip；默认指挥官技能需要 KubeJS。未安装 ESPoints 时部署界面使用占位地图；如需战术地图、据点或 `155火炮支援` 选点，客户端和服务器必须同时安装工作区同步版 ESPoints 与 Ping Wheel。开发环境仅在传入 `-PespetroIncludeLocalEspoints=true` 时注入兄弟项目 JAR。

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
    "team_select_seconds": 60,
    "deploy_timeout_seconds": 3,
    "deploy_warning_seconds": 0,
    "defend_commander_vote_seconds": 1,
    "attack_commander_vote_seconds": 1,
    "defend_faction_select_seconds": 1,
    "attack_faction_select_seconds": 1,
    "faction_pool_size": 6,
    "faction_reveal_seconds": 3,
    "round_end_seconds": 10,
    "respawn_invincibility_ticks": 60,
    "main_base_invulnerability_radius": 150.0,
    "class_switch_cooldown_seconds": 60,
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
    "regen_delay_seconds": 2,
    "regen_per_second": 2,
    "full_recovery_seconds": 12
  },
  "governance": {
    "impeachment_vote_seconds": 60,
    "impeachment_cooldown_seconds": 600,
    "commander_vacancy_seconds": 180
  }
}
```

### `game`

| 字段 | 内置值 | 缺失/无文件回退 | 单位与说明 |
| --- | ---: | ---: | --- |
| `team_select_seconds` | 60 | 60 | 地图载入后的自由选边时间 |
| `deploy_timeout_seconds` | 3 | 240 | 部署阶段总时间，秒 |
| `deploy_warning_seconds` | 0 | 30 | 部署结束警告，秒 |
| `defend_commander_vote_seconds` | 1 | 20 | 守方指挥官投票，秒 |
| `attack_commander_vote_seconds` | 1 | 20 | 攻方指挥官投票，秒 |
| `defend_faction_select_seconds` | 1 | 30 | 守方编制选择，秒 |
| `attack_faction_select_seconds` | 1 | 30 | 攻方编制选择，秒 |
| `faction_pool_size` | 6 | 6 | 每次编制候选池数量 |
| `faction_reveal_seconds` | 3 | 8 | 双方编制揭示时间 |
| `round_end_seconds` | 10 | 10 | 回合结算界面时间 |
| `respawn_invincibility_ticks` | 60 | 60 | 复活保护 tick，20 tick = 1 秒 |
| `main_base_invulnerability_radius` | 150.0 | 150.0 | 本方原部署点的水平无敌半径，方块；玩家和所属本阵营的载具均受保护，设为 `0` 可关闭 |
| `class_switch_cooldown_seconds` | 60 | 60 | 每名玩家成功选择职业后的独立换职冷却，秒；首次选职不受已有冷却限制，设为 `0` 可关闭 |
| `teammate_name_tag_distance` | 10.0 | 10.0 | 队友名牌距离，方块 |
| `waiting_y` | 200 | 200 | 尚未选择阵营及阶段切换时的高空等待 Y；已选择阵营后的首次部署、死亡重新部署和主动重新部署均以冒险模式临时锁在本方原部署点 |

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
| `regen_delay_seconds` | 2 | 最后一次消耗后，开始恢复前等待的秒数 |
| `regen_per_second` | 2 | 恢复期间每秒恢复的最低体力值 |
| `full_recovery_seconds` | 12 | 从最后一次消耗起，完全耗尽的体力回满所需的最长秒数；会按体力上限自动提高每秒恢复量，设为 `0` 使用旧版固定恢复速度 |

### 静态教程

教程不再是配置项。玩家在主城 GUI 点击“进入新手教程”后，会打开静态、可滚动的 MUtil 教程页；对局过程中不会自动推送动态提示。

持续奔跑会在开始时结算一次消耗，之后每秒结算一次；跳跃按次结算。停止消耗后经过 `regen_delay_seconds`，立即恢复一次，之后每秒恢复。默认 `full_recovery_seconds: 12` 会根据 `player_stamina` 自动提高恢复量，保证从最后一次消耗算起 12 秒内回满；`regen_per_second` 仍是最低恢复速度。若等待时间长到无法满足目标，系统会自动缩短有效等待时间。

体力耗尽时，服务端会禁止玩家奔跑和跳跃。体力未满时，客户端 HUD 会在 Action Bar 下方显示一条 `40×2` 像素、无数字的细白线，线条长度表示剩余体力；体力回满后自动隐藏。修改地图的 `EsConfig/game.json` 后必须重启。

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
| `cooldown_seconds` | 800 | 800 | 玩家建造兵站冷却的**默认值**；可被 `logistics.radio.cooldown_seconds` 覆盖（≥0 时） |
| `required_planks` | 0 | 640 | **补给建材点数**默认值（非任意原版木板）；可被 `logistics.radio.required_planks` 覆盖；0 为不消耗 |
| `armor_stand_health` | 5 | 5 | 核心生命值，解析时至少为 1 |
| `destroy_troop_penalty` | 20 | 20 | 兵站被摧毁时本方扣兵力 |

放置阶段/权限/排斥半径/队友人数等见 [LOGISTICS_CONFIG.md](LOGISTICS_CONFIG.md) 的 `logistics.radio`。

弹药补给无冷却，不在此 JSON 中配置。补给只有在玩家确有弹药缺口且
Radio 能足额支付职业变体的 `ammo_cost` 时才会提交；满弹或库存不足不会扣费或发物品。

## `team_pack.json`

```json
{
  "team_pack": {
    "cooldown_seconds": 120,
    "durability": 1,
    "break_speed_multiplier": 8.0,
    "teammate_count": 0,
    "teammate_radius": 8.0,
    "enemy_placement_radius": 50.0,
    "enemy_burn_radius": 30.0,
    "wave_seconds": 60,
    "minimum_respawn_seconds": 20
  }
}
```

| 字段 | 默认值 | 说明 |
| --- | ---: | --- |
| `cooldown_seconds` | 120 | 每个小队部署队包的冷却，最小为 0 |
| `durability` | 1 | Rally 耐久 |
| `break_speed_multiplier` | 8.0 | 破坏速度倍率 |
| `teammate_count` | 0 | 放置者之外所需的附近同小队队员人数；0 表示不要求队员 |
| `teammate_radius` | 8.0 | 以实际放置点为中心检测同小队队员的半径，最小为 0 |
| `enemy_placement_radius` | 50.0 | 附近有敌人时禁止放置 |
| `enemy_burn_radius` | 30.0 | 敌人进入后烧毁 Rally |
| `wave_seconds` | 60 | 小队共享波次周期；个人就绪时刻对齐到该时钟 |
| `minimum_respawn_seconds` | 20 | 死亡后最短个人等待，再与波次对齐 |

队包只允许**小队队长**通过 **Alt 轮盘 → 部署 Rally** 放置（不要求背包有 Rally 物品；手持信标放置不再注册为队包）。附近人数只统计同维度、存活且非旁观模式的玩家。个人复活冷却固定为 `wave_seconds`（部署落地后立刻重新计满该秒数，不随共享波次时钟缩短）。

主部署 GUI 中 Rally 显示的是**当前玩家**的个人就绪倒计时（选中后排队 `spawnAt`），客户端每秒更新标签且不重建整页布局。若玩家在排队期间改选 HAB/原部署点/前哨并成功部署，服务端会取消其 Rally 队列，冷却结束后不会再被拉回队包。

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

## `VehSpawn.json`

地图先注册载具类型，再为每种类型按 JSON 顺序提供攻守双方出生点：

```json
{
  "VehTypes": ["tank", "apc"],
  "spawn_points": {
    "tank": {
      "tank_1": {
        "attack": {"x": -94, "y": 65, "z": 36, "yaw": -90},
        "defend": {"x": 94, "y": 65, "z": 36, "yaw": 90}
      }
    },
    "apc": {
      "apc_1": {
        "attack": {"x": -94, "y": 65, "z": -36, "yaw": -90},
        "defend": {"x": 94, "y": 65, "z": -36, "yaw": 90}
      }
    }
  }
}
```

也接受每种类型直接使用点位数组的等价格式。编制声明的 `VehTypes` 必须全部存在于当前地图；某类型的 `entity` 数量也不得超过该类型点位数，否则整个编制不会进入本局随机池。实体按数组顺序与点位顺序一一对应。

## `SquadTypes.json`

```json
{
  "types": [
    {"id": "infantry", "display_name": "步兵队"},
    {"id": "support", "display_name": "支援队"},
    {"id": "vehicle", "display_name": "载具队"},
    {"id": "recon", "display_name": "侦查队"},
    {"id": "none", "display_name": "无"}
  ]
}
```

小队按钮使用 `display_name` 的第一个 Unicode 字符作为类别标记；`none` 不显示标记。

## 阵营/编制 JSON

每个 `EsFactions/<formation_id>.json` 定义一个编制。文件名（不含 `.json`）是编制 ID；
`faction.faction_id` 则是该编制所属的阵营字符串。这两个概念彼此独立。

### 完整示例

```json
{
  "VehTypes": ["transport"],
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
      "entity": ["minecraft:minecart", "minecraft:horse"],
      "display_name": "§6运输车",
      "per_max_count": 2,
      "respawn_minutes": 5,
      "troop_value": 5,
      "entity_tags": ["example_transport"]
    }
  },
  "classes": {
    "EXAMPLE_RIFLEMAN": {
      "name": "步枪兵",
      "description": "标准步兵",
      "role": "突击",
      "icon": "rifleman",
      "vehicle_crew": false,
      "maxPlayers": 8,
      "teammates_need": 2,
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
| `max_habs_per_radio` | Integer | 每个 Radio 作用范围内允许存在的己方 HAB 数量；默认 `2` |

编制图片的源文件分辨率不限。客户端会完整采样整张纹理，并将其拉伸或缩放到与内置编制图片完全相同的固定卡片图片区，不会裁剪；因此宽高比不同的图片可能发生形变。没有配置、路径无效或客户端资源包中不存在时，卡片仍保持相同尺寸，并在编制名下显示“还没配置图片喵”。服务器与客户端需要安装包含该资源的同一模组或资源包。

`faction_id` 使用区分大小写、保留空白的精确字符串比较。某方确定编制后，另一方的候选池会排除所有拥有相同 `faction_id` 的编制，确保攻守双方不属于同一阵营。缺失或空白的 `faction_id` 会导致整个编制拒绝载入。

### `classes.<class_id>`

| 字段 | 类型 | 默认/说明 |
| --- | --- | --- |
| `name`, `description`, `role` | String | GUI 信息 |
| `icon` | String | 可选；`assets/espetro/textures/gui/roles/` 下不带扩展名的职业图标短名 |
| `vehicle_crew` | Boolean | 可选；`true` 表示该职业可使用受限载具座位。缺失时为兼容旧编制，仅 `icon: "crewman"` 自动视为载具组员；显式 `false` 可关闭该兼容识别。也接受 `vehicleCrew` |
| `IconImage` | String | 可选；**文件系统完整路径**的职业图标（优先于 `icon`），例 `/home/shu/图片/Icon/rifleman.png` |
| `maxPlayers` | Integer | 必须大于 0。默认（`team_count: false`）为编制/队伍总上限；`team_count: true` 时为**每个班组小队**上限 |
| `team_count` | Boolean | 默认 `false`；也接受 `teamCount`。`true` 时人数在班组小队内统计，未入小队不可选该职业 |
| `max_per_squad` | Integer | 默认 0（不限）。仅 `team_count: false` 时生效：每个班组小队内该职业上限，必须 `≤ maxPlayers` |
| `teammates_need` | Integer | 默认 0（额外人数门槛关闭）；选择该职业时所在小队至少需要的人数，**包含选择者自己**。也接受 `teammatesNeed` |
| `strict_count` | Boolean | 变体计数模式，默认 `true`；也接受别名 `strictCount`。详见下方说明 |
| `troopValue` | Integer | 0 或缺失时回退 1；阵亡扣兵力 |
| `healthBonus` | Integer | 默认 0；额外生命点，`4` 等于两颗心 |
| `speedBonus` | Float | 默认 0；移动速度比例，`0.1` 等于提高 10% |
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

#### `strict_count` 行为说明

- **`strict_count: true`**（默认）：每个变体有**独立**人数上限，但选择任一变体时仍会先占用**父职业**一个名额（父职业 `maxPlayers` / 小队父职业上限）。变体 `maxPlayers` 之和必须**严格等于**职业的 `maxPlayers`；不满足时拒绝加载整个编制。非 `team_count` 时变体人数在队伍记分板中独立维护；界面显示 `[当前/上限]`。
- **`strict_count: false`**：变体仅代表不同配装，**没有**独立人数名额，只计父职业人数。不检查变体满员、不维护变体记分板，不要求变体 `maxPlayers` 总和等于职业 `maxPlayers`。`playerVariants` 仍记录以供装备、补给使用。界面变体行仅显示选择人数，不标红。

缺失 `strict_count` 时默认 `true`，确保旧数据包兼容。

#### `team_count` / `max_per_squad` 行为说明

- **共性（入队门槛）**：**所有职业**均须先加入班组小队后才能选择；未入队时服务端返回 `REQUIRES_SQUAD`，界面禁用全部职业按钮。
- **`teammates_need`**：在通用入队门槛之上增加小队人数要求。例如 `2` 表示至少两人小队、`6` 表示至少六人小队；不满足时职业按钮标红并显示可读原因，服务端仍会再次校验。该限制对部署界面和弹药箱轮盘选职都生效。
- **未入队 UI**：`team_count: true` 的按钮标红且**不显示** `[人数/上限]`；其它职业仍显示 `[当前选择人数/编制总上限]`，按钮禁用。
- **已入队 UI**：`team_count: true` 显示 `[小队 当前/小队上限]` 且可点（未满时）；其它职业显示 `[队伍当前/编制总上限]`（若有 `max_per_squad` 可附加 `·小队 a/b`）。
- **`team_count: true`**：`maxPlayers` 为每个班组小队内**父职业**上限；任意变体都计入该上限。只扫本小队，**忽略编制总限与队伍记分板**。离队取消该职业。`max_per_squad` 忽略。
- **`team_count: false`（默认）**：父职业 `maxPlayers` 为整支攻/守队伍总限。可选 `max_per_squad`。`max_per_squad ≤ maxPlayers` 即可。
- **变体**：无论 `strict_count` 如何，**选变体 = 选父职业的一个席位**；`strict_count: true` 时再叠加变体自身上限。
- **换职入口**：服务端只接受部署选点等待状态、本方原部署点 `6` 格范围和附近己方弹药箱职业轮盘发出的选职请求。普通 HAB、前哨与玩家上一次部署坐标不属于 J 键换职区；弹药箱请求会在点击职业时再次校验位置和归属。

#### `vehicle_crew` 与载具座位

- 该限制只在当前战场的 `DEPLOYING`、`BATTLE` 阶段生效，管理员同样不能绕过；主城与非活动战场不限制。
- SBW/DragonRise 坦克的 0、1、2 号座位、步战/APC 的 0、1 号座位及直升机的 0 号座位要求职业配置 `vehicle_crew: true`。其余座位和其他载具类型不限制。
- 首次上车和车内换座都由服务端校验。非载具组员上车时会自动顺位到第一个空闲的非受限座位；若不存在这种座位，则拒绝上车并提示载具没有空余位置。玩家在受限座位上更换为其他职业或离开小队时会立即下车。
- `superbwarfare`、`dragonrise_reforge` 都是 Espetro 的软依赖；未安装时不会加载座位兼容代码，也不会影响 Espetro 启动。


任何变体无效、（`strict_count: true` 时）人数不相等，都会在启动时输出 `[编制拒载]` 警告并拒绝载入整个编制，不会只跳过出错职业。

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

只有实际存在弹药缺口且补入至少一种物品时才扣除 `ammo_cost`；补给无冷却。
覆盖弹药箱的 Radio 必须能够完整支付费用；不足时整次取消，不发物品也不部分扣款。

### `VehTypes` 与 `vehicles.<vehicle_type>`

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `VehTypes` | 必填 | 本编制使用的载具类型数组；类型必须与 `vehicles` key 一致并存在于当前地图 |
| `entity` | 必填 | 实体注册 ID 数组，可来自其他模组；顺序对应地图点位顺序 |
| `display_name` | vehicle type | 显示名，可含 `§` 颜色码 |
| `per_max_count` | 1 | 同类型最大同时部署数量 |
| `respawn_minutes` | 5 | 单辆刷新冷却 |
| `troop_value` | 0 | 载具死亡/被摧毁时扣除的所属队伍兵力 |
| `entity_tags` | `[]` | 生成后附加到实体的 tag 数组 |
| `fightveh` | 兼容旧配置时为 true | 战斗载具；可在本方基地或载具补给站装卸弹药，不可运输建材，可用于更换职业 |
| `supplyveh` | false | 补给载具；可在基地或 Radio 附近装卸弹药和建材，可用于更换职业 |
| `capacity` | 按类型 | 弹药与建材共享总容量；战斗载具默认 500、补给载具默认 3000、其它载具默认 300 |
| `initial_deploy_delay_seconds.attack` | 0 | 此编制作为进攻方时，从布防阶段开始到系统自动生成首批载具的等待秒数 |
| `initial_deploy_delay_seconds.defend` | 0 | 此编制作为防守方时，从布防阶段开始到系统自动生成首批载具的等待秒数 |

编制文件不再保存部署坐标。部署位置完全由获胜地图的 `VehSpawn.json` 决定。
布防阶段开始时为每个类型设置首次部署倒计时；倒计时结束后，系统按 `entity` 数组与
`VehSpawn.json` 点位的顺序自动生成每个槽位的一辆首发载具。延迟未到前不会强加载出生区块。
手动部署已取消：载具被摧毁后才开始 `respawn_minutes` 刷新计时，冷却结束后自动在原出生点
刷新。所有玩家都可在职业选择菜单的“发起弹劾”右侧打开“载具信息”，查询己方全部载具的
在场数和冷却时间；信息界面按事件增量更新，不逐帧重建。

为兼容新增载具补给字段之前的 `EsFactions`，当 `fightveh` 与 `supplyveh` **同时缺失**时，
该类型按 `fightveh: true` 加载，F 轮盘可在本方基地或载具补给站范围内使用。新配置应显式
声明其中一种类型；若不需要装卸与更换职业交互，可将两项都显式设为 `false`。任何载具仍会
保留 F 轮盘「补给步兵」功能，消耗载具弹药按职业缺口补给。

## 随 JAR 导出的示例编制

| 文件 ID | 队伍 | `faction_id` | 说明 |
| --- | --- | --- | --- |
| `pla_heavy_brigade` | ATTACK | `PLA` | PLA 重型合成旅 |
| `pla_medium_brigade` | ATTACK | `PLA` | PLA 中型合成旅 |
| `russia_army` | ATTACK | `RUSSIA` | 俄罗斯陆上部队 |
| `russia_logistics` | ATTACK | `RUSSIA` | 俄罗斯后勤编制 |
| `middle_east_militia` | ATTACK | `MIDDLE_EAST_MILITIA` | 中东联合武装 |
| `us_airborne` | DEFEND | `USA` | 美国空降部队 |
| `us_cavalry` | DEFEND | `USA` | 美国骑兵旅 |
| `ukraine_irregular` | DEFEND | `UKRAINE_IRREGULAR` | 乌萨克非正规武装 |
| `example_attack` | ATTACK | `EXAMPLE_ATK` | 最小可运行进攻示例 |
| `example_defend` | DEFEND | `EXAMPLE_DEF` | 最小可运行防守示例 |

空的旧 `pla_rapid_force` 不会导出。示例只在目标文件缺失时写入，服主修改后的同名文件不会被后续启动覆盖。

## 旧配置与生成文件

- `data/espetro/config/*.json` 与 `data/espetro/factions/*.json` 已停止作为运行时配置；请迁移到每地图 `EsConfig` 与根目录 `EsFactions`。
- 当前源码没有注册 Espetro Forge TOML。已有 `config/espetro-common.toml` 是旧构建遗留，不控制现行业务逻辑。
- `assets/espetro/lang/*.json` 是翻译资源，不应作为服务器业务配置。

## 配置命令

```text
/espetro reload
/espetro prestart
/espetro stop
/espetro end <attack|defend|draw>
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
/bastion list
/bastion select <uuid>
/bastion deploy
/outpost list
/outpost deploy <index>
/outpost redeploy
```

管理员配置命令通常要求权限等级 2；`/vehicle list` 对所有玩家开放。

`/espetro stop` 可在地图投票、地图装载、部署、战斗或结算阶段强制终止当前游戏。
它会清除投票、职业、换职冷却、阵营、小队、兵力、比赛统计、兵站、队包、临时屏障、部署载具及冷却状态，
并把所有在线玩家送回主世界主城、打开 MUtil 主城界面。若地图切换正在执行，清理会在切换后自动接续，
避免留下半完成状态。

战场维度在服务器启动时由 Forge 创建首份副本。玩家全部回城后，`stop` 会先把战场从服务器 Tick
列表摘除，再由独立 IO 线程以“不保存本局破坏”的方式关闭区块、实体和 POI 存储句柄，并删除存档中的
整个战场维度目录。下一局再次选中该地图时，系统会从只读 `EsWorld/<地图文件夹>` 重新复制地形并挂载
新的 `ServerLevel`；任何时候都不会写入或删除 `EsWorld` 原件。

这里不会调用会强制保存全部区块的 `ServerLevel.close()`；该保存等待会阻塞服务端主线程。
战场使用可丢弃存储关闭路径，因此地图删除和复制期间主世界及其他维度仍可正常 Tick。
