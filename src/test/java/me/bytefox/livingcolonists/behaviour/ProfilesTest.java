package me.bytefox.livingcolonists.behaviour;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProfilesTest {
    @Test
    void profilesSetEveryModule() {
        assertEquals(Level.OFF, Profile.MINIMAL.level(Module.MEMORY));
        assertEquals(Level.OFF, Profile.MINIMAL.level(Module.PERSONALITY));
        assertEquals(Level.LOW, Profile.MINIMAL.level(Module.PERCEPTION));
        assertEquals(Level.DEFAULT, Profile.DEFAULT.level(Module.MEMORY));
        assertEquals(Level.HIGH, Profile.IMMERSIVE.level(Module.MEMORY));
        assertEquals(Level.OFF, Profile.MINIMAL.level(Module.CONVERSATION));
        assertEquals(Level.HIGH, Profile.IMMERSIVE.level(Module.CONVERSATION));
        for (Profile profile : Profile.values()) {
            for (Module module : Module.values()) {
                profile.level(module);
            }
        }
    }

    @Test
    void anOverrideBeatsTheProfile() {
        assertEquals(Level.HIGH, Levels.resolve(Profile.DEFAULT, Module.MEMORY, "high"));
        assertEquals(Level.OFF, Levels.resolve(Profile.IMMERSIVE, Module.PERSONALITY, "off"));
        assertEquals(Level.DEFAULT, Levels.resolve(Profile.DEFAULT, Module.MEMORY, Levels.FROM_PROFILE));
        assertEquals(Level.HIGH, Levels.resolve(Profile.IMMERSIVE, Module.MEMORY, "nonsense"));
    }

    @Test
    void ownersOfficersAndFriendsMayTalk() {
        assertTrue(ColonyAccess.canTalk(ColonyAccess.Role.OWNER, true));
        assertTrue(ColonyAccess.canTalk(ColonyAccess.Role.OFFICER, true));
        assertTrue(ColonyAccess.canTalk(ColonyAccess.Role.FRIEND, true));
        assertFalse(ColonyAccess.canTalk(ColonyAccess.Role.OTHER, true));
        assertTrue(ColonyAccess.canTalk(ColonyAccess.Role.OTHER, false));
    }

    @Test
    void onlyOwnersAndOfficersGiveOrders() {
        assertTrue(ColonyAccess.canGiveOrders(ColonyAccess.Role.OWNER, true));
        assertTrue(ColonyAccess.canGiveOrders(ColonyAccess.Role.OFFICER, true));
        assertFalse(ColonyAccess.canGiveOrders(ColonyAccess.Role.FRIEND, true));
        assertFalse(ColonyAccess.canGiveOrders(ColonyAccess.Role.OTHER, true));
        assertTrue(ColonyAccess.canGiveOrders(ColonyAccess.Role.OTHER, false));
    }

    @Test
    void cooldownBlocksMessagesThatComeTooFast() {
        Cooldown cooldown = new Cooldown();
        UUID player = UUID.randomUUID();

        assertEquals(Optional.empty(), cooldown.tryUse(player, 100, 60));
        assertEquals(Optional.of(40L), cooldown.tryUse(player, 120, 60));
        assertEquals(Optional.empty(), cooldown.tryUse(player, 160, 60));
        assertEquals(Optional.empty(), cooldown.tryUse(UUID.randomUUID(), 161, 60));
        assertEquals(Optional.empty(), cooldown.tryUse(player, 161, 0));
    }
}
