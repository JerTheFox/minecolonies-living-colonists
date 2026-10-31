package me.bytefox.livingcolonists.settings;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PresetsTest {
    @Test
    void presetsSetOnlyAddressesAndModelsNeverAKey() {
        for (Presets preset : Presets.values()) {
            assertTrue(preset.fields().containsKey("baseUrl") && preset.fields().containsKey("chatModel"), preset.label());
            preset.fields().keySet().forEach(id -> assertFalse(id.toLowerCase().contains("key"), id));
            // The settings screen's ids (SettingsSchema needs the config, which a test can't load).
            preset.fields().keySet().forEach(id -> assertTrue(java.util.Set.of("baseUrl", "chatModel", "characterModel",
                    "embeddingsBaseUrl", "embeddingsModel").contains(id), id));
        }
    }
}
