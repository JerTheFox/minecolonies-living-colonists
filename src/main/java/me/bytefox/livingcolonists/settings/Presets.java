package me.bytefox.livingcolonists.settings;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ready settings for common providers (#47), one click on the settings screen: the address and the
 * models; the API key is left for the player. DeepSeek has no embeddings API, so its preset uses
 * OpenRouter's, which takes a key of its own.
 */
public enum Presets {
    DEEPSEEK("DeepSeek", "https://api.deepseek.com", "deepseek-flash",
            "https://openrouter.ai/api/v1", "baai/bge-m3"),
    OPENROUTER("OpenRouter", "https://openrouter.ai/api/v1", "deepseek/deepseek-v4.1-flash-1017",
            "https://openrouter.ai/api/v1", "baai/bge-m3"),
    OLLAMA("Ollama", "http://127.0.0.1:11434/v1", "qwen2.5:14b", "http://127.0.0.1:11434/v1", "bge-m3");

    private final String label;
    private final Map<String, String> fields;

    Presets(String label, String baseUrl, String chatModel, String embeddingsBaseUrl, String embeddingsModel) {
        this.label = label;
        Map<String, String> map = new LinkedHashMap<>();
        map.put("baseUrl", baseUrl);
        map.put("chatModel", chatModel);
        map.put("characterModel", "");
        map.put("embeddingsBaseUrl", embeddingsBaseUrl);
        map.put("embeddingsModel", embeddingsModel);
        this.fields = Map.copyOf(map);
    }

    public String label() {
        return label;
    }

    /** Setting id to value, as the settings screen edits them. */
    public Map<String, String> fields() {
        return fields;
    }
}
