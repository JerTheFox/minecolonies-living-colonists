package me.bytefox.livingcolonists.llm;

/**
 * Tokens one request used.
 *
 * @param cachedTokens prompt tokens the provider served from its cache (cheaper)
 */
public record Usage(long promptTokens, long completionTokens, long cachedTokens) {
    public static final Usage NONE = new Usage(0, 0, 0);
}
