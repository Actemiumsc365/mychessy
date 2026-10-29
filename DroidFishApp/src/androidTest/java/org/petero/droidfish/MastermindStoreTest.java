package org.petero.droidfish;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import junit.framework.TestCase;
import org.petero.droidfish.gamelogic.MastermindCoach;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.gamelogic.UndoInfo;

public class MastermindStoreTest extends TestCase {
    private Context context;
    private MastermindStore store;

    @Override
    protected void setUp() {
        context = new ContextWrapper(DroidFishApp.getContext()) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name + "_instrumentation", mode);
            }
        };
        store = new MastermindStore(context);
        store.clear();
    }

    @Override
    protected void tearDown() {
        store.clear();
    }

    public void testMistakesPersistAndDeduplicate() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        Move played = TextIO.stringToMove(position, "f3");
        MastermindCoach.Evaluation before = new MastermindCoach.Evaluation(TextIO.toFEN(position),
                20, 16, TextIO.stringToMove(position, "e4"), true);
        position.makeMove(played, new UndoInfo());
        MastermindCoach.Mistake mistake = MastermindCoach.compare(before,
                new MastermindCoach.Evaluation(TextIO.toFEN(position), -180, 16, null, true), played);
        assertNotNull(mistake);
        assertTrue(store.record(mistake));
        assertFalse(store.record(mistake));
        MastermindStore reopened = new MastermindStore(context);
        assertTrue(reopened.summary().contains("Pawn moves: 1"));
        assertTrue(reopened.summary().contains("2.00 pawn loss"));
    }

    public void testDrillsPersistDeduplicateAndClear() throws Exception {
        List<MastermindCoach.Drill> drills = MastermindCoach.openingDrills("1. e4 e5 2. Nf3 Nc6 *", true);
        assertEquals(2, store.addDrills(drills));
        assertEquals(0, store.addDrills(drills));
        MastermindStore reopened = new MastermindStore(context);
        assertEquals(2, reopened.drills().size());
        assertTrue(reopened.drills().get(0).accepts("e4"));
        reopened.clear();
        assertEquals(0, store.drills().size());
        assertEquals("No reviewed mistakes saved.", store.summary());
    }

    public void testStorageIsBoundedAndMalformedValuesDoNotCrash() throws Exception {
        List<MastermindCoach.Drill> drills = new ArrayList<>();
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        for (int index = 0; index < 245; index++) {
            position.fullMoveCounter = index + 1;
            drills.add(new MastermindCoach.Drill(TextIO.toFEN(position), "e2e4"));
        }
        store.addDrills(drills);
        assertEquals(240, store.drills().size());
        context.getSharedPreferences("mastermind_training", Context.MODE_PRIVATE).edit()
                .putString("mistakes", "[null,42,\"invalid\"]").commit();
        assertEquals("No reviewed mistakes saved.", store.summary());
    }
}