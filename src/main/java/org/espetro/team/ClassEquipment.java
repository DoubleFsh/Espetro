package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
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

    /**
     * 清空玩家背包及装备栏
     */
    public static void clearEquipment(Player player) {
        beginEquipmentMutation(player);
        try {
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
        applyBonus(player, kit);
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

    private static String normalizeEquipmentSlotName(String slotName) {
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

    private static String toCommandSlotName(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> "armor.head";
            case CHEST -> "armor.chest";
            case LEGS -> "armor.legs";
            case FEET -> "armor.feet";
            case MAINHAND -> "weapon.mainhand";
            case OFFHAND -> "weapon.offhand";
        };
    }

    private static EquipmentSlot getWearableSlotFromItemArgs(String itemArgs) {
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

    private static String extractItemId(String itemArgs) {
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

    private static String normalizeItemArgs(String args) {
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

    /**
     * 应用属性加成（TODO）
     */
    private static void applyBonus(Player player, FactionDataLoader.ClassKitData kit) {
        if (kit.healthBonus > 0) {
            Espetro.LOGGER.debug("应用生命加成: {} -> {}", kit.name, kit.healthBonus);
        }
        if (kit.speedBonus > 0) {
            Espetro.LOGGER.debug("应用速度加成: {} -> {}", kit.name, kit.speedBonus);
        }
    }
}
