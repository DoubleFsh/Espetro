package org.espetro.client.gui;

/**
 * 兵力统计缓存：由网络包 / 阵营变更驱动。
 * 世界 HUD 不再绘制；仅 {@link UnifiedDeployScreen} 在右上角倒计时下方读取缓存展示。
 */
public final class TroopCountOverlay {

    private static int attackTroops;
    private static int defendTroops;
    private static boolean visible;
    private static String cachedTeam;
    private static String displayLine;

    private TroopCountOverlay() {
    }

    public static void updateTroopCounts(int attack, int defend) {
        attackTroops = Math.max(0, attack);
        defendTroops = Math.max(0, defend);
        visible = true;
        if (cachedTeam == null) {
            cachedTeam = ClientGameState.getPlayerTeam();
        }
        rebuildDisplay();
        notifyDeployScreen();
    }

    public static void hide() {
        visible = false;
        displayLine = null;
        notifyDeployScreen();
    }

    public static void show() {
        visible = true;
        rebuildDisplay();
        notifyDeployScreen();
    }

    public static void onTeamChanged(String team) {
        cachedTeam = team;
        rebuildDisplay();
        notifyDeployScreen();
    }

    /** 供 UnifiedDeployScreen 右上角展示；null 表示不显示。 */
    public static String getDisplayLine() {
        return displayLine;
    }

    public static boolean isVisible() {
        return visible && displayLine != null;
    }

    private static void rebuildDisplay() {
        if (!visible) {
            displayLine = null;
            return;
        }
        String team = cachedTeam;
        if (team == null || team.isBlank()) {
            displayLine = null;
            return;
        }
        boolean attack = "ATTACK".equals(team);
        boolean defend = "DEFEND".equals(team);
        if (!attack && !defend) {
            displayLine = null;
            return;
        }
        int troops = attack ? attackTroops : defendTroops;
        String label = attack ? "§c■ 进攻方" : "§9■ 防守方";
        String color = troops > 50 ? "§a" : (troops > 20 ? "§e" : "§c");
        displayLine = label + ": " + color + troops;
    }

    private static void notifyDeployScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc != null && mc.screen instanceof UnifiedDeployScreen screen) {
            screen.updateTroopLabel(displayLine);
        }
    }
}
