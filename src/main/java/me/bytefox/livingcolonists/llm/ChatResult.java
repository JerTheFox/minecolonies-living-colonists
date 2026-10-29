package me.bytefox.livingcolonists.llm;

/** The outcome of one completion request: a reply, or a reason it failed. */
public sealed interface ChatResult {
    record Reply(String text, Usage usage) implements ChatResult {
    }

    /**
     * @param reason    for the server log; never contains the API key
     * @param retryable a temporary problem (timeout, rate limit, server error) worth trying again
     */
    record Failure(String reason, boolean retryable) implements ChatResult {
    }
}
