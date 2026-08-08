# Espetro 类参考

本页按包列出当前源码中的顶层类。业务开发优先使用 `org.espetro`；`com.example.hcrpoints` 是仓库内已排除构建的旧 HCR 副本。当前 HCR AAD 使用模组 ID `espoints` 和包名 `com.example.espoints`，源码位于 `/home/shu/IdeaProjects/espetro-HCR`。

## `org.espetro`

| 类 | 职责 |
| --- | --- |
| `Espetro` | Forge 主入口、服务端生命周期、热重载、广播和队伍查询 |
| `EspetroClient` | 客户端安全初始化、按键、兵力/体力 HUD、跳跃和名牌事件 |
| `Config` | 空占位文件，无运行时类 |
| `CaptureTick` | 空占位文件 |
| `PointManager` | 空占位文件 |
| `KeyMappingEspetro` | 空占位文件 |

## API、配置与资源

| 类 | 职责 |
| --- | --- |
| `org.espetro.api.EspetroAPI` | 对外阵营、小队、队长、指挥官查询、指挥官技能选点提交/队列读取和冷却/状态查询 |
| `org.espetro.api.ActiveBattlefieldSnapshot` | 当前冻结地图配置、背景内容 SHA-256、PNG 尺寸与只读内容 |
| `org.espetro.api.TacticalMapStateSnapshot` | 带单调 revision/session 的不可变 Radio、HAB、Rally、原部署点、主基地与补给站快照 |
| `org.espetro.config.GameConfig` | `game.json` 加载与 getter，包括游戏流程、兵力和秒制体力参数 |
| `org.espetro.data.EspetroDataResources` | 数据包资源优先级、列表和 UTF-8 读取 |
| `org.espetro.kubejs.EspetroKubeJSPlugin` | KubeJS 硬依赖插件入口，注册脚本绑定、类过滤、指挥官技能 API、ESPoints 可选绑定和 MUtil 绑定 |
| `org.espetro.kubejs.EspetroKubeJSBindings` | KubeJS 全局 `Espetro` facade，封装常用服务端 API、指挥官技能入口、战术地图打开、管理器 getter、冷却/状态查询和选点兼容队列 |
| `org.espetro.kubejs.EspetroKubeJSDefaultScripts` | 首次启动时按技能写入独立的默认 KubeJS 指挥官技能注册/实现脚本，并停用旧版合并默认脚本 |
| `org.espetro.kubejs.commander.CommanderSkillBuilder` | KubeJS 指挥官技能创建 builder |
| `org.espetro.kubejs.commander.EspetroCommanderSkills` | KubeJS 指挥官技能注册、回调执行、战术地图打开和冷却/状态查询 |
| `org.espetro.kubejs.commander.KubeCommanderSkillDefinition` | KubeJS 注册的单个指挥官技能定义 |
| `org.espetro.kubejs.commander.KubeCommanderSkillEvent` | KubeJS 指挥官技能回调事件 |
| `org.espetro.protection.MainBaseProtection` | 本方原部署点玩家/载具无敌区的阵营、半径与部署点判定 |
| `org.espetro.protection.MainBaseProtectionEventHandler` | 事件驱动取消安全区内的 LivingAttack/Hurt/Damage |
| `org.espetro.runtime.ServerRuntimeMaintenance` | 分摊运行时清理和维护任务 |

## 阵营、职业与状态

| 类 | 职责 |
| --- | --- |
| `FactionDataLoader` | 加载所有编制、职业和载具 JSON |
| `FactionDataProvider` | 服务器启动时创建/加载 loader |
| `FactionConfig` | 旧式简化阵营模型 |
| `FactionConfigLoader` | `config/espetro/factions` 旧回退加载器 |
| `GamePhase` | 八个游戏阶段 |
| `GameStateManager` | 阶段状态机、玩家加入、部署和重置 |
| `TeamManager` | 原版记分板 ATTACK/DEFEND 队伍 |
| `ClassSelectManager` | 双方编制选择投票 |
| `ClassCountManager` | 队伍级职业/装备变体人数，以及玩家队伍、编制、职业和变体状态 |
| `ClassEquipment` | 清空、发放、自动穿戴职业装备变体和职业公共属性加成 |
| `ClassType` | 旧式硬编码职业枚举，JSON 系统的兼容备用 |
| `TroopCountManager` | 双方兵力、初始化、修改和死亡扣除 |
| `VoteManager` | 指挥官候选、投票、结果和当前指挥官 |
| `CommanderSkillType` | 指挥官技能兼容 ID，包括 `drone_detection`、`vehicle_supply_station` 和 `artillery_155` |
| `CommanderSkillManager` | KubeJS 技能权限、冷却、ESPoints 选点桥接、状态查询和选点兼容队列 |
| `SpawnPointConfig` | 双方部署点数据包配置 |

## 小队与部署设施

| 类 | 职责 |
| --- | --- |
| `SquadManager` | 创建、加入、退出、删除小队和快照 |
| `TeamPackManager` | 小队队包物品、部署、冷却、复活和销毁 |
| `OutpostManager` | 防守方部署阶段前哨和重新部署 |
| `BastionData` | 单个兵站、核心、补给点和 NBT |
| `BastionManager` | 兵站生命周期、伤害、复活选择、补给和冷却 |
| `BastionItems` | 兵站工具物品注册 |
| `BastionBuildingWandItem` | 指挥官兵站建造工具 |
| `BastionEventHandler` | 兵站/队包方块、伤害、死亡和补给事件 |
| `BastionCommand` | `/bastion` 命令 |

## 体力

| 类 | 职责 |
| --- | --- |
| `StaminaManager` | 服务端权威体力状态、每秒奔跑消耗、跳跃消耗、延迟恢复和同步 |

## 载具

| 类 | 职责 |
| --- | --- |
| `VehicleConfig` | 将编制 `vehicles` JSON 转换为运行时配置 |
| `VehicleManager` | 指挥官手动部署、首次/重生冷却、阵营数量、载具补给库存、实体追踪和清理 |
| `VehicleItems` | 载具部署物品注册 |
| `VehicleEventHandler` | 使用部署物品和载具实体事件 |
| `VehicleCommand` | `/vehicle` 命令 |

## 命令

| 类 | 职责 |
| --- | --- |
| `EspetroCommand` | `/espetro` 管理命令树 |
| `OutpostCommand` | `/outpost` 命令树 |

## 客户端与 GUI

| 类 | 职责 |
| --- | --- |
| `ClientPacketHandlers` | 客户端网络包分派 |
| `HcrTacticalMapBridge` | 通过反射桥接 HCR AAD / ESPoints 1.1.0+ 战术地图 |
| `TeammateNameTagRenderer` | 战术队友名牌规则 |
| `ClientGameState` | 客户端阶段/队伍/编制快照 |
| `ClientTacticalState` | 客户端指挥官和小队快照 |
| `MutilScreen` | Espetro MUtil 页面基类 |
| `EspetroMutilWidgets` | 面板、文本、按钮等组件 |
| `ScrollableList` | 可滚动 MUtil 容器 |
| `ScreenFadeIn` | 页面淡入工具 |
| `TeamSelectionScreen` / `TeamSelectionGui` | 队伍选择 |
| `FactionSelectionScreen` | 编制投票选择 |
| `FactionRevealScreen` | 双方编制揭示 |
| `ClassSelectScreen` / `ClassSelectionScreen` / `ClassSelectionGui` | 职业选择与兼容页面 |
| `SquadScreen` | 小队创建、加入、离开和成员列表 |
| `CommanderVoteScreen` | 指挥官投票 |
| `CommanderSkillScreen` | 指挥官技能 |
| `DeployPointSelectScreen` | 旧式部署点选择 |
| `UnifiedDeployScreen` | 兵站、队包、前哨和原部署点统一页面 |
| `VehicleDeployScreen` | 载具部署页面 |
| `TroopCountOverlay` | 双方兵力 HUD |
| `StaminaOverlay` | 体力未满时在 Action Bar 下方绘制无数字的细白线 |

## Espetro 网络包

| 类 | 方向/职责 |
| --- | --- |
| `NetworkManager` | 通道注册、发送辅助方法和状态广播 |
| `TeamSelectPacket` | 客户端请求选队 |
| `ClassSelectPacket` | 客户端请求选择职业及装备变体 |
| `RequestClassSelectionPacket` | 请求职业列表 |
| `OpenClassSelectionPacket` / `ClassSelectScreenPacket` | 打开职业页面 |
| `ClassCountSyncPacket` | 同步职业与装备变体实时人数 |
| `CastVotePacket` / `CommanderVotePacket` / `VoteDataPacket` | 指挥官投票 |
| `FactionRevealPacket` / `OpenFactionScreenPacket` | 编制选择与揭示页面 |
| `GamePhaseSyncPacket` / `GameStateResponsePacket` / `RequestGameStatePacket` | 阶段和玩家状态 |
| `WaitingStatusPacket` | 等待状态 |
| `TroopCountSyncPacket` | 兵力同步 |
| `StaminaJumpPacket` | 客户端通知跳跃动作，服务端执行权威扣除或拦截 |
| `StaminaSyncPacket` | 服务端向对应客户端同步体力启用状态、当前值和上限 |
| `SquadActionPacket` / `SquadSyncPacket` | 小队请求和快照 |
| `CommanderSkillPacket` / `CommanderSkillSyncPacket` | 技能请求和冷却 |
| `BastionSelectionPacket` / `DeployPointSelectPacket` / `UnifiedDeployScreenPacket` | 部署点选择 |
| `VehicleDeployScreenPacket` | 载具部署页面数据 |

## 仓库内旧 HCR 副本

以下 `com.example.hcrpoints` 类是旧版副本，与独立 `ds` 项目中的 `com.example.espoints` 实现职责重叠，不应继续修改或参与构建。

### 核心、配置与命令

`HCRPointsMod`、`HCRAPI`、`CapturePoint`、`CapturePointManager`、`CaptureState`、`DisplayState`、`ModConfig`、`TacticalMapConfig`、`MapPlayerDisplayConfig`、`HCRCommand`、`TeamfightPresetManager`、`CapturePointPresetManager`。

### 客户端、GUI 和 HUD

`AudioManager`、`ClientEventHandler`、`ClientProxy`、`PlayerTeamIndicator`、`CapturePointDetailsScreen`、`MDRenderScreen`、`ServerConfigScreen`、`TacticalMapConfigScreen`、`AreaInfoHUD`、`CapturePointHUD`、`CurrentCapturePointHUD`、`MapDisplayMode`、`MessagePopup`、`ReinforcementsHUD`、`TacticalMapHUD`。

### 网络与工具

`NetworkHandler`、`PlayLowReinforcementAudioMessage`、`ShowMessagePopupMessage`、`SyncCapturePointsMessage`、`SyncConfigMessage`、`SyncMapPlayerDisplayMessage`、`SyncOperationModeMessage`、`SyncPlayerPositionsMessage`、`ModLogger`、`TutorialManager`。

当前独立 `ds` 项目还包含更新的战术标点、地图数据包和部署同步类；以 `ds/docs/DEVELOPMENT.md` 为准。
