package me.bytefox.livingcolonists.llm;

import java.util.List;

/** The outcome of one completion request: a reply, or a reason it failed. */
public sealed interface ChatResult {
    /**
     * @param toolCalls functions the model wants run before it answers; then {@code text} may be empty
     */
    record Reply(String text, Usage usage, List<ToolCall> toolCalls) implements ChatResult {
        public Reply {
            toolCalls = List.copyOf(toolCalls);
        }

        public Reply(String text, Usage usage) {
            this(text, usage, List.of());
        }

        public boolean wantsTools() {
            return !toolCalls.isEmpty();
        }
    }

    /**
     * @param reason    for the server log; never contains the API key
     * @param retryable a temporary problem (timeout, rate limit, server error) worth trying again
     */
    record Failure(String reason, boolean retryable) implements ChatResult {
    }
}
