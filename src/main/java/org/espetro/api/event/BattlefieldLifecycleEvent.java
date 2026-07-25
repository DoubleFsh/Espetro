package org.espetro.api.event;

import net.minecraftforge.eventbus.api.Event;
import org.espetro.api.ActiveBattlefieldSnapshot;

/** Events emitted after a battlefield is activated and before its snapshot is discarded. */
public abstract class BattlefieldLifecycleEvent extends Event {
    private final ActiveBattlefieldSnapshot snapshot;

    protected BattlefieldLifecycleEvent(ActiveBattlefieldSnapshot snapshot) {
        this.snapshot = snapshot;
    }

    public ActiveBattlefieldSnapshot snapshot() {
        return snapshot;
    }

    public static final class Activated extends BattlefieldLifecycleEvent {
        public Activated(ActiveBattlefieldSnapshot snapshot) {
            super(snapshot);
        }
    }

    public static final class Cleared extends BattlefieldLifecycleEvent {
        public Cleared(ActiveBattlefieldSnapshot snapshot) {
            super(snapshot);
        }
    }
}
