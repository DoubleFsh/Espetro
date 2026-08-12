package org.espetro.team;

import com.google.gson.Gson;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FactionAudioConfigTest {
    private final Gson gson = new Gson();

    @Test
    void readsCanonicalAudioPackIndex() {
        FactionDataLoader.FactionData data = gson.fromJson(
            "{\"audio_pack\":\"modern_russia\"}", FactionDataLoader.FactionData.class);
        assertEquals("modern_russia", data.audioPack);
    }

    @Test
    void readsLegacyStyleAliases() {
        FactionDataLoader.FactionData camel = gson.fromJson(
            "{\"audioPack\":\"camel\"}", FactionDataLoader.FactionData.class);
        FactionDataLoader.FactionData index = gson.fromJson(
            "{\"audio_index\":\"index\"}", FactionDataLoader.FactionData.class);

        assertEquals("camel", camel.audioPack);
        assertEquals("index", index.audioPack);
    }
}
