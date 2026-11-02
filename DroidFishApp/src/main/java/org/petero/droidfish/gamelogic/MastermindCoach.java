package org.petero.droidfish.gamelogic;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.petero.droidfish.PGNOptions;

public final class MastermindCoach {
    public static final int MIN_DEPTH = 12;

    private MastermindCoach() { }

    public static String explainLine(Position position, List<Move> moves) {
        if (moves == null || moves.isEmpty() || !TextIO.isValid(position, moves.get(0)))
            return "No matching engine recommendation yet.";
        Position replay = new Position(position);
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < Math.min(8, moves.size()); index++) {
            Move move = moves.get(index);
            if (!TextIO.isValid(replay, move))
                break;
            if (index > 0)
                text.append('\n');
            text.append(index == 0 ? "Play: " : index == 1 ? "Best reply: " :
                    replay.whiteMove == position.whiteMove ? "Continue: " : "Opponent: ");
            text.append(TextIO.moveToString(replay, move, false, false));
            replay.makeMove(move, new UndoInfo());
        }
        text.append("\n\n").append(explain(position, moves.get(0)));
        return text.toString();
    }

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
        public final ArrayList<Move> line;

        public Evaluation(String fen, int whiteScore, int depth, Move bestMove, boolean exact) {
            this(fen, whiteScore, depth, bestMove, exact, java.util.Collections.emptyList());
        }

        public Evaluation(String fen, int whiteScore, int depth, Move bestMove, boolean exact,
                          List<Move> line) {
            this.fen = fen;
            this.whiteScore = whiteScore;
            this.depth = depth;
            this.bestMove = bestMove;
            this.exact = exact;
            this.line = new ArrayList<>(line);
        }
    }

    public static String explainMistake(Evaluation before, Evaluation after, Move played)
            throws ChessParseError {
        if (before == null || after == null || !before.exact || !after.exact ||
                before.depth < MIN_DEPTH || after.depth < MIN_DEPTH)
            return "This move needs more analysis before it can be reviewed.";
        Mistake mistake = compare(before, after, played);
        if (mistake == null)
            return "No estimated loss of at least one pawn found for this move.";
        String text = "Played: " + mistake.move + "\nBetter: " + mistake.recommendation +
                "\nEstimated loss: " + lossText(mistake.loss) + " pawns.";
        if (!after.line.isEmpty())
            text += "\n\nOpponent's continuation:\n" + explainLine(TextIO.readFEN(after.fen), after.line);
        return text;
    }

    public static final class Mistake {
        public final String key;
        public final String category;
        public final String move;
        public final String recommendation;
        public final int loss;
        public final Drill practice;

        Mistake(String key, String category, String move, String recommendation, int loss, Drill practice) {
            this.key = key;
            this.category = category;
            this.move = move;
            this.recommendation = recommendation;
            this.loss = loss;
            this.practice = practice;
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
            TextIO.moveToString(position, before.bestMove, false, false), loss,
            new Drill(before.fen, TextIO.moveToUCIString(before.bestMove)));
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

    public static final class Review {
        public int total;
        public int reviewed;
        public final ArrayList<ReviewEntry> mistakes = new ArrayList<>();

        public String summary() throws ChessParseError {
            StringBuilder text = new StringBuilder("Reviewed ").append(reviewed).append(" of ")
                    .append(total).append(" moves.\n");
            if (reviewed < total)
                text.append("Mate-score or incomplete positions are not assigned a pawn-loss estimate.\n");
            if (mistakes.isEmpty())
                return text.append(reviewed == 0 ? "\nNo moves have usable analysis yet." :
                        "\nNo estimated loss of at least one pawn found in the reviewed moves.").toString();
            text.append("\nBiggest mistakes\n");
            for (int index = 0; index < mistakes.size(); index++) {
                ReviewEntry entry = mistakes.get(index);
                text.append('\n').append(index + 1).append(". Move ").append(entry.moveNumber)
                        .append(entry.white ? " (White)\n" : " (Black)\n")
                        .append(explainMistake(entry.before, entry.after, entry.played)).append('\n');
            }
            return text.toString();
        }
    }

    public static final class ReviewEntry {
        public final Evaluation before;
        public final Evaluation after;
        public final Move played;
        public final Mistake mistake;
        public final int moveNumber;
        public final boolean white;

        private ReviewEntry(Evaluation before, Evaluation after, Move played, Mistake mistake,
                            Position position) {
            this.before = before;
            this.after = after;
            this.played = played;
            this.mistake = mistake;
            moveNumber = position.fullMoveCounter;
            white = position.whiteMove;
        }
    }

    public static Review review(String pgn, java.util.Map<String, Evaluation> evaluations,
                                Boolean playerWhite) throws ChessParseError {
        GameTree tree = new GameTree(null);
        PGNOptions options = new PGNOptions();
        options.imp.variations = false;
        if (!tree.readPGN(pgn, options))
            throw new ChessParseError("Cannot read the game");
        Review review = new Review();
        while (!tree.variations().isEmpty()) {
            Position beforePosition = new Position(tree.currentPos);
            Move move = tree.variations().get(0);
            Evaluation before = evaluations.get(TextIO.toFEN(beforePosition));
            tree.goForward(0);
            if (playerWhite != null && beforePosition.whiteMove != playerWhite)
                continue;
            review.total++;
            Evaluation after = evaluations.get(TextIO.toFEN(tree.currentPos));
            if (before == null || after == null || !before.exact || !after.exact ||
                    before.depth < MIN_DEPTH || after.depth < MIN_DEPTH)
                continue;
            review.reviewed++;
            Mistake mistake = compare(before, after, move);
            if (mistake != null)
                review.mistakes.add(new ReviewEntry(before, after, move, mistake, beforePosition));
        }
        review.mistakes.sort((first, second) -> Integer.compare(second.mistake.loss, first.mistake.loss));
        while (review.mistakes.size() > 3)
            review.mistakes.remove(review.mistakes.size() - 1);
        return review;
    }
}