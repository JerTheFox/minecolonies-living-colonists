package me.bytefox.livingcolonists.scenarios;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.minecolonies.api.colony.ICitizenData;
import com.minecolonies.api.colony.buildings.IBuilding;
import me.bytefox.livingcolonists.bonds.Drift;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What happens in a colony by itself, day by day. */
class ColonyLifeScenariosTest {
    private Stage stage;

    @BeforeEach
    void setUp() throws Exception {
        stage = new Stage("Darcy Abbott", "Hunter Casey", "Fiona Lowe");
    }

    @AfterEach
    void tearDown() {
        stage.close();
    }

    @Test
    void housematesDriftTheWayTheirChemistryGoesAndOthersDont() {
        IBuilding home = mock(IBuilding.class);
        ICitizenData darcy = stage.citizens.get(0);
        ICitizenData hunter = stage.citizens.get(1);
        when(darcy.getHomeBuilding()).thenReturn(home);
        when(hunter.getHomeBuilding()).thenReturn(home);
        stage.passDays(80);
        double chemistry = Drift.chemistry(stage.colony.getID() + ":1:2");
        int rapport = stage.bonds.colonists().rapport(stage.colony, 1, 2);
        int expected = (int) Math.signum(Drift.daily(false, true, true, chemistry, 0));
        assertEquals(expected, (int) Math.signum(rapport), "chemistry " + chemistry + ", rapport " + rapport);
        assertTrue(Math.abs(rapport) <= Drift.CAP, "past the cap: " + rapport);
        assertEquals(0, stage.bonds.colonists().rapport(stage.colony, 1, 3), "Fiona shares nothing with Darcy");
    }
}
