package org.espetro.logistics;

/** Pure resupply arithmetic shared by the server transaction and unit tests. */
public final class AmmoResupplyPolicy {

    private AmmoResupplyPolicy() {
    }

    public static int grantCount(int current, int maximum, int perUse) {
        int missing = Math.max(0, Math.max(0, maximum) - Math.max(0, current));
        return Math.min(missing, Math.max(0, perUse));
    }

    public static boolean canAfford(int available, int cost) {
        return Math.max(0, available) >= Math.max(0, cost);
    }
}
