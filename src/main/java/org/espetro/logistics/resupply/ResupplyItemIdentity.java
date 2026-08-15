package org.espetro.logistics.resupply;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared identity for configured resupply items.  Catalog generation, current
 * counts and settlement must all use this function so a missing {@code nbt}
 * matches every tag variant of the item, while an explicit tag matches exactly.
 */
public final class ResupplyItemIdentity {
    private static final Pattern MAGAZINE_FAMILY = Pattern.compile(
        "MagazineFamily\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAGAZINE_AMMO = Pattern.compile(
        "AmmoId\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern MAGAZINE_CAPACITY = Pattern.compile(
        "MaxCapacity\\s*:\\s*(\\d+)");
    private static final Pattern SPARE_MAGAZINE_COMMAND = Pattern.compile(
        "^taczmagazines:magazine\\s*(\\{.*})\\s*(\\d+)?\\s*$",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    private ResupplyItemIdentity() {
    }

    public record Configured(String registryId, @Nullable String nbt, boolean exactTag) {
        public Configured {
            registryId = registryId == null ? "" : registryId.trim();
            if (nbt != null && nbt.isBlank()) nbt = null;
        }

        public boolean magazineItem() {
            return registryId.toLowerCase(Locale.ROOT).startsWith("taczmagazines:");
        }
    }

    public record MagazineKey(String family, String ammoId, int capacity) {
        public MagazineKey {
            family = family == null ? "" : family.trim();
            ammoId = ammoId == null ? "" : ammoId.trim();
            capacity = Math.max(0, capacity);
        }

        public boolean valid() {
            return !family.isBlank() && !ammoId.isBlank() && capacity > 0;
        }
    }

    public static Configured parse(@Nullable String rawId, @Nullable String configuredNbt) {
        String raw = rawId == null ? "" : rawId.trim();
        String nbt = configuredNbt;
        boolean exact = nbt != null && !nbt.isBlank();
        int tagStart = raw.indexOf('{');
        if (tagStart >= 0) {
            if (!exact) nbt = raw.substring(tagStart);
            exact = nbt != null && !nbt.isBlank();
            raw = raw.substring(0, tagStart).trim();
        }
        return new Configured(raw, exact ? nbt : null, exact);
    }

    public static Optional<MagazineKey> magazineKey(@Nullable String nbt) {
        if (nbt == null || nbt.isBlank()) return Optional.empty();
        Matcher family = MAGAZINE_FAMILY.matcher(nbt);
        Matcher ammo = MAGAZINE_AMMO.matcher(nbt);
        Matcher capacity = MAGAZINE_CAPACITY.matcher(nbt);
        if (!family.find() || !ammo.find() || !capacity.find()) return Optional.empty();
        MagazineKey key = new MagazineKey(family.group(1), ammo.group(1),
            Integer.parseInt(capacity.group(1)));
        return key.valid() ? Optional.of(key) : Optional.empty();
    }

    /**
     * Counts a top-level spare magazine command.  Gun-embedded
     * {@code TaCZMag_StoredMagazine} tags are ignored because they are not
     * a separate inventory stack.
     */
    public static Optional<SpareMagazine> spareMagazineCommand(@Nullable String command) {
        if (command == null) return Optional.empty();
        String trimmed = command.trim();
        if (trimmed.isEmpty() || trimmed.contains("TaCZMag_StoredMagazine")) {
            return Optional.empty();
        }
        Matcher matcher = SPARE_MAGAZINE_COMMAND.matcher(trimmed);
        if (!matcher.matches()) return Optional.empty();
        Optional<MagazineKey> key = magazineKey(matcher.group(1));
        if (key.isEmpty()) return Optional.empty();
        int count = 1;
        if (matcher.group(2) != null && !matcher.group(2).isBlank()) {
            count = Math.max(1, Integer.parseInt(matcher.group(2)));
        }
        return Optional.of(new SpareMagazine(key.get(), count));
    }

    public static boolean matchesNormal(boolean exactTag, boolean sameItem, boolean sameTags) {
        return exactTag ? sameItem && sameTags : sameItem;
    }

    public record SpareMagazine(MagazineKey key, int count) {
    }
}
