package org.espetro.client.gui;

import org.espetro.network.GovernanceStatePacket;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.UUID;

/**
 * Client-side cache for commander governance (impeachment / vacancy).
 * Always updated from {@link GovernanceStatePacket}, independent of which screen is open.
 */
public final class ClientGovernanceState {

    private static GovernanceStatePacket latest = new GovernanceStatePacket(java.util.List.of());
    private static long receivedAtMs;

    private ClientGovernanceState() {
    }

    public static void update(GovernanceStatePacket packet) {
        latest = packet == null ? new GovernanceStatePacket(java.util.List.of()) : packet;
        receivedAtMs = System.currentTimeMillis();
    }

    public static void clear() {
        latest = new GovernanceStatePacket(java.util.List.of());
        receivedAtMs = 0L;
    }

    public static GovernanceStatePacket get() {
        return latest;
    }

    public static long getReceivedAtMs() {
        return receivedAtMs;
    }

    @Nullable
    public static GovernanceStatePacket.TeamState forTeam(@Nullable String team) {
        if (team == null) {
            return null;
        }
        for (GovernanceStatePacket.TeamState state : latest.teams) {
            if (team.equals(state.team)) {
                return state;
            }
        }
        return null;
    }

    public static int secondsLeft(GovernanceStatePacket.TeamState state) {
        if (state == null) {
            return 0;
        }
        int elapsed = (int) Math.max(0L, (System.currentTimeMillis() - receivedAtMs) / 1000L);
        return Math.max(0, state.remainingSeconds - elapsed);
    }

    public static int voteCount(GovernanceStatePacket.TeamState state, UUID candidate) {
        if (state == null || candidate == null || state.voteCounts == null) {
            return 0;
        }
        return state.voteCounts.getOrDefault(candidate.toString(), 0);
    }

    public static boolean isMyVote(GovernanceStatePacket.TeamState state, UUID candidate) {
        return state != null && candidate != null && Objects.equals(state.myVote, candidate);
    }
}
