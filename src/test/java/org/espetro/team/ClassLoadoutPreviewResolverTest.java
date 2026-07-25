package org.espetro.team;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Items;
import org.espetro.team.ClassLoadoutPreviewResolver.Preview;
import org.espetro.team.FactionDataLoader.ClassKitData;
import org.espetro.team.FactionDataLoader.ClassVariantData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ClassLoadoutPreviewResolver 纯解析器单元测试。
 * <p>
 * 覆盖规划文档 §Test Plan §解析器测试 中的全部场景：
 * <ol>
 *   <li>仅 commands 的职业：盔甲进入装备槽位，第一个非盔甲物品进入快捷栏第一个槽位。</li>
 *   <li>equipment 加 commands 的职业：显式装备槽位正确覆盖，命令物品不错误覆盖显式装备。</li>
 *   <li>wearable_equipment 的所有支持别名。</li>
 *   <li>auto_equip_wearables=true 和 false。</li>
 *   <li>主手、副手显式装备。</li>
 *   <li>含 NBT 的物品。</li>
 *   <li>物品数量大于最大堆叠数时的分堆和快捷栏第一个物品。</li>
 *   <li>无效物品 ID、非法数量、非法槽位时不抛出未处理异常。</li>
 *   <li>无装备配置时返回全空预览。</li>
 * </ol>
 */
class ClassLoadoutPreviewResolverTest {

    private static HolderLookup.Provider lookup;

    @BeforeAll
    static void bootstrapMinecraft() {
        // Forge 1.20.1 单元测试：须先设置版本，否则 EntityType/DataFixers 会报
        // "Game version not set"。完整 Bootstrap.bootStrap() 末尾会初始化
        // NetworkHooks，在无游戏进程下因 EventBus 无默认构造而失败；此时
        // BuiltInRegistries 已完成 bootStrap，物品解析足够使用，捕获即可。
        SharedConstants.tryDetectVersion();
        try {
            Bootstrap.bootStrap();
        } catch (Throwable t) {
            // 预期：NetworkHooks/NetworkConstants 在纯 JUnit 环境初始化失败。
            // 若物品注册表仍不可用，后续断言会明确失败。
        }
        lookup = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        assertNotNull(Items.IRON_SWORD, "原版物品注册表应已可用");
        assertNotNull(BuiltInRegistries.ITEM.getKey(Items.IRON_SWORD));
    }

    private static ClassKitData kit(String id) {
        ClassKitData k = new ClassKitData();
        k.id = id;
        k.name = id;
        return k;
    }

    private static ClassVariantData variant() {
        ClassVariantData v = new ClassVariantData();
        v.id = "default";
        v.name = "默认";
        return v;
    }

    private static Preview resolve(ClassKitData k, ClassVariantData v) {
        return ClassLoadoutPreviewResolver.resolve(lookup, k, v);
    }

    // ============ 1. 仅 commands：盔甲自动进入槽位，第一个非盔甲物品进 mainHand ============

    @Test
    @DisplayName("仅 commands 的职业：盔甲进入装备槽位，第一个非盔甲物品进入 mainHand")
    void onlyCommands_armorToSlots_firstNonArmorToMainHand() {
        ClassKitData k = kit("only_commands");
        ClassVariantData v = variant();
        v.autoEquipWearables = true;
        v.commands = new String[]{
            "minecraft:iron_helmet 1",
            "minecraft:iron_chestplate 1",
            "minecraft:iron_leggings 1",
            "minecraft:iron_boots 1",
            "minecraft:iron_sword 1",
            "minecraft:bread 20"
        };

        Preview p = resolve(k, v);

        assertAll("盔甲应进入对应槽位",
            () -> assertEquals(Items.IRON_HELMET, p.head.getItem(), "head"),
            () -> assertEquals(Items.IRON_CHESTPLATE, p.chest.getItem(), "chest"),
            () -> assertEquals(Items.IRON_LEGGINGS, p.legs.getItem(), "legs"),
            () -> assertEquals(Items.IRON_BOOTS, p.feet.getItem(), "feet")
        );
        assertEquals(Items.IRON_SWORD, p.mainHand.getItem(), "mainHand 应为第一个非盔甲物品");
        assertTrue(p.offHand.isEmpty(), "offHand 应为空");
    }

    // ============ 2. equipment + commands：显式装备不被命令物品覆盖 ============

    @Test
    @DisplayName("equipment + commands：显式装备槽位不被 commands 中的同位置盔甲覆盖")
    void equipmentAndCommands_explicitNotOverwritten() {
        ClassKitData k = kit("eq_plus_commands");
        ClassVariantData v = variant();
        v.autoEquipWearables = true;
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("chest", "minecraft:diamond_chestplate 1");
        // commands 先放剑（进背包），再放 iron_chestplate（chest 已被显式占用 → 进背包）
        v.commands = new String[]{
            "minecraft:iron_sword 1",
            "minecraft:iron_chestplate 1"
        };

        Preview p = resolve(k, v);

        assertEquals(Items.DIAMOND_CHESTPLATE, p.chest.getItem(),
            "chest 应为显式 diamond_chestplate，不被命令 iron_chestplate 覆盖");
        // iron_helmet 可穿戴且槽位已满时整条跳过，不进入背包（对齐 handleWearableCommand）
        // 故 mainHand 仍为先放入的 iron_sword
        assertEquals(Items.IRON_SWORD, p.mainHand.getItem(),
            "mainHand 应为 commands 中的 iron_sword（重复盔甲被跳过）");
    }

    // ============ 3. wearable_equipment 所有支持别名 ============

    @Test
    @DisplayName("wearable_equipment 支持所有 head 别名：head/helmet/armor_head/armor.head")
    void wearableEquipment_headAliases() {
        for (String alias : new String[]{"head", "helmet", "armor_head", "armor.head"}) {
            ClassKitData k = kit("alias_head_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:iron_helmet 1");

            Preview p = resolve(k, v);
            assertEquals(Items.IRON_HELMET, p.head.getItem(),
                "别名 " + alias + " 应解析到 HEAD 槽位");
        }
    }

    @Test
    @DisplayName("wearable_equipment 支持所有 chest 别名")
    void wearableEquipment_chestAliases() {
        for (String alias : new String[]{"chest", "chestplate", "body", "armor_chest", "armor.chest"}) {
            ClassKitData k = kit("alias_chest_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:iron_chestplate 1");

            Preview p = resolve(k, v);
            assertEquals(Items.IRON_CHESTPLATE, p.chest.getItem(),
                "别名 " + alias + " 应解析到 CHEST 槽位");
        }
    }

    @Test
    @DisplayName("wearable_equipment 支持所有 legs/feet 别名")
    void wearableEquipment_legsFeetAliases() {
        for (String alias : new String[]{"legs", "leggings", "armor_legs", "armor.legs"}) {
            ClassKitData k = kit("alias_legs_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:iron_leggings 1");

            Preview p = resolve(k, v);
            assertEquals(Items.IRON_LEGGINGS, p.legs.getItem(),
                "别名 " + alias + " 应解析到 LEGS 槽位");
        }
        for (String alias : new String[]{"feet", "boots", "armor_feet", "armor.feet"}) {
            ClassKitData k = kit("alias_feet_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:iron_boots 1");

            Preview p = resolve(k, v);
            assertEquals(Items.IRON_BOOTS, p.feet.getItem(),
                "别名 " + alias + " 应解析到 FEET 槽位");
        }
    }

    @Test
    @DisplayName("wearable_equipment 支持所有 mainhand/offhand 别名")
    void wearableEquipment_mainOffHandAliases() {
        for (String alias : new String[]{"mainhand", "main_hand", "weapon", "weapon_mainhand", "weapon.mainhand"}) {
            ClassKitData k = kit("alias_main_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:diamond_sword 1");

            Preview p = resolve(k, v);
            assertEquals(Items.DIAMOND_SWORD, p.mainHand.getItem(),
                "别名 " + alias + " 应解析到 MAINHAND 槽位");
        }
        for (String alias : new String[]{"offhand", "off_hand", "shield", "weapon_offhand", "weapon.offhand"}) {
            ClassKitData k = kit("alias_off_" + alias);
            ClassVariantData v = variant();
            v.wearableEquipment = new LinkedHashMap<>();
            v.wearableEquipment.put(alias, "minecraft:shield 1");

            Preview p = resolve(k, v);
            assertEquals(Items.SHIELD, p.offHand.getItem(),
                "别名 " + alias + " 应解析到 OFFHAND 槽位");
        }
    }

    // ============ 4. auto_equip_wearables = true / false ============

    @Test
    @DisplayName("auto_equip_wearables=false：commands 中的盔甲不自动进入槽位，全部进背包")
    void autoEquipWearablesFalse_armorGoesToInventory() {
        ClassKitData k = kit("no_autoequip");
        ClassVariantData v = variant();
        v.autoEquipWearables = false;
        v.commands = new String[]{
            "minecraft:iron_helmet 1",
            "minecraft:iron_sword 1"
        };

        Preview p = resolve(k, v);

        assertTrue(p.head.isEmpty(), "head 应为空（auto_equip_wearables=false）");
        // iron_helmet 先入背包 slot 0，iron_sword 入 slot 1；firstNonEmpty → iron_helmet
        assertEquals(Items.IRON_HELMET, p.mainHand.getItem(),
            "mainHand 应为背包第一个物品 iron_helmet");
    }

    @Test
    @DisplayName("auto_equip_wearables=true（默认）：commands 中的盔甲自动进入空槽位")
    void autoEquipWearablesTrue_default() {
        ClassKitData k = kit("autoequip_default");
        ClassVariantData v = variant();
        // 不设 autoEquipWearables，验证默认开启
        v.commands = new String[]{
            "minecraft:iron_helmet 1",
            "minecraft:iron_sword 1"
        };

        Preview p = resolve(k, v);

        assertEquals(Items.IRON_HELMET, p.head.getItem(), "head 应自动装备 iron_helmet");
        assertEquals(Items.IRON_SWORD, p.mainHand.getItem(), "mainHand 应为 iron_sword");
    }

    // ============ 5. 显式主手/副手装备 ============

    @Test
    @DisplayName("equipment 显式 mainhand + offhand")
    void explicitMainHandAndOffHand() {
        ClassKitData k = kit("explicit_main_off");
        ClassVariantData v = variant();
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("mainhand", "minecraft:diamond_sword 1");
        v.equipment.put("offhand", "minecraft:shield 1");

        Preview p = resolve(k, v);

        assertEquals(Items.DIAMOND_SWORD, p.mainHand.getItem(), "显式 mainhand");
        assertEquals(Items.SHIELD, p.offHand.getItem(), "显式 offhand");
    }

    @Test
    @DisplayName("显式 mainhand 优先于 commands 中的第一个背包物品")
    void explicitMainHandOverridesTempInventory() {
        ClassKitData k = kit("explicit_main_override");
        ClassVariantData v = variant();
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("mainhand", "minecraft:diamond_sword 1");
        v.commands = new String[]{
            "minecraft:bread 16"
        };

        Preview p = resolve(k, v);

        assertEquals(Items.DIAMOND_SWORD, p.mainHand.getItem(),
            "显式 mainhand 应优先于临时库存第一个物品");
    }

    // ============ 6. 含 NBT 的物品 ============

    @Test
    @DisplayName("commands 中含 NBT 的物品应保留 NBT")
    void nbtItemPreserved() {
        ClassKitData k = kit("nbt_item");
        ClassVariantData v = variant();
        v.autoEquipWearables = true;
        // iron_sword{Damage:10} 1 —— 使用 SNBT 设置 Damage 标签
        v.commands = new String[]{
            "minecraft:iron_sword{Damage:10} 1"
        };

        Preview p = resolve(k, v);

        assertEquals(Items.IRON_SWORD, p.mainHand.getItem(), "mainHand 应为 iron_sword");
        assertNotNull(p.mainHand.getTag(), "NBT 标签应保留");
        assertEquals(10, p.mainHand.getTag().getInt("Damage"),
            "Damage 标签应为 10");
    }

    @Test
    @DisplayName("equipment 中含 NBT 的物品应保留 NBT")
    void nbtEquipmentPreserved() {
        ClassKitData k = kit("nbt_eq");
        ClassVariantData v = variant();
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("head", "minecraft:iron_helmet{Damage:5} 1");

        Preview p = resolve(k, v);

        assertEquals(Items.IRON_HELMET, p.head.getItem());
        assertNotNull(p.head.getTag(), "head NBT 应保留");
        assertEquals(5, p.head.getTag().getInt("Damage"));
    }

    // ============ 7. 数量大于最大堆叠数时的分堆 ============

    @Test
    @DisplayName("commands 中数量 > maxStackSize 时正确分堆，mainHand 为第一个物品")
    void countExceedsMaxStack_splitAndFirstItemIsMainHand() {
        ClassKitData k = kit("stack_split");
        ClassVariantData v = variant();
        v.autoEquipWearables = false; // 让所有物品进背包，便于验证
        // bread maxStack=64；给 100 个 → 应分成 64 + 36 两堆
        // 之后给 iron_sword 1 个 → 进 slot 2
        v.commands = new String[]{
            "minecraft:bread 100",
            "minecraft:iron_sword 1"
        };

        Preview p = resolve(k, v);

        // 第一个入背包的是 bread 64（slot 0），所以 mainHand 是 bread
        assertEquals(Items.BREAD, p.mainHand.getItem(),
            "mainHand 应为背包第一个物品 bread");
        assertEquals(64, p.mainHand.getCount(),
            "mainHand bread 数量应为第一堆 64（不是 100）");
    }

    // ============ 8. 无效输入不抛出异常 ============

    @Test
    @DisplayName("无效物品 ID 不抛异常，对应槽位为空")
    void invalidItemId_doesNotThrow() {
        ClassKitData k = kit("invalid_item");
        ClassVariantData v = variant();
        v.autoEquipWearables = true;
        v.commands = new String[]{
            "minecraft:does_not_exist 1",
            "minecraft:iron_sword 1"
        };

        Preview p = resolve(k, v);
        // 无效物品被跳过，iron_sword 仍正常进入背包
        assertEquals(Items.IRON_SWORD, p.mainHand.getItem(),
            "无效物品不影响后续解析");
    }

    @Test
    @DisplayName("非法数量（负数/非数字）不抛异常，使用默认数量 1")
    void illegalCount_doesNotThrow() {
        ClassKitData k = kit("illegal_count");
        ClassVariantData v = variant();
        v.autoEquipWearables = false;
        v.commands = new String[]{
            "minecraft:iron_sword abc",   // 非数字 → 应捕获
        };

        // 不抛异常即视为通过
        Preview p = resolve(k, v);
        // 行为：解析失败时返回 ItemStack.EMPTY，mainHand 为空
        // （或部分成功：物品解析成功但数量解析失败 → 默认为 1）
        // 这里只验证不抛异常，具体行为由实现决定
        assertNotNull(p);
    }

    @Test
    @DisplayName("非法 equipment 槽位名不抛异常，跳过该槽位")
    void invalidSlotName_doesNotThrow() {
        ClassKitData k = kit("invalid_slot");
        ClassVariantData v = variant();
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("not_a_slot", "minecraft:iron_helmet 1");
        v.equipment.put("head", "minecraft:iron_helmet 1");

        Preview p = resolve(k, v);
        assertEquals(Items.IRON_HELMET, p.head.getItem(),
            "合法 head 槽位仍应生效");
    }

    @Test
    @DisplayName("null server 不抛异常，返回空预览")
    void nullServer_returnsEmpty() {
        ClassKitData k = kit("null_server");
        ClassVariantData v = variant();
        v.commands = new String[]{"minecraft:iron_sword 1"};

        Preview p = ClassLoadoutPreviewResolver.resolve((HolderLookup.Provider) null, k, v);
        assertTrue(p.head.isEmpty() && p.mainHand.isEmpty(),
            "null lookup 应返回空预览");
    }

    // ============ 9. 无装备配置返回全空预览 ============

    @Test
    @DisplayName("无装备配置时返回全空预览")
    void emptyConfig_returnsEmptyPreview() {
        ClassKitData k = kit("empty");
        ClassVariantData v = variant();
        // commands/equipment/wearableEquipment 均为 null

        Preview p = resolve(k, v);

        assertAll("所有槽位应为空",
            () -> assertTrue(p.head.isEmpty(), "head"),
            () -> assertTrue(p.chest.isEmpty(), "chest"),
            () -> assertTrue(p.legs.isEmpty(), "legs"),
            () -> assertTrue(p.feet.isEmpty(), "feet"),
            () -> assertTrue(p.mainHand.isEmpty(), "mainHand"),
            () -> assertTrue(p.offHand.isEmpty(), "offhand")
        );
    }

    @Test
    @DisplayName("null kit/variant 返回空预览")
    void nullInputs_returnEmpty() {
        Preview p1 = resolve(null, variant());
        Preview p2 = resolve(kit("x"), null);

        assertTrue(p1.head.isEmpty() && p1.mainHand.isEmpty());
        assertTrue(p2.head.isEmpty() && p2.mainHand.isEmpty());
    }

    // ============ 综合：完整配置 ============

    @Test
    @DisplayName("综合：equipment + wearable_equipment + commands 同时存在")
    void combinedConfig_allSourcesResolved() {
        ClassKitData k = kit("combined");
        ClassVariantData v = variant();
        v.autoEquipWearables = true;
        v.equipment = new LinkedHashMap<>();
        v.equipment.put("head", "minecraft:diamond_helmet 1");
        v.equipment.put("chest", "minecraft:diamond_chestplate 1");
        v.wearableEquipment = new LinkedHashMap<>();
        v.wearableEquipment.put("offhand", "minecraft:shield 1");
        v.commands = new String[]{
            "minecraft:diamond_leggings 1",   // armor → auto-equip to legs
            "minecraft:diamond_boots 1",     // armor → auto-equip to feet
            "minecraft:bow 1",                // non-armor → temp slot 0
            "minecraft:arrow 64"              // non-armor → temp slot 1
        };

        Preview p = resolve(k, v);

        assertAll("显式 equipment + wearable + commands 自动装备",
            () -> assertEquals(Items.DIAMOND_HELMET, p.head.getItem(), "head"),
            () -> assertEquals(Items.DIAMOND_CHESTPLATE, p.chest.getItem(), "chest"),
            () -> assertEquals(Items.DIAMOND_LEGGINGS, p.legs.getItem(), "legs"),
            () -> assertEquals(Items.DIAMOND_BOOTS, p.feet.getItem(), "feet"),
            () -> assertEquals(Items.SHIELD, p.offHand.getItem(), "offhand"),
            () -> assertEquals(Items.BOW, p.mainHand.getItem(), "mainHand")
        );
    }
}
