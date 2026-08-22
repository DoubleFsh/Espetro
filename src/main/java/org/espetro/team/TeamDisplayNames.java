package org.espetro.team;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.espetro.mapconfig.BattlefieldContext;

/**
 * Player-facing ATTACK/DEFEND labels.
 * <p>
 * Team ids stay {@code ATTACK}/{@code DEFEND}. RAAS is a symmetric mode and
 * shows 阵营A/阵营B; AAS keeps 进攻方/防守方.
 * <p>
 * Server and integrated-server logic read {@link BattlefieldContext#getObjectiveMode()}.
 * Remote clients fall back to {@code ClientGameState.getObjectiveMode()} when
 * the battlefield context is empty in that JVM.
 */
public final class TeamDisplayNames {

    private TeamDisplayNames() {
    }

    public static boolean isSymmetricMode() {
        return isSymmetricMode(resolveObjectiveMode());
    }

    public static boolean isSymmetricMode(String objectiveMode) {
        return objectiveMode != null && "RAAS".equalsIgnoreCase(objectiveMode.trim());
    }

    public static String displayName(String team) {
        return displayName(team, isSymmetricMode());
    }

    public static String displayName(String team, boolean symmetric) {
        if (isAttack(team)) {
            return symmetric ? "阵营A" : "进攻方";
        }
        return symmetric ? "阵营B" : "防守方";
    }

    public static String shortLabel(String team) {
        return shortLabel(team, isSymmetricMode());
    }

    public static String shortLabel(String team, boolean symmetric) {
        if (isAttack(team)) {
            return symmetric ? "A" : "进攻";
        }
        return symmetric ? "B" : "防守";
    }

    public static String prefix(String team) {
        return isAttack(team) ? "\u00a7c" : "\u00a79";
    }

    public static String coloredDisplayName(String team) {
        return prefix(team) + displayName(team);
    }

    static String resolveObjectiveMode() {
        String serverMode = BattlefieldContext.getObjectiveMode();
        if (serverMode != null && !serverMode.isBlank()) {
            return serverMode;
        }
        String clientMode = clientObjectiveMode();
        return clientMode == null ? "" : clientMode;
    }

    private static boolean isAttack(String team) {
        return team != null && "ATTACK".equalsIgnoreCase(team.trim());
    }

    private static String clientObjectiveMode() {
        try {
            return DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> ClientMode::get);
        } catch (Throwable ignored) {
            return "";
        }
    }

    private static final class ClientMode {
        private ClientMode() {
        }

        static String get() {
            String mode = org.espetro.client.gui.ClientGameState.getObjectiveMode();
            return mode == null ? "" : mode;
        }
    }
}
