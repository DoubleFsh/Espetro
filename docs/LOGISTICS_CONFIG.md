# Espetro 后勤与补给配置

后勤配置位于数据包的 `data/espetro/config/logistics.json`。服务器可在世界数据包中
覆盖内置文件，然后执行 `/reload` 或 `/espetro reload` 生效。

## 玩家流程

1. 玩家右击匹配的补给方块，建材和弹药物品进入背包。
2. 玩家携带补给右击己方 FOB Radio，将物品存入 FOB 公共库存。
3. 建材库存会依次支付 HAB 和弹药箱的建设成本。
4. HAB 建成后等待激活时间；被敌军压制或 Radio 生命低于阈值时不能部署。
5. 玩家右击已建成的弹药箱补充当前职业装备变体的弹药，并按该变体的 `resupply.ammo_cost`
   扣除 FOB 弹药库存；库存大于 0、但不足完整费用时仍发放补给，并扣除全部剩余
   库存至 0；库存已经为 0 时不发放补给。

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
    "radio_teammate_radius": 30.0,
    "require_teammate": true,
    "hab_construction_cost": 500,
    "ammo_crate_construction_cost": 100,
    "default_resupply_ammo_cost": 50,
    "hab_activation_seconds": 30,
    "hab_reactivation_seconds": 30,
    "hab_disable_radio_health": 75,
    "sources": []
  }
}
```

`hab_disable_radio_health` 是 Radio 最大生命值的百分比。`sources` 按顺序匹配，命中第一个
来源后停止。来源中的非空条件必须全部满足。

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

Radio 不会扫描范围内的载具或容器，也不会自动读取其中的物品。载具里的补给必须先由
玩家取出并放进自己的背包，再由玩家右击己方 Radio 核心（或潜行右击其弹药箱）存入
FOB 共享库存。编制载具 JSON 不需要配置后勤载具类型，Radio 的放置条件也与载具无关。
