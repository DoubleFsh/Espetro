package org.espetro.logistics.resupply;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.PacketDistributor;
import org.espetro.Espetro;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.bastion.FobSupplyTracker;
import org.espetro.compat.taczmagazines.MagazineCompat;
import org.espetro.compat.taczmagazines.MagazineCompatProvider;
import org.espetro.logistics.LogisticsConfig;
import org.espetro.network.NetworkManager;
import org.espetro.network.ResupplyCatalogPacket;
import org.espetro.network.ResupplyEntryDeltaPacket;
import org.espetro.network.SelectResupplyEntryPacket;
import org.espetro.network.VehicleSupplyActionPacket;
import org.espetro.team.ClassCountManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Server-owned, short-lived resupply menus (the plan's ResupplyService).
 * Every click resolves the source, class, inventory and balance again; the
 * client never submits an ItemStack, price or maximum.
 */
public final class ResupplySessionManager {
    private static final int MAX_ITEMS = 64;
    private static final int RESULT_CACHE_SIZE = 64;
    private static final int RATE_LIMIT_CACHE_SIZE = 8192;
    private static final long SESSION_TIMEOUT_TICKS = 20L * 30L;
    private static final AtomicLong NEXT_CATALOGUE_REVISION = new AtomicLong(1L);
    private static final Map<UUID, Session> SESSIONS = new HashMap<>();
    private static final LinkedHashMap<ActionKey, Long> LAST_ACTION_TICKS =
        new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ActionKey, Long> eldest) {
                return size() > RATE_LIMIT_CACHE_SIZE;
            }
        };
    private static long lastSweepTick = Long.MIN_VALUE / 2;

    private ResupplySessionManager() {
    }

    public static void open(ServerPlayer player, ResupplySourceRef source) {
        if (player == null || source == null) return;
        ResolvedSource resolved = resolveSource(player, source, null);
        if (resolved == null) {
            player.displayClientMessage(Component.literal("§c补给来源已失效或不属于己方。"), true);
            return;
        }

        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
        String variantId = ClassCountManager.getInstance().getPlayerVariant(player.getUUID());
        FactionDataLoader loader = FactionDataProvider.getOrCreateLoader();
        FactionDataLoader.ClassKitData kit = classId == null ? null : loader.getClassKit(classId);
        FactionDataLoader.ClassVariantData variant = kit == null ? null : kit.getVariant(variantId);
        FactionDataLoader.ResupplyData data = variant == null ? null : variant.resupply;
        if (data == null || data.items == null || data.items.length == 0) {
            player.displayClientMessage(Component.literal("§c当前职业变体没有逐项补给配置。"), true);
            return;
        }

        List<EntrySpec> specs = new ArrayList<>(Math.min(MAX_ITEMS, data.items.length));
        for (int i = 0; i < data.items.length && i < MAX_ITEMS; i++) {
            specs.add(resolveEntry(i, data.items[i], data));
        }
        UUID token = UUID.randomUUID();
        long catalogue = NEXT_CATALOGUE_REVISION.getAndIncrement();
        long now = player.getServer() == null ? 0L : player.getServer().getTickCount();
        Session session = new Session(player.getUUID(), token, catalogue, source,
            resolved.accountId(), classId, variantId == null ? "" : variantId,
            List.copyOf(specs), now);
        SESSIONS.put(player.getUUID(), session);
        sendCatalogue(player, session, resolved);
    }

    public static void select(ServerPlayer player, SelectResupplyEntryPacket packet) {
        Session session = SESSIONS.get(player.getUUID());
        if (session == null || !session.token.equals(packet.token())) {
            sendClosed(player, packet.token(), packet.actionSeq(), "补给会话不存在或已经过期。");
            return;
        }
        synchronized (session) {
            ResupplyEntryDeltaPacket cached = session.results.get(packet.actionSeq());
            if (cached != null) {
                send(player, cached);
                return;
            }
            if (packet.actionSeq() <= session.highestActionSeq) {
                send(player, failure(session, packet.actionSeq(), null,
                    "请求序号已经过期。", false, currentBalance(player, session)));
                return;
            }
            session.highestActionSeq = packet.actionSeq();

            long now = player.getServer() == null ? 0L : player.getServer().getTickCount();
            if (now - session.lastAccessTick > SESSION_TIMEOUT_TICKS
                || packet.catalogRevision() != session.catalogRevision
                || !session.source.equals(packet.source())
                || !sameLoadout(player, session)) {
                SESSIONS.remove(player.getUUID());
                ResupplyEntryDeltaPacket closed = failure(session, packet.actionSeq(), null,
                    "职业、来源或目录已变化，请重新打开补给菜单。", true, 0);
                remember(session, packet.actionSeq(), closed);
                send(player, closed);
                return;
            }
            session.lastAccessTick = now;

            if (packet.entryIndex() < 0 || packet.entryIndex() >= session.entries.size()) {
                ResupplyEntryDeltaPacket result = failure(session, packet.actionSeq(), null,
                    "补给条目不存在。", false, currentBalance(player, session));
                remember(session, packet.actionSeq(), result);
                send(player, result);
                return;
            }

            ResolvedSource source = resolveSource(player, session.source, session.accountId);
            if (source == null) {
                SESSIONS.remove(player.getUUID());
                ResupplyEntryDeltaPacket closed = failure(session, packet.actionSeq(), null,
                    "补给来源已失效、距离过远或不再属于己方。", true, 0);
                remember(session, packet.actionSeq(), closed);
                send(player, closed);
                return;
            }

            EntrySpec spec = session.entries.get(packet.entryIndex());
            ActionKey actionKey = new ActionKey(player.getUUID(), source.accountId(),
                session.classId, session.variantId, spec.index);
            long previousTick = LAST_ACTION_TICKS.getOrDefault(actionKey, Long.MIN_VALUE / 2);
            if (now - previousTick < 1L) {
                ResupplyEntryDeltaPacket result = failure(session, packet.actionSeq(),
                    view(player, source, spec), "操作过快，请稍后重试。", false,
                    source.balance());
                remember(session, packet.actionSeq(), result);
                send(player, result);
                return;
            }
            LAST_ACTION_TICKS.put(actionKey, now);

            TransactionResult transaction = transact(player, source, spec);
            if (transaction.success) session.stateRevision++;
            ResupplyCatalogPacket.Entry entry = view(player, source, spec);
            ResupplyEntryDeltaPacket result = new ResupplyEntryDeltaPacket(session.token,
                packet.actionSeq(), session.stateRevision, source.balance(), transaction.success,
                false, transaction.message, entry);
            remember(session, packet.actionSeq(), result);
            send(player, result);
        }
    }

    public static void close(UUID playerId, UUID token) {
        Session session = SESSIONS.get(playerId);
        if (session != null && session.token.equals(token)) SESSIONS.remove(playerId);
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId == null) return;
        SESSIONS.remove(playerId);
        LAST_ACTION_TICKS.keySet().removeIf(key -> playerId.equals(key.playerId));
    }

    public static void clearAll() {
        SESSIONS.clear();
        LAST_ACTION_TICKS.clear();
        lastSweepTick = Long.MIN_VALUE / 2;
    }

    public static int activeSessionCount() {
        return SESSIONS.size();
    }

    /**
     * Bounded server-tick cleanup.  Besides timeout, this actively invalidates
     * menus when the player changes loadout or the backing source disappears.
     */
    public static void tick(MinecraftServer server) {
        if (server == null || SESSIONS.isEmpty()) return;
        long sweepTick = server.getTickCount();
        if (sweepTick - lastSweepTick < 20L) return;
        lastSweepTick = sweepTick;
        Iterator<Map.Entry<UUID, Session>> iterator = SESSIONS.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Session> entry = iterator.next();
            Session session = entry.getValue();
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            long now = sweepTick;
            boolean expired = player == null
                || now - session.lastAccessTick > SESSION_TIMEOUT_TICKS;
            boolean invalid = !expired && (!sameLoadout(player, session)
                || resolveSource(player, session.source, session.accountId) == null);
            if (!expired && !invalid) continue;
            iterator.remove();
            if (player != null) {
                send(player, new ResupplyEntryDeltaPacket(session.token,
                    session.highestActionSeq, session.stateRevision, 0,
                    false, true, expired ? "补给会话已超时。" : "补给来源或职业已变化。", null));
            }
        }
        LAST_ACTION_TICKS.entrySet().removeIf(entry ->
            sweepTick - entry.getValue() > SESSION_TIMEOUT_TICKS);
    }

    private static void sendCatalogue(ServerPlayer player, Session session,
                                      ResolvedSource source) {
        List<ResupplyCatalogPacket.Entry> entries = new ArrayList<>(session.entries.size());
        for (EntrySpec entry : session.entries) entries.add(view(player, source, entry));
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player),
            new ResupplyCatalogPacket(session.token, session.catalogRevision,
                session.stateRevision, session.source, source.balance(), entries));
    }

    private static EntrySpec resolveEntry(int index, FactionDataLoader.ResupplyItem configured,
                                          FactionDataLoader.ResupplyData parent) {
        if (configured == null || configured.id == null) {
            return EntrySpec.unavailable(index, "", 1, 1, 1, "配置为空");
        }
        ResupplyItemIdentity.Configured identity =
            ResupplyItemIdentity.parse(configured.id, configured.nbt);
        String raw = identity.registryId();
        String nbt = identity.nbt();
        boolean exactTag = identity.exactTag();
        int cost = configured.ammoCost != null ? configured.ammoCost
            : parent.ammoCost != null ? parent.ammoCost
            : LogisticsConfig.get().defaultResupplyAmmoCost;
        int count = Math.max(1, configured.count);
        int max = Math.max(count, configured.max);
        ResourceLocation id = ResourceLocation.tryParse(raw);
        if (id == null) return EntrySpec.unavailable(index, raw, count, max, cost, "物品ID无效");
        Optional<Item> registered = BuiltInRegistries.ITEM.getOptional(id);
        if (registered.isEmpty()) {
            return EntrySpec.unavailable(index, raw, count, max, cost, "所需模组或物品未加载");
        }
        ItemStack template = new ItemStack(registered.get());
        if (nbt != null && !nbt.isBlank()) {
            try {
                CompoundTag parsed = TagParser.parseTag(nbt);
                template.setTag(parsed);
            } catch (CommandSyntaxException error) {
                return EntrySpec.unavailable(index, raw, count, max, cost, "物品NBT无效");
            }
        }
        MagazineCompat magazine = MagazineCompatProvider.get();
        Optional<MagazineCompat.Identity> magazineIdentity = magazine.identity(template);
        if ("taczmagazines".equals(id.getNamespace()) && magazineIdentity.isEmpty()) {
            return EntrySpec.unavailable(index, raw, count, max, cost,
                magazine.available() ? "弹匣身份无效" : "TaCZ Magazines未加载或版本不兼容");
        }
        return new EntrySpec(index, raw, template, exactTag, count, max, Math.max(0, cost),
            magazineIdentity.orElse(null), "");
    }

    private static TransactionResult transact(ServerPlayer player, ResolvedSource source,
                                               EntrySpec spec) {
        if (!spec.unavailableReason.isEmpty()) return TransactionResult.fail(spec.unavailableReason);
        if (source.balance() < spec.ammoCost) return TransactionResult.fail("来源弹药不足");
        if (spec.magazineIdentity != null) return transactMagazine(player, source, spec);
        int current = countNormal(player, spec);
        int wanted = org.espetro.logistics.AmmoResupplyPolicy.grantCount(
            current, spec.max, spec.count);
        int grant = Math.min(wanted, insertionCapacity(player, spec.template));
        if (grant <= 0) return TransactionResult.fail(current >= spec.max ? "已达到补给上限" : "背包没有空间");

        List<ItemStack> before = snapshotInventory(player);
        if (!source.consume(spec.ammoCost)) return TransactionResult.fail("来源弹药刚刚发生变化");
        ItemStack inserted = spec.template.copy();
        inserted.setCount(grant);
        boolean added = player.getInventory().add(inserted) && inserted.isEmpty();
        if (!added) {
            restoreInventory(player, before);
            source.refund(spec.ammoCost);
            return TransactionResult.fail("背包变化导致事务回滚");
        }
        finish(player, source);
        return TransactionResult.ok("已补给 " + spec.template.getHoverName().getString()
            + " ×" + grant + "，消耗 " + spec.ammoCost + " 弹药");
    }

    private static TransactionResult transactMagazine(ServerPlayer player,
                                                       ResolvedSource source, EntrySpec spec) {
        MagazineCompat compat = MagazineCompatProvider.get();
        List<MagazineSlot> matching = matchingMagazines(player, spec, compat);
        int full = matching.stream().filter(MagazineSlot::full)
            .mapToInt(slot -> slot.stack.getCount()).sum();
        if (full >= spec.max) return TransactionResult.fail("满弹匣已达到上限");
        ItemStack replacement = compat.createFull(spec.template);
        if (replacement.isEmpty()) return TransactionResult.fail("无法创建该弹匣");
        MagazineSlot partial = matching.stream().filter(slot -> !slot.full)
            .min(Comparator.comparingInt(MagazineSlot::rounds)
                .thenComparingInt(MagazineSlot::slot)).orElse(null);
        if (partial == null && insertionCapacity(player, replacement) < 1) {
            return TransactionResult.fail("背包没有空间");
        }

        List<ItemStack> before = snapshotInventory(player);
        if (!source.consume(spec.ammoCost)) return TransactionResult.fail("来源弹药刚刚发生变化");
        boolean committed;
        if (partial != null && partial.stack.getCount() == 1) {
            player.getInventory().setItem(partial.slot, replacement.copy());
            committed = true;
        } else {
            if (partial != null) player.getInventory().getItem(partial.slot).shrink(1);
            ItemStack inserted = replacement.copy();
            committed = player.getInventory().add(inserted) && inserted.isEmpty();
        }
        if (!committed) {
            restoreInventory(player, before);
            source.refund(spec.ammoCost);
            return TransactionResult.fail("背包变化导致弹匣事务回滚");
        }
        finish(player, source);
        return TransactionResult.ok(partial == null
            ? "已领取1个满弹匣，消耗 " + spec.ammoCost + " 弹药"
            : "已丢弃最低余弹并替换为满弹匣，消耗 " + spec.ammoCost + " 弹药");
    }

    private static void finish(ServerPlayer player, ResolvedSource source) {
        player.getInventory().setChanged();
        player.inventoryMenu.broadcastChanges();
        player.containerMenu.broadcastChanges();
        BastionManager.getInstance().recordResupply(player.getUUID());
        source.notifyChanged();
    }

    private static ResupplyCatalogPacket.Entry view(ServerPlayer player, ResolvedSource source,
                                                     EntrySpec spec) {
        int current = spec.magazineIdentity == null ? countNormal(player, spec)
            : countFullMagazines(player, spec, MagazineCompatProvider.get());
        String reason = spec.unavailableReason;
        boolean selectable = reason.isEmpty();
        if (selectable && current >= spec.max) {
            selectable = false;
            reason = spec.magazineIdentity == null ? "已达到上限" : "满弹匣已达到上限";
        }
        if (selectable && source.balance() < spec.ammoCost) {
            selectable = false;
            reason = "来源弹药不足";
        }
        if (selectable) {
            if (spec.magazineIdentity == null && insertionCapacity(player, spec.template) <= 0) {
                selectable = false;
                reason = "背包没有空间";
            } else if (spec.magazineIdentity != null
                && !hasReplaceableMagazineOrSpace(player, spec, MagazineCompatProvider.get())) {
                selectable = false;
                reason = "背包没有空间";
            }
        }
        return new ResupplyCatalogPacket.Entry(spec.index, spec.template, spec.configuredId,
            spec.count, spec.max, spec.ammoCost, current, selectable, reason);
    }

    private static int countNormal(ServerPlayer player, EntrySpec spec) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) continue;
            if (ResupplyItemIdentity.matchesNormal(spec.exactTag,
                stack.is(spec.template.getItem()),
                ItemStack.isSameItemSameTags(stack, spec.template))) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int insertionCapacity(ServerPlayer player, ItemStack template) {
        int capacity = 0;
        for (ItemStack stack : player.getInventory().items) {
            if (stack.isEmpty()) capacity += template.getMaxStackSize();
            else if (ItemStack.isSameItemSameTags(stack, template)) {
                capacity += Math.max(0, stack.getMaxStackSize() - stack.getCount());
            }
            if (capacity >= 1_000_000) return 1_000_000;
        }
        return capacity;
    }

    private static int countFullMagazines(ServerPlayer player, EntrySpec spec,
                                          MagazineCompat compat) {
        return matchingMagazines(player, spec, compat).stream()
            .filter(MagazineSlot::full).mapToInt(slot -> slot.stack.getCount()).sum();
    }

    private static boolean hasReplaceableMagazineOrSpace(ServerPlayer player, EntrySpec spec,
                                                         MagazineCompat compat) {
        List<MagazineSlot> matching = matchingMagazines(player, spec, compat);
        if (matching.stream().anyMatch(slot -> !slot.full && slot.stack.getCount() == 1)) return true;
        return insertionCapacity(player, compat.createFull(spec.template)) > 0;
    }

    private static List<MagazineSlot> matchingMagazines(ServerPlayer player, EntrySpec spec,
                                                        MagazineCompat compat) {
        List<MagazineSlot> matches = new ArrayList<>();
        for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
            ItemStack stack = player.getInventory().items.get(slot);
            Optional<MagazineCompat.Identity> identity = compat.identity(stack);
            if (identity.isEmpty() || !identity.get().equals(spec.magazineIdentity)) continue;
            int rounds = compat.ammoCount(stack);
            matches.add(new MagazineSlot(slot, stack, rounds,
                rounds >= spec.magazineIdentity.capacity()));
        }
        return matches;
    }

    private static List<ItemStack> snapshotInventory(ServerPlayer player) {
        return player.getInventory().items.stream().map(ItemStack::copy).toList();
    }

    private static void restoreInventory(ServerPlayer player, List<ItemStack> snapshot) {
        for (int i = 0; i < snapshot.size(); i++) {
            player.getInventory().setItem(i, snapshot.get(i).copy());
        }
        player.getInventory().setChanged();
    }

    private static boolean sameLoadout(ServerPlayer player, Session session) {
        String classId = ClassCountManager.getInstance().getPlayerClass(player.getUUID());
        String variantId = ClassCountManager.getInstance().getPlayerVariant(player.getUUID());
        return session.classId.equals(classId)
            && session.variantId.equals(variantId == null ? "" : variantId);
    }

    private static int currentBalance(ServerPlayer player, Session session) {
        ResolvedSource source = resolveSource(player, session.source, session.accountId);
        return source == null ? 0 : source.balance();
    }

    @Nullable
    private static ResolvedSource resolveSource(ServerPlayer player, ResupplySourceRef ref,
                                                @Nullable UUID expectedAccount) {
        if (ref.kind() == ResupplySourceRef.Kind.MAIN_BASE_AMMO) {
            // 主出生点无限弹药箱：必须仍是部署点旁自动放置的弹药箱（防伪造），
            // 弹药值无限且永不消耗。
            if (!org.espetro.logistics.DeploySupplyStationPlacer
                .isMainBaseAmmoCrate(
                    (net.minecraft.server.level.ServerLevel) player.serverLevel(), ref.blockPos())) {
                return null;
            }
            return new ResolvedSource(new UUID(0L, 1L), () -> Integer.MAX_VALUE,
                amount -> true, amount -> { }, () -> { });
        }
        if (ref.kind() == ResupplySourceRef.Kind.RADIO) {
            BastionData nearby = org.espetro.network.RadioRadialPacket
                .findFriendlyRadioNearby(player, ref.blockPos());
            if (nearby == null || expectedAccount != null
                && !expectedAccount.equals(nearby.getBastionId())) return null;
            return new ResolvedSource(nearby.getBastionId(), nearby::getAmmunitionSupplies,
                nearby::consumeAmmunitionSupplies,
                amount -> nearby.addAmmunitionSupplies(amount,
                    LogisticsConfig.get().maxAmmunition),
                () -> FobSupplyTracker.notifySupplyChanged(nearby));
        }
        VehicleSupplyActionPacket.Interaction interaction =
            VehicleSupplyActionPacket.resolveInteraction(player, ref.entityId());
        if (interaction == null || expectedAccount != null
            && !expectedAccount.equals(interaction.vehicleId())) return null;
        return new ResolvedSource(interaction.vehicleId(), interaction.supply()::getAmmo,
            amount -> interaction.supply().removeAmmo(amount) == amount,
            amount -> interaction.supply().addAmmo(amount), () -> { });
    }

    private static ResupplyEntryDeltaPacket failure(Session session, long seq,
                                                     @Nullable ResupplyCatalogPacket.Entry entry,
                                                     String message, boolean close, int balance) {
        return new ResupplyEntryDeltaPacket(session.token, seq, session.stateRevision,
            Math.max(0, balance), false, close, message, entry);
    }

    private static void sendClosed(ServerPlayer player, UUID token, long seq, String message) {
        send(player, new ResupplyEntryDeltaPacket(token, seq, 0L, 0,
            false, true, message, null));
    }

    private static void remember(Session session, long seq, ResupplyEntryDeltaPacket result) {
        session.results.put(seq, result);
    }

    private static void send(ServerPlayer player, ResupplyEntryDeltaPacket packet) {
        NetworkManager.NET.send(PacketDistributor.PLAYER.with(() -> player), packet);
    }

    private record EntrySpec(int index, String configuredId, ItemStack template,
                             boolean exactTag, int count, int max, int ammoCost,
                             @Nullable MagazineCompat.Identity magazineIdentity,
                             String unavailableReason) {
        static EntrySpec unavailable(int index, String id, int count, int max, int cost,
                                     String reason) {
            return new EntrySpec(index, id, ItemStack.EMPTY, false, count, max,
                Math.max(0, cost), null, reason);
        }
    }

    private record MagazineSlot(int slot, ItemStack stack, int rounds, boolean full) {
    }

    private record ActionKey(UUID playerId, UUID accountId, String classId,
                             String variantId, int entryIndex) {
    }

    private record TransactionResult(boolean success, String message) {
        static TransactionResult ok(String message) {
            return new TransactionResult(true, message);
        }

        static TransactionResult fail(String message) {
            return new TransactionResult(false, message);
        }
    }

    @FunctionalInterface
    private interface IntValue {
        int get();
    }

    @FunctionalInterface
    private interface IntAction {
        boolean apply(int amount);
    }

    @FunctionalInterface
    private interface RefundAction {
        void apply(int amount);
    }

    private record ResolvedSource(UUID accountId, IntValue balanceValue,
                                  IntAction consumeAction, RefundAction refundAction,
                                  Runnable changeNotifier) {
        int balance() {
            return Math.max(0, balanceValue.get());
        }

        boolean consume(int amount) {
            return amount == 0 || consumeAction.apply(amount);
        }

        void refund(int amount) {
            if (amount > 0) refundAction.apply(amount);
        }

        void notifyChanged() {
            changeNotifier.run();
        }
    }

    private static final class Session {
        private final UUID playerId;
        private final UUID token;
        private final long catalogRevision;
        private long stateRevision = 1L;
        private final ResupplySourceRef source;
        private final UUID accountId;
        private final String classId;
        private final String variantId;
        private final List<EntrySpec> entries;
        private long lastAccessTick;
        private long highestActionSeq;
        private final LinkedHashMap<Long, ResupplyEntryDeltaPacket> results =
            new LinkedHashMap<>(RESULT_CACHE_SIZE + 1, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(
                        Map.Entry<Long, ResupplyEntryDeltaPacket> eldest) {
                    return size() > RESULT_CACHE_SIZE;
                }
            };

        private Session(UUID playerId, UUID token, long catalogRevision,
                        ResupplySourceRef source, UUID accountId, String classId,
                        String variantId, List<EntrySpec> entries, long lastAccessTick) {
            this.playerId = playerId;
            this.token = token;
            this.catalogRevision = catalogRevision;
            this.source = source;
            this.accountId = accountId;
            this.classId = classId;
            this.variantId = variantId;
            this.entries = entries;
            this.lastAccessTick = lastAccessTick;
        }
    }
}
