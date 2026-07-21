package org.espetro.logistics;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.espetro.Espetro;
import org.espetro.data.EspetroDataResources;

import javax.annotation.Nullable;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class LogisticsConfig {

    private static final Gson GSON = new Gson();
    private static LogisticsSettings settings = new LogisticsSettings();

    private LogisticsConfig() {
    }

    public static LogisticsSettings get() {
        return settings;
    }

    public static void load(MinecraftServer server) {
        LogisticsSettings loaded = new LogisticsSettings();
        ResourceLocation location = ResourceLocation.fromNamespaceAndPath(Espetro.MOD_ID, "config/logistics.json");
        try {
            var resource = EspetroDataResources.getPreferred(server.getResourceManager(), location);
            if (resource.isPresent()) {
                try (var reader = new InputStreamReader(resource.get().open(), StandardCharsets.UTF_8)) {
                    JsonObject root = GSON.fromJson(reader, JsonObject.class);
                    if (root != null && root.has("logistics")) {
                        loaded = GSON.fromJson(root.get("logistics"), LogisticsSettings.class);
                    }
                }
            }
        } catch (Exception e) {
            Espetro.LOGGER.error("加载 logistics.json 失败，使用默认配置", e);
        }
        loaded.normalize();
        settings = loaded;
        Espetro.LOGGER.info("后勤配置已加载: {} 个补给来源, FOB上限 {}/{}",
            loaded.sources.size(), loaded.maxConstruction, loaded.maxAmmunition);
    }

    public static class LogisticsSettings {
        @SerializedName("max_construction")
        public int maxConstruction = 20_000;
        @SerializedName("max_ammunition")
        public int maxAmmunition = 20_000;
        @SerializedName("pickup_cooldown_seconds")
        public int pickupCooldownSeconds = 5;
        @SerializedName("deposit_radius")
        public double depositRadius = 8.0;
        @SerializedName("radio_build_radius")
        public double radioBuildRadius = 150.0;
        @SerializedName("radio_exclusion_radius")
        public double radioExclusionRadius = 400.0;
        @SerializedName("radio_teammate_radius")
        public double radioTeammateRadius = 30.0;
        @SerializedName("radio_teammate_count")
        public int radioTeammateCount = 1;
        @SerializedName("require_teammate")
        public boolean requireTeammate = true;
        /** Radio 放置规则；未配置时由旧的 radio_* 平铺字段生成等价配置。 */
        public RadioPlacementSettings radio;
        @SerializedName("hab_construction_cost")
        public int habConstructionCost = 500;
        @SerializedName("ammo_crate_construction_cost")
        public int ammoCrateConstructionCost = 100;
        @SerializedName("default_resupply_ammo_cost")
        public int defaultResupplyAmmoCost = 50;
        @SerializedName("hab_activation_seconds")
        public int habActivationSeconds = 30;
        @SerializedName("hab_reactivation_seconds")
        public int habReactivationSeconds = 30;
        @SerializedName("hab_disable_radio_health")
        public int habDisableRadioHealth = 75;
        public List<SupplySource> sources = new ArrayList<>();

        private void normalize() {
            maxConstruction = Math.max(0, maxConstruction);
            maxAmmunition = Math.max(0, maxAmmunition);
            pickupCooldownSeconds = Math.max(0, pickupCooldownSeconds);
            depositRadius = Math.max(1.0, depositRadius);
            radioBuildRadius = Math.max(1.0, radioBuildRadius);
            radioExclusionRadius = Math.max(0.0, radioExclusionRadius);
            radioTeammateRadius = Math.max(0.0, radioTeammateRadius);
            radioTeammateCount = requireTeammate ? Math.max(0, radioTeammateCount) : 0;
            if (radio == null) {
                radio = RadioPlacementSettings.fromLegacy(this);
            }
            radio.normalize();
            // 保持旧 Java 调用和 KubeJS 字段读取到当前生效值。
            radioBuildRadius = radio.buildRadius;
            radioExclusionRadius = radio.exclusionRadius;
            radioTeammateRadius = radio.teammateRadius;
            radioTeammateCount = radio.teammateCount;
            requireTeammate = radio.teammateCount > 0;
            habConstructionCost = Math.max(0, habConstructionCost);
            ammoCrateConstructionCost = Math.max(0, ammoCrateConstructionCost);
            defaultResupplyAmmoCost = Math.max(0, defaultResupplyAmmoCost);
            habActivationSeconds = Math.max(0, habActivationSeconds);
            habReactivationSeconds = Math.max(0, habReactivationSeconds);
            if (sources == null) {
                sources = new ArrayList<>();
            }
            sources.removeIf(java.util.Objects::isNull);
            for (SupplySource source : sources) {
                source.normalize();
            }
        }

        public RadioPlacementSettings getRadio() {
            if (radio == null) {
                radio = RadioPlacementSettings.fromLegacy(this);
                radio.normalize();
            }
            return radio;
        }
    }

    /** Radio 建立条件。cooldown/required_planks/max_active_per_team 为 -1 时回退 bastion.json。 */
    public static class RadioPlacementSettings {
        @SerializedName("allowed_phases")
        public List<String> allowedPhases = new ArrayList<>(List.of("BATTLE"));
        @SerializedName("require_commander")
        public boolean requireCommander = false;
        @SerializedName("allow_squad_leader")
        public boolean allowSquadLeader = true;
        @SerializedName("cooldown_seconds")
        public int cooldownSeconds = -1;
        @SerializedName("required_planks")
        public int requiredPlanks = -1;
        @SerializedName("creative_bypasses_planks")
        public boolean creativeBypassesPlanks = true;
        @SerializedName("max_active_per_team")
        public int maxActivePerTeam = -1;
        @SerializedName("build_radius")
        public double buildRadius = 150.0;
        @SerializedName("require_target_block")
        public boolean requireTargetBlock = false;
        @SerializedName("exclusion_radius")
        public double exclusionRadius = 400.0;
        @SerializedName("teammate_count")
        public int teammateCount = 1;
        @SerializedName("teammate_radius")
        public double teammateRadius = 30.0;

        private static RadioPlacementSettings fromLegacy(LogisticsSettings legacy) {
            RadioPlacementSettings result = new RadioPlacementSettings();
            result.buildRadius = legacy.radioBuildRadius;
            result.exclusionRadius = legacy.radioExclusionRadius;
            result.teammateRadius = legacy.radioTeammateRadius;
            result.teammateCount = legacy.radioTeammateCount;
            return result;
        }

        private void normalize() {
            if (allowedPhases == null) {
                allowedPhases = new ArrayList<>(List.of("BATTLE"));
            } else {
                allowedPhases.removeIf(phase -> phase == null || phase.isBlank());
                allowedPhases.replaceAll(phase -> phase.trim().toUpperCase(java.util.Locale.ROOT));
            }
            cooldownSeconds = Math.max(-1, cooldownSeconds);
            requiredPlanks = Math.max(-1, requiredPlanks);
            maxActivePerTeam = Math.max(-1, maxActivePerTeam);
            buildRadius = Math.max(0.0, buildRadius);
            exclusionRadius = Math.max(0.0, exclusionRadius);
            teammateCount = Math.max(0, teammateCount);
            teammateRadius = Math.max(0.0, teammateRadius);
        }

        public boolean allowsPhase(String phaseName) {
            return phaseName != null && allowedPhases.contains(phaseName.toUpperCase(java.util.Locale.ROOT));
        }
    }

    public static class SupplySource {
        public String id = "default";
        public String team;
        public List<String> blocks = new ArrayList<>();
        @SerializedName("source_ids")
        public List<String> sourceIds = new ArrayList<>();
        @SerializedName("block_entity_nbt")
        public String blockEntityNbt;
        public List<SourceLocation> locations = new ArrayList<>();
        public List<SupplyItem> construction = new ArrayList<>();
        public List<SupplyItem> ammunition = new ArrayList<>();

        private void normalize() {
            if (id == null || id.isBlank()) id = "default";
            if (blocks == null) blocks = new ArrayList<>();
            if (sourceIds == null) sourceIds = new ArrayList<>();
            if (locations == null) locations = new ArrayList<>();
            if (construction == null) construction = new ArrayList<>();
            if (ammunition == null) ammunition = new ArrayList<>();
            locations.removeIf(java.util.Objects::isNull);
            construction.removeIf(java.util.Objects::isNull);
            ammunition.removeIf(java.util.Objects::isNull);
            construction.forEach(SupplyItem::normalize);
            ammunition.forEach(SupplyItem::normalize);
        }
    }

    public static class SourceLocation {
        public String dimension;
        public int[] position;
        public double radius = 0.5;
    }

    public static class SupplyItem {
        public String id;
        public String nbt;
        public int count = 1;
        @SerializedName("points_per_item")
        public int pointsPerItem = 1;
        @SerializedName("supply_id")
        public String supplyId;

        private void normalize() {
            count = Math.max(1, count);
            pointsPerItem = Math.max(1, pointsPerItem);
        }
    }
}
