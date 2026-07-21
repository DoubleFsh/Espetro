# Espetro

Espetro 是 Minecraft Forge 1.20.1 的战术小队与侵攻流程模组，提供阵营、编制、职业、小队、指挥官、指挥官技能、兵力、体力、兵站、队包、前哨和载具系统。

## 核心流程

```text
等待玩家
 -> 守方指挥官投票
 -> 攻方指挥官投票
 -> 守方编制选择
 -> 攻方编制选择
 -> 编制揭示
 -> 部署
 -> 战斗
```

## 环境与依赖

| 项目 | 版本 |
| --- | --- |
| Java | 17 |
| Minecraft | 1.20.1 |
| Forge | 47.4.20 |
| MUtil | 6.3.0 |
| AuraTip | 1.1.1-beta |
| OELib | 0.2.4 |
| Rhino (`rhino`) | 2001.2.2-build.17 |
| Architectury API (`architectury`) | 9.1.12 或更高 |
| ESPoints (`espoints`) | 1.0.6-final 或更高 |
| KubeJS (`kubejs`) | Forge 1.20.1 使用 `2001.6.5-build.26`，强制依赖 |

Espetro、MUtil、AuraTip、OELib、Rhino、Architectury API 和 KubeJS 是基础运行依赖。AuraTip 与 OELib 是外置模组，不会打入 Espetro JAR；MUtil 负责复杂界面，AuraTip 负责轮盘与教程提示。所有指挥官技能由 KubeJS 脚本注册和实现：`startup_scripts` 注册技能，`server_scripts` 执行技能效果。ESPoints 1.0.6-final 以 `espoints` 为模组 ID，并依赖 Espetro；Espetro 将 ESPoints 声明为软依赖。未安装时 Espetro 仍可启动，但部署界面的战术地图会使用占位显示，`155火炮支援` 无法打开选点地图。生产环境如需战术地图或火炮选点，客户端和服务器都必须安装 ESPoints。Esvoice 是可选的战术语音扩展，但 Esvoice 会依赖 Espetro。

KubeJS 是强制依赖。加载时会自动暴露 `Espetro`、`EspetroAPI`、`EspetroCommanderSkills`、底层管理器、ESPoints 桥接类和 MUtil 类给 KubeJS。默认三项指挥官技能均通过 KubeJS 执行，并按技能拆分为独立脚本；`无人机侦测` 使用 `level.getPlayers()` 和 `target.getPotionEffects().add(...)` 高亮范围内敌方玩家，`载具补给站` 使用 `level.getBlock(...).set(...)` 与 `level.createEntity(...).spawn()` 部署，`155火炮支援` 的默认火力效果由 `kubejs/server_scripts/00_espetro_artillery_155.js` 以 KubeJS `ServerEvents.tick` 队列、`level.createEntity(...)`、`entity.setPositionAndRotation(...)`、`entity.setMotionX/Y/Z(...)` 和 `entity.spawn()` 实现。

## 默认按键

| 按键 | 功能 |
| --- | --- |
| `K` | 请求服务端状态并打开队伍/小队相关界面 |
| `J` | 打开职业选择 |
| `Y` | 打开指挥官技能界面 |
| 长按左 `Alt` | 打开战术轮盘；点击部署/后勤进入二级轮盘，松开 `Alt` 确认 |
| 部署界面 `C` / `B` | 调整战术地图显示范围 |

## 新手教程

引导式对局教程可在数据包 `data/espetro/config/game.json` 的 `tutorial` 节点中开关（默认 `enabled: false`）。开启后进服与阶段切换会完全通过 AuraTip 原生提示层展示分步内容，覆盖队伍、投票、编制、部署、小队、兵站、队包、前哨、载具、指挥官技能、体力与兵力等；不会再向聊天栏重复发送教程正文。玩家可用 `/espetro tutorial` 重开或跳过。详见 [配置文档](docs/CONFIGURATION.md) 的 `tutorial` 小节。

## 配置

业务配置全部使用 JSON 数据包，可通过世界数据包覆盖并使用 `/reload` 或 `/espetro reload` 热重载：

- `data/espetro/config/game.json`
- `data/espetro/config/spawn_points.json`
- `data/espetro/config/bastion.json`
- `data/espetro/config/team_pack.json`
- `data/espetro/config/logistics.json`
- `data/espetro/config/outposts.json`
- `data/espetro/factions/*.json`

详见[配置文档](docs/CONFIGURATION.md)。

## 体力

体力由服务端权威管理。持续奔跑按秒消耗体力，跳跃按次消耗；停止消耗并经过配置的秒数后，体力按秒恢复。体力未满时，客户端会在准星下方显示一条无数字的细白线，线条长度表示剩余体力，回满后自动隐藏。

相关参数位于 `game.json` 的 `stamina` 节点：`player_stamina`、`sprint_cost_per_second`、`jump_cost`、`regen_delay_seconds` 和 `regen_per_second`。

## 构建

```bash
cd /home/shu/Java/Projects/Espetro
./gradlew build
```

产物位于 `build/libs/espetro-<version>.jar`。

### 开发客户端启动（双显卡 / GLFW 报错）

若出现 `Failed to find a valid GLFW profile` / `GLXBadFBConfig`，多为 NVIDIA 驱动与内核模块版本不一致。项目已在 `runClient` 中默认强制走 Intel Mesa：

```bash
./gradlew runClient
# 或
./scripts/run-client.sh
```

根因修复：更新内核/驱动包后**重启系统**，再确认 `nvidia-smi` 与 `glxinfo -B` 正常。

## 文档

- [配置与 JSON 规范](docs/CONFIGURATION.md)
- [指挥官技能脚本配置与开发](docs/COMMANDER_SKILL_SCRIPTS.md)
- [开发与 API 文档](docs/DEVELOPMENT.md)
- [KubeJS 联动开发文档](docs/KUBEJS_INTEGRATION.md)
- [完整类参考](docs/CLASS_REFERENCE.md)

## 与 HCR AAD 源码的边界

当前仓库还保留了一份 `src/main/java/com/example/hcrpoints` 旧源码副本，但构建已排除它。当前 ESPoints 源码位于 `/home/shu/IdeaProjects/espetro-HCR`，模组 ID 为 `espoints`，包名为 `com.example.espoints`；Espetro 仅通过可选依赖和反射桥接调用战术地图。新的战术地图功能应在 ESPoints 开发，Espetro 业务代码应放在 `org.espetro`。
