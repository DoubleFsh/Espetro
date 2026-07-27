package org.espetro.mapconfig;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GameSettingsSnapshotTest {

    @Test
    void classSwitchCooldownDefaultsToSixtySeconds() {
        GameSettingsSnapshot settings = GameSettingsSnapshot.parse(
            JsonParser.parseString("{}").getAsJsonObject());

        assertEquals(60, settings.classSwitchCooldownSeconds);
        assertEquals(2, settings.regenDelaySeconds);
        assertEquals(12, settings.fullRecoverySeconds);
    }

    @Test
    void classSwitchCooldownCanBeConfiguredOrDisabled() {
        GameSettingsSnapshot configured = GameSettingsSnapshot.parse(
            JsonParser.parseString("""
                {"game":{"class_switch_cooldown_seconds":15}}
                """).getAsJsonObject());
        GameSettingsSnapshot disabled = GameSettingsSnapshot.parse(
            JsonParser.parseString("""
                {"game":{"class_switch_cooldown_seconds":0}}
                """).getAsJsonObject());

        assertEquals(15, configured.classSwitchCooldownSeconds);
        assertEquals(0, disabled.classSwitchCooldownSeconds);
    }

    @Test
    void negativeClassSwitchCooldownIsClampedToZero() {
        GameSettingsSnapshot settings = GameSettingsSnapshot.parse(
            JsonParser.parseString("""
                {"game":{"class_switch_cooldown_seconds":-10}}
                """).getAsJsonObject());

        assertEquals(0, settings.classSwitchCooldownSeconds);
    }

    @Test
    void staminaFullRecoveryTargetCanBeConfiguredOrDisabled() {
        GameSettingsSnapshot configured = GameSettingsSnapshot.parse(
            JsonParser.parseString("""
                {"stamina":{"full_recovery_seconds":8}}
                """).getAsJsonObject());
        GameSettingsSnapshot disabled = GameSettingsSnapshot.parse(
            JsonParser.parseString("""
                {"stamina":{"full_recovery_seconds":0}}
                """).getAsJsonObject());

        assertEquals(8, configured.fullRecoverySeconds);
        assertEquals(0, disabled.fullRecoverySeconds);
    }
}
