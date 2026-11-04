package org.petero.droidfish.online;

import java.util.ArrayList;
import java.util.regex.Pattern;

import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.MoveGen;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.gamelogic.UndoInfo;

/** Chess logic helpers for games played through the Lichess Board API. */
public final class LichessGame {
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,40}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_]{10,200}");

    private LichessGame() {
    }

    /** Result of replaying a move list. */
    public static final class Replay {
        public final Position pos;
        public final Move lastMove;
        public final int plies;

        Replay(Position pos, Move lastMove, int plies) {
            this.pos = pos;
            this.lastMove = lastMove;
            this.plies = plies;
        }
    }

    /** Replay space separated UCI moves from "startpos" or a FEN. */
    public static Replay replay(String initialFen, String moves) throws ChessParseError {
        String fen = ((initialFen == null) || initialFen.isEmpty() || "startpos".equals(initialFen))
                ? TextIO.startPosFEN : initialFen;
        Position pos = TextIO.readFEN(fen);
        Move last = null;
        int plies = 0;
        if (moves != null) {
            for (String uci : moves.trim().split("\\s+")) {
                if (uci.isEmpty())
                    continue;
                Move m = parseMove(pos, uci);
                if (m == null)
                    throw new ChessParseError("Illegal move from server: " + uci);
                pos.makeMove(m, new UndoInfo());
                last = m;
                plies++;
            }
        }
        return new Replay(pos, last, plies);
    }

    /** Convert a UCI move, including king-takes-rook castling, to a legal move, or null. */
    public static Move parseMove(Position pos, String uci) {
        Move m = TextIO.UCIstringToMove(uci);
        if (m == null)
            return null;
        int piece = pos.getPiece(m.from);
        int target = pos.getPiece(m.to);
        boolean king = (piece == Piece.WKING) || (piece == Piece.BKING);
        boolean ownRook = (piece == Piece.WKING && target == Piece.WROOK) ||
                          (piece == Piece.BKING && target == Piece.BROOK);
        if (king && ownRook)
            m = new Move(m.from, m.to > m.from ? m.from + 2 : m.from - 2, Piece.EMPTY);
        for (Move legal : MoveGen.instance.legalMoves(pos))
            if (legal.equals(m))
                return legal;
        return null;
    }

    /** Legal moves going from "from" to "to" (several when promoting). */
    public static ArrayList<Move> matchingMoves(Position pos, int from, int to) {
        ArrayList<Move> ret = new ArrayList<>();
        for (Move legal : MoveGen.instance.legalMoves(pos))
            if ((legal.from == from) && (legal.to == to))
                ret.add(legal);
        return ret;
    }

    /** True if s is safe to use as a Lichess id or username in a URL path. */
    public static boolean isValidId(String s) {
        return (s != null) && ID.matcher(s).matches();
    }

    /** True if s looks like a Lichess personal access token. */
    public static boolean isValidToken(String s) {
        return (s != null) && TOKEN.matcher(s).matches();
    }

    /** Format clock milliseconds as m:ss, or s.t below ten seconds. */
    public static String formatClock(long ms) {
        if (ms < 0)
            ms = 0;
        if (ms < 10000)
            return String.format(java.util.Locale.US, "0:%02d.%d", ms / 1000, (ms % 1000) / 100);
        long s = (ms + 999) / 1000;
        if (s >= 3600)
            return String.format(java.util.Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60);
        return String.format(java.util.Locale.US, "%d:%02d", s / 60, s % 60);
    }
}
