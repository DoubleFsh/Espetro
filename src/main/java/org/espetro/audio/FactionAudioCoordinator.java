package org.espetro.audio;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.espetro.Espetro;
import org.espetro.network.AudioCuePacket;
import org.espetro.network.NetworkManager;
import org.espetro.team.ClassCountManager;
import org.espetro.team.ClassSelectManager;
import org.espetro.team.FactionDataLoader;
import org.espetro.team.FactionDataProvider;
import org.espetro.team.GamePhase;
import org.espetro.team.GameStateManager;

import java.util.concurrent.ThreadLocalRandom;

/** Resolves each player's selected formation and sends only a small client-local audio cue. */
public final class FactionAudioCoordinator {
    private FactionAudioCoordinator() {
    }

    /** Starts entry audio immediately for all assigned players. */
    public static void broadcastEntry() {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendEntry(player);
        }
    }

    /** Sends entry audio after this player's deploy screen packet has been queued to the connection. */
    public static void sendEntry(ServerPlayer player) {
        String team = playerTeam(player);
        if ("ATTACK".equals(team)) {
            send(player, team, AudioCuePacket.Cue.ENTRY_ATTACK);
        } else if ("DEFEND".equals(team)) {
            send(player, team, AudioCuePacket.Cue.ENTRY_DEFEND);
        }
    }

    /**
     * Sends capture audio to the capturing side and lost-point audio to the opposing side.
     * Accepts either ATTACK/DEFEND or Espetro's scoreboard team IDs.
     */
    public static boolean broadcastCapture(String capturingTeam) {
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return false;
        }
        String normalizedCaptor = AudioCuePolicy.normalizeTeam(capturingTeam);
        MinecraftServer server = Espetro.getServer();
        if (server == null || normalizedCaptor == null) return false;

        int sent = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = playerTeam(player);
            if (team == null) continue;
            AudioCuePacket.Cue cue = normalizedCaptor.equals(team)
                ? AudioCuePacket.Cue.CAPTURED : AudioCuePacket.Cue.LOST;
            if (send(player, team, cue)) sent++;
        }
        return sent > 0;
    }

    /**
     * A previously owned point has reached neutral: its old owner hears the
     * losing warning while the side lowering the flag hears the capturing cue.
     */
    public static boolean broadcastNeutralized(String originalOwnerTeam,
                                               String activeAttackingTeam) {
        if (GameStateManager.getInstance().getCurrentPhase() != GamePhase.BATTLE) {
            return false;
        }
        String owner = AudioCuePolicy.normalizeTeam(originalOwnerTeam);
        String attacker = AudioCuePolicy.resolveNeutralizingTeam(
            originalOwnerTeam, activeAttackingTeam);
        MinecraftServer server = Espetro.getServer();
        if (server == null || owner == null || attacker == null) return false;

        int sent = 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = playerTeam(player);
            if (team == null) continue;
            AudioCuePacket.Cue cue;
            if (owner.equals(team)) {
                cue = AudioCuePacket.Cue.LOSING_POINT;
            } else if (attacker.equals(team)) {
                cue = AudioCuePacket.Cue.CAPTURING_POINT;
            } else {
                continue;
            }
            if (send(player, team, cue)) sent++;
        }
        return sent > 0;
    }

    /** Sends one global 10% easter-egg roll to winners and defeat audio to losers. */
    public static void broadcastRoundResult(String winningTeam) {
        MinecraftServer server = Espetro.getServer();
        if (server == null) return;
        String normalizedWinner = AudioCuePolicy.normalizeTeam(winningTeam);
        if (normalizedWinner == null) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                NetworkManager.sendToPlayer(player,
                    new AudioCuePacket(AudioCuePacket.Cue.STOP, null));
            }
            return;
        }

        boolean easterEgg = AudioCuePolicy.useEasterEgg(
            ThreadLocalRandom.current().nextDouble());
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String team = playerTeam(player);
            if (team == null) continue;
            AudioCuePacket.Cue cue;
            if (normalizedWinner.equals(team)) {
                cue = easterEgg ? AudioCuePacket.Cue.VICTORY_EASTER_EGG
                    : AudioCuePacket.Cue.VICTORY;
            } else {
                cue = AudioCuePacket.Cue.DEFEAT;
            }
            send(player, team, cue);
        }
    }

    private static boolean send(ServerPlayer player, String team, AudioCuePacket.Cue cue) {
        String audioPack = audioPackForTeam(team);
        if (audioPack == null) return false;
        NetworkManager.sendToPlayer(player, new AudioCuePacket(cue, audioPack));
        return true;
    }

    private static String audioPackForTeam(String team) {
        ClassSelectManager selection = ClassSelectManager.getInstance();
        String factionId = "ATTACK".equals(team)
            ? selection.getFinalAttackClass()
            : "DEFEND".equals(team) ? selection.getFinalDefendClass() : null;
        if (factionId == null) return null;
        FactionDataLoader.FactionData faction =
            FactionDataProvider.getOrCreateLoader().getFaction(factionId);
        return faction == null ? null : AudioPackId.normalize(faction.audioPack);
    }

    private static String playerTeam(ServerPlayer player) {
        if (player == null) return null;
        String team = ClassCountManager.getInstance().getPlayerTeam(player.getUUID());
        if (team == null) team = Espetro.getPlayerTeam(player);
        return AudioCuePolicy.normalizeTeam(team);
    }
}
