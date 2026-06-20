package org.espetro.client;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.Team;
import net.minecraftforge.client.event.RenderNameTagEvent;
import net.minecraftforge.eventbus.api.Event;
import org.espetro.client.gui.ClientGameState;
import org.espetro.client.gui.ClientTacticalState;
import org.espetro.team.TeamManager;

/**
 * 按战术规则控制队友头顶名牌显示。
 */
public final class TeammateNameTagRenderer {

    private TeammateNameTagRenderer() {
    }

    public static void onRenderNameTag(RenderNameTagEvent event) {
        if (!(event.getEntity() instanceof Player target)) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        Player viewer = mc.player;
        if (viewer == null || target == viewer || !isFriendlyTeammate(viewer, target)
            || !isLookedAtWithinDistance(viewer, target, event.getPartialTick())) {
            event.setResult(Event.Result.DENY);
            return;
        }

        String name = target.getName().getString();
        int color = ClientTacticalState.getNameColor(name) & 0xFFFFFF;
        event.setContent(Component.literal(name)
            .setStyle(Style.EMPTY.withColor(TextColor.fromRgb(color))));
        event.setResult(Event.Result.ALLOW);
    }

    private static boolean isFriendlyTeammate(Player viewer, Player target) {
        Team viewerTeam = viewer.getTeam();
        Team targetTeam = target.getTeam();
        String viewerTeamId = viewerTeam != null ? viewerTeam.getName() : null;
        String targetTeamId = targetTeam != null ? targetTeam.getName() : null;

        if (isEspetroTeam(viewerTeamId)) {
            return viewerTeamId.equals(targetTeamId);
        }

        String localTeam = ClientGameState.getPlayerTeam();
        if (localTeam == null || targetTeam == null) {
            return false;
        }

        return ("ATTACK".equals(localTeam) && TeamManager.ATTACK_TEAM_ID.equals(targetTeam.getName()))
            || ("DEFEND".equals(localTeam) && TeamManager.DEFEND_TEAM_ID.equals(targetTeam.getName()));
    }

    private static boolean isEspetroTeam(String teamId) {
        return TeamManager.ATTACK_TEAM_ID.equals(teamId) || TeamManager.DEFEND_TEAM_ID.equals(teamId);
    }

    private static boolean isLookedAtWithinDistance(Player viewer, Player target, float partialTick) {
        double maxDistance = ClientTacticalState.getTeammateNameTagDistance();
        if (viewer.distanceToSqr(target) > maxDistance * maxDistance) {
            return false;
        }
        if (!viewer.hasLineOfSight(target)) {
            return false;
        }

        Vec3 eye = viewer.getEyePosition(partialTick);
        Vec3 look = viewer.getViewVector(partialTick).normalize();
        Vec3 end = eye.add(look.scale(maxDistance));
        AABB targetBox = target.getBoundingBox().inflate(0.45);
        return targetBox.clip(eye, end).isPresent();
    }
}
