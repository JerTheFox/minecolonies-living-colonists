package me.bytefox.livingcolonists.behaviour;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LogLevelTest {
    @Test
    void eachLevelLetsLessThrough() {
        assertTrue(LogLevel.ALL.chat() && LogLevel.ALL.actions() && LogLevel.ALL.technical());
        assertTrue(!LogLevel.NO_CHAT.chat() && LogLevel.NO_CHAT.actions() && LogLevel.NO_CHAT.technical());
        assertTrue(!LogLevel.TECHNICAL.chat() && !LogLevel.TECHNICAL.actions() && LogLevel.TECHNICAL.technical());
        assertFalse(LogLevel.OFF.chat() || LogLevel.OFF.actions() || LogLevel.OFF.technical());
    }
}
