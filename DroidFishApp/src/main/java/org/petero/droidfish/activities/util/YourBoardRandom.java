package org.petero.droidfish.activities.util;

import java.util.ArrayList;
import java.util.Random;

import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.MoveGen;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

/** Random piece placement for the Your Board feature. */
public final class YourBoardRandom {
    private static final int MAX_ATTEMPTS = 5000;

    private YourBoardRandom() {
    }

    /** Create a random legal position with random material for both sides, or null. */
    public static Position randomPosition(Random rnd, boolean whiteMove) {
        ArrayList<Integer> pieces = new ArrayList<>();
        addSide(pieces, rnd, true);
        addSide(pieces, rnd, false);
        return place(pieces, rnd, whiteMove);
    }

    /** Place the pieces of pos on random squares, or return null if impossible. */
    public static Position shuffle(Position pos, Random rnd) {
        ArrayList<Integer> pieces = new ArrayList<>();
        for (int sq = 0; sq < 64; sq++) {
            int p = pos.getPiece(sq);
            if (p != Piece.EMPTY)
                pieces.add(p);
        }
        return place(pieces, rnd, pos.whiteMove);
    }

    private static void addSide(ArrayList<Integer> pieces, Random rnd, boolean white) {
        pieces.add(white ? Piece.WKING : Piece.BKING);
        addN(pieces, white ? Piece.WQUEEN : Piece.BQUEEN, rnd.nextInt(2));
        addN(pieces, white ? Piece.WROOK : Piece.BROOK, rnd.nextInt(3));
        addN(pieces, white ? Piece.WBISHOP : Piece.BBISHOP, rnd.nextInt(3));
        addN(pieces, white ? Piece.WKNIGHT : Piece.BKNIGHT, rnd.nextInt(3));
        addN(pieces, white ? Piece.WPAWN : Piece.BPAWN, rnd.nextInt(9));
    }

    private static void addN(ArrayList<Integer> pieces, int piece, int n) {
        for (int i = 0; i < n; i++)
            pieces.add(piece);
    }

    private static Position place(ArrayList<Integer> pieces, Random rnd, boolean whiteMove) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            Position pos = new Position();
            pos.setWhiteMove(whiteMove);
            boolean placed = true;
            for (int p : pieces) {
                int sq = randomFreeSquare(pos, p, rnd);
                if (sq < 0) {
                    placed = false;
                    break;
                }
                pos.setPiece(sq, p);
            }
            if (placed && isPlayable(pos))
                return pos;
        }
        return null;
    }

    private static int randomFreeSquare(Position pos, int piece, Random rnd) {
        boolean pawn = (piece == Piece.WPAWN) || (piece == Piece.BPAWN);
        ArrayList<Integer> free = new ArrayList<>();
        for (int sq = 0; sq < 64; sq++) {
            int y = Position.getY(sq);
            if (pawn && ((y == 0) || (y == 7)))
                continue;
            if (pos.getPiece(sq) == Piece.EMPTY)
                free.add(sq);
        }
        return free.isEmpty() ? -1 : free.get(rnd.nextInt(free.size()));
    }

    /** True if the position is valid and the side to move has at least one legal move. */
    public static boolean isPlayable(Position pos) {
        try {
            Position p = TextIO.readFEN(TextIO.toFEN(pos));
            return !MoveGen.instance.legalMoves(p).isEmpty();
        } catch (ChessParseError e) {
            return false;
        }
    }
}
