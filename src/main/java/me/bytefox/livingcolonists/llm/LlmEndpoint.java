package me.bytefox.livingcolonists.llm;

/**
 * Where requests go: an OpenAI-compatible API, a model on it and its key.
 *
 * @param apiKey empty for endpoints that need no key, such as a local model
 */
public record LlmEndpoint(String baseUrl, String model, String apiKey) {
    /** Never prints the API key. */
    @Override
    public String toString() {
        return "LlmEndpoint[baseUrl=" + baseUrl + ", model=" + model + ", key=" + (apiKey.isEmpty() ? "none" : "set") + "]";
    }
}
