package me.bytefox.livingcolonists.behaviour;

/** How much the mod writes to the server log. */
public enum LogLevel {
    /** Chat lines, what colonists do (look-ups, orders, promises, memory, who a message is for) and technical lines. */
    ALL,
    /** What colonists do and technical lines, without the chat itself. */
    NO_CHAT,
    /** Start-up, settings changes, warnings and errors only. */
    TECHNICAL,
    /** Errors only. */
    OFF;

    public boolean chat() {
        return this == ALL;
    }

    public boolean actions() {
        return this == ALL || this == NO_CHAT;
    }

    public boolean technical() {
        return this != OFF;
    }
}
