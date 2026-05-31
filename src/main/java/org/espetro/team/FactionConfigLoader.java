package org.espetro.team;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLPaths;
import org.espetro.Espetro;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 阵营配置加载器
 */
public class FactionConfigLoader {

    private static final Gson GSON = new Gson();

    /**
     * 加载指定阵营的配置
     */
    public static FactionConfig loadFaction(String factionId) {
        try {
            Path configPath = FMLPaths.GAMEDIR.get().resolve("config/espetro/factions/" + factionId + ".json");
            
            if (Files.exists(configPath)) {
                String json = Files.readString(configPath);
                JsonObject jsonObject = GSON.fromJson(json, JsonObject.class);
                
                FactionConfig config = new FactionConfig();
                
                if (jsonObject.has("faction")) {
                    JsonObject faction = jsonObject.getAsJsonObject("faction");
                    config.name = getString(faction, "name", factionId);
                    config.team = getString(faction, "team", "DEFEND");
                    config.icon = getString(faction, "icon", "");
                    config.color = getString(faction, "color", "FFFFFF");
                }
                
                return config;
            }
        } catch (IOException e) {
            Espetro.LOGGER.error("加载阵营配置失败: {}", factionId, e);
        }
        
        return null;
    }

    private static String getString(JsonObject obj, String key, String defaultValue) {
        return obj.has(key) ? obj.get(key).getAsString() : defaultValue;
    }
}
