# RAAS + AUI 进度（本轮）

## 已完成

### 差分合并
- 结论：`Projects/Espetro` 新于 `Java/Espetro`；未盲目覆盖。见 `MERGE_Java_Espetro_DIFF.md`。

### RAAS / 显示名（Espetro）
- `TeamDisplayNames`：RAAS → 阵营A/B；AAS → 进攻方/防守方
- `TeamSelectionScreen` / 聊天与阶段文案多处接入
- RAAS：前哨双方不可用；无攻方等待屏障；编制选择每局随机先后

### RAAS 运行时（ESPoints）
- `ObjectiveLayout` 迁入 `com.example.espoints.objective`
- `raasSymmetric`：全点 batch=1、中立开局、占领方加兵力、全占胜利
- AAS 路径保留
- 文档：`espetro-HCR/docs/RAAS_ESPOINTS.md`

### AUI 去 Mutil 命名
- `MutilScreen` → `EspetroMenuScreen`
- `EspetroMutilWidgets` → `EspetroAuiWidgets`
- `MutilHudOverlay` → `EspetroHudOverlay`
- ESPoints 同步：`EspetroMenuScreen` / `HcrAuiWidgets`
- 仍保留 **auratip** 硬依赖（`AuraTipRadialController` 仍用 AuraTip API；`AuiRadial` 并存）

## 本轮（占点权威归 ESPoints）
- Espetro 删除 `ObjectiveLayout`；`ESPointsMapSnapshot` 只保留原始 JSON + 种子
- ESPoints 自读 `EsConfig`；仅 AAS/RAAS；`RANDOM` 拒绝
- 耦合：`modifyTeamTroops` / `notifyObjectiveVictory` / `setResolvedObjectiveMode` / `teamDisplayName`
- `ESPointsAPI.refreshCachedMode` + `getLastSelectedLaneId`

## 待你确认 / 后续
1. **auratip** 是否改为 optional / 删除（径向是否已可只用 `AuiRadial`）？
2. Server/SquadB mods 目录若有多个 espetro-*.jar / espoints-*.jar，请只留一个最新版，避免双载。
