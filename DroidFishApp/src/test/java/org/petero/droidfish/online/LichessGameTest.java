package org.petero.droidfish.online;

import org.junit.Test;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class LichessGameTest {
    @Test
    public void replaysMovesFromStartpos() throws Exception {
        LichessGame.Replay r = LichessGame.replay("startpos", "e2e4 e7e5 g1f3");
        assertEquals(3, r.plies);
        assertFalse(r.pos.whiteMove);
        assertEquals(Piece.WKNIGHT, r.pos.getPiece(TextIO.getSquare("f3")));
        assertEquals(TextIO.getSquare("f3"), r.lastMove.to);
    }

    @Test
    public void emptyMovesGiveInitialPosition() throws Exception {
        LichessGame.Replay r = LichessGame.replay("startpos", "");
        assertEquals(0, r.plies);
        assertNull(r.lastMove);
        assertEquals(TextIO.startPosFEN, TextIO.toFEN(r.pos));
    }

    @Test
    public void acceptsKingToRookCastling() throws Exception {
        Position pos = TextIO.readFEN("r3k2r/8/8/8/8/8/8/R3K2R w KQkq - 0 1");
        Move m = LichessGame.parseMove(pos, "e1h1");
        assertNotNull(m);
        assertEquals(TextIO.getSquare("g1"), m.to);
        m = LichessGame.parseMove(pos, "e1a1");
        assertNotNull(m);
        assertEquals(TextIO.getSquare("c1"), m.to);
        assertNotNull(LichessGame.parseMove(pos, "e1g1"));
    }

    @Test
    public void rejectsIllegalMoves() throws Exception {
        Position pos = TextIO.readFEN(TextIO.startPosFEN);
        assertNull(LichessGame.parseMove(pos, "e2e5"));
        assertNull(LichessGame.parseMove(pos, "zz"));
        try {
            LichessGame.replay("startpos", "e2e4 e2e4");
            assertTrue("expected failure", false);
        } catch (org.petero.droidfish.gamelogic.ChessParseError expected) {
        }
    }

    @Test
    public void promotionHasFourChoices() throws Exception {
        Position pos = TextIO.readFEN("8/P7/8/8/8/8/8/k6K w - - 0 1");
        assertEquals(4, LichessGame.matchingMoves(pos, TextIO.getSquare("a7"), TextIO.getSquare("a8")).size());
        assertNotNull(LichessGame.parseMove(pos, "a7a8q"));
    }

    @Test
    public void validatesIdsAndTokens() {
        assertTrue(LichessGame.isValidId("Magnus_Fan-1"));
        assertFalse(LichessGame.isValidId("../api/account"));
        assertFalse(LichessGame.isValidId(""));
        // Built at runtime so secret scanners don't mistake the fake token for a real one.
        assertTrue(LichessGame.isValidToken("lip" + "_" + "testtokenonly0000"));
        assertFalse(LichessGame.isValidToken("lip_abc def"));
    }

    @Test
    public void formatsClocks() {
        assertEquals("10:00", LichessGame.formatClock(600000));
        assertEquals("0:09.5", LichessGame.formatClock(9500));
        assertEquals("1:00:00", LichessGame.formatClock(3600000));
        assertEquals("0:00.0", LichessGame.formatClock(-5));
    }
}
