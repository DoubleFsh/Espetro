# KubeJS 指挥官技能配置与开发文档

## 文件位置

指挥官技能完全由 KubeJS 脚本注册和实现：

```text
<gameDir>/kubejs/
├── startup_scripts/
│   ├── 00_espetro_drone_detection.js
│   ├── 00_espetro_vehicle_supply_station.js
│   └── 00_espetro_artillery_155.js
└── server_scripts/
    ├── 00_espetro_drone_detection.js
    ├── 00_espetro_vehicle_supply_station.js
    └── 00_espetro_artillery_155.js
```

Espetro 启动时会在缺少文件的情况下写入默认脚本，每个指挥官技能都有独立的 startup 注册脚本和 server 实现脚本。已有自定义脚本不会被覆盖；如果旧版合并脚本 `00_espetro_commander_skills.js` 仍带有 Espetro 默认文件头，Espetro 会将它改名为 `00_espetro_commander_skills.js.disabled` 保存，避免旧脚本与拆分后的脚本重复加载。服务器可以直接修改这些 KubeJS 脚本，自定义后应自行维护。

- `startup_scripts` 负责注册技能元数据：ID、显示文本、触发方式和冷却；默认每个技能一个文件。
- `server_scripts` 负责实现技能效果；默认每个技能一个文件。
- `game.json` 只保存游戏流程、兵力和体力参数，不保存任何指挥官技能配置。
- 技能脚本按 KubeJS 1.20.1 / Rhino 语法编写，遵守 KubeJS 自身的启动脚本和服务端脚本重载规则。

## 注册技能

在 `startup_scripts` 中使用 `EspetroCommanderSkills.create(id)` 创建技能：

```js
EspetroCommanderSkills.create('drone_detection')
  .displayName('无人机侦测')
  .description('短时间高亮指挥官附近敌方玩家')
  .stats('§8高亮半径: 100格 | 持续: 10秒 | 冷却: 60秒')
  .activate()
  .cooldownSeconds(60)
  .register()

EspetroCommanderSkills.create('artillery_155')
  .displayName('155火炮支援')
  .description('打开 ESPoints 战术地图选择炮击坐标，再交给 KubeJS 执行火力效果')
  .stats('§8ESPoints地图选点 | KubeJS两批实体炮击 | 冷却: 180秒')
  .targetMap()
  .cooldownSeconds(180)
  .register()
```

`CommanderSkillBuilder` 方法：

| 方法 | 说明 |
| --- | --- |
| `displayName(text)` / `name(text)` | 指挥官技能界面显示名。 |
| `description(text)` | 界面描述。 |
| `stats(text)` | 界面附加状态文本。 |
| `activate()` | 点击技能后立即执行对应 `server_scripts` 回调。 |
| `targetMap()` / `artilleryTarget()` | 点击技能后先打开 ESPoints 战术地图，右键选点后执行回调。 |
| `cooldownSeconds(seconds)` / `cooldown(seconds)` | 回调返回成功后的冷却秒数。 |
| `enabled(false)` / `disabled()` | 禁用该技能注册。 |
| `register()` | 写入 Espetro 运行时技能表。 |

## 实现技能

在 `server_scripts` 中使用 `EspetroCommanderSkills.on(id, callback)` 注册技能效果：

```js
var DroneMobEffects = Java.loadClass('net.minecraft.world.effect.MobEffects')

EspetroCommanderSkills.on('drone_detection', event => {
  const range = 100.0
  const commander = event.commander()
  const level = event.level()
  let count = 0

  const players = level.getPlayers()
  for (let i = 0; i < players.size(); i++) {
    const target = players.get(i)
    if (String(target.getUUID()) === String(commander.getUUID())) continue
    const targetTeam = Espetro.getPlayerTeam(target)
    if (targetTeam == null || String(targetTeam) === String(event.team())) continue
    if (target.getDistance(commander.getX(), commander.getY(), commander.getZ()) > range) continue

    target.getPotionEffects().add(DroneMobEffects.GLOWING, 10 * 20, 0, false, false)
    count++
  }

  event.tell('§a无人机侦测已执行，范围内高亮敌方玩家: ' + count)
  return true
})
```

回调返回 `false` 表示技能失败，不进入冷却。返回 `true`、`undefined` 或其他值表示成功。默认脚本尽量使用 KubeJS 已封装的原版方法，例如 `level.getPlayers()`、`level.getBlock(...)`、`level.createEntity(...)`、`entity.spawn()`、`entity.setMotionX/Y/Z(...)` 和 `livingEntity.getPotionEffects().add(...)`。`event.effects()` 和 `EspetroCommanderSkills.*` 中的 Java 辅助方法保留给兼容或复杂场景使用，默认技能不依赖它们。

`KubeCommanderSkillEvent` 常用方法：

| 方法 | 返回/行为 |
| --- | --- |
| `getDefinition()` / `definition()` | 当前技能定义。 |
| `getSkillId()` | 技能 ID。 |
| `getCommander()` | 触发技能的 `ServerPlayer`。 |
| `getCommanderId()` / `getCommanderName()` | 指挥官 UUID/名称。 |
| `getTeam()` | `ATTACK`、`DEFEND` 或 `null`。 |
| `getLevel()` / `getServer()` | 当前服务端世界/服务器。 |
| `getDimensionId()` | 维度 ID 字符串。 |
| `hasTarget()` | 是否包含战术地图选点坐标。 |
| `getX()` / `getY()` / `getZ()` | 事件坐标；选点技能中 X/Z 来自 ESPoints，Y 为 Espetro 服务端高度图结果。 |
| `getBlockPos()` / `getBlockX()` / `getBlockY()` / `getBlockZ()` | 方块坐标。 |
| `getRequest()` / `request()` | 选点技能的 `ArtillerySupportRequest`，普通技能为 `null`。 |
| `effects()` / `getEffects()` / `getApi()` | Espetro 提供的效果辅助 API。 |
| `tell(message)` | 给指挥官发送消息。 |
| `broadcastTeam(message)` / `broadcastAll(message)` | 广播消息。 |

## EspetroCommanderSkills API

| 方法 | 说明 |
| --- | --- |
| `create(id)` / `skill(id)` | 创建技能 builder。 |
| `register(definition)` | 注册已有 `KubeCommanderSkillDefinition`。 |
| `clearDefinitions()` | 清空技能定义；完全自定义技能列表时可在 startup 脚本开头调用。 |
| `on(id, callback)` | 注册服务端技能回调。 |
| `getDefinition(id)` / `getDefinitions()` / `getSkillIds()` | 查询技能定义。 |
| `execute(player, id)` / `activate(player, id)` | 从 KubeJS 手动触发技能，仍执行权限、阶段和冷却校验。 |
| `openTargetMap(player, id)` / `openTacticalMap(player, id)` | 直接打开 ESPoints 选点地图并记录回调技能 ID。 |
| `openArtilleryMap(player)` | 打开默认 `artillery_155` 选点地图。 |
| `droneDetection(event, range, durationSeconds)` | 兼容辅助：高亮指定范围内敌方玩家，返回数量；失败返回 `-1`。默认脚本改用 `level.getPlayers()` 和 `getPotionEffects().add(...)`。 |
| `deployVehicleSupplyStation(event, config)` | 兼容辅助：按脚本配置部署载具补给站。默认脚本改用 `level.getBlock(...).set(...)`、`level.createEntity(...)` 和 `entity.spawn()`。 |
| `fireEntity(event, ...)` | 可选 Java 辅助 API，生成实体并设置朝向目标的初速度。 |
| `fireBatched(event, config)` | 可选 Java 辅助 API，按两批火炮支援逻辑调度实体发射；默认 155 火炮脚本不依赖它。 |
| `randomPointInCircle(event, x, z, radius)` | 返回圆形范围内随机 `[x, z]`。 |

## 默认技能

### 无人机侦测

无人机侦测应高亮一定范围内的敌方玩家：

```js
var DroneMobEffects = Java.loadClass('net.minecraft.world.effect.MobEffects')

EspetroCommanderSkills.on('drone_detection', event => {
  const range = 100.0
  const durationSeconds = 10
  const commander = event.commander()
  const level = event.level()
  let count = 0

  const players = level.getPlayers()
  for (let i = 0; i < players.size(); i++) {
    const target = players.get(i)
    if (String(target.getUUID()) === String(commander.getUUID())) continue
    const targetTeam = Espetro.getPlayerTeam(target)
    if (targetTeam == null || String(targetTeam) === String(event.team())) continue
    if (target.getDistance(commander.getX(), commander.getY(), commander.getZ()) > range) continue

    target.getPotionEffects().add(DroneMobEffects.GLOWING, durationSeconds * 20, 0, false, false)
    count++
  }

  console.info('[Espetro] drone_detection highlighted enemies through KubeJS wrappers within ' + range + ' blocks: ' + count)
  event.tell('§a无人机侦测已执行，范围内高亮敌方玩家: ' + count)
  return true
})
```

### 载具补给站

```js
var SupplyBuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
var SupplyResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')
var SupplyComponent = Java.loadClass('net.minecraft.network.chat.Component')

EspetroCommanderSkills.on('vehicle_supply_station', event => {
  const x = event.blockX()
  const y = event.blockY()
  const z = event.blockZ()

  event.level().getBlock(x + 2, y, z).set(SupplyResourceLocation.tryParse('minecraft:barrel'))

  const type = SupplyBuiltInRegistries.ENTITY_TYPE.get(SupplyResourceLocation.tryParse('minecraft:armor_stand'))
  const entity = event.level().createEntity(type)
  entity.setPositionAndRotation(x + 0.5, y, z + 0.5, 0, 0)
  entity.setCustomName(SupplyComponent.literal('载具补给站'))
  entity.setCustomNameVisible(true)
  entity.addTag('espetro_vehicle_supply_station')
  entity.addTag('espetro_commander_skill')
  entity.spawn()
  return true
})
```

### 155火炮支援

`artillery_155` 使用 `targetMap()` 注册。玩家右键 ESPoints 战术地图后，Espetro 构造包含目标坐标的事件，再执行 KubeJS 回调。

默认生成的 `server_scripts/00_espetro_artillery_155.js` 使用纯 KubeJS 实现火力效果：脚本维护 `ServerEvents.tick` 调度队列，并通过 `event.level().createEntity(...)`、`entity.mergeNbt(...)`、`entity.setPositionAndRotation(...)`、`entity.setMotionX/Y/Z(...)` 和 `entity.spawn()` 生成斜向下发射的实体。Espetro 不负责该技能的实体生成，Java 侧的 `fireBatched` 只是保留给脚本作者选择使用的辅助 API。

默认文件中的调度结构如下。技能回调只负责把 JavaScript 发射任务压入队列；实际发射发生在后续服务端 tick 中。

```js
var EspetroArtilleryTasks = []
var EspetroArtilleryTick = 0

ServerEvents.tick(event => {
  EspetroArtilleryTick++
  for (let i = EspetroArtilleryTasks.length - 1; i >= 0; i--) {
    const task = EspetroArtilleryTasks[i]
    if (task.tick <= EspetroArtilleryTick) {
      task.run()
      EspetroArtilleryTasks.splice(i, 1)
    }
  }
})
```

```js
EspetroCommanderSkills.on('artillery_155', event => {
  return espetroFirePureKubeArtillery(event, {
    entity: 'minecraft:tnt',
    nbt: '{Fuse:260s}',
    targetY: event.y(),
    impactRadius: 90,
    launchHeight: 600,
    clampSpawnYToBuildHeight: true,
    sourceDistance: 160,
    sourceRange: 70,
    velocity: 3.5,
    firstBatchShots: 2,
    firstBatchIntervalTicks: 20 * 20,
    secondBatchDelayTicks: 2 * 20 * 20,
    secondBatchWaves: 6,
    secondBatchIntervalTicks: 4 * 20,
    secondBatchEntitiesPerWave: 4,
    approachYawDegrees: Math.random() * 360
  })
})
```

`espetroFirePureKubeArtillery(event, config)` 是默认 KubeJS 文件内的普通 JavaScript 函数，不是 Java 绑定。服务器可以直接复制、改名或拆分它。该函数会解析并合并可选实体 SNBT，然后用 KubeJS 封装方法设置实体位置、朝向和三轴初速度；`nbt` 必须是 `{...}` 形式的 SNBT 对象。参数：

| 字段 | 默认 | 说明 |
| --- | ---: | --- |
| `entity` / `entityId` | `minecraft:tnt` | 发射实体注册 ID。 |
| `nbt` | 空 | 实体 SNBT。 |
| `targetY` | 选点高度图 Y | 炮击目标 Y；可在脚本中固定。 |
| `impactRadius` / `radius` | 80 | 轰炸点水平圆形覆盖半径。 |
| `launchHeight` | 600 | 发射点相对 `targetY` 的高度。 |
| `clampSpawnYToBuildHeight` | `true` | 是否把发射 Y 限制在当前维度构建高度内，避免原版维度中 600 格高空实体无法生成。 |
| `sourceDistance` | 260 | 发射源中心相对目标点沿反方向偏移距离。 |
| `sourceRange` / `sourceSpread` | 90 | 发射源随机圆形范围。 |
| `velocity` | 3.2 | 初速度。 |
| `inaccuracy` | 0 | 目标点额外随机偏差。 |
| `approachYawDegrees` | 随机 | 本次轰炸总体发射方向角；所有批次共用该方向。 |
| `firstBatchShots` | 2 | 第一批发射次数；每次 1 个实体。 |
| `firstBatchIntervalTicks` | 400 | 第一批每次发射间隔，默认 20 秒。 |
| `secondBatchDelayTicks` | `firstBatchShots * firstBatchIntervalTicks` | 第二批开始延迟。 |
| `secondBatchWaves` / `secondBatchTimes` | 6 | 第二批发射波次数。 |
| `secondBatchIntervalTicks` | 80 | 第二批波次间隔。 |
| `secondBatchEntitiesPerWave` / `secondBatchEntitiesPerShot` | 4 | 第二批每波实体数量。 |

## 自定义技能示例

`startup_scripts/espetro_custom_skills.js`：

```js
EspetroCommanderSkills.create('smoke_barrage')
  .displayName('烟幕支援')
  .description('在指挥官当前位置释放烟幕')
  .stats('§8半径: 10格 | 冷却: 45秒')
  .activate()
  .cooldownSeconds(45)
  .register()
```

`server_scripts/espetro_custom_skills.js`：

```js
EspetroCommanderSkills.on('smoke_barrage', event => {
  const dim = event.getDimensionId()
  const x = event.getX()
  const y = event.getY()
  const z = event.getZ()

  for (let i = 0; i < 8; i++) {
    const p = EspetroCommanderSkills.randomPointInCircle(event, x, z, 10)
    event.effects().command(
      `execute in ${dim} run particle minecraft:campfire_cosy_smoke ${p[0]} ${y + 1} ${p[1]} 1 1 1 0.02 80 force`
    )
  }
  return true
})
```

## 类暴露

Espetro 的 KubeJS 插件放行并绑定以下类：

- `org.espetro.*`：Espetro 公开类和管理器。
- `com.example.espoints.*`：安装 ESPoints 时可通过 `Java.loadClass` 访问。
- `se.mickelus.mutil.*`：MUtil 类。
- 全局绑定包含 `Espetro`、`EspetroAPI`、`EspetroCommanderSkills`、`EspetroCommanderSkillBuilder`、`EspetroCommanderSkillDefinition`、`EspetroCommanderSkillEvent`、`MUtil` 和各 `Espetro*Manager`。

需要直接调用底层 API 时优先使用 `Espetro` facade；只有 facade 不覆盖的功能再使用 `Java.loadClass()` 或 manager 绑定。
