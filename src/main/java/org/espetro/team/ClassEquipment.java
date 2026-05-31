package org.espetro.team;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.espetro.Espetro;

/**
 * 职业装备管理器 - 使用MC原版 /give 指令分发所有装备
 * <p>
 * JSON中每个职业配置一个 commands 字符串数组，每个元素是 /give 命令的参数部分。
 * 代码自动拼接 "give &lt;玩家名&gt; " 前缀后执行。
 * <p>
 * 所有物品（含护甲）均发放到背包，不自动穿戴。
 */
public class ClassEquipment {

    /**
     * 清空玩家背包及装备栏
     */
    public static void clearEquipment(Player player) {
        player.getInventory().clearContent();
        player.setItemSlot(EquipmentSlot.HEAD, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.CHEST, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        player.setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
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
        if (kit != null) {
            equipFromKit(sp, kit);
        } else {
            Espetro.LOGGER.warn("未找到职业配置: {}", classId);
        }
    }

    /**
     * 根据阵营和职业给予玩家装备（通过 /give 指令）
     */
    public static void equipPlayer(Player player, String factionId, String classId) {
        equipPlayer(player, classId);
    }

    /**
     * 从命令数组执行所有 /give 指令
     */
    private static void equipFromKit(ServerPlayer player, FactionDataLoader.ClassKitData kit) {
        // 先清空背包
        player.getInventory().clearContent();

        if (kit.commands == null || kit.commands.length == 0) {
            Espetro.LOGGER.warn("职业 {} 无 commands 配置", kit.id);
            return;
        }

        MinecraftServer server = player.getServer();
        if (server == null) return;

        String playerName = player.getName().getString();

        for (String args : kit.commands) {
            if (args == null || args.isBlank()) continue;
            String fullCmd = "give " + playerName + " " + args.trim();
            try {
                server.getCommands().performPrefixedCommand(
                    server.createCommandSourceStack(),
                    fullCmd
                );
                Espetro.LOGGER.debug("执行: {}", fullCmd);
            } catch (Exception e) {
                Espetro.LOGGER.error("[!] give指令失败: {}", fullCmd);
                Espetro.LOGGER.error("[!] 错误: {}", e.getMessage());
            }
        }

        applyBonus(player, kit);
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
