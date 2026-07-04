# Espetro 开发文档

## 架构

```text
数据包 JSON
  -> FactionDataLoader / GameConfig / SpawnPointConfig
  -> GameStateManager 阶段状态机
  -> VoteManager / ClassSelectManager
  -> ClassCountManager / SquadManager
  -> StaminaManager
  -> BastionManager / TeamPackManager / VehicleManager
  -> NetworkManager
  -> ClientGameState 与各 GUI/HUD
```

服务端是阵营、职业、小队、指挥官和部署状态的唯一权威。客户端只能发送选择请求，最终结果必须由服务端校验后同步。

## 入口与生命周期

`Espetro` 是 Forge 主入口。客户端通过 `DistExecutor` 延迟调用 `EspetroClient.init()`，避免专用服务器解析客户端类。

服务器启动顺序：

1. `FactionDataProvider` 在 `ServerAboutToStartEvent` 加载编制。
2. `Espetro.onServerStarting` 保存服务器实例并调用 `reloadAllConfigs()`。
3. 初始化记分板职业计数、兵站、队包和游戏状态。
4. 每个服务器 tick 更新阶段、运行时维护和指挥官技能；玩家 tick 更新服务端权威体力状态。
5. 停服时清空玩家职业装备并清理世界实体/内存状态。

## 对外 API

### `EspetroAPI`

```java
String team = EspetroAPI.getPlayerTeam(serverPlayer); // ATTACK / DEFEND / null
int squadId = EspetroAPI.getPlayerSquadId(serverPlayer.getUUID());
boolean leader = EspetroAPI.isSquadLeader(serverPlayer.getUUID());
boolean commander = EspetroAPI.isCommander(serverPlayer.getUUID());
```

调用约束：

- 这些方法读取服务端状态，只应在逻辑服务端使用。
- 玩家未在线或尚未选队时可能返回 `null`/`-1`/`false`。
- 不要缓存权限超过一个 tick；指挥官和队长可能在运行时变更。

### `Espetro`

```java
Espetro.broadcastToAll("§e行动即将开始");
Espetro.broadcastToTeam("ATTACK", "§c进攻 A 点");
String team = Espetro.getPlayerTeam(player);
Espetro.reloadAllConfigs();
```

`reloadAllConfigs()` 必须在服务器主线程调用。

## 编制与职业

### `FactionDataLoader`

```java
FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
loader.ensureLoaded(server.getResourceManager());

FactionDataLoader.FactionData faction = loader.getFaction("pla_medium_brigade");
FactionDataLoader.ClassKitData kit = loader.getClassKit("PLA_MEDIUM_ASSAULT");
Map<String, FactionDataLoader.VehicleData> vehicles =
    loader.getFactionVehicles("pla_medium_brigade");
```

返回的数组缓存用于热路径；调用方不应修改 `getFactionArray()`、`getClassesForFaction()` 和 `getClassIdsForFaction()` 的内容。

### `ClassEquipment`

```java
ClassEquipment.clearEquipment(player);
ClassEquipment.equipPlayer(player, "PLA_MEDIUM_ASSAULT");
```

装备字符串会拼接到 `/give` 或 `/item replace`。配置来源不可信时必须先校验物品 ID 与 SNBT，避免执行任意命令参数。

### `ClassCountManager`

```java
ClassCountManager classes = ClassCountManager.getInstance();
if (!classes.isFull("ATTACK", classId)) {
    classes.selectClass(player, classId);
}
```

该管理器同时保存玩家队伍、编制、职业和计数。换队/退出时必须调用对应清理流程并广播新的计数。

## 游戏状态

### `GamePhase`

阶段顺序：`WAITING_FOR_PLAYERS`、`DEFEND_COMMANDER_VOTE`、`ATTACK_COMMANDER_VOTE`、`DEFEND_FACTION_SELECT`、`ATTACK_FACTION_SELECT`、`FACTION_REVEAL`、`DEPLOYING`、`BATTLE`。

```java
GameStateManager state = GameStateManager.getInstance();
if (state.getCurrentPhase() == GamePhase.DEPLOYING) {
    int remaining = state.getDeployTimeRemainingSeconds();
}
```

不要在客户端直接修改 `GameStateManager`；客户端使用 `ClientGameState` 的同步快照。

### 投票和编制选择

```java
VoteManager votes = VoteManager.getInstance();
votes.castVote(voter, targetPlayerId);

ClassSelectManager selection = ClassSelectManager.getInstance();
selection.selectClass(voter, factionId);
```

名称虽然是 `ClassSelectManager`，这里管理的是双方最终“编制/阵营”投票；具体职业由 `ClassCountManager` 处理。

## 小队与队包

### `SquadManager`

```java
SquadManager squads = SquadManager.getInstance();
SquadManager.ActionResult result = squads.createSquad(player, "Alpha");
if (result.success) {
    NetworkManager.syncSquadsToTeam(result.team);
}
```

```java
int squadId = squads.getPlayerSquadId(player.getUUID());
boolean leader = squads.isSquadLeader(player.getUUID());
List<SquadManager.SquadSnapshot> view = squads.getSquadSnapshots("ATTACK");
```

快照用于网络/UI/外部模组读取，不应暴露内部可变小队对象。

### `TeamPackManager`

```java
TeamPackManager packs = TeamPackManager.getInstance();
String error = packs.placeTeamPack(player, level, targetPos);
if (error != null) {
    player.sendSystemMessage(Component.literal(error));
}
```

队长转移后调用 `handleSquadLeaderTransition`，小队改变后调用 `reconcileTeam`，以同步物品、冷却和现有队包。

## 兵站、前哨和载具

### `BastionManager`

```java
BastionData bastion = BastionManager.getInstance()
    .createBastion(level, pos, "ATTACK", "兵站 A");

BastionManager.getInstance().damageBastionCore(bastion, 1.0F, attacker);
```

`BastionData` 可保存为 NBT，但引用 `ServerLevel`，不得直接跨网络发送。客户端部署列表使用 `UnifiedDeployScreenPacket.BastionItem` DTO。

### `OutpostManager`

```java
String error = OutpostManager.getInstance().tryStartRedeploy(player);
String deployError = OutpostManager.getInstance().tryDeploy(player, index);
```

### `VehicleConfig` 与 `VehicleManager`

```java
VehicleConfig.loadConfig(server);
VehicleConfig.VehicleTypeConfig config =
    VehicleConfig.getVehicleConfig(factionId, vehicleType);

String result = VehicleManager.getInstance().deployVehicle(commander, vehicleType);
```

部署前必须校验指挥官、编制、数量上限、冷却、实体注册 ID 和安全落点。

## 指挥官技能

```java
CommanderSkillManager skills = CommanderSkillManager.getInstance();
boolean activated = skills.activateSkill(commander, CommanderSkillType.DRONE_DETECTION);
int cooldown = skills.getRemainingCooldownSeconds(
    commander.getUUID(),
    CommanderSkillType.DRONE_DETECTION
);
```

新增技能时需同时扩展枚举、服务端校验/效果、同步包和客户端界面。

## 体力系统

`StaminaManager` 在逻辑服务端维护玩家的会话级体力。重生或重新进入时恢复到 `player_stamina`，退出或停服时清除内存状态。`player_stamina: -1` 会禁用整个系统。

- 持续奔跑开始时扣除一次 `sprint_cost_per_second`，之后每 20 tick 再扣除一次。
- 跳跃每次扣除 `jump_cost`。
- 停止消耗 `regen_delay_seconds × 20` tick 后恢复一次，之后每 20 tick 恢复 `regen_per_second`。
- 客户端的 `StaminaJumpPacket` 只报告跳跃动作；是否扣除或阻止跳跃仍由服务端决定。
- `StaminaSyncPacket` 只发给对应玩家，用于更新 `StaminaOverlay` 和客户端耗尽状态。
- `StaminaOverlay` 仅在体力未满时显示，在准星下方绘制 `40×2` 像素的无数字白线。

修改结算周期或包结构时，需要同时检查客户端抑制奔跑/跳跃、重生重置、配置热重载和专用服务器类加载。

## 网络开发

`NetworkManager` 集中注册所有 Espetro 网络包。典型流程：

```java
public static void encode(MyPacket packet, FriendlyByteBuf buf) { }
public static MyPacket decode(FriendlyByteBuf buf) { return new MyPacket(); }
public static void handle(MyPacket packet, Supplier<NetworkEvent.Context> context) { }
```

要求：

1. 客户端请求必须在服务端重新验证阶段、阵营、权限和索引范围。
2. 世界状态变更通过 `enqueueWork` 在主线程执行。
3. GUI 打开包只能在客户端处理；使用现有 `ClientPacketHandlers` 隔离客户端类。
4. 修改现有编码时同步升级网络协议。
5. 集合、名字和 ID 必须限制数量与长度。

## 客户端开发

`EspetroClient` 注册三个按键、兵力/体力 overlay、跳跃监听和队友名牌。客户端状态分为：

- `ClientGameState`：阶段、队伍、编制及界面权限。
- `ClientTacticalState`：指挥官、小队成员和名牌颜色。
- `ClientPacketHandlers`：网络包到 GUI/状态类的分派。

GUI 基类是 `MutilScreen`，常用组件在 `EspetroMutilWidgets`。`ScrollableList` 用于可滚动列表，`ScreenFadeIn` 处理淡入动画。

## HCR AAD 源码边界

仓库中的 `com.example.hcrpoints` 是旧副本且已从构建排除；独立项目 `/home/shushu/IdeaProjects/ds` 才是当前 HCR AAD 源码。HCR AAD 1.0.4-final 的模组 ID 是 `espoints`，包名是 `com.example.espoints`，并强制依赖 Espetro。Espetro 将其声明为可选依赖，通过 `HcrTacticalMapBridge` 反射调用战术地图，以避免循环编译依赖。

本地存在 `../ds/build/libs/espoints-${hcr_aad_version}.jar` 时，Gradle 会自动把它加入 Espetro 开发运行环境。开发规则：

- Espetro 阵营/职业/小队功能修改 `org.espetro`。
- 据点/战术地图/标点功能修改 `ds`。
- 如暂时无法移除旧副本，发布前至少用类清单比较两个目录，避免同名类实现不同。

## 构建与验证

```bash
./gradlew compileJava
./gradlew build
./gradlew runClient
./gradlew runServer
```

提交前至少验证：

- 数据包覆盖优先级与 `/reload` 热重载。
- 完整八阶段状态机和中途加入。
- 指挥官、编制、职业、小队人数和权限同步。
- 死亡扣兵力、兵站/队包/前哨复活。
- 载具上限、冷却、实体死亡回收。
- 体力每秒消耗/恢复、恢复延迟、耗尽限制、HUD 显隐和重生重置。
- 玩家退出与停服不会保留装备或运行时缓存。
- 专用服务器不加载 `net.minecraft.client` 类。
