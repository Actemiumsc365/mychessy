package org.petero.droidfish.gamelogic;

import org.junit.Test;
import static org.junit.Assert.*;

public class MastermindCoachTest {
    @Test
    public void explainsLegalMoveWithoutMutatingPosition() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        String explanation = MastermindCoach.explain(position, TextIO.stringToMove(position, "e4"));
        assertTrue(explanation.contains("pawn moves from e2 to e4"));
        assertTrue(explanation.contains("central area"));
        assertEquals(TextIO.startPosFEN, TextIO.toFEN(position));
        assertTrue(MastermindCoach.explain(position, new Move(0, 63, 0)).contains("No matching"));
    }

    @Test
    public void explainsCastlingAndEnPassant() throws Exception {
        Position castle = TextIO.readFEN("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        assertTrue(MastermindCoach.explain(castle, TextIO.stringToMove(castle, "O-O")).contains("Castling"));
        Position ep = TextIO.readFEN("4k3/8/8/3pP3/8/8/8/4K3 w - d6 0 2");
        assertTrue(MastermindCoach.explain(ep, TextIO.stringToMove(ep, "exd6")).contains("en passant"));
    }

    @Test
    public void comparesWhiteScoresAndRejectsShallowOrUnrelatedAnalysis() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        Move best = TextIO.stringToMove(position, "e4");
        Move played = TextIO.stringToMove(position, "f3");
        MastermindCoach.Evaluation before = new MastermindCoach.Evaluation(TextIO.toFEN(position), 30, 16, best, true);
        position.makeMove(played, new UndoInfo());
        MastermindCoach.Evaluation after = new MastermindCoach.Evaluation(TextIO.toFEN(position), -150, 16, null, true);
        MastermindCoach.Mistake mistake = MastermindCoach.compare(before, after, played);
        assertNotNull(mistake);
        assertEquals(180, mistake.loss);
        assertEquals("Pawn moves", mistake.category);
        assertNull(MastermindCoach.compare(before,
                new MastermindCoach.Evaluation(after.fen, -150, 4, null, true), played));
        assertNull(MastermindCoach.compare(before,
                new MastermindCoach.Evaluation(before.fen, -150, 16, null, true), played));
        assertNull(MastermindCoach.compare(before,
                new MastermindCoach.Evaluation(after.fen, -150, 16, null, false), played));
    }

    @Test
    public void blackLossHasCorrectSign() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        position.makeMove(TextIO.stringToMove(position, "e4"), new UndoInfo());
        Move best = TextIO.stringToMove(position, "e5");
        Move played = TextIO.stringToMove(position, "f6");
        MastermindCoach.Evaluation before = new MastermindCoach.Evaluation(TextIO.toFEN(position), 20, 14, best, true);
        position.makeMove(played, new UndoInfo());
        MastermindCoach.Mistake mistake = MastermindCoach.compare(before,
                new MastermindCoach.Evaluation(TextIO.toFEN(position), 240, 14, null, true), played);
        assertNotNull(mistake);
        assertEquals(220, mistake.loss);
    }

    @Test
    public void drillsComeFromPlayedMainLineAndSelectedSide() throws Exception {
        String pgn = "[Event \"Personal game\"]\n\n1. e4 e5 2. Nf3 Nc6 *";
        assertEquals(2, MastermindCoach.openingDrills(pgn, true).size());
        MastermindCoach.Drill drill = MastermindCoach.openingDrills(pgn, true).get(0);
        assertTrue(drill.accepts("e4"));
        assertTrue(drill.accepts("e2e4"));
        assertFalse(drill.accepts("d4"));
        assertEquals("e7e5", MastermindCoach.openingDrills(pgn, false).get(0).answer);
    }
}