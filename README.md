# Espetro

Espetro 是 Minecraft Forge 1.20.1 的战术小队与侵攻流程模组，提供阵营、编制、职业、小队、指挥官、兵力、体力、兵站、队包、前哨和载具系统。

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
| HCR AAD (`espoints`) | 1.0.4-final 或更高 |

Espetro 和 MUtil 是基础运行依赖。HCR AAD 1.0.4-final 以 `espoints` 为模组 ID，并依赖 Espetro；未安装时 Espetro 仍可启动，但部署界面的战术地图会使用占位显示。生产环境如需战术地图，客户端和服务器都必须安装 HCR AAD。Esvoice 是可选的战术语音扩展，但 Esvoice 会依赖 Espetro。

## 默认按键

| 按键 | 功能 |
| --- | --- |
| `K` | 请求服务端状态并打开队伍/小队相关界面 |
| `J` | 打开职业选择 |
| `Y` | 打开指挥官技能界面 |
| 部署界面 `C` | 职业选择 |
| 部署界面 `B` | 小队界面 |

## 配置

业务配置全部使用 JSON 数据包，可通过世界数据包覆盖并使用 `/reload` 或 `/espetro reload` 热重载：

- `data/espetro/config/game.json`
- `data/espetro/config/spawn_points.json`
- `data/espetro/config/bastion.json`
- `data/espetro/config/team_pack.json`
- `data/espetro/config/outposts.json`
- `data/espetro/factions/*.json`

详见[配置文档](docs/CONFIGURATION.md)。

## 体力

体力由服务端权威管理。持续奔跑按秒消耗体力，跳跃按次消耗；停止消耗并经过配置的秒数后，体力按秒恢复。体力未满时，客户端会在准星下方显示一条无数字的细白线，线条长度表示剩余体力，回满后自动隐藏。

相关参数位于 `game.json` 的 `stamina` 节点：`player_stamina`、`sprint_cost_per_second`、`jump_cost`、`regen_delay_seconds` 和 `regen_per_second`。

## 构建

```bash
cd /home/shushu/IdeaProjects/Espetro
./gradlew build
```

产物位于 `build/libs/espetro-<version>.jar`。

## 文档

- [配置与 JSON 规范](docs/CONFIGURATION.md)
- [开发与 API 文档](docs/DEVELOPMENT.md)
- [完整类参考](docs/CLASS_REFERENCE.md)

## 与 HCR AAD 源码的边界

当前仓库还保留了一份 `src/main/java/com/example/hcrpoints` 旧源码副本，但构建已排除它。当前 HCR AAD 源码位于 `/home/shushu/IdeaProjects/ds`，模组 ID 为 `espoints`，包名为 `com.example.espoints`；Espetro 仅通过可选依赖和反射桥接调用战术地图。新的 HCR 功能应在 `ds` 开发，Espetro 业务代码应放在 `org.espetro`。
