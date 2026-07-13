package org.espetro.kubejs;

import dev.latvian.mods.kubejs.KubeJSPlugin;
import dev.latvian.mods.kubejs.script.BindingsEvent;
import dev.latvian.mods.kubejs.script.ScriptType;
import dev.latvian.mods.kubejs.util.ClassFilter;
import org.espetro.Espetro;
import org.espetro.api.EspetroAPI;
import org.espetro.bastion.BastionData;
import org.espetro.bastion.BastionManager;
import org.espetro.config.GameConfig;
import org.espetro.kubejs.commander.CommanderSkillBuilder;
import org.espetro.kubejs.commander.EspetroCommanderSkills;
import org.espetro.kubejs.commander.KubeCommanderSkillDefinition;
import org.espetro.kubejs.commander.KubeCommanderSkillEvent;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassEquipment;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.CommanderSkillManager;
import org.espetro.team.CommanderSkillType;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;
import org.espetro.team.OutpostManager;
import org.espetro.team.SpawnPointConfig;
import org.espetro.team.SquadManager;
import org.espetro.team.TeamPackManager;
import org.espetro.team.TeamManager;
import org.espetro.team.TroopCountManager;
import org.espetro.team.VoteManager;
import org.espetro.network.NetworkManager;
import org.espetro.vehicle.VehicleConfig;
import org.espetro.vehicle.VehicleManager;
import se.mickelus.mutil.MUtilMod;

public class EspetroKubeJSPlugin extends KubeJSPlugin {
    @Override
    public void registerClasses(ScriptType type, ClassFilter filter) {
        filter.allow("org.espetro");
        filter.allow("com.example.espoints");
        filter.allow("se.mickelus.mutil");
    }

    @Override
    public void registerBindings(BindingsEvent event) {
        if (event.getType().isServer()) {
            EspetroCommanderSkills.clearHandlers();
        }

        event.add("Espetro", EspetroKubeJSBindings.class);
        event.add("EspetroAPI", EspetroAPI.class);
        event.add("EspetroMod", Espetro.class);
        event.add("EspetroCommanderSkills", EspetroCommanderSkills.class);
        event.add("EspetroCommanderSkillBuilder", CommanderSkillBuilder.class);
        event.add("EspetroCommanderSkillDefinition", KubeCommanderSkillDefinition.class);
        event.add("EspetroCommanderSkillEvent", KubeCommanderSkillEvent.class);
        event.add("EspetroGameConfig", GameConfig.class);
        event.add("EspetroGamePhase", GamePhase.class);
        event.add("EspetroCommanderSkillType", CommanderSkillType.class);
        event.add("EspetroArtillerySupportRequest", CommanderSkillManager.ArtillerySupportRequest.class);

        event.add("EspetroClassCountManager", ClassCountManager.class);
        event.add("EspetroClassEquipment", ClassEquipment.class);
        event.add("EspetroClassSelectManager", ClassSelectManager.class);
        event.add("EspetroFactionDataLoader", FactionDataLoader.class);
        event.add("EspetroFactionDataProvider", FactionDataProvider.class);
        event.add("EspetroGameStateManager", GameStateManager.class);
        event.add("EspetroNetworkManager", NetworkManager.class);
        event.add("EspetroSquadManager", SquadManager.class);
        event.add("EspetroSpawnPointConfig", SpawnPointConfig.class);
        event.add("EspetroTeamManager", TeamManager.class);
        event.add("EspetroVoteManager", VoteManager.class);
        event.add("EspetroTroopCountManager", TroopCountManager.class);
        event.add("EspetroCommanderSkillManager", CommanderSkillManager.class);
        event.add("EspetroBastionData", BastionData.class);
        event.add("EspetroBastionManager", BastionManager.class);
        event.add("EspetroTeamPackManager", TeamPackManager.class);
        event.add("EspetroOutpostManager", OutpostManager.class);
        event.add("EspetroVehicleConfig", VehicleConfig.class);
        event.add("EspetroVehicleManager", VehicleManager.class);
        event.add("MUtil", MUtilMod.class);
        event.add("MUtilMod", MUtilMod.class);

        addClassIfPresent(event, "ESPointsCommanderScriptAPI", "com.example.espoints.api.ESPointsCommanderScriptAPI");
        addClassIfPresent(event, "ESPointsTacticalMarkerManager", "com.example.espoints.tactical.TacticalMarkerManager");
        addClassIfPresent(event, "ESPointsTacticalMarkerType", "com.example.espoints.tactical.TacticalMarkerType");
        addClassIfPresent(event, "ESPointsTacticalMapJsonConfig", "com.example.espoints.config.TacticalMapJsonConfig");
    }

    private void addClassIfPresent(BindingsEvent event, String binding, String className) {
        try {
            event.add(binding, Class.forName(className));
        } catch (ClassNotFoundException ignored) {
        }
    }
}
