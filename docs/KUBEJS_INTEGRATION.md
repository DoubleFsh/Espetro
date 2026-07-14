# Espetro KubeJS 联动开发文档

## 定位

Espetro 在 Forge 1.20.1 中将 Rhino、Architectury API 和 KubeJS 声明为强制依赖。所有指挥官技能由 KubeJS 实现：

- `kubejs/startup_scripts` 注册技能。
- `kubejs/server_scripts` 实现技能效果。
- 默认脚本按技能拆分为独立 `.js` 文件，注册文件和实现文件一一对应。
- Espetro 只负责技能注册入口、权限/阶段/冷却状态、界面同步和 ESPoints 选点桥接；默认技能的实际效果由 KubeJS 脚本本身完成。

## 加载方式

`kubejs.plugins.txt` 加载 `org.espetro.kubejs.EspetroKubeJSPlugin`。插件会注册绑定并放行类过滤：

| 绑定 | 用途 |
| --- | --- |
| `Espetro` | 脚本 facade，推荐优先使用。 |
| `EspetroAPI` | Java API 静态入口。 |
| `EspetroCommanderSkills` | 指挥官技能注册、回调、选点地图打开和冷却/状态查询。 |
| `EspetroCommanderSkillBuilder` | 技能创建 builder。 |
| `EspetroCommanderSkillDefinition` | 技能定义对象。 |
| `EspetroCommanderSkillEvent` | 技能回调事件对象。 |
| `Espetro*Manager` | 阵营、职业、小队、指挥官、兵站、前哨、载具等底层管理器。 |
| `MUtil` / `MUtilMod` | MUtil 主类。 |
| `ESPoints*` | 安装 ESPoints 时绑定的战术地图与标点桥接类。 |

`kubejs.classfilter.txt` 放行：

```text
+org.espetro
+com.example.espoints
+se.mickelus.mutil
```

KubeJS 脚本也可以使用：

```js
const SquadManager = Java.loadClass('org.espetro.team.SquadManager')
const TacticalMarkerType = Java.loadClass('com.example.espoints.tactical.TacticalMarkerType')
```

## 常用 API

| 方法 | 说明 |
| --- | --- |
| `Espetro.getPlayer(playerRef)` | 将玩家对象、名称或 UUID 转成在线 `ServerPlayer`。 |
| `Espetro.getPlayerTeam(playerRef)` | 返回 `ATTACK`、`DEFEND` 或 `null`。 |
| `Espetro.getPlayerSquadId(playerRef)` | 返回小队 ID，未加入时为 `-1`。 |
| `Espetro.getSquads(team)` | 返回指定阵营的小队快照。 |
| `Espetro.isCommander(playerRef)` / `isSquadLeader(playerRef)` | 查询指挥官/队长状态。 |
| `Espetro.broadcastToAll(message)` / `broadcastToTeam(team, message)` | 广播消息。 |
| `Espetro.activateCommanderSkill(player, skillId)` | 手动触发技能。 |
| `Espetro.openCommanderTargetMap(player, skillId)` | 打开 ESPoints 选点地图。 |
| `Espetro.getCommanderSkillCooldown(playerRef, skillId)` | 查询技能冷却。 |
| `Espetro.getCommanderSkillStatus(playerRef, skillId)` | 查询注册、权限、阶段、冷却和可用状态。 |
| `Espetro.submitCommanderSkillTarget(player, x, z)` | 手动提交战术地图坐标。 |
| `Espetro.submitArtillerySupportTarget(player, x, z)` | 旧名称兼容别名。 |

示例：

```js
PlayerEvents.loggedIn(event => {
  const team = Espetro.getPlayerTeam(event.player)
  const squadId = Espetro.getPlayerSquadId(event.player)
  event.player.tell(`Team: ${team}, squad: ${squadId}`)
})

ServerEvents.loaded(event => {
  Espetro.broadcastToAll('§eEspetro KubeJS bridge loaded')
})
```

## 指挥官技能

注册：

```js
EspetroCommanderSkills.create('repair_drop')
  .displayName('维修空投')
  .description('在指挥官当前位置生成维修物资')
  .stats('§8冷却: 90秒')
  .activate()
  .cooldownSeconds(90)
  .register()
```

实现：

```js
var RepairDropBuiltInRegistries = Java.loadClass('net.minecraft.core.registries.BuiltInRegistries')
var RepairDropResourceLocation = Java.loadClass('net.minecraft.resources.ResourceLocation')

EspetroCommanderSkills.on('repair_drop', event => {
  const type = RepairDropBuiltInRegistries.ENTITY_TYPE.get(RepairDropResourceLocation.tryParse('minecraft:chest_minecart'))
  const entity = event.level().createEntity(type)
  entity.setPositionAndRotation(event.x(), event.y(), event.z(), 0, 0)
  entity.spawn()
  return true
})
```

完整说明见 [KubeJS 指挥官技能配置与开发文档](COMMANDER_SKILL_SCRIPTS.md)。

## 155 火炮支援

当前流程：

1. 指挥官在 Espetro 技能界面使用 `artillery_155`。
2. Espetro 校验指挥官权限、游戏阶段和冷却。
3. 如果技能 trigger 为 `target_map` 或 `artillery_target`，Espetro 通过 ESPoints 打开战术地图。
4. ESPoints 客户端支持鼠标滚轮缩放、左键拖拽、右键选点。
5. ESPoints 服务端校验地图边界后调用 `EspetroAPI.submitCommanderSkillTarget(player, x, z)`；旧版仍可调用 `submitArtillerySupportTarget`。
6. Espetro 用服务端高度图补出 Y 坐标，创建 `KubeCommanderSkillEvent`。
7. KubeJS `server_scripts` 中注册的 `EspetroCommanderSkills.on('artillery_155', callback)` 执行实际火力效果。

默认火炮脚本位于 `kubejs/server_scripts/00_espetro_artillery_155.js`，使用纯 KubeJS `ServerEvents.tick` 调度队列，并通过 KubeJS 封装的原版对象方法生成实体：`level.createEntity(...)` 创建实体，`entity.mergeNbt(...)` 合并可选 NBT，`entity.setPositionAndRotation(...)` 设置发射点和朝向，`entity.setMotionX/Y/Z(...)` 设置初速度，最后 `entity.spawn()` 生成实体。KubeJS 脚本负责决定实体 ID、目标高度、覆盖半径、发射源范围、速度、批次数量和总体入射角。

## 兼容队列 API

Espetro 保留指挥官技能选点请求队列，供日志、额外特效或第三方系统观测；`ArtillerySupportRequest` 名称为兼容旧 API 保留。

| 方法 | 说明 |
| --- | --- |
| `Espetro.drainCommanderSkillTargetRequests()` | 返回当前请求并清空队列。 |
| `Espetro.getCommanderSkillTargetRequests()` | 返回当前请求快照。 |
| `Espetro.getLatestCommanderSkillTargetRequest()` | 返回最近一次请求或 `null`。 |
| `Espetro.drainArtillerySupportRequests()` | 返回当前请求并清空队列。 |
| `Espetro.getArtillerySupportRequests()` | 返回当前请求快照。 |
| `Espetro.getLatestArtillerySupportRequest()` | 返回最近一次请求或 `null`。 |

注意：

- 队列不持久化，重启或游戏重置会清空。
- `drainCommanderSkillTargetRequests()` / `drainArtillerySupportRequests()` 是消费型 API。
- 修改游戏状态必须在逻辑服务端主线程执行。
