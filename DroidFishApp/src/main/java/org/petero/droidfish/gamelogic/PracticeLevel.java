package org.petero.droidfish.gamelogic;

public enum PracticeLevel {
    BEGINNER(1320),
    INTERMEDIATE(1700),
    ADVANCED(2200),
    MASTERMIND(Integer.MAX_VALUE);

    public final int elo;

    PracticeLevel(int elo) {
        this.elo = elo;
    }

    public static PracticeLevel fromSetting(String value) {
        for (PracticeLevel level : values()) {
            if (level.name().equals(value))
                return level;
        }
        return MASTERMIND;
    }
}