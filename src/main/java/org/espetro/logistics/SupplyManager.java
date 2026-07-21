package org.espetro.logistics;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public final class SupplyManager {

    public static final String NBT_SUPPLY_TYPE = "EspetroSupplyType";
    public static final String NBT_SUPPLY_ID = "EspetroSupplyId";
    public static final String NBT_SUPPLY_POINTS = "EspetroSupplyPoints";
    public static final String NBT_SOURCE_ID = "EspetroSupplySourceId";

    private static final SupplyManager INSTANCE = new SupplyManager();
    private final Map<UUID, Long> pickupCooldowns = new HashMap<>();

    private SupplyManager() {
    }

    public static SupplyManager getInstance() {
        return INSTANCE;
    }

    public void reset() {
        pickupCooldowns.clear();
    }

    public boolean handleSourceInteraction(ServerPlayer player, ServerLevel level, BlockPos pos) {
        LogisticsConfig.SupplySource source = findSource(player, level, pos);
        if (source == null) {
            return false;
        }

        long cooldownMillis = LogisticsConfig.get().pickupCooldownSeconds * 1000L;
        long now = System.currentTimeMillis();
        Long lastPickup = pickupCooldowns.get(player.getUUID());
        if (lastPickup != null && now - lastPickup < cooldownMillis) {
            long remaining = Math.max(1L, (cooldownMillis - (now - lastPickup) + 999L) / 1000L);
            player.sendSystemMessage(Component.literal("§e补给装载中，" + remaining + " 秒后可再次领取。"));
            return true;
        }

        int construction = giveSupplies(player, source, source.construction, SupplyType.CONSTRUCTION);
        int ammunition = giveSupplies(player, source, source.ammunition, SupplyType.AMMUNITION);
        if (construction + ammunition <= 0) {
            player.sendSystemMessage(Component.literal("§c该补给来源没有可发放的有效物品。"));
            return true;
        }

        pickupCooldowns.put(player.getUUID(), now);
        org.espetro.tutorial.TutorialManager.getInstance().tryShow(
            player, org.espetro.tutorial.TutorialStep.LOGISTICS_SUPPLY);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.sendSystemMessage(Component.literal(
            "§a已领取补给 §7| §6建材 " + construction + " §7| §b弹药 " + ammunition
                + " §7（右击己方 Radio 存入 FOB）"));
        return true;
    }

    /**
     * 只把玩家背包中的 Espetro 补给存入 FOB。
     * 不扫描附近实体或容器，也不会直接读取载具库存。
     */
    public DepositResult depositAll(ServerPlayer player, BastionData bastion) {
        if (!Objects.equals(Espetro.getPlayerTeam(player), bastion.getTeam())) {
            return DepositResult.failure("§c不能向敌方 Radio 存入补给。");
        }
        if (player.distanceToSqr(
            bastion.getPosition().getX() + 0.5,
            bastion.getPosition().getY() + 0.5,
            bastion.getPosition().getZ() + 0.5
        ) > square(LogisticsConfig.get().depositRadius)) {
            return DepositResult.failure("§c距离 Radio 太远。");
        }

        int constructionCapacity = Math.max(0,
            LogisticsConfig.get().maxConstruction - bastion.getConstructionSupplies());
        int ammunitionCapacity = Math.max(0,
            LogisticsConfig.get().maxAmmunition - bastion.getAmmunitionSupplies());
        int construction = 0;
        int ammunition = 0;

        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            SupplyType type = getSupplyType(stack);
            if (type == null || stack.isEmpty()) {
                continue;
            }
            int pointsPerItem = Math.max(1, getPointsPerItem(stack));
            int capacity = type == SupplyType.CONSTRUCTION ? constructionCapacity : ammunitionCapacity;
            if (capacity <= 0) {
                continue;
            }
            int consume = Math.min(stack.getCount(), capacity / pointsPerItem);
            if (consume <= 0) {
                continue;
            }
            int points = consume * pointsPerItem;
            stack.shrink(consume);
            if (type == SupplyType.CONSTRUCTION) {
                construction += points;
                constructionCapacity -= points;
            } else {
                ammunition += points;
                ammunitionCapacity -= points;
            }
        }

        if (construction + ammunition <= 0) {
            return DepositResult.failure("§e背包中没有可存入的 Espetro 补给，或 FOB 库存已满。");
        }

        bastion.addConstructionSupplies(construction, LogisticsConfig.get().maxConstruction);
        bastion.addAmmunitionSupplies(ammunition, LogisticsConfig.get().maxAmmunition);
        BastionManager.getInstance().advanceFobConstruction(bastion);
        org.espetro.tutorial.TutorialManager.getInstance().tryShow(
            player, org.espetro.tutorial.TutorialStep.FOB_SUPPLY);
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return new DepositResult(true, construction, ammunition, null);
    }

    @Nullable
    public LogisticsConfig.SupplySource findSource(ServerPlayer player, ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        BlockEntity blockEntity = level.getBlockEntity(pos);
        String team = Espetro.getPlayerTeam(player);
        for (LogisticsConfig.SupplySource source : LogisticsConfig.get().sources) {
            if (source.team != null && !source.team.isBlank() && !source.team.equalsIgnoreCase(team)) {
                continue;
            }
            if (!matchesBlock(source.blocks, state)) {
                continue;
            }
            if (!matchesLocation(source.locations, level, pos)) {
                continue;
            }
            if (!matchesSourceId(source.sourceIds, blockEntity)) {
                continue;
            }
            if (!matchesBlockEntityNbt(source.blockEntityNbt, blockEntity)) {
                continue;
            }
            return source;
        }
        return null;
    }

    private int giveSupplies(ServerPlayer player, LogisticsConfig.SupplySource source,
                             List<LogisticsConfig.SupplyItem> entries, SupplyType type) {
        int points = 0;
        for (LogisticsConfig.SupplyItem entry : entries) {
            ItemStack stack = createSupplyStack(source, entry, type);
            if (stack.isEmpty()) {
                continue;
            }
            points += stack.getCount() * Math.max(1, entry.pointsPerItem);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
        }
        return points;
    }

    private ItemStack createSupplyStack(LogisticsConfig.SupplySource source,
                                        LogisticsConfig.SupplyItem entry, SupplyType type) {
        if (entry.id == null || entry.id.isBlank()) {
            Espetro.LOGGER.warn("补给来源 {} 含有缺失物品 ID 的 {} 条目", source.id, type.id());
            return ItemStack.EMPTY;
        }
        ResourceLocation itemId = ResourceLocation.tryParse(entry.id);
        if (itemId == null || !BuiltInRegistries.ITEM.containsKey(itemId)) {
            Espetro.LOGGER.warn("无效补给物品: {}", entry.id);
            return ItemStack.EMPTY;
        }
        Item item = BuiltInRegistries.ITEM.get(itemId);
        ItemStack stack = new ItemStack(item, Math.max(1, entry.count));
        if (entry.nbt != null && !entry.nbt.isBlank()) {
            try {
                stack.setTag(TagParser.parseTag(entry.nbt));
            } catch (CommandSyntaxException e) {
                Espetro.LOGGER.warn("补给物品 NBT 无效: {} {}", entry.id, entry.nbt);
                return ItemStack.EMPTY;
            }
        }
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(NBT_SUPPLY_TYPE, type.id());
        tag.putString(NBT_SUPPLY_ID,
            entry.supplyId == null || entry.supplyId.isBlank() ? entry.id : entry.supplyId);
        tag.putInt(NBT_SUPPLY_POINTS, Math.max(1, entry.pointsPerItem));
        tag.putString(NBT_SOURCE_ID, source.id);
        return stack;
    }

    @Nullable
    public SupplyType getSupplyType(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return null;
        }
        return SupplyType.fromId(tag.getString(NBT_SUPPLY_TYPE));
    }

    public int getPointsPerItem(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag != null && tag.contains(NBT_SUPPLY_POINTS)
            ? Math.max(1, tag.getInt(NBT_SUPPLY_POINTS))
            : 1;
    }

    /**
     * 背包中 Espetro 建材补给的总点数（与存入 FOB 的 construction 点数同一体系）。
     */
    public int countConstructionPoints(ServerPlayer player) {
        int total = 0;
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || getSupplyType(stack) != SupplyType.CONSTRUCTION) {
                continue;
            }
            total += stack.getCount() * getPointsPerItem(stack);
        }
        return total;
    }

    /**
     * 按点数从背包扣除 Espetro 建材补给。点数不足时不修改背包并返回 false。
     * 按整件扣减（与存入 FOB 相同）；无法拆分单件点数。
     */
    public boolean consumeConstructionPoints(ServerPlayer player, int points) {
        if (points <= 0) {
            return true;
        }
        if (countConstructionPoints(player) < points) {
            return false;
        }
        int remaining = points;
        for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty() || getSupplyType(stack) != SupplyType.CONSTRUCTION) {
                continue;
            }
            int pointsPerItem = getPointsPerItem(stack);
            int consume = Math.min(stack.getCount(), (remaining + pointsPerItem - 1) / pointsPerItem);
            if (consume <= 0) {
                continue;
            }
            stack.shrink(consume);
            remaining -= consume * pointsPerItem;
        }
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        return true;
    }

    private boolean matchesBlock(List<String> matchers, BlockState state) {
        if (matchers == null || matchers.isEmpty()) {
            return true;
        }
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (String matcher : matchers) {
            if (matcher == null || matcher.isBlank()) continue;
            if (matcher.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(matcher.substring(1));
                if (tagId != null) {
                    TagKey<Block> tag = BlockTags.create(tagId);
                    if (state.is(tag)) return true;
                }
            } else if (matcher.equals(blockId.toString())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesLocation(List<LogisticsConfig.SourceLocation> locations,
                                    ServerLevel level, BlockPos pos) {
        if (locations == null || locations.isEmpty()) {
            return true;
        }
        String dimension = level.dimension().location().toString();
        for (LogisticsConfig.SourceLocation location : locations) {
            if (location.position == null || location.position.length < 3) continue;
            if (location.dimension != null && !location.dimension.isBlank()
                && !location.dimension.equals(dimension)) continue;
            BlockPos configured = new BlockPos(
                location.position[0], location.position[1], location.position[2]);
            if (configured.distSqr(pos) <= square(Math.max(0.5, location.radius))) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSourceId(List<String> sourceIds, @Nullable BlockEntity blockEntity) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return true;
        }
        if (blockEntity == null) {
            return false;
        }
        CompoundTag tag = blockEntity.saveWithFullMetadata();
        String sourceId = tag.getString("source_id");
        return sourceIds.stream().anyMatch(id -> Objects.equals(id, sourceId));
    }

    private boolean matchesBlockEntityNbt(@Nullable String expectedSnbt, @Nullable BlockEntity blockEntity) {
        if (expectedSnbt == null || expectedSnbt.isBlank()) {
            return true;
        }
        if (blockEntity == null) {
            return false;
        }
        try {
            CompoundTag expected = TagParser.parseTag(expectedSnbt);
            return NbtUtils.compareNbt(expected, blockEntity.saveWithFullMetadata(), true);
        } catch (CommandSyntaxException e) {
            Espetro.LOGGER.warn("补给来源 block_entity_nbt 无效: {}", expectedSnbt);
            return false;
        }
    }

    private static double square(double value) {
        return value * value;
    }

    public record DepositResult(boolean success, int construction, int ammunition,
                                @Nullable String error) {
        public static DepositResult failure(String error) {
            return new DepositResult(false, 0, 0, error);
        }
    }
}
