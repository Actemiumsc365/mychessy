package org.petero.droidfish.gamelogic;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class PracticeLevelTest {
    @Test
    public void levelsIncreaseToUnrestrictedBoss() {
        assertEquals(1320, PracticeLevel.BEGINNER.elo);
        assertTrue(PracticeLevel.BEGINNER.elo < PracticeLevel.INTERMEDIATE.elo);
        assertTrue(PracticeLevel.INTERMEDIATE.elo < PracticeLevel.ADVANCED.elo);
        assertEquals(Integer.MAX_VALUE, PracticeLevel.MASTERMIND.elo);
    }

    @Test
    public void settingsRoundTripAndUnknownValuesPreserveFullStrength() {
        for (PracticeLevel level : PracticeLevel.values())
            assertEquals(level, PracticeLevel.fromSetting(level.name()));
        assertEquals(PracticeLevel.MASTERMIND, PracticeLevel.fromSetting(null));
        assertEquals(PracticeLevel.MASTERMIND, PracticeLevel.fromSetting("unknown"));
    }
}