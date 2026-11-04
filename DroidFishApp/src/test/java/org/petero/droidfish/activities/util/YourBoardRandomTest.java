package org.petero.droidfish.activities.util;

import java.util.Random;

import org.junit.Test;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class YourBoardRandomTest {
    @Test
    public void randomPositionsAreLegalAndPlayable() {
        Random rnd = new Random(1);
        for (int i = 0; i < 200; i++) {
            Position pos = YourBoardRandom.randomPosition(rnd, i % 2 == 0);
            assertNotNull(pos);
            assertTrue(YourBoardRandom.isPlayable(pos));
            assertEquals(i % 2 == 0, pos.whiteMove);
        }
    }

    @Test
    public void shuffleKeepsSameMaterial() throws Exception {
        Position start = TextIO.readFEN(TextIO.startPosFEN);
        Random rnd = new Random(2);
        for (int i = 0; i < 50; i++) {
            Position pos = YourBoardRandom.shuffle(start, rnd);
            assertNotNull(pos);
            assertTrue(YourBoardRandom.isPlayable(pos));
            for (int p = Piece.WKING; p <= Piece.BPAWN; p++)
                assertEquals(count(start, p), count(pos, p));
        }
    }

    @Test
    public void shuffleWithoutKingsFails() {
        Position pos = new Position();
        pos.setPiece(Position.getSquare(0, 0), Piece.WQUEEN);
        assertNull(YourBoardRandom.shuffle(pos, new Random(3)));
    }

    private static int count(Position pos, int piece) {
        int n = 0;
        for (int sq = 0; sq < 64; sq++)
            if (pos.getPiece(sq) == piece)
                n++;
        return n;
    }
}
