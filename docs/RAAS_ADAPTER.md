# RAAS 适配说明

本实现保持 Espetro 与 ESPoints 的现有职责边界：地图配置由 Espetro 在启动时读取和校验，战场激活时只生成一次本局路线，ESPoints 继续接收原有 `plannedPoints` JSON。

## 运行流程

1. `ESPointsMapSnapshot.load` 读取 `CapturePoints.json`，并把 AAS/RAAS 配置冻结为 `ObjectiveLayout`。
2. `BattlefieldContext.activate` 为本局生成随机种子，通过 `ActiveMapConfig.forRound` 解析本局模式和路线。
3. RAAS 从一条合法 lane 的每个 stage 中选择一个点，转换为 A、B、C……顺序 batch。
4. 转换后的 JSON 写入活动战场快照并交给 `GameConfigBridge`、公共 API 和 ESPoints；源配置不变。
5. 客户端只同步 `AAS`/`RAAS` 模式并显示在部署页。路线 ID、未选择的候选点和种子不通过游戏状态包公开。

## 兼容性

- 没有 `objectiveMode` 的旧地图等同于 AAS，不需要迁移。
- 新网络包增加了目标模式字段，协议版本由 `1.31` 更新为 `1.32`，客户端与服务端必须同时更新。
- `ActiveBattlefieldSnapshot` 保留旧构造函数，并新增目标模式、路线与种子只读字段。
- RAAS 的最终输出仍是 ESPoints 现有的 `totalBatches + plannedPoints` 结构。

## 校验原则

- 地图启动时拒绝未知点、重复点 ID、重复路线 ID、空阶段和跨阶段重复引用。
- 每条 RAAS 路线允许 3～26 个阶段。
- 相同种子始终产生相同模式和路线，方便复盘与测试。
- `pos1`/`pos2` 同时兼容对象坐标和旧版 `[x,y,z]` 数组。

配置示例和 BlockOps 档案转换命令见 `docs/CONFIGURATION.md`。
