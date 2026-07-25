package org.espetro.api.event;

import net.minecraftforge.eventbus.api.Event;
import org.espetro.team.GamePhase;

/** Server-side notification for integrations that need phase-bound behavior. */
public final class GamePhaseChangedEvent extends Event {
    private final GamePhase previous;
    private final GamePhase current;

    public GamePhaseChangedEvent(GamePhase previous, GamePhase current) {
        this.previous = previous;
        this.current = current;
    }

    public GamePhase previous() {
        return previous;
    }

    public GamePhase current() {
        return current;
    }
}
