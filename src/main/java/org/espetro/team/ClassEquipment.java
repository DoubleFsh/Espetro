package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.espetro.Espetro;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 职业装备管理器 - 使用MC原版 /give 指令分发所有装备
 * <p>
 * JSON中每个职业配置一个 commands 字符串数组，每个元素是 /give 命令的参数部分。
 * 代码自动拼接 "give &lt;玩家名&gt; " 前缀后执行。
 * <p>
 * 可穿戴装备会在发放后自动穿到对应装备栏，也可通过 equipment 指定槽位直接穿戴。
 */
public class ClassEquipment {

    private static final Set<UUID> EQUIPMENT_MUTATION_PLAYERS = new HashSet<>();
    /** Tracks the last equipped class key (factionId:classId:variantId) per player,
     *  so that a class change forces re-equip even if the inventory still has items. */
    private static final Map<UUID, String> LAST_EQUIPPED_KEY = new java.util.HashMap<>();
    /** Stable IDs make class changes replace modifiers instead of stacking them. */
    static final UUID CLASS_HEALTH_BONUS_ID =
        UUID.fromString("dd348d6d-91e3-4f54-aa7a-cd6847dad14a");
    static final UUID CLASS_SPEED_BONUS_ID =
        UUID.fromString("2a836d60-7e0b-4518-81f9-4f038fe51a35");

    /**
     * 是否看起来尚未发过职业装（护甲全空且背包几乎无物）。
     * 用于「选上了职业但装备被清掉/从未发出」时的补发。
     */
    public static boolean needsLoadout(Player player) {
        if (player == null) {
            return false;
        }
        if (!player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
            || !player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
            || !player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
            || !player.getItemBySlot(EquipmentSlot.FEET).isEmpty()) {
            return false;
        }
        int nonEmpty = 0;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            if (!player.getInventory().getItem(i).isEmpty()) {
                nonEmpty++;
                if (nonEmpty >= 4) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 若玩家已有职业记录且当前像未发装，则按当前职业补发。
     * 落地部署后调用，避免「面板上已选职但背包空」。
     * <p>
     * 额外检查：若玩家当前职业与上次发装时不同，强制重新发装，
     * 解决「死后换职业但旧装备仍留在背包导致新装备不生效」的问题。
     */
    public static void ensureEquippedIfNeeded(ServerPlayer player) {
        if (player == null) {
            return;
        }
        ClassCountManager counts = ClassCountManager.getInstance();
        String classId = counts.getPlayerClass(player.getUUID());
        String variantId = counts.getPlayerVariant(player.getUUID());
        String factionId = counts.getPlayerFaction(player.getUUID());
        if (classId == null || variantId == null || factionId == null) {
            return;
        }

        String currentKey = factionId + ":" + classId + ":" + variantId;
        String lastKey = LAST_EQUIPPED_KEY.get(player.getUUID());
        boolean classChanged = lastKey != null && !currentKey.equals(lastKey);

        if (!classChanged && !needsLoadout(player)) {
            return;
        }
        equipPlayer(player, factionId, classId, variantId);
    }

    /**
     * 清空玩家背包及装备栏
     */
    public static void clearEquipment(Player player) {
        beginEquipmentMutation(player);
        try {
            clearClassBonuses(player);
            player.getInventory().clearContent();
            player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
            player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            player.getInventory().setChanged();

            if (player instanceof ServerPlayer sp) {
                sp.inventoryMenu.setCarried(ItemStack.EMPTY);
                sp.containerMenu.setCarried(ItemStack.EMPTY);
                sp.inventoryMenu.broadcastChanges();
                sp.containerMenu.broadcastChanges();
            }
        } finally {
            endEquipmentMutation(player);
        }
    }

    public static boolean isEquipmentMutation(ServerPlayer player) {
        return player != null && EQUIPMENT_MUTATION_PLAYERS.contains(player.getUUID());
    }

    /**
     * 根据职业给予玩家装备（通过 /give 指令）
     */
    public static void equipPlayer(Player player, String classId) {
        if (!(player instanceof ServerPlayer sp)) return;

        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            loader.ensureLoaded(server.getResourceManager());
        }

        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit == null) {
            Espetro.LOGGER.warn("未找到职业配置: {}", classId);
            return;
        }
        if (kit.variants == null || kit.variants.size() != 1) {
            Espetro.LOGGER.warn("职业 {} 有多个装备变体，必须指定 variantId", classId);
            return;
        }
        equipFromVariant(sp, kit, kit.variants.values().iterator().next());
    }

    /**
     * 根据阵营和职业给予玩家装备（通过 /give 指令）
     */
    public static void equipPlayer(Player player, String factionId, String classId) {
        if (!(player instanceof ServerPlayer sp)) return;
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            loader.ensureLoaded(server.getResourceManager());
        }
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        if (kit == null || factionId == null || !factionId.equals(kit.factionId)
            || kit.variants == null || kit.variants.size() != 1) {
            Espetro.LOGGER.warn("未找到唯一职业装备变体: {} / {}", factionId, classId);
            return;
        }
        equipFromVariant(sp, kit, kit.variants.values().iterator().next());
    }

    public static void equipPlayer(Player player, String factionId, String classId, String variantId) {
        if (!(player instanceof ServerPlayer sp)) return;
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        if (server != null) {
            loader.ensureLoaded(server.getResourceManager());
        }
        FactionDataLoader.ClassKitData kit = loader.getClassKit(classId);
        FactionDataLoader.ClassVariantData variant = kit != null ? kit.getVariant(variantId) : null;
        if (kit == null || variant == null || factionId == null || !factionId.equals(kit.factionId)) {
            Espetro.LOGGER.warn("未找到职业装备变体: {} / {} / {}", factionId, classId, variantId);
            return;
        }
        equipFromVariant(sp, kit, variant);
    }

    /**
     * 从命令数组执行所有 /give 指令
     */
    private static void equipFromVariant(ServerPlayer player, FactionDataLoader.ClassKitData kit,
                                         FactionDataLoader.ClassVariantData variant) {
        beginEquipmentMutation(player);
        try {
            equipFromVariantInternal(player, kit, variant);
        } finally {
            endEquipmentMutation(player);
        }
    }

    private static void equipFromVariantInternal(ServerPlayer player, FactionDataLoader.ClassKitData kit,
                                                 FactionDataLoader.ClassVariantData variant) {
        // 先清空背包
        player.getInventory().clearContent();

        boolean hasCommands = variant.commands != null && variant.commands.length > 0;
        boolean hasConfiguredEquipment = hasConfiguredEquipment(variant);
        applyBonus(player, kit);
        if (!hasCommands && !hasConfiguredEquipment) {
            Espetro.LOGGER.warn("职业 {} 变体 {} 无 commands/equipment 配置", kit.id, variant.id);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        String playerName = player.getName().getString();

        equipConfiguredEquipment(player, kit, variant, server, playerName);
        if (hasCommands) {
            for (String args : variant.commands) {
                if (args == null || args.isBlank()) continue;
                String itemArgs = normalizeItemArgs(args);
                if (shouldAutoEquipWearables(variant) && handleWearableCommand(player, server, playerName, itemArgs)) {
                    continue;
                }

                String fullCmd = "give " + playerName + " " + itemArgs;
                executeCommand(server, fullCmd, "give");
            }
        }

        if (shouldAutoEquipWearables(variant)) {
            equipWearableItems(player);
        }
        // 记录本次发装标识，用于后续判断职业是否变更
        LAST_EQUIPPED_KEY.put(player.getUUID(), kit.factionId + ":" + kit.id + ":" + variant.id);
    }

    private static void beginEquipmentMutation(Player player) {
        if (player instanceof ServerPlayer) {
            EQUIPMENT_MUTATION_PLAYERS.add(player.getUUID());
        }
    }

    private static void endEquipmentMutation(Player player) {
        if (player instanceof ServerPlayer) {
            EQUIPMENT_MUTATION_PLAYERS.remove(player.getUUID());
        }
    }

    /**
     * 按 JSON 中 equipment/wearable_equipment 指定的槽位直接装备物品。
     */
    private static void equipConfiguredEquipment(
        ServerPlayer player,
        FactionDataLoader.ClassKitData kit,
        FactionDataLoader.ClassVariantData variant,
        MinecraftServer server,
        String playerName
    ) {
        Map<String, String> equipment = collectConfiguredEquipment(variant);
        if (equipment.isEmpty()) return;

        for (Map.Entry<String, String> entry : equipment.entrySet()) {
            String slotName = normalizeEquipmentSlotName(entry.getKey());
            String itemArgs = normalizeItemArgs(entry.getValue());
            if (slotName == null) {
                Espetro.LOGGER.warn("职业 {} 的 equipment 槽位无效: {}", kit.id, entry.getKey());
                continue;
            }
            if (itemArgs.isBlank()) continue;

            String fullCmd = "item replace entity " + playerName + " " + slotName + " with " + itemArgs;
            executeCommand(server, fullCmd, "item replace");
        }

        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
    }

    /**
     * 旧 commands 中的可穿戴物品直接装备到槽位，避免先 /give 到背包后再搬运导致同步不生效。
     */
    private static boolean handleWearableCommand(
        ServerPlayer player,
        MinecraftServer server,
        String playerName,
        String itemArgs
    ) {
        EquipmentSlot slot = getWearableSlotFromItemArgs(itemArgs);
        if (slot == null) {
            return false;
        }
        if (!player.getItemBySlot(slot).isEmpty()) {
            Espetro.LOGGER.debug("跳过重复可穿戴装备: {} -> {}", itemArgs, slot.getName());
            return true;
        }

        String slotName = toCommandSlotName(slot);
        if (slotName == null) {
            return false;
        }

        String fullCmd = "item replace entity " + playerName + " " + slotName + " with " + itemArgs;
        executeCommand(server, fullCmd, "item replace");
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        return true;
    }

    /**
     * 将背包中可穿戴的装备移动到对应装备栏。
     */
    private static void equipWearableItems(ServerPlayer player) {
        boolean changed = false;

        for (int i = 0; i < player.getInventory().items.size(); i++) {
            ItemStack stack = player.getInventory().items.get(i);
            if (stack.isEmpty()) continue;

            EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(stack);
            if (!slot.isArmor() || !player.getItemBySlot(slot).isEmpty()) continue;

            ItemStack wearable = stack.split(1);
            player.setItemSlot(slot, wearable);
            if (stack.isEmpty()) {
                player.getInventory().items.set(i, ItemStack.EMPTY);
            }
            changed = true;
        }

        if (changed) {
            player.getInventory().setChanged();
            player.inventoryMenu.broadcastChanges();
            player.containerMenu.broadcastChanges();
        }
    }

    private static boolean hasConfiguredEquipment(FactionDataLoader.ClassVariantData variant) {
        return variant.equipment != null && !variant.equipment.isEmpty()
            || variant.wearableEquipment != null && !variant.wearableEquipment.isEmpty();
    }

    private static boolean shouldAutoEquipWearables(FactionDataLoader.ClassVariantData variant) {
        return variant.autoEquipWearables == null || variant.autoEquipWearables;
    }

    private static Map<String, String> collectConfiguredEquipment(FactionDataLoader.ClassVariantData variant) {
        Map<String, String> equipment = new LinkedHashMap<>();
        if (variant.equipment != null) {
            equipment.putAll(variant.equipment);
        }
        if (variant.wearableEquipment != null) {
            equipment.putAll(variant.wearableEquipment);
        }
        return equipment;
    }

    static String normalizeEquipmentSlotName(String slotName) {
        if (slotName == null) return null;

        String key = slotName.trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_');

        return switch (key) {
            case "head", "helmet", "armor_head", "armor.head" -> "armor.head";
            case "chest", "chestplate", "body", "armor_chest", "armor.chest" -> "armor.chest";
            case "legs", "leggings", "armor_legs", "armor.legs" -> "armor.legs";
            case "feet", "boots", "armor_feet", "armor.feet" -> "armor.feet";
            case "mainhand", "main_hand", "weapon", "weapon_mainhand", "weapon.mainhand" -> "weapon.mainhand";
            case "offhand", "off_hand", "shield", "weapon_offhand", "weapon.offhand" -> "weapon.offhand";
            default -> null;
        };
    }

    static String toCommandSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "armor.head";
            case CHEST -> "armor.chest";
            case LEGS -> "armor.legs";
            case FEET -> "armor.feet";
            case MAINHAND -> "weapon.mainhand";
            case OFFHAND -> "weapon.offhand";
        };
    }

    /**
     * 根据 equipment/wearable_equipment 中的槽位别名解析出对应的装备槽位。
     * 返回 null 表示槽位名无效。
     */
    static EquipmentSlot resolveEquipmentSlot(String slotName) {
        String normalized = normalizeEquipmentSlotName(slotName);
        if (normalized == null) return null;
        return switch (normalized) {
            case "armor.head" -> EquipmentSlot.HEAD;
            case "armor.chest" -> EquipmentSlot.CHEST;
            case "armor.legs" -> EquipmentSlot.LEGS;
            case "armor.feet" -> EquipmentSlot.FEET;
            case "weapon.mainhand" -> EquipmentSlot.MAINHAND;
            case "weapon.offhand" -> EquipmentSlot.OFFHAND;
            default -> null;
        };
    }

    static EquipmentSlot getWearableSlotFromItemArgs(String itemArgs) {
        if (itemArgs == null || itemArgs.isBlank()) {
            return null;
        }

        String itemId = extractItemId(itemArgs);
        if (itemId.isBlank()) {
            return null;
        }

        ResourceLocation itemLocation = ResourceLocation.tryParse(itemId);
        if (itemLocation == null) {
            Espetro.LOGGER.warn("无法解析装备物品ID: {}", itemId);
            return null;
        }

        Item item = BuiltInRegistries.ITEM.get(itemLocation);
        if (item == net.minecraft.world.item.Items.AIR) {
            return null;
        }

        EquipmentSlot slot = LivingEntity.getEquipmentSlotForItem(new ItemStack(item));
        return slot.isArmor() ? slot : null;
    }

    static String extractItemId(String itemArgs) {
        String trimmed = itemArgs.trim();
        int end = trimmed.length();

        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c) || c == '{' || c == '[') {
                end = i;
                break;
            }
        }
        return trimmed.substring(0, end);
    }

    static String normalizeItemArgs(String args) {
        if (args == null) return "";

        String trimmed = args.trim();
        if (trimmed.regionMatches(true, 0, "with ", 0, 5)) {
            return trimmed.substring(5).trim();
        }
        return trimmed;
    }

    private static void executeCommand(MinecraftServer server, String fullCmd, String commandName) {
        try {
            server.getCommands().performPrefixedCommand(
                server.createCommandSourceStack().withSuppressedOutput(),
                fullCmd
            );
            Espetro.LOGGER.debug("执行: {}", fullCmd);
        } catch (Exception e) {
            Espetro.LOGGER.error("[!] {}指令失败: {}", commandName, fullCmd);
            Espetro.LOGGER.error("[!] 错误: {}", e.getMessage());
        }
    }

    /** Apply the selected class' non-stacking transient attribute modifiers. */
    public static void applyClassBonuses(Player player, FactionDataLoader.ClassKitData kit) {
        applyBonus(player, kit);
    }

    public static void clearClassBonuses(Player player) {
        if (player == null) {
            return;
        }
        LAST_EQUIPPED_KEY.remove(player.getUUID());
        float previousHealth = player.getHealth();
        removeModifier(player.getAttribute(Attributes.MAX_HEALTH), CLASS_HEALTH_BONUS_ID);
        removeModifier(player.getAttribute(Attributes.MOVEMENT_SPEED), CLASS_SPEED_BONUS_ID);
        // Losing maximum health must never heal; only clamp an over-cap current value.
        if (player.getHealth() > player.getMaxHealth()) {
            player.setHealth(Math.min(previousHealth, player.getMaxHealth()));
        }
    }

    private static void applyBonus(Player player, FactionDataLoader.ClassKitData kit) {
        if (player == null || kit == null) {
            return;
        }
        float previousHealth = player.getHealth();
        AttributeInstance maxHealth = player.getAttribute(Attributes.MAX_HEALTH);
        AttributeInstance movementSpeed = player.getAttribute(Attributes.MOVEMENT_SPEED);
        removeModifier(maxHealth, CLASS_HEALTH_BONUS_ID);
        removeModifier(movementSpeed, CLASS_SPEED_BONUS_ID);

        double healthBonus = ClassAttributeBonusPolicy.healthAmount(kit.healthBonus);
        if (maxHealth != null && healthBonus != 0.0D) {
            maxHealth.addTransientModifier(new AttributeModifier(
                CLASS_HEALTH_BONUS_ID,
                "Espetro class health bonus",
                healthBonus,
                AttributeModifier.Operation.ADDITION));
        }
        double speedBonus = ClassAttributeBonusPolicy.speedMultiplier(kit.speedBonus);
        if (movementSpeed != null && speedBonus != 0.0D) {
            movementSpeed.addTransientModifier(new AttributeModifier(
                CLASS_SPEED_BONUS_ID,
                "Espetro class speed bonus",
                speedBonus,
                AttributeModifier.Operation.MULTIPLY_BASE));
        }
        // A class switch is not a heal. Deployment code remains responsible for full health.
        player.setHealth(ClassAttributeBonusPolicy.clampCurrentHealth(
            previousHealth, player.getMaxHealth()));
        Espetro.LOGGER.debug("应用职业属性: {} -> 生命 +{}, 速度 {}%",
            kit.name, healthBonus, speedBonus * 100.0D);
    }

    private static void removeModifier(AttributeInstance attribute, UUID id) {
        if (attribute != null && attribute.getModifier(id) != null) {
            attribute.removeModifier(id);
        }
    }
}
