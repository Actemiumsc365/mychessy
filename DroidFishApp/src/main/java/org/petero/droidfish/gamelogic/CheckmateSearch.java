package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.List;

public final class CheckmateSearch {
    public static final String NO_MATE = "No forced checkmate in this position";

    private CheckmateSearch() { }

    public static String command(boolean enabled) {
        return enabled ? "go mate 64 movetime 2500" : "go infinite";
    }

    public static final class Result {
        public final int distance;
        public final ArrayList<Move> moves;
        public final String line;

        private Result(int distance, List<Move> moves, String line) {
            this.distance = distance;
            this.moves = new ArrayList<>(moves);
            this.line = line;
        }
    }

    public static Result select(Result previous, Position position, boolean mateScore,
                                int distance, boolean bounded, List<Move> moves) {
        if (bounded)
            return previous;
        if (!mateScore || distance <= 0)
            return null;
        if (moves == null || moves.size() != distance * 2 - 1)
            return previous;
        Position replay = new Position(position);
        StringBuilder line = new StringBuilder();
        for (Move move : moves) {
            if (!TextIO.isValid(replay, move))
                return previous;
            if (line.length() > 0)
                line.append(' ');
            line.append(TextIO.moveToString(replay, move, false, false));
            replay.makeMove(move, new UndoInfo());
        }
        if (!MoveGen.inCheck(replay) || !MoveGen.instance.legalMoves(replay).isEmpty())
            return previous;
        if (previous != null && previous.distance < distance)
            return previous;
        return new Result(distance, moves, line.toString());
    }
}