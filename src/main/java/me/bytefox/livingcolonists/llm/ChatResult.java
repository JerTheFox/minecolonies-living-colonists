package me.bytefox.livingcolonists.llm;

/** The outcome of one completion request: a reply, or a reason it failed. */
public sealed interface ChatResult {
    record Reply(String text) implements ChatResult {
    }

    /** {@code reason} is for the server log and never contains the API key. */
    record Failure(String reason) implements ChatResult {
    }
}
