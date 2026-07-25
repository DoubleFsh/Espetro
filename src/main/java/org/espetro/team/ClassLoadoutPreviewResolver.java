package org.espetro.team;

import com.mojang.brigadier.StringReader;
import net.minecraft.commands.arguments.item.ItemParser;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.espetro.Espetro;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端权威装备预览解析器。
 * <p>
 * 在不调用 /give、/item replace、performPrefixedCommand 也不修改真实玩家状态的前提下，
 * 复现 {@link ClassEquipment#equipFromVariantInternal} 的纯解析部分，输出一个 6 槽位的
 * {@link Preview} 给网络层使用。
 * <p>
 * 与 ClassEquipment 现有装备发放语义保持一致：
 * <ul>
 *   <li>显式 equipment 直接写入对应装备槽位。</li>
 *   <li>auto_equip_wearables=true 时，可穿戴命令物品优先进入空装备槽位。</li>
 *   <li>剩余可穿戴物品按照原有逻辑从临时背包转入装备槽位。</li>
 *   <li>非可穿戴物品按照原版背包插入规则放入临时库存。</li>
 *   <li>最终临时库存快捷栏第 0 槽（可为空）作为人物主手显示物品。</li>
 *   <li>offhand 只接受显式副手配置；commands 中的盾牌等物品继续按现有逻辑进入背包。</li>
 * </ul>
 */
public final class ClassLoadoutPreviewResolver {

    /**
     * 6 槽位预览数据。所有 ItemStack 都已 {@link ItemStack#copy() 复制}，
     * 调用方可以安全缓存。
     */
    public static final class Preview {
        public final ItemStack head;
        public final ItemStack chest;
        public final ItemStack legs;
        public final ItemStack feet;
        public final ItemStack mainHand;
        public final ItemStack offHand;

        public Preview(ItemStack head, ItemStack chest, ItemStack legs, ItemStack feet,
                       ItemStack mainHand, ItemStack offHand) {
            this.head = head == null ? ItemStack.EMPTY : head;
            this.chest = chest == null ? ItemStack.EMPTY : chest;
            this.legs = legs == null ? ItemStack.EMPTY : legs;
            this.feet = feet == null ? ItemStack.EMPTY : feet;
            this.mainHand = mainHand == null ? ItemStack.EMPTY : mainHand;
            this.offHand = offHand == null ? ItemStack.EMPTY : offHand;
        }

        public static Preview empty() {
            return new Preview(
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY);
        }

        public ItemStack bySlot(EquipmentSlot slot) {
            return switch (slot) {
                case HEAD -> head;
                case CHEST -> chest;
                case LEGS -> legs;
                case FEET -> feet;
                case MAINHAND -> mainHand;
                case OFFHAND -> offHand;
            };
        }
    }

    private ClassLoadoutPreviewResolver() {
    }

    /**
     * 解析单个职业变体的装备配置，生成预览。
     * <p>
     * 任何解析失败（无效物品 ID、非法数量、非法槽位、SNBT 错误）都会被捕获，
     * 仅记录一次服务端警告并将对应槽位退化为 {@link ItemStack#EMPTY}，
     * 不会抛出异常也不会阻止整个预览生成。
     */
    public static Preview resolve(MinecraftServer server,
                                  FactionDataLoader.ClassKitData kit,
                                  FactionDataLoader.ClassVariantData variant) {
        if (server == null) {
            return Preview.empty();
        }
        return resolve(server.registryAccess(), kit, variant);
    }

    /**
     * 测试与原逻辑复用入口：直接接受 {@link HolderLookup.Provider}，避免对
     * {@link MinecraftServer} 的硬依赖。{@link #resolve(MinecraftServer,
     * FactionDataLoader.ClassKitData, FactionDataLoader.ClassVariantData)}
     * 仅将 {@code server.registryAccess()} 转发到此方法。
     */
    public static Preview resolve(HolderLookup.Provider lookup,
                                  FactionDataLoader.ClassKitData kit,
                                  FactionDataLoader.ClassVariantData variant) {
        if (kit == null || variant == null) {
            return Preview.empty();
        }
        if (lookup == null) {
            return Preview.empty();
        }

        // 复用 ClassEquipment 中已有的小工具，保证别名一致。
        ItemStack head = ItemStack.EMPTY;
        ItemStack chest = ItemStack.EMPTY;
        ItemStack legs = ItemStack.EMPTY;
        ItemStack feet = ItemStack.EMPTY;
        ItemStack offHand = ItemStack.EMPTY;
        ItemStack mainHandExplicit = ItemStack.EMPTY;
        SimpleTempInventory tempInventory = new SimpleTempInventory();
        boolean autoEquipWearables = variant.autoEquipWearables == null
            || variant.autoEquipWearables;

        // === 1. 处理 equipment + wearable_equipment：显式槽位直接写入 ===
        Map<String, String> equipmentMap = collectConfiguredEquipment(variant);
        for (Map.Entry<String, String> entry : equipmentMap.entrySet()) {
            String slotName = entry.getKey();
            String itemArgs = ClassEquipment.normalizeItemArgs(entry.getValue());
            if (itemArgs.isBlank()) {
                continue;
            }
            EquipmentSlot slot = ClassEquipment.resolveEquipmentSlot(slotName);
            if (slot == null) {
                Espetro.LOGGER.warn("[预览解析] 职业 {} 变体 {} 的 equipment 槽位无效: {}",
                    kit.id, variant.id, slotName);
                continue;
            }

            ParsedItem parsed = parseItem(lookup, itemArgs, kit, variant);
            if (parsed == null || parsed.stack.isEmpty()) {
                continue;
            }

            // 显式配置直接覆盖对应槽位。保留 count > maxStackSize 的不寻常配置，
            // 由后续渲染处理。
            switch (slot) {
                case HEAD -> head = parsed.stack.copy();
                case CHEST -> chest = parsed.stack.copy();
                case LEGS -> legs = parsed.stack.copy();
                case FEET -> feet = parsed.stack.copy();
                case OFFHAND -> offHand = parsed.stack.copy();
                case MAINHAND -> mainHandExplicit = parsed.stack.copy();
            }
        }

        // === 2. 处理 commands（对齐 ClassEquipment.equipFromVariantInternal）===
        // handleWearableCommand：可穿戴且槽位空 → 整堆 item replace 到装备槽，不进入背包；
        // 可穿戴且槽位已占用 → 整条命令跳过（不 /give）；非可穿戴 → 进入临时背包。
        if (variant.commands != null) {
            for (String raw : variant.commands) {
                if (raw == null) continue;
                String args = ClassEquipment.normalizeItemArgs(raw);
                if (args.isBlank()) continue;

                ParsedItem parsed = parseItem(lookup, args, kit, variant);
                if (parsed == null || parsed.stack.isEmpty()) {
                    continue;
                }

                ItemStack stack = parsed.stack;
                if (autoEquipWearables) {
                    EquipmentSlot armorSlot = armorSlotFor(stack);
                    if (armorSlot != null) {
                        if (slotIsEmpty(armorSlot, head, chest, legs, feet)) {
                            // 与 item replace ... with <itemArgs> 一致：整堆写入装备槽。
                            ItemStack equipped = stack.copy();
                            switch (armorSlot) {
                                case HEAD -> head = equipped;
                                case CHEST -> chest = equipped;
                                case LEGS -> legs = equipped;
                                case FEET -> feet = equipped;
                                default -> { /* 主手/副手不在 auto_equip_wearables 范围内 */ }
                            }
                        }
                        // 槽位已满时跳过整条（不进入背包），与 handleWearableCommand 一致。
                        continue;
                    }
                }

                // 非可穿戴（或未开启 auto_equip）：按 /give 语义进入临时背包。
                tempInventory.add(stack);
            }
        }

        // === 3. auto_equip_wearables 收尾：equipWearableItems —— 从背包各移 1 件到空槽 ===
        if (autoEquipWearables) {
            for (int i = 0; i < tempInventory.slots.size(); i++) {
                ItemStack stack = tempInventory.slots.get(i);
                if (stack.isEmpty()) continue;
                EquipmentSlot armorSlot = armorSlotFor(stack);
                if (armorSlot == null || !slotIsEmpty(armorSlot, head, chest, legs, feet)) {
                    continue;
                }
                ItemStack single = stack.split(1);
                switch (armorSlot) {
                    case HEAD -> head = single;
                    case CHEST -> chest = single;
                    case LEGS -> legs = single;
                    case FEET -> feet = single;
                    default -> { /* 主手/副手不在 auto_equip_wearables 范围内 */ }
                }
                if (stack.isEmpty()) {
                    tempInventory.slots.set(i, ItemStack.EMPTY);
                } else {
                    tempInventory.slots.set(i, stack);
                }
            }
        }

        // === 4. 主手：显式 mainhand 优先，否则用快捷栏第 0 槽（inventory.items[0]）===
        ItemStack mainHand = mainHandExplicit;
        if (mainHand.isEmpty()) {
            mainHand = tempInventory.hotbarSlot0().copy();
        }

        return new Preview(head, chest, legs, feet, mainHand, offHand);
    }

    // ==================== 内部辅助 ====================

    private static Map<String, String> collectConfiguredEquipment(
        FactionDataLoader.ClassVariantData variant) {
        Map<String, String> equipment = new LinkedHashMap<>();
        if (variant.equipment != null) {
            equipment.putAll(variant.equipment);
        }
        if (variant.wearableEquipment != null) {
            equipment.putAll(variant.wearableEquipment);
        }
        return equipment;
    }

    private static final class ParsedItem {
        final ItemStack stack;

        ParsedItem(ItemStack stack) {
            this.stack = stack;
        }
    }

    /**
     * 解析单个 commands/equipment 字符串为 ItemStack。
     * 格式与 /give 一致：{@code <itemId>[<nbt>] <count>}，count 可省略默认为 1。
     * 解析失败时返回 null，已记录警告。
     */
    private static ParsedItem parseItem(HolderLookup.Provider lookup, String itemArgs,
                                        FactionDataLoader.ClassKitData kit,
                                        FactionDataLoader.ClassVariantData variant) {
        try {
            // ItemParser.parseForItem 需要 HolderLookup<Item>，而不是 HolderLookup.Provider。
            // 通过 lookupOrThrow(Registries.ITEM) 取得原版物品注册表的视图。
            HolderLookup.RegistryLookup<Item> itemLookup = lookup.lookupOrThrow(Registries.ITEM);
            StringReader reader = new StringReader(itemArgs);
            ItemParser.ItemResult result = ItemParser.parseForItem(itemLookup, reader);
            Holder<Item> holder = result.item();
            if (holder == null) {
                return null;
            }
            ItemStack stack = new ItemStack(holder);
            CompoundTag nbt = result.nbt();
            if (nbt != null) {
                stack.setTag(nbt);
            }

            // 跳过空格并尝试读取数量。
            reader.skipWhitespace();
            int count = 1;
            if (reader.canRead()) {
                // 跳过空白后还有内容时再读
                count = reader.readInt();
            }
            if (count < 1) {
                count = 1;
            }
            stack.setCount(count);
            return new ParsedItem(stack);
        } catch (Exception e) {
            Espetro.LOGGER.warn("[预览解析] 职业 {} 变体 {} 物品参数解析失败: {} ({})",
                kit.id, variant.id, itemArgs, e.getMessage());
            return null;
        }
    }

    private static EquipmentSlot armorSlotFor(ItemStack stack) {
        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
        return slot.isArmor() ? slot : null;
    }

    private static boolean slotIsEmpty(EquipmentSlot slot, ItemStack head, ItemStack chest,
                                       ItemStack legs, ItemStack feet) {
        return switch (slot) {
            case HEAD -> head.isEmpty();
            case CHEST -> chest.isEmpty();
            case LEGS -> legs.isEmpty();
            case FEET -> feet.isEmpty();
            default -> false;
        };
    }

    /**
     * 模拟玩家主背包（9 快捷栏 + 27 主库存 = 36 槽位）的简单容器。
     * 仅实现 {@code add(stack)} 的拆分逻辑与首次非空槽位查询，
     * 不模拟玩家绑定，避免触发任何服务端事件。
     */
    private static final class SimpleTempInventory {
        private static final int SLOT_COUNT = 36;
        private final List<ItemStack> slots = new ArrayList<>(SLOT_COUNT);

        SimpleTempInventory() {
            for (int i = 0; i < SLOT_COUNT; i++) {
                slots.add(ItemStack.EMPTY);
            }
        }

        /**
         * 按原版 Inventory.add 的顺序向背包放入物品：
         * 优先填充 hotbar 已有同种物品的堆栈，再放空槽位。
         */
        void add(ItemStack stack) {
            if (stack.isEmpty()) return;
            // 1. 先填充已有同种物品的非满堆栈（合并）。
            for (int i = 0; i < SLOT_COUNT && !stack.isEmpty(); i++) {
                ItemStack existing = slots.get(i);
                if (existing.isEmpty()) continue;
                if (!ItemStack.isSameItemSameTags(existing, stack)) continue;
                int max = Math.min(existing.getMaxStackSize(), stack.getMaxStackSize());
                int room = max - existing.getCount();
                if (room <= 0) continue;
                int moved = Math.min(room, stack.getCount());
                existing.grow(moved);
                stack.shrink(moved);
            }
            // 2. 再放入空槽位。
            for (int i = 0; i < SLOT_COUNT && !stack.isEmpty(); i++) {
                ItemStack existing = slots.get(i);
                if (!existing.isEmpty()) continue;
                int max = stack.getMaxStackSize();
                int moved = Math.min(max, stack.getCount());
                ItemStack placed = stack.copyWithCount(moved);
                slots.set(i, placed);
                stack.shrink(moved);
            }
            // 剩余物品（背包满）直接丢弃，模拟真实玩家背包满时的行为。
        }

        /**
         * 快捷栏第 0 槽（与玩家 inventory.selected=0 时主手一致）。
         * 可为空：auto_equip 从 slot0 挪走盔甲后，真实玩家主手也会是空的。
         */
        ItemStack hotbarSlot0() {
            return slots.get(0);
        }

        @Override
        public String toString() {
            int nonEmpty = 0;
            for (ItemStack s : slots) {
                if (!s.isEmpty()) nonEmpty++;
            }
            return String.format(Locale.ROOT, "SimpleTempInventory{nonEmpty=%d}", nonEmpty);
        }
    }
}
