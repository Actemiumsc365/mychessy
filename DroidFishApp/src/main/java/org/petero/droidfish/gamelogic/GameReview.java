package org.petero.droidfish.gamelogic;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.petero.droidfish.EngineOptions;
import org.petero.droidfish.PGNOptions;
import org.petero.droidfish.engine.DroidComputerPlayer;

public final class GameReview implements SearchListener {
    public interface Progress {
        void update(int completed, int total);
    }

    private final LinkedBlockingQueue<MastermindCoach.Evaluation> updates = new LinkedBlockingQueue<>(16);
    private volatile String engineError;

    public Map<String, MastermindCoach.Evaluation> analyze(String pgn,
            Map<String, MastermindCoach.Evaluation> cached, Progress progress)
            throws ChessParseError, InterruptedException, IOException {
        GameTree tree = new GameTree(null);
        PGNOptions options = new PGNOptions();
        options.imp.variations = false;
        if (!tree.readPGN(pgn, options))
            throw new ChessParseError("Cannot read the game");
        ArrayList<Position> positions = new ArrayList<>();
        ArrayList<Move> moves = new ArrayList<>();
        positions.add(new Position(tree.currentPos));
        while (!tree.variations().isEmpty()) {
            moves.add(tree.variations().get(0));
            tree.goForward(0);
            positions.add(new Position(tree.currentPos));
        }
        Map<String, MastermindCoach.Evaluation> result = new LinkedHashMap<>();
        ArrayList<Move> history = new ArrayList<>();
        DroidComputerPlayer engine = new DroidComputerPlayer(this);
        engine.setEngineOptions(new EngineOptions());
        try {
            for (int index = 0; index < positions.size(); index++) {
                if (Thread.currentThread().isInterrupted())
                    throw new InterruptedException();
                Position position = positions.get(index);
                String fen = TextIO.toFEN(position);
                MastermindCoach.Evaluation evaluation = cached.get(fen);
                if (evaluation == null || !evaluation.exact || evaluation.depth < MastermindCoach.MIN_DEPTH) {
                    if (MoveGen.instance.legalMoves(position).isEmpty()) {
                        evaluation = new MastermindCoach.Evaluation(fen, 0, MastermindCoach.MIN_DEPTH, null, false);
                    } else {
                        updates.clear();
                        engine.queueAnalyzeRequest(DroidComputerPlayer.SearchRequest.analyzeRequest(index + 1,
                                positions.get(0), new ArrayList<>(history), new Position(position), false, "stockfish", 1));
                        evaluation = await(fen);
                    }
                }
                result.put(fen, evaluation);
                if (index < moves.size())
                    history.add(moves.get(index));
                progress.update(index + 1, positions.size());
            }
            return result;
        } finally {
            engine.shutdownEngine();
        }
    }

    private MastermindCoach.Evaluation await(String fen) throws InterruptedException, IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (engineError != null)
                throw new IOException(engineError);
            MastermindCoach.Evaluation evaluation = updates.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (evaluation != null && fen.equals(evaluation.fen))
                return evaluation;
        }
        throw new IOException(engineError == null ? "Analysis took too long. Try reviewing again." : engineError);
    }

    @Override
    public void notifyPV(int id, Position position, ArrayList<PvInfo> lines, Move ponderMove) {
        if (lines.isEmpty())
            return;
        PvInfo best = lines.get(0);
        if ((!best.isMate && best.depth < MastermindCoach.MIN_DEPTH) ||
                best.upperBound || best.lowerBound || best.pv.isEmpty())
            return;
        updates.offer(new MastermindCoach.Evaluation(TextIO.toFEN(position),
                position.whiteMove ? best.score : -best.score, best.depth, best.pv.get(0),
                !best.isMate, best.pv));
    }

    @Override
    public void reportEngineError(String error) {
        engineError = error;
        updates.offer(new MastermindCoach.Evaluation("", 0, 0, null, false));
    }

    @Override public void notifyDepth(int id, int depth) { }
    @Override public void notifyCurrMove(int id, Position position, Move move, int moveNumber) { }
    @Override public void notifyStats(int id, long nodes, int nps, long hits, int hash, int time, int depth) { }
    @Override public void notifyBookInfo(int id, String book, ArrayList<Move> moves, String eco, int distance) { }
    @Override public void notifySearchResult(int id, String command, Move ponder) { }
    @Override public void notifyEngineName(String name) { }
    @Override public void notifyEngineInitialized() { }
}