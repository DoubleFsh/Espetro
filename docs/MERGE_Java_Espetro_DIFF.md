# Java/Espetro → Projects/Espetro 差分报告

日期：实现 RAAS/AUI 计划时生成。

## 结论

| 树 | 路径 | 状态 |
|----|------|------|
| 主干 | `/home/shu/Java/Projects/Espetro` | 含 RAAS PR、AUI、audio、工事等，**更新** |
| 对照 | `/home/shu/Java/Espetro` | 分支 `es-gs`，**无** `ObjectiveLayout` / AUI，整体偏旧 |

- `org/espetro` 下约 **96** 个文件内容不同；Projects 独有大量目录（`aui`、`audio`、工事等）。
- **未做盲目覆盖合并**：会冲掉 Projects 已有功能。
- **权威据点实现**：`/home/shu/IdeaProjects/espetro-HCR`（非 Espetro 内嵌 `com.example.hcrpoints` 拷贝）。

## 合入策略

1. 默认以 Projects 为准。
2. 仅当对照树有明确 bugfix 且 Projects 仍缺时，人工挑补丁合入。
3. 本轮未发现对照树独有、且 Projects 缺失的 RAAS/AUI 关键修复 → **零自动合入**。

若你指定对照树某次 commit 必须并入，再按文件 cherry-pick。
