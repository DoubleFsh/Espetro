package org.espetro.compat.taczmagazines;

import net.minecraftforge.fml.ModList;
import org.espetro.Espetro;

/** Lazy, one-time optional-mod linkage. */
public final class MagazineCompatProvider {
    private static volatile MagazineCompat instance;

    private MagazineCompatProvider() {
    }

    public static MagazineCompat get() {
        MagazineCompat current = instance;
        if (current != null) return current;
        synchronized (MagazineCompatProvider.class) {
            current = instance;
            if (current != null) return current;
            if (!ModList.get().isLoaded("taczmagazines")) {
                current = NoopMagazineCompat.INSTANCE;
            } else {
                try {
                    current = new ReflectiveMagazineCompat();
                    Espetro.LOGGER.info("TaCZ Magazines compatibility enabled");
                } catch (ReflectiveOperationException | LinkageError error) {
                    Espetro.LOGGER.error("TaCZ Magazines is loaded but its public API is incompatible", error);
                    current = NoopMagazineCompat.INSTANCE;
                }
            }
            instance = current;
            return current;
        }
    }

    public static void resetForTests() {
        instance = null;
    }
}
