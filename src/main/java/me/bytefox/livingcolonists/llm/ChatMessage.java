package me.bytefox.livingcolonists.llm;

import java.util.List;

/**
 * One message of a chat completions conversation.
 *
 * @param toolCalls  functions an assistant message asks to run; empty otherwise
 * @param toolCallId for a tool message, the call it answers; null otherwise
 */
public record ChatMessage(String role, String content, List<ToolCall> toolCalls, String toolCallId) {
    public ChatMessage {
        toolCalls = List.copyOf(toolCalls);
    }

    public static ChatMessage system(String content) {
        return new ChatMessage("system", content, List.of(), null);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage("user", content, List.of(), null);
    }

    /** An earlier line of the colonist, from memory. */
    public static ChatMessage assistant(String content) {
        return new ChatMessage("assistant", content, List.of(), null);
    }

    /** The model's turn that asked for functions, sent back with their results. */
    public static ChatMessage assistantCalling(List<ToolCall> calls) {
        return new ChatMessage("assistant", "", calls, null);
    }

    public static ChatMessage toolResult(String callId, String content) {
        return new ChatMessage("tool", content, List.of(), callId);
    }
}
