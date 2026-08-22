---
name: apricity-ui
description: >
  Write and debug ApricityUI (AUI) HTML/CSS/JS screens, overlays, HUD, radials,
  toasts, and containers for Espetro and EsPoints. Use whenever creating or
  editing AUI pages, replacing AuraTip/MUtil GUI, opening ApricityScreen or
  createDocument, using Ore theme, or the user mentions AUI / ApricityUI /
  晴雪UI. Use when the user runs /apricity-ui.
---

# ApricityUI 开发参考

写任何 AUI 页面前，先读本 skill 的官方原文：

`references/ai-skill.md`

那是 https://doc.sighs.cc/ApricityUI/skill 的完整副本。更细的专题（Screen / Overlay / Web API / 扩展元素 / Ore）以 https://doc.sighs.cc/ApricityUI/ 和 GitHub `Tower-of-Sighs/AUI` 的 `docs/` 为准。

本工程是 **环境 A（Java 模组依赖 AUI）**，不是纯 KubeJS 整合包。热路径用 Java 改 DOM，页面内 JS 只做展示辅助。

## 本仓库约定

- 钉住 `com.sighs:ApricityUI-forge-1.20.1:1.2.2-hotfix1`，`modId=apricityui`。
- 模组内页面放 `src/main/resources/assets/<modid>/apricity/`，逻辑路径不带 `assets/` 前缀（如 `overlays/radial.html`）。
- 每个 Overlay / Screen **create 一次**，之后只改 text/class/属性。禁止每帧 `createDocument` 或 `refresh()`。
- Overlay 打开时不要整树替换（对标旧 AuraTip：活跃期间禁止 `setMenus` / close+open）。
- 轮盘：持久 Overlay + Java 算悬停角；**不要扇形底景**。补给成功不要弹「无法操作」。
- 战术地图瓦片引擎、右侧快捷栏、工事世界线框不改成 HTML；部署页用自定义元素嵌入现有绘制。
- Ore 主题：写样式前读完整 `ore.css` / `example.html`（见原文第五步），不要猜类名。Overlay 检查 Ore 根规则会不会铺满整页背景。
- 客户端线程才能碰 Document；网络回调先 `Minecraft.getInstance().execute(...)`。
- `createDocument` 失败返回 null。refresh 后旧 Element 全部作废。

## 调试

开发实例打开 `config/apricityui-client.toml`：

```toml
[debug]
autoReload = true
aiAutoScreenshot = true
```

改 `run/apricity/` 或资源页后读 `run/screenshots/aui/` 最新图，日志搜 `[AUI HTML]` / `[AUI CSS]` / `[AUI JS]`。

## 交付前

对照 `references/ai-skill.md` 文末自查清单，再确认没有重新引入 `cc.sighs.auratip` 或 `se.mickelus.mutil`。
