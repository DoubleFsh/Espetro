package org.espetro.team;

/**
 * 阵营数据Provider
 * Owns the startup-frozen external formation loader.
 */
public class FactionDataProvider {

    private static FactionDataLoader loader;

    public static FactionDataLoader getOrCreateLoader() {
        if (loader == null) {
            loader = new FactionDataLoader();
        }
        return loader;
    }
}
