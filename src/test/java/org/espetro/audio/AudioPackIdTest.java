package org.espetro.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AudioPackIdTest {

    @Test
    void acceptsTrimmedSingleDirectoryNames() {
        assertEquals("现代俄军", AudioPackId.normalize(" 现代俄军 "));
        assertEquals("pack.v2", AudioPackId.normalize("pack.v2"));
    }

    @Test
    void rejectsPathsAndWindowsInvalidCharacters() {
        assertNull(AudioPackId.normalize("../outside"));
        assertNull(AudioPackId.normalize("nested/pack"));
        assertNull(AudioPackId.normalize("C:\\pack"));
        assertNull(AudioPackId.normalize("bad?pack"));
        assertNull(AudioPackId.normalize(".."));
    }
}
