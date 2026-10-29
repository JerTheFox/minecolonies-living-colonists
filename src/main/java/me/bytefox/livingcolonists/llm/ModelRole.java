package me.bytefox.livingcolonists.llm;

/** Which configured model a request uses. */
public enum ModelRole {
    /** Replies, working out whom a message addresses, small talk: fast and cheap. */
    CHAT,
    /** Backgrounds and long-memory summaries: rare, so a smarter model is affordable. */
    CHARACTER
}
