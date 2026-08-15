package org.espetro.team;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.espetro.logistics.resupply.ResupplyItemIdentity;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Golden checks for the migrated EsFactions resupply tables.  Prefers the
 * test-pack authority, then the GPT-produced deployment copy.
 */
class EsFactionsResupplyGoldenTest {

    @Test
    void migratedVariantsHavePerItemCostAndMatchingMagazineEntries() throws Exception {
        Path root = resolveAuthoritativeEsFactions();
        assumeTrue(root != null && Files.isDirectory(root),
            "没有找到权威 EsFactions 目录");

        List<Path> files;
        try (Stream<Path> stream = Files.list(root)) {
            files = stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                .sorted()
                .toList();
        }
        assertFalse(files.isEmpty(), "EsFactions 目录为空: " + root);

        int variants = 0;
        int items = 0;
        int magazines = 0;
        List<String> failures = new ArrayList<>();
        for (Path file : files) {
            JsonObject rootJson = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            if (!rootJson.has("classes") || !rootJson.get("classes").isJsonObject()) continue;
            for (var classEntry : rootJson.getAsJsonObject("classes").entrySet()) {
                if (!classEntry.getValue().isJsonObject()) continue;
                JsonObject kit = classEntry.getValue().getAsJsonObject();
                if (!kit.has("variants") || !kit.get("variants").isJsonObject()) continue;
                for (var variantEntry : kit.getAsJsonObject("variants").entrySet()) {
                    if (!variantEntry.getValue().isJsonObject()) continue;
                    variants++;
                    JsonObject variant = variantEntry.getValue().getAsJsonObject();
                    String path = file.getFileName() + "/" + classEntry.getKey()
                        + "/" + variantEntry.getKey();
                    if (variant.has("resupply") && variant.get("resupply").isJsonObject()
                        && variant.getAsJsonObject("resupply").has("ammo_cost")) {
                        failures.add(path + ": 仍有变体顶层 resupply.ammo_cost");
                    }
                    JsonObject resupply = variant.has("resupply")
                        && variant.get("resupply").isJsonObject()
                        ? variant.getAsJsonObject("resupply") : null;
                    if (resupply == null || !resupply.has("items")
                        || !resupply.get("items").isJsonArray()) {
                        failures.add(path + ": 缺少 resupply.items");
                        continue;
                    }
                    Map<ResupplyItemIdentity.MagazineKey, Integer> configuredMags =
                        new LinkedHashMap<>();
                    for (JsonElement element : resupply.getAsJsonArray("items")) {
                        items++;
                        if (!element.isJsonObject()) {
                            failures.add(path + ": 补给项不是对象");
                            continue;
                        }
                        JsonObject item = element.getAsJsonObject();
                        if (!item.has("ammo_cost") || item.get("ammo_cost").getAsInt() != 1) {
                            failures.add(path + ": 补给项缺少 ammo_cost=1");
                        }
                        if (!item.has("id") || item.get("id").getAsString().isBlank()) {
                            failures.add(path + ": 补给项缺少 id");
                            continue;
                        }
                        String rawId = item.get("id").getAsString();
                        String nbt = item.has("nbt") ? item.get("nbt").getAsString() : null;
                        ResupplyItemIdentity.Configured identity =
                            ResupplyItemIdentity.parse(rawId, nbt);
                        if (identity.registryId().indexOf(' ') >= 0) {
                            failures.add(path + ": id 仍含数量后缀 " + rawId);
                        }
                        if (identity.magazineItem()) {
                            magazines++;
                            if (item.get("count").getAsInt() != 1) {
                                failures.add(path + ": 弹匣 count 必须为 1");
                            }
                            Optional<ResupplyItemIdentity.MagazineKey> key =
                                ResupplyItemIdentity.magazineKey(identity.nbt());
                            if (key.isEmpty()) {
                                failures.add(path + ": 弹匣缺少规范身份 NBT");
                            } else if (configuredMags.put(key.get(), item.get("max").getAsInt())
                                != null) {
                                failures.add(path + ": 重复弹匣身份 " + key.get());
                            }
                        }
                    }
                    Map<ResupplyItemIdentity.MagazineKey, Integer> issued = new LinkedHashMap<>();
                    if (variant.has("commands") && variant.get("commands").isJsonArray()) {
                        for (JsonElement command : variant.getAsJsonArray("commands")) {
                            ResupplyItemIdentity.spareMagazineCommand(command.getAsString())
                                .ifPresent(spare -> issued.merge(spare.key(), spare.count(), Integer::sum));
                        }
                    }
                    for (var issuedEntry : issued.entrySet()) {
                        Integer max = configuredMags.get(issuedEntry.getKey());
                        if (max == null) {
                            failures.add(path + ": 初始备用弹匣没有补给项 " + issuedEntry.getKey());
                        } else if (!max.equals(issuedEntry.getValue())) {
                            failures.add(path + ": 弹匣 max=" + max + " 不等于初始备用 "
                                + issuedEntry.getValue() + " " + issuedEntry.getKey());
                        }
                    }
                    if (classEntry.getKey().toUpperCase(Locale.ROOT).contains("RAIDER")
                        && variantEntry.getKey().contains("红点")) {
                        boolean has5021 = configuredMags.keySet().stream().anyMatch(key ->
                            "58x21_50".equals(key.family()) && key.capacity() == 50);
                        if (!has5021) {
                            failures.add(path + ": Raider 红点备用弹匣应为 58x21_50");
                        }
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertTrue(variants > 0, "没有扫描到任何变体");
        if (files.size() == 5) {
            assertEquals(143, variants, "五份权威编制应变体数为 143");
        }
        assertTrue(items >= variants, "补给项数异常: items=" + items + " variants=" + variants);
        assertTrue(magazines > 0, "没有扫描到弹匣补给项");
    }

    private static Path resolveAuthoritativeEsFactions() {
        String override = System.getProperty("espetro.esfactions");
        if (override != null && !override.isBlank()) return Path.of(override);
        try {
            var resource = EsFactionsResupplyGoldenTest.class.getResource("/esfactions_pack");
            if (resource != null && "file".equals(resource.getProtocol())) {
                Path fromClasspath = Path.of(resource.toURI());
                if (Files.isDirectory(fromClasspath)) return fromClasspath;
            }
        } catch (Exception ignored) {
        }
        Path testResources = Path.of("src/test/resources/esfactions_pack");
        if (Files.isDirectory(testResources)) return testResources;
        Path deployment = Path.of("build/deployment/EsFactions");
        if (Files.isDirectory(deployment)) return deployment;
        Path examples = Path.of("src/main/resources/espetro_examples/EsFactions");
        return Files.isDirectory(examples) ? examples : null;
    }
}
