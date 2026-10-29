package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.petero.droidfish.PGNOptions;

public final class MastermindCoach {
    public static final int MIN_DEPTH = 12;

    private MastermindCoach() { }

    public static String explain(Position position, Move move) {
        Position before = new Position(position);
        if (move == null || !TextIO.isValid(before, move))
            return "No matching engine recommendation yet.";
        int piece = Piece.makeWhite(before.getPiece(move.from));
        int captured = before.getPiece(move.to);
        String notation = TextIO.moveToString(before, move, false, false);
        StringBuilder text = new StringBuilder("Engine recommendation: ").append(notation).append(". ");
        if (piece == Piece.WKING && Math.abs(move.to - move.from) == 2) {
            text.append("Castling moves the king away from the center and brings the rook into play. ");
        } else {
            text.append("The ").append(pieceName(piece)).append(" moves from ")
                .append(TextIO.squareToString(move.from)).append(" to ")
                .append(TextIO.squareToString(move.to)).append(". ");
        }
        if (captured != Piece.EMPTY)
            text.append("It captures a ").append(pieceName(captured)).append(". ");
        else if (piece == Piece.WPAWN && Position.getX(move.from) != Position.getX(move.to))
            text.append("It captures a pawn en passant. ");
        if (move.promoteTo != Piece.EMPTY)
            text.append("The pawn promotes to a ").append(pieceName(move.promoteTo)).append(". ");
        if (notation.endsWith("#"))
            text.append("This is checkmate. ");
        else if (notation.endsWith("+"))
            text.append("It checks the opposing king. ");
        int file = Position.getX(move.to);
        int rank = Position.getY(move.to);
        if (file >= 2 && file <= 5 && rank >= 2 && rank <= 5)
            text.append("The destination is in the central area. ");
        text.append("These are observable features of the move, not a proof of the engine's full reasoning.");
        return text.toString();
    }

    private static String pieceName(int piece) {
        switch (Piece.makeWhite(piece)) {
        case Piece.WKING: return "king";
        case Piece.WQUEEN: return "queen";
        case Piece.WROOK: return "rook";
        case Piece.WBISHOP: return "bishop";
        case Piece.WKNIGHT: return "knight";
        default: return "pawn";
        }
    }

    public static final class Evaluation {
        public final String fen;
        public final int whiteScore;
        public final int depth;
        public final Move bestMove;
        public final boolean exact;

        public Evaluation(String fen, int whiteScore, int depth, Move bestMove, boolean exact) {
            this.fen = fen;
            this.whiteScore = whiteScore;
            this.depth = depth;
            this.bestMove = bestMove;
            this.exact = exact;
        }
    }

    public static final class Mistake {
        public final String key;
        public final String category;
        public final String move;
        public final String recommendation;
        public final int loss;

        Mistake(String key, String category, String move, String recommendation, int loss) {
            this.key = key;
            this.category = category;
            this.move = move;
            this.recommendation = recommendation;
            this.loss = loss;
        }
    }

    public static Mistake compare(Evaluation before, Evaluation after, Move played)
            throws ChessParseError {
        if (before == null || after == null || !before.exact || !after.exact ||
            before.depth < MIN_DEPTH || after.depth < MIN_DEPTH || played == null)
            return null;
        Position position = TextIO.readFEN(before.fen);
        if (!TextIO.isValid(position, played) || before.bestMove == null ||
            !TextIO.isValid(position, before.bestMove) || played.equals(before.bestMove))
            return null;
        Position result = new Position(position);
        result.makeMove(played, new UndoInfo());
        if (!TextIO.toFEN(result).equals(after.fen))
            return null;
        int loss = (before.whiteScore - after.whiteScore) * (position.whiteMove ? 1 : -1);
        if (loss < 100)
            return null;
        int piece = Piece.makeWhite(position.getPiece(played.from));
        String category;
        if (position.getPiece(played.to) != Piece.EMPTY)
            category = "Captures";
        else if (piece == Piece.WKING)
            category = "King moves";
        else if (piece == Piece.WPAWN)
            category = "Pawn moves";
        else if (position.fullMoveCounter <= 12)
            category = "Opening piece moves";
        else
            category = "Piece moves";
        return new Mistake(before.fen + "|" + TextIO.moveToUCIString(played), category,
                TextIO.moveToString(position, played, false, false),
                TextIO.moveToString(position, before.bestMove, false, false), loss);
    }

    public static final class Drill {
        public final String fen;
        public final String answer;

        public Drill(String fen, String answer) {
            this.fen = fen;
            this.answer = answer;
        }

        public String key() { return fen + "|" + answer; }

        public boolean accepts(String input) throws ChessParseError {
            Move move = TextIO.stringToMove(TextIO.readFEN(fen), input.trim());
            return move != null && TextIO.moveToUCIString(move).equals(answer);
        }
    }

    public static List<Drill> openingDrills(String pgn, boolean white) throws ChessParseError {
        GameTree tree = new GameTree(null);
        PGNOptions options = new PGNOptions();
        options.imp.variations = false;
        if (!tree.readPGN(pgn, options))
            throw new ChessParseError("Cannot read the loaded game");
        List<Drill> drills = new ArrayList<>();
        if (!TextIO.toFEN(tree.startPos).equals(TextIO.startPosFEN))
            return drills;
        for (int ply = 0; ply < 24; ply++) {
            ArrayList<Move> moves = tree.variations();
            if (moves.isEmpty())
                break;
            Move move = moves.get(0);
            if (tree.currentPos.whiteMove == white)
                drills.add(new Drill(TextIO.toFEN(tree.currentPos), TextIO.moveToUCIString(move)));
            tree.goForward(0);
        }
        return drills;
    }

    public static String lossText(int centipawns) {
        return String.format(Locale.US, "%.2f", centipawns / 100.0);
    }
}