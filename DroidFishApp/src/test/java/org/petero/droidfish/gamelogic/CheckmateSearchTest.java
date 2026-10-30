package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class CheckmateSearchTest {
    @Test
    public void normalCommandIsUnchangedAndMateSearchIsBounded() {
        assertEquals("go infinite", CheckmateSearch.command(false));
        assertEquals("go mate 64 movetime 2500", CheckmateSearch.command(true));
    }

    @Test
    public void acceptsOnlyExactWinningMateAndPreservesPosition() throws Exception {
        Position position = TextIO.readFEN("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1");
        String fen = TextIO.toFEN(position);
        Move mate = TextIO.stringToMove(position, "Qg7#");
        assertNotNull(mate);
        CheckmateSearch.Result result = CheckmateSearch.select(null, position, true, 1, false,
                Collections.singletonList(mate));
        assertNotNull(result);
        assertEquals(1, result.distance);
        assertTrue(result.line.endsWith("#"));
        assertEquals(fen, TextIO.toFEN(position));
        assertNull(CheckmateSearch.select(result, position, false, 500, false, Collections.singletonList(mate)));
        assertNull(CheckmateSearch.select(null, position, true, -1, false, Collections.singletonList(mate)));
        assertNull(CheckmateSearch.select(null, position, true, 1, true, Collections.singletonList(mate)));
    }

    @Test
    public void rejectsIncompleteIllegalAndNonMatingLines() throws Exception {
        Position position = TextIO.readFEN(TextIO.startPosFEN);
        Move quiet = TextIO.stringToMove(position, "e4");
        assertNull(CheckmateSearch.select(null, position, true, 1, false, Collections.singletonList(quiet)));
        assertNull(CheckmateSearch.select(null, position, true, 2, false, Collections.singletonList(quiet)));
        assertNull(CheckmateSearch.select(null, position, true, 1, false,
                Collections.singletonList(new Move(0, 63, 0))));
        assertNull(CheckmateSearch.select(null, position, true, 1, false, new ArrayList<Move>()));
    }

    @Test
    public void retainsShortestValidatedMate() throws Exception {
        Position position = TextIO.readFEN("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1");
        Move shortMove = TextIO.stringToMove(position, "Qg7#");
        CheckmateSearch.Result shortest = CheckmateSearch.select(null, position, true, 1, false,
                Collections.singletonList(shortMove));
        Position replay = new Position(position);
        Move first = TextIO.stringToMove(replay, "Qf6+");
        assertNotNull(first);
        replay.makeMove(first, new UndoInfo());
        Move reply = TextIO.stringToMove(replay, "Kg8");
        assertNotNull(reply);
        replay.makeMove(reply, new UndoInfo());
        Move last = TextIO.stringToMove(replay, "Qg7#");
        assertNotNull(last);
        CheckmateSearch.Result longer = CheckmateSearch.select(null, position, true, 2, false,
                Arrays.asList(first, reply, last));
        assertNotNull(longer);
        assertSame(shortest, CheckmateSearch.select(shortest, position, true, 2, false,
                Arrays.asList(first, reply, last)));
        assertEquals(1, CheckmateSearch.select(longer, position, true, 1, false,
                Collections.singletonList(shortMove)).distance);
    }
}