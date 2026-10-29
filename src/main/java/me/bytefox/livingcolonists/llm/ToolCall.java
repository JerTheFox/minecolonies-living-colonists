package me.bytefox.livingcolonists.llm;

/**
 * The model asking to run a function.
 *
 * @param arguments the arguments as the model wrote them: a JSON object in a string
 */
public record ToolCall(String id, String name, String arguments) {
}
