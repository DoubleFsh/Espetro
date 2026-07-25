# Espetro

Espetro 是 Minecraft Forge 1.20.1 的战术小队与侵攻流程模组，提供阵营、编制、职业、小队、指挥官、指挥官技能、兵力、体力、兵站、队包、前哨和载具系统。

## 核心流程

```text
主世界主城
 -> 管理员 /espetro prestart
 -> 全服地图投票
 -> 载入获胜战场维度
 -> 选择攻/守方
 -> 守方指挥官投票
 -> 攻方指挥官投票
 -> 守方编制选择
 -> 攻方编制选择
 -> 编制揭示
 -> 部署
 -> 战斗
 -> 结算并返回主城
```

管理员可在任意阶段使用 `/espetro stop` 强制结束游戏。所有在线玩家会立即回到主世界主城，
本局投票、队伍、职业、装备、统计和临时战场对象会被清理，随后重新打开 MUtil 主城界面。
战场维度会从服务器 Tick 列表摘除，其区块、实体和 POI 存储句柄会在独立 IO 线程中以“不保存本局破坏”
的方式关闭，随后删除存档中的整个战场维度目录。下一局再次选中该地图时，会重新从只读 `EsWorld`
模板复制并挂载新的战场维度；`EsWorld` 中的原始地图不会被修改。

## 环境与依赖

| 项目 | 版本 |
| --- | --- |
| Java | 17 |
| Minecraft | 1.20.1 |
| Forge | 47.4.20 |
| MUtil | 6.3.0 |
| OELib | 0.2.4 |
| Rhino (`rhino`) | 2001.2.2-build.17 |
| Architectury API (`architectury`) | 9.1.12 或更高 |
| ESPoints (`espoints`) | 1.0.6-final（本工作区同步版）或更高 |
| KubeJS (`kubejs`) | Forge 1.20.1 使用 `2001.6.5-build.26`，强制依赖 |

Espetro、MUtil、OELib、Rhino、Architectury API 和 KubeJS 是基础运行依赖。MUtil 负责全部 Espetro 界面、战术轮盘与 HUD 元素；AuraTip 不再是 Espetro 的依赖。所有指挥官技能由 KubeJS 脚本注册和实现：`startup_scripts` 注册技能，`server_scripts` 执行技能效果。ESPoints 以 `espoints` 为模组 ID，并依赖 Espetro；Espetro 将 ESPoints 声明为软依赖。未安装时 Espetro 仍可启动，但部署界面的战术地图会使用占位显示，`155火炮支援` 无法打开选点地图。生产环境如需战术地图、据点或火炮选点，客户端和服务器都必须安装当前工作区同步后的 ESPoints；该版本已经移除 AuraTip、OELib 依赖，并从 Espetro 的当前地图快照读取配置。为防止 ESPoints 影响 Espetro 的独立开发启动，本地兄弟项目默认不注入，可用 `-PespetroIncludeLocalEspoints=true` 显式启用。Esvoice 是可选的战术语音扩展，但 Esvoice 会依赖 Espetro。

KubeJS 是强制依赖。加载时会自动暴露 `Espetro`、`EspetroAPI`、`EspetroCommanderSkills`、底层管理器、ESPoints 桥接类和 MUtil 类给 KubeJS。默认三项指挥官技能均通过 KubeJS 执行，并按技能拆分为独立脚本；`无人机侦测` 使用 `level.getPlayers()` 和 `target.getPotionEffects().add(...)` 高亮范围内敌方玩家，`载具补给站` 使用 `level.getBlock(...).set(...)` 与 `level.createEntity(...).spawn()` 部署，`155火炮支援` 的默认火力效果由 `kubejs/server_scripts/00_espetro_artillery_155.js` 以 KubeJS `ServerEvents.tick` 队列、`level.createEntity(...)`、`entity.setPositionAndRotation(...)`、`entity.setMotionX/Y/Z(...)` 和 `entity.spawn()` 实现。

## 默认按键

| 按键 | 功能 |
| --- | --- |
| `K` | 请求服务端状态并打开队伍/小队相关界面 |
| `J` | 打开统一部署/职业/小队主界面 |
| 长按左 `Alt` | 打开 MUtil 战术轮盘，松开 `Alt` 确认；指挥官技能会直接加入轮盘 |
| 部署界面 `C` / `B` | 调整战术地图显示范围 |

## 新手教程

主城 GUI 的「进入新手教程」或 `/espetro tutorial reopen` 启动服务端权威进度的引导：

- 自绘 **TutorialHudOverlay**（底部说明卡 + 左下「退出教程」），不再依赖关闭 AuraTip 卡片才能下一步。
- 对应阶段打开 **只读预览 GUI**（`tutorialPreviewMode`），点击不会改真实战局。
- **Enter** = 下一步；聊天栏打开时不拦截。
- 左下角或 `/espetro tutorial skip` = 完全退出。

## 配置

所有地图、编制和地图级配置位于游戏/服务端根目录，且只在进程启动时加载：

```text
EsDimensions.json
EsFactions/*.json
EsWorld/<地图文件夹>/
├── level.dat
├── region/
└── EsConfig/
    ├── game.json
    ├── spawn_points.json
    ├── outposts.json
    ├── bastion.json
    ├── logistics.json
    ├── team_pack.json
    ├── SquadTypes.json
    ├── VehSpawn.json
    ├── TacticalMap.json
    ├── CapturePoints.json
    └── map.png              # 可选，由 TacticalMap.json 引用
```

首次启动会从 JAR 安全导出地图与配置示例（含 Y64 超平坦 `test_flat`），**不会**向 `EsFactions/` 写入任何预设或示例编制——编制 JSON 需自行放入该目录。已有同名文件永不覆盖。`TacticalMap.json` 与 `CapturePoints.json` 是每张地图的必需文件；缺失或非法时该地图会被拒绝。修改外部配置后必须重启客户端或服务端；`/reload` 与 `/espetro reload` 不会重读这些启动冻结配置。

详见[配置文档](docs/CONFIGURATION.md)和[使用手册](docs/USER_GUIDE.md)。

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
- [玩家与管理员使用手册](docs/USER_GUIDE.md)
- [指挥官技能脚本配置与开发](docs/COMMANDER_SKILL_SCRIPTS.md)
- [开发与 API 文档](docs/DEVELOPMENT.md)
- [KubeJS 联动开发文档](docs/KUBEJS_INTEGRATION.md)
- [完整类参考](docs/CLASS_REFERENCE.md)

## 与 HCR AAD 源码的边界

当前仓库还保留了一份 `src/main/java/com/example/hcrpoints` 旧源码副本，但构建已排除它。当前 ESPoints 源码位于 `/home/shu/IdeaProjects/espetro-HCR`，模组 ID 为 `espoints`，包名为 `com.example.espoints`；Espetro 仅通过可选依赖和反射桥接调用战术地图。新的战术地图功能应在 ESPoints 开发，Espetro 业务代码应放在 `org.espetro`。
