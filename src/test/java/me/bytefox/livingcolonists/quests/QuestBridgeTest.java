package me.bytefox.livingcolonists.quests;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.quests.IQuestInstance;
import com.minecolonies.api.quests.IQuestManager;
import com.minecolonies.core.datalistener.QuestJsonListener;
import com.minecolonies.core.quests.QuestManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;

/** The spike of #60 against MineColonies' real quest engine, without a world. */
class QuestBridgeTest {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath("livingcolonists", "spike/bread");

    @Test
    void ourTemplateIsParsedByTheEngineAndGoesWhereTheClientsGetTheirs() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        when(server.registryAccess()).thenReturn(registries);
        when(server.getPlayerList().getPlayers()).thenReturn(List.of());

        assertTrue(QuestBridge.register(server, ID, SpikeQuest.json("Darcy Abbott")));
        assertNotNull(IQuestManager.GLOBAL_SERVER_QUESTS.get(ID));
        assertEquals(3, IQuestManager.GLOBAL_SERVER_QUESTS.get(ID).getObjectiveCount());
        Field field = QuestJsonListener.class.getDeclaredField("globalJsonElementMap");
        field.setAccessible(true);
        assertTrue(((Map<?, ?>) field.get(null)).containsKey(ID), "clients would not get it");

        // MineColonies' own reload clears both; ours come back.
        IQuestManager.GLOBAL_SERVER_QUESTS.clear();
        ((Map<?, ?>) field.get(null)).clear();
        QuestBridge.restore(server);
        assertNotNull(IQuestManager.GLOBAL_SERVER_QUESTS.get(ID));
        assertTrue(((Map<?, ?>) field.get(null)).containsKey(ID));
    }

    @Test
    void theColonistWeChooseOffersItAndTheOfferSurvivesSavingAndLoading() throws Exception {
        MinecraftServer server = mock(MinecraftServer.class, RETURNS_DEEP_STUBS);
        RegistryAccess.Frozen registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        when(server.registryAccess()).thenReturn(registries);
        when(server.getPlayerList().getPlayers()).thenReturn(List.of());
        assertTrue(QuestBridge.register(server, ID, SpikeQuest.json("Darcy Abbott")));

        IColony colony = mock(IColony.class, RETURNS_DEEP_STUBS);
        ICitizenData darcy = mock(ICitizenData.class, RETURNS_DEEP_STUBS);
        when(darcy.getId()).thenReturn(7);
        when(colony.getCitizenManager().getCivilian(7)).thenReturn(darcy);
        QuestManager quests = new QuestManager(colony);
        when(colony.getQuestManager()).thenReturn(quests);

        IQuestInstance quest = QuestBridge.offer(colony, ID, darcy, List.of());
        assertNotNull(quest);
        assertEquals(7, quest.getQuestGiverId());
        verify(darcy).assignQuest(quest);
        assertTrue(quests.getAvailableQuests().contains(quest), "not among the colony's available quests");

        CompoundTag saved = quests.serializeNBT(registries);
        QuestManager loaded = new QuestManager(colony);
        loaded.deserializeNBT(registries, saved);
        assertEquals(1, loaded.getAvailableQuests().size(), "lost on saving: " + saved);
        assertEquals(ID, loaded.getAvailableQuests().get(0).getId());
        assertEquals(7, loaded.getAvailableQuests().get(0).getQuestGiverId());
    }
}
