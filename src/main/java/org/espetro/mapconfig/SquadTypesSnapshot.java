package org.espetro.mapconfig;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * SquadTypes.json snapshot. System always provides category "none" ("无").
 */
public final class SquadTypesSnapshot {

    public static final String NONE_ID = "none";
    public static final String NONE_DISPLAY = "无";

    public record Category(String id, String displayName) {
        public String firstDisplayCodePoint() {
            if (NONE_ID.equals(id) || displayName == null || displayName.isEmpty()) {
                return "";
            }
            return new String(Character.toChars(displayName.codePointAt(0)));
        }
    }

    public final List<Category> categories;
    public final List<String> errors;

    public SquadTypesSnapshot(List<Category> categories, List<String> errors) {
        this.categories = List.copyOf(categories);
        this.errors = List.copyOf(errors);
    }

    public boolean isValid() {
        return errors.isEmpty();
    }

    public Category find(String id) {
        if (id == null || id.isBlank() || NONE_ID.equals(id)) {
            return new Category(NONE_ID, NONE_DISPLAY);
        }
        for (Category c : categories) {
            if (c.id.equals(id)) {
                return c;
            }
        }
        return null;
    }

    public static SquadTypesSnapshot parse(JsonObject root) {
        List<String> errors = new ArrayList<>();
        List<Category> list = new ArrayList<>();
        list.add(new Category(NONE_ID, NONE_DISPLAY));
        Set<String> seen = new LinkedHashSet<>();
        seen.add(NONE_ID);

        if (!root.has("types") || !root.get("types").isJsonArray()) {
            errors.add("SquadTypes.json 缺少 types 数组");
            return new SquadTypesSnapshot(list, errors);
        }
        JsonArray arr = root.getAsJsonArray("types");
        for (JsonElement el : arr) {
            if (!el.isJsonObject()) {
                errors.add("types 元素必须是对象");
                continue;
            }
            JsonObject o = el.getAsJsonObject();
            if (!o.has("id") || !o.get("id").isJsonPrimitive()) {
                errors.add("小队类别缺少 id");
                continue;
            }
            String id = o.get("id").getAsString().trim().toLowerCase(Locale.ROOT);
            if (!id.matches("[a-z0-9_]+")) {
                errors.add("小队类别 id 非法: " + id);
                continue;
            }
            if (!seen.add(id)) {
                errors.add("重复的小队类别 id: " + id);
                continue;
            }
            String display = o.has("display_name") && o.get("display_name").isJsonPrimitive()
                ? o.get("display_name").getAsString()
                : id;
            if (NONE_ID.equals(id)) {
                continue; // system-provided
            }
            list.add(new Category(id, display));
        }
        return new SquadTypesSnapshot(list, errors);
    }

    public static SquadTypesSnapshot defaults() {
        List<Category> list = List.of(
            new Category(NONE_ID, NONE_DISPLAY),
            new Category("infantry", "步兵队"),
            new Category("support", "支援队"),
            new Category("vehicle", "载具队"),
            new Category("recon", "侦查队")
        );
        return new SquadTypesSnapshot(list, List.of());
    }
}
