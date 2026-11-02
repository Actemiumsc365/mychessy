package org.petero.droidfish.gamelogic;

import org.junit.Test;
import static org.junit.Assert.*;

public class MastermindCoachTest {
    @Test
    public void gameReviewRanksThreeLargestLossesAndReportsCoverage() throws Exception {
        String pgn = "1. f3 e5 2. g4 Nc6 3. a3 d5 4. h3 Nf6 *";
        GameTree tree = new GameTree(null);
        assertTrue(tree.readPGN(pgn, new org.petero.droidfish.PGNOptions()));
        java.util.Map<String, MastermindCoach.Evaluation> evaluations = new java.util.HashMap<>();
        int[] losses = { 150, 500, 300, 200 };
        int index = 0;
        while (!tree.variations().isEmpty()) {
            Position before = new Position(tree.currentPos);
            Move played = tree.variations().get(0);
            tree.goForward(0);
            if (!before.whiteMove)
                continue;
            Move best = TextIO.stringToMove(before, "e4");
            evaluations.put(TextIO.toFEN(before), new MastermindCoach.Evaluation(TextIO.toFEN(before), 1000, 16, best, true));
            evaluations.put(TextIO.toFEN(tree.currentPos), new MastermindCoach.Evaluation(TextIO.toFEN(tree.currentPos),
                    1000 - losses[index++], 16, null, true));
        }
        MastermindCoach.Review review = MastermindCoach.review(pgn, evaluations, true);
        assertEquals(4, review.total);
        assertEquals(4, review.reviewed);
        assertEquals(3, review.mistakes.size());
        assertEquals(500, review.mistakes.get(0).mistake.loss);
        assertEquals(300, review.mistakes.get(1).mistake.loss);
        assertEquals(200, review.mistakes.get(2).mistake.loss);
        assertTrue(review.summary().contains("Reviewed 4 of 4"));
        MastermindCoach.Review empty = MastermindCoach.review(pgn, java.util.Collections.emptyMap(), true);
        assertEquals(0, empty.reviewed);
        assertTrue(empty.summary().contains("No moves have usable analysis"));
    }

    @Test
    public void mistakeExplanationIncludesBetterMoveAndOpponentReply() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        Move played = TextIO.stringToMove(position, "f3");
        MastermindCoach.Evaluation before = new MastermindCoach.Evaluation(TextIO.toFEN(position),
                30, 16, TextIO.stringToMove(position, "e4"), true);
        position.makeMove(played, new UndoInfo());
        Move reply = TextIO.stringToMove(position, "e5");
        MastermindCoach.Evaluation after = new MastermindCoach.Evaluation(TextIO.toFEN(position),
                -150, 16, reply, true, java.util.Collections.singletonList(reply));
        String text = MastermindCoach.explainMistake(before, after, played);
        assertTrue(text.contains("Played: f3"));
        assertTrue(text.contains("Better: e4"));
        assertTrue(text.contains("1.80 pawns"));
        assertTrue(text.contains("Opponent's continuation:"));
        assertTrue(text.contains("Play: e5"));
        assertTrue(MastermindCoach.explainMistake(null, after, played).contains("more analysis"));
    }

    @Test
    public void explainsConcreteReplyAndContinuationWithoutChangingPosition() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        Position replay = new Position(position);
        java.util.ArrayList<Move> moves = new java.util.ArrayList<>();
        for (String notation : new String[] { "e4", "e5", "Nf3" }) {
            Move move = TextIO.stringToMove(replay, notation);
            moves.add(move);
            replay.makeMove(move, new UndoInfo());
        }
        String explanation = MastermindCoach.explainLine(position, moves);
        assertTrue(explanation.contains("Play: e4"));
        assertTrue(explanation.contains("Best reply: e5"));
        assertTrue(explanation.contains("Continue: Nf3"));
        assertEquals(TextIO.startPosFEN, TextIO.toFEN(position));
        assertTrue(MastermindCoach.explainLine(position, java.util.Collections.emptyList()).contains("No matching"));
    }

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
        assertTrue(mistake.practice.accepts("e4"));
        assertFalse(mistake.practice.accepts("f3"));
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