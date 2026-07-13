package org.espetro.kubejs.commander;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.script.CommanderScriptAPI;
import org.espetro.script.CommanderScriptEvent;
import org.espetro.script.CommanderScriptManager;
import org.espetro.team.CommanderSkillManager;

import javax.annotation.Nullable;

public class KubeCommanderSkillEvent extends CommanderScriptEvent {
    private final KubeCommanderSkillDefinition definition;
    @Nullable
    private final CommanderSkillManager.ArtillerySupportRequest request;
    private final CommanderScriptAPI effects;

    public KubeCommanderSkillEvent(KubeCommanderSkillDefinition definition,
                                   ServerPlayer commander,
                                   String team) {
        super(definition.id(), commander, team);
        this.definition = definition;
        this.request = null;
        this.effects = new CommanderScriptAPI(CommanderScriptManager.getInstance(), this);
    }

    public KubeCommanderSkillEvent(KubeCommanderSkillDefinition definition,
                                   CommanderSkillManager.ArtillerySupportRequest request,
                                   ServerPlayer commander,
                                   ServerLevel level,
                                   BlockPos blockPos) {
        super(definition.id(), commander, request.team(), level, request.x(), request.y(), request.z(), blockPos, true);
        this.definition = definition;
        this.request = request;
        this.effects = new CommanderScriptAPI(CommanderScriptManager.getInstance(), this);
    }

    public KubeCommanderSkillDefinition getDefinition() {
        return definition;
    }

    public KubeCommanderSkillDefinition definition() {
        return definition;
    }

    @Nullable
    public CommanderSkillManager.ArtillerySupportRequest getRequest() {
        return request;
    }

    @Nullable
    public CommanderSkillManager.ArtillerySupportRequest request() {
        return request;
    }

    public CommanderScriptAPI getEffects() {
        return effects;
    }

    public CommanderScriptAPI effects() {
        return effects;
    }

    public CommanderScriptAPI getApi() {
        return effects;
    }
}
