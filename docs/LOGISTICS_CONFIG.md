# Espetro 后勤与补给配置

地图后勤配置位于 `EsWorld/<地图>/EsConfig/logistics.json`；可建造工事与载具补给范围位于
`config/espetro/fortifications.json`。两者都在服务端启动时冻结，修改后需要完整重启。

## 玩家流程

1. 小队长、火力组长或指挥官通过 Alt 轮盘选择“建造工事”。
2. 建造费用从覆盖目标点的己方 Radio 库存原子扣除；余额不足时不放置也不部分扣款。
3. 玩家右击已建成的弹药箱打开「补给步兵 / 更换职业」菜单；潜行右击直接补充当前职业装备变体的实际缺口，Radio 弹药不足完整费用时整次取消。
4. 对准己方补给载具可用 F 轮盘装卸弹药与建材；战斗载具仅能装卸弹药。
5. Radio 本体不提供存入物资、步兵补给，也不会因为收到物资自动生成弹药箱。

只有 Espetro 发放并带有补给标签的物品可以存入 FOB。普通橡木木板或箭不会被误收。

## 完整结构

```json
{
  "logistics": {
    "max_construction": 20000,
    "max_ammunition": 20000,
    "pickup_cooldown_seconds": 5,
    "deposit_radius": 8.0,
    "radio_build_radius": 150.0,
    "radio_exclusion_radius": 400.0,
    "radio_teammate_count": 1,
    "radio_teammate_radius": 30.0,
    "require_teammate": true,
    "radio": {
      "allowed_phases": ["BATTLE"],
      "require_commander": false,
      "allow_squad_leader": true,
      "cooldown_seconds": -1,
      "required_planks": -1,
      "creative_bypasses_planks": true,
      "max_active_per_team": -1,
      "build_radius": 150.0,
      "require_target_block": false,
      "exclusion_radius": 400.0,
      "teammate_count": 1,
      "teammate_radius": 30.0
    },
    "hab_construction_cost": 500,
    "ammo_crate_construction_cost": 100,
    "default_resupply_ammo_cost": 50,
    "hab_activation_seconds": 0,
    "hab_reactivation_seconds": 30,
    "hab_disable_radio_health": 75,
    "sources": []
  }
}
```

`hab_disable_radio_health` 是 Radio 最大生命值的百分比。`sources` 按顺序匹配，命中第一个
来源后停止。来源中的非空条件必须全部满足。

平铺字段 `radio_*` / `require_teammate` 仍受支持，并会同步进嵌套 `radio`（或由嵌套写回）。
推荐在数据包中直接配置 `logistics.radio`。

### Radio 建立条件（`logistics.radio`）

| 字段 | 默认 | 说明 |
| --- | --- | --- |
| `allowed_phases` | `["BATTLE"]` | 允许部署的 `GamePhase` 名（大写） |
| `require_commander` | false | `true` 时必须是指挥官 |
| `allow_squad_leader` | true | 是否允许小队长；默认与「指挥或小队长」等价 |
| `cooldown_seconds` | -1 | 玩家建造冷却；`-1` 回退 `bastion.json` 的 `cooldown_seconds` |
| `required_planks` | -1 | **所需补给建材点数**；`-1` 回退 bastion；`0` 不消耗。只认 Espetro 补给站发放、带 `EspetroSupplyType=construction` 的物品点数，**不是**任意原版木板 |
| `creative_bypasses_planks` | true | 创造模式是否免建材点数（兵站 HAB 除外，始终消耗 `hab_construction_cost`） |
| `max_active_per_team` | -1 | 每队活跃 Radio 上限；`-1` 使用代码默认 4 |
| `build_radius` | 150.0 | API/`FobSnapshot` 用半径；**不**限制 wand 视线放置距离 |
| `require_target_block` | false | `true` 时必须看中实心方块，不允许回退到脚下 |
| `exclusion_radius` | 400.0 | 与其它活跃 Radio 的最小间距 |
| `teammate_count` | 1 | 放置者之外所需附近同阵营队友；`0` 关闭 |
| `teammate_radius` | 30.0 | 队友检测半径 |

`radio_teammate_count` 是放置者之外所需的附近同阵营队友人数，设为 `0` 可关闭人数要求；
`radio_teammate_radius` 是以 Radio 实际放置点为中心的检测半径。只统计同维度、存活且非
旁观模式的玩家。旧字段 `require_teammate: false` 仍受支持，并等价于把人数设为 `0`。

部署 Radio 时若 `required_planks`（生效值）> 0，玩家须从补给站领取足够建材点数；
与存入 FOB 的 construction 点数同一体系。

## 原生补给方块

给予并放置原生补给方块：

```mcfunction
/give @s espetro:supply_source
```

方块默认保存 `source_id: "default"`。管理员可按位置设置任意 ID：

```mcfunction
/data merge block ~ ~ ~ {source_id:"attack_main"}
```

对应配置：

```json
{
  "id": "attack_main",
  "team": "ATTACK",
  "blocks": ["espetro:supply_source"],
  "source_ids": ["attack_main"],
  "construction": [
    {
      "id": "minecraft:oak_planks",
      "count": 64,
      "points_per_item": 1,
      "supply_id": "construction_material"
    }
  ],
  "ammunition": [
    {
      "id": "minecraft:arrow",
      "count": 64,
      "points_per_item": 1,
      "supply_id": "ammunition"
    }
  ]
}
```

`team` 可省略；填写后只有该阵营玩家能使用。`source_ids` 仅适用于具有方块实体且保存
`source_id` 的方块。

## 原版与其他模组方块

`blocks` 接受完整方块 ID 和以 `#` 开头的方块标签：

```json
{
  "id": "warehouse",
  "blocks": [
    "minecraft:barrel",
    "#forge:chests/wooden",
    "othermod:supply_crate"
  ],
  "construction": [
    {
      "id": "minecraft:oak_planks",
      "nbt": "{display:{Name:'{\"text\":\"Construction\"}'}}",
      "count": 32,
      "points_per_item": 2,
      "supply_id": "warehouse_construction"
    }
  ],
  "ammunition": []
}
```

其他模组方块只要存在于注册表即可，不需要实现 Espetro API。

## 方块实体 NBT

`block_entity_nbt` 使用 SNBT，并按“部分匹配”检查。下面的来源只匹配带指定自定义名称和
标签的容器，方块实体内其他字段不影响匹配：

```json
{
  "id": "tagged_depot",
  "blocks": ["minecraft:barrel"],
  "block_entity_nbt": "{CustomName:'{\"text\":\"Main Depot\"}',ForgeData:{EspetroDepot:1b}}",
  "construction": [
    {
      "id": "minecraft:oak_planks",
      "count": 64,
      "points_per_item": 1,
      "supply_id": "construction"
    }
  ],
  "ammunition": [
    {
      "id": "minecraft:arrow",
      "nbt": "{EspetroAmmoClass:\"rifle\"}",
      "count": 64,
      "points_per_item": 1,
      "supply_id": "rifle_ammo"
    }
  ]
}
```

可使用 `/data get block <x> <y> <z>` 查看方块实体的实际 NBT，再把需要区分来源的字段
写进 `block_entity_nbt`。

## 坐标限制

`locations` 可把普通方块限制为指定维度和位置附近的补给源：

```json
{
  "id": "main_base",
  "blocks": ["minecraft:barrel"],
  "locations": [
    {
      "dimension": "minecraft:overworld",
      "position": [120, 64, -300],
      "radius": 2.0
    }
  ],
  "construction": [
    {
      "id": "minecraft:oak_planks",
      "count": 64,
      "points_per_item": 1
    }
  ],
  "ammunition": []
}
```

省略 `dimension` 表示所有维度。配置多个位置时，匹配任意一个即可。

## 发放物品 NBT

每个发放条目支持：

| 字段 | 说明 |
| --- | --- |
| `id` | 原版或其他模组物品 ID。 |
| `nbt` | 可选 SNBT，会先写入物品。 |
| `count` | 每次领取数量，最小为 1。 |
| `points_per_item` | 每个物品存入 FOB 后增加的点数，最小为 1。 |
| `supply_id` | 自定义补给 ID；省略时使用物品 ID。 |

Espetro 随后写入以下物品 NBT：

```snbt
{
  EspetroSupplyType:"construction",
  EspetroSupplyId:"construction_material",
  EspetroSupplyPoints:1,
  EspetroSupplySourceId:"default"
}
```

弹药的 `EspetroSupplyType` 为 `"ammunition"`。这些标签用于服务端识别实际补给物品；
可以在发放物品的 `nbt` 中增加其他模组需要的 ID 或标签。

## 载具与 Radio 的补给关系

载具不扫描世界容器；主基地手动刷新/部署时由系统自动装填一次基础库存。之后补给载具在
Radio 或本方原部署点附近使用 F 轮盘装卸物资；战斗载具在本方原部署点或建造出的载具补给站
附近装卸弹药。每次转移量、间隔和作用半径由 `config/espetro/fortifications.json` 配置。
任何载具的 F 轮盘都提供“补给步兵”：按职业弹药缺口发放，消耗载具弹药，无冷却，
不要求载具处于装卸补给范围内。只有战斗载具和补给载具的轮盘提供“更换职业”。
首发载具同样自动装填。


## Radio 与兵站（HAB）拆分（2026-07）

- **Radio**：从 Alt 的“建造工事”选择，先预览占地，左键确认后手持铁铲修建；**不消耗建材**，建成后持有 construction/ammunition 库存，地图显示 `build_radius` 范围圈。
- **兵站 HAB**：同样通过施工预览建造，仅能位于**己方** Radio 建造半径内；费用 `hab_construction_cost` 在确认施工范围时从覆盖该点的己方 Radio 扣除，总和不足则整笔失败。
- **摧毁**：任意玩家可使用铁铲右键逐步拆除 Radio、HAB 和 JSON 工事。Radio 默认需约 30 秒、HAB 默认约 10 秒；参数位于 `fortifications.json` 的 `builtin_construction`。拆 HAB 不扣兵力；Radio 归零时按 `bastion.json` 处理兵力损失。
- **地图显示**：HAB 失去同阵营 Radio 的 `build_radius` 覆盖后会停止运作，并从 ESPoints 战术地图隐藏；覆盖恢复后重新显示。
- **队友人数**：嵌套字段 `logistics.radio.teammate_count` 为权威（覆盖平铺 `radio_teammate_count`）。设为 `0` 关闭人数要求。
- 弹药箱通过“建造工事”单独建造，不会随 Radio 库存变化自动生成。
- 职业弹药补给按实际缺口发放；背包已达到各 `resupply.items[].max` 时不产生补给。
  Radio 的 ammunition 必须足额覆盖该变体的 `ammo_cost` 才会提交，不能部分扣费或透支发放。
