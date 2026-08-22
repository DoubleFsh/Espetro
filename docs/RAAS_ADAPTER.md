# RAAS / AAS 适配说明（Espetro 薄适配层）

## 职责划分

| 模组 | 职责 |
| --- | --- |
| **Espetro** | 装载地图目录；提供 `EsConfig` 绝对路径 + 本局种子；阵营/编制/兵力/阶段机；显示名 |
| **ESPoints** | 读 `CapturePoints.json` / `TacticalMap.json`；AAS/RAAS 解析与选路；占点运行时；战术地图/标点/火炮 |

仅两种模式：`AAS`、`RAAS`（`RANDOM` 已移除）。

## Espetro 侧流程

1. `ESPointsMapSnapshot.load` 读取战术地图与 **原始** `CapturePoints.json`（不选路）。
2. `ActiveMapConfig.forRound(seed)` 只盖章本局种子。
3. `BattlefieldContext.activate` 发布 `BattlefieldLifecycleEvent.Activated`，快照带 `esConfigPath` + `objectiveSeed`。
4. ESPoints 自读 EsConfig 后调用 `EspetroAPI.setResolvedObjectiveMode`，客户端经 `GamePhaseSync` 刷新。

## 显示名

- AAS：进攻方 / 防守方
- RAAS：阵营A / 阵营B
- 队伍 id 始终 `ATTACK` / `DEFEND`

## 兼容

- 没有 `objectiveMode` 的旧地图等同于 AAS。
- 配置里写 `RANDOM` 会在加载期失败，请改成显式 `AAS` 或 `RAAS`。

详见 `IdeaProjects/espetro-HCR/docs/RAAS_ESPOINTS.md`。
