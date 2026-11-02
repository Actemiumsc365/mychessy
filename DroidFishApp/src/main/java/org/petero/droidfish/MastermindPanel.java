package org.petero.droidfish;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.text.InputType;
import android.util.Pair;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;

import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.DroidChessController;
import org.petero.droidfish.gamelogic.MastermindCoach;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.gamelogic.UndoInfo;
import org.petero.droidfish.gamelogic.GameReview;

final class MastermindPanel {
    private final Activity activity;
    private final DroidChessController controller;
    private final MastermindStore store;
    private final LinkedHashMap<String, MastermindCoach.Evaluation> evaluations = new LinkedHashMap<>();
    private MastermindCoach.Evaluation latest;
    private GUIInterface.ThinkingInfo mateThinking;
    private int drillIndex;
    private Pair<Position, Move> lastPlayed;
    private String mistakeNotice;
    private Thread reviewThread;
    private String savedId;
    private String savedTitle;
    private String finishedPosition;
    private android.app.ProgressDialog reviewProgress;

    MastermindPanel(Activity activity, DroidChessController controller) {
        this.activity = activity;
        this.controller = controller;
        store = new MastermindStore(activity);
    }

    void onEvaluation(MastermindCoach.Evaluation evaluation) {
        if (!evaluation.fen.equals(controller.getFEN()))
            return;
        latest = evaluation;
        if (!evaluation.exact || evaluation.depth < MastermindCoach.MIN_DEPTH)
            return;
        MastermindCoach.Evaluation previous = evaluations.get(evaluation.fen);
        if (previous == null || evaluation.depth >= previous.depth) {
            evaluations.remove(evaluation.fen);
            evaluations.put(evaluation.fen, evaluation);
        }
        if (evaluations.size() > 256)
            evaluations.remove(evaluations.keySet().iterator().next());
        updateLastMove();
    }

    void onMovePlayed(Position position, Move move, boolean computerMove) {
        if (!computerMove && move != null && TextIO.isValid(position, move)) {
            lastPlayed = new Pair<>(new Position(position), move);
            mistakeNotice = null;
            updateLastMove();
        }
    }

    private MastermindCoach.Evaluation afterLastMove() {
        if (lastPlayed == null)
            return null;
        Position after = new Position(lastPlayed.first);
        after.makeMove(lastPlayed.second, new UndoInfo());
        return evaluations.get(TextIO.toFEN(after));
    }

    private void updateLastMove() {
        if (lastPlayed == null)
            return;
        try {
            MastermindCoach.Mistake mistake = MastermindCoach.compare(
                    evaluations.get(TextIO.toFEN(lastPlayed.first)), afterLastMove(), lastPlayed.second);
            if (mistake != null) {
                store.record(mistake);
                mistakeNotice = "Your " + mistake.move + ": estimated loss " +
                        MastermindCoach.lossText(mistake.loss) + " pawns. Better: " + mistake.recommendation + ".";
            }
        } catch (ChessParseError ignored) {
            mistakeNotice = null;
        }
    }

    String mistakeNotice() {
        return mistakeNotice;
    }

    String lastMoveExplanation() {
        if (lastPlayed == null)
            return "Play a move first. Its review will appear after the engine analyzes the reply.";
        try {
            return MastermindCoach.explainMistake(evaluations.get(TextIO.toFEN(lastPlayed.first)),
                    afterLastMove(), lastPlayed.second);
        } catch (ChessParseError exception) {
            return "The reviewed position could not be read.";
        }
    }

    void showTools() {
        android.widget.Switch automatic = new android.widget.Switch(activity);
        automatic.setText("Automatic hints");
        int padding = (int)(24 * activity.getResources().getDisplayMetrics().density);
        automatic.setPadding(padding, padding / 2, padding, padding / 2);
        automatic.setChecked(android.preference.PreferenceManager.getDefaultSharedPreferences(activity)
            .getBoolean("automaticHints", true));
        automatic.setOnCheckedChangeListener((button, checked) -> ((DroidFish)activity).setAutomaticHints(checked));
        new AlertDialog.Builder(activity).setTitle(R.string.coach_and_games)
            .setView(automatic)
                .setItems(new String[] { "Explain my last move", "Review game", "Practice mistakes", "Save game", "Saved games", "Share game" }, (dialog, selected) -> {
                    switch (selected) {
                    case 0: message("Your last move", lastMoveExplanation()); break;
                    case 1: reviewGame(); break;
                    case 2: practiceMistakes(); break;
                    case 3: saveGame(); break;
                    case 4: showSavedGames(); break;
                    case 5: ((DroidFish)activity).shareGameOrText(true); break;
                    }
                })
                .setNegativeButton(android.R.string.cancel, null).show();
    }

    void close() {
        if (reviewThread != null)
            reviewThread.interrupt();
        if (reviewProgress != null) {
            reviewProgress.dismiss();
            reviewProgress = null;
        }
    }

    void onGameStatus(boolean finished) {
        if (!finished) {
            finishedPosition = null;
            return;
        }
        String fen = controller.getFEN();
        if (fen.equals(finishedPosition))
            return;
        finishedPosition = fen;
        activity.getWindow().getDecorView().post(() -> {
            if (!activity.isFinishing() && fen.equals(controller.getFEN()))
                new AlertDialog.Builder(activity).setTitle("Game complete")
                        .setMessage("Review the three biggest mistakes?")
                        .setPositiveButton("Review game", (dialog, selected) -> reviewGame())
                        .setNegativeButton("Close", null).show();
        });
    }

    void beginGame() {
        close();
        clearEvaluations();
        savedId = null;
        savedTitle = null;
        finishedPosition = null;
    }

    private void saveGame() {
        EditText name = new EditText(activity);
        name.setSingleLine(true);
        name.setContentDescription("Game name");
        name.setFilters(new android.text.InputFilter[] { new android.text.InputFilter.LengthFilter(80) });
        name.setText(savedTitle == null ? "Game " + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", new java.util.Date()) : savedTitle);
        new AlertDialog.Builder(activity).setTitle("Save game").setView(name)
                .setPositiveButton("Save", (dialog, selected) -> {
                    try {
                        savedId = store.saveGame(savedId, name.getText().toString(),
                                controller.getGameMode().getModeNr(), controller.toByteArray());
                        savedTitle = name.getText().toString().trim();
                        android.widget.Toast.makeText(activity, "Game saved", android.widget.Toast.LENGTH_SHORT).show();
                    } catch (IllegalArgumentException | IllegalStateException exception) {
                        message("Save game", exception.getMessage());
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void showSavedGames() {
        List<MastermindStore.SavedGame> games = store.games();
        if (games.isEmpty()) {
            message("Saved games", "No saved games yet.");
            return;
        }
        String[] names = new String[games.size()];
        for (int index = 0; index < games.size(); index++) {
            MastermindStore.SavedGame game = games.get(index);
            names[index] = game.title + "\n" + android.text.format.DateFormat.format("yyyy-MM-dd HH:mm", game.savedAt);
        }
        new AlertDialog.Builder(activity).setTitle("Saved games").setItems(names, (dialog, selected) -> {
            MastermindStore.SavedGame game = games.get(selected);
            new AlertDialog.Builder(activity).setTitle(game.title).setMessage("Resume this saved position and player side?")
                    .setPositiveButton("Resume", (confirmation, button) -> {
                        try {
                            ((DroidFish)activity).resumeSavedGame(game);
                            clearEvaluations();
                            savedId = game.id;
                            savedTitle = game.title;
                        } catch (Exception exception) {
                            message("Saved games", "This saved game could not be opened. Your current game was kept.");
                        }
                    }).setNeutralButton("Delete", (confirmation, button) ->
                        new AlertDialog.Builder(activity).setTitle("Delete saved game?").setMessage(game.title)
                                .setPositiveButton("Delete", (prompt, choice) -> {
                                    try {
                                        store.deleteGame(game.id);
                                        if (game.id.equals(savedId)) {
                                            savedId = null;
                                            savedTitle = null;
                                        }
                                    } catch (IllegalStateException exception) {
                                        message("Saved games", exception.getMessage());
                                    }
                                }).setNegativeButton(android.R.string.cancel, null).show())
                    .setNegativeButton(android.R.string.cancel, null).show();
        }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void reviewGame() {
        if (reviewThread != null && reviewThread.isAlive())
            return;
        String pgn = controller.getPGN();
        int mode = controller.getGameMode().getModeNr();
        Boolean white = mode == GameMode.PLAYER_WHITE ? Boolean.TRUE :
                mode == GameMode.PLAYER_BLACK ? Boolean.FALSE : null;
        java.util.Map<String, MastermindCoach.Evaluation> cached = new LinkedHashMap<>(evaluations);
        android.app.ProgressDialog progress = new android.app.ProgressDialog(activity);
        reviewProgress = progress;
        progress.setTitle("Review game");
        progress.setMessage("Analyzing positions...");
        progress.setCancelable(true);
        progress.setButton(DialogInterface.BUTTON_NEGATIVE, activity.getString(android.R.string.cancel),
                (dialog, selected) -> close());
        progress.setOnCancelListener(dialog -> close());
        progress.show();
        reviewThread = new Thread(() -> {
            try {
                java.util.Map<String, MastermindCoach.Evaluation> analyzed = new GameReview().analyze(pgn, cached,
                        (done, total) -> activity.runOnUiThread(() -> {
                            if (progress.isShowing())
                                progress.setMessage("Analyzing position " + done + " of " + total);
                        }));
                MastermindCoach.Review review = MastermindCoach.review(pgn, analyzed, white);
                String text = review.summary();
                activity.runOnUiThread(() -> {
                    if (!progress.isShowing() || activity.isFinishing())
                        return;
                    progress.dismiss();
                    evaluations.putAll(analyzed);
                    while (evaluations.size() > 256)
                        evaluations.remove(evaluations.keySet().iterator().next());
                    updateLastMove();
                    for (MastermindCoach.ReviewEntry entry : review.mistakes)
                        store.record(entry.mistake);
                    AlertDialog.Builder result = new AlertDialog.Builder(activity).setTitle("Game review")
                            .setMessage(text).setPositiveButton(android.R.string.ok, null);
                    if (!review.mistakes.isEmpty())
                        result.setNeutralButton("Practice mistakes", (dialog, selected) -> practiceMistakes());
                    result.show();
                });
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                activity.runOnUiThread(() -> {
                    if (progress.isShowing())
                        progress.dismiss();
                });
            } catch (Exception exception) {
                activity.runOnUiThread(() -> {
                    if (progress.isShowing() && !activity.isFinishing()) {
                        progress.dismiss();
                        message("Game review", "Review could not finish: " + exception.getMessage());
                    }
                });
            }
        }, "game-review");
        reviewThread.start();
    }

    void clearEvaluations() {
        evaluations.clear();
        latest = null;
        mateThinking = null;
        lastPlayed = null;
        mistakeNotice = null;
    }

    void onMateThinking(GUIInterface.ThinkingInfo thinking) {
        mateThinking = thinking;
    }

    void show() {
        if (!controller.humansTurn()) {
            message("Best continuation", "The opponent is thinking. Your next hint will appear after its move.");
            return;
        }
        explain();
    }

    private void explain() {
        if (mateThinking != null && controller.getFEN().equals(mateThinking.positionFen)) {
            if (mateThinking.mateResult != null) {
                try {
                    message("Move explanation", "Mate in " + mateThinking.mateResult.distance + ": " +
                            mateThinking.mateResult.line + "\n\n" +
                            MastermindCoach.explain(TextIO.readFEN(mateThinking.positionFen),
                                    mateThinking.mateResult.moves.get(0)));
                } catch (ChessParseError exception) {
                    message("Move explanation", "This analysis position is no longer available.");
                }
                return;
            }
            if (!mateThinking.pvMoves.isEmpty() && !mateThinking.pvMoves.get(0).isEmpty()) {
                try {
                    message("Best continuation", MastermindCoach.explainLine(
                            TextIO.readFEN(mateThinking.positionFen), mateThinking.pvMoves.get(0)));
                } catch (ChessParseError exception) {
                    message("Move explanation", "This analysis position is no longer available.");
                }
                return;
            }
        }
        if (latest == null || !latest.fen.equals(controller.getFEN())) {
            message("Best continuation", "Analyzing your move. The recommendation will appear on the board shortly.");
            return;
        }
        try {
            message("Move explanation", MastermindCoach.explain(TextIO.readFEN(latest.fen), latest.bestMove));
        } catch (ChessParseError exception) {
            message("Move explanation", "This analysis position is no longer available.");
        }
    }

    private void record() {
        if (controller.checkmateOnly()) {
            message("Reviewed move", "Centipawn-loss review is available with Checkmate-only suggestions turned off.");
            return;
        }
        Pair<Position, Move> reviewed = controller.getReviewedMove();
        if (reviewed == null) {
            message("Reviewed move", "No previous move in this game.");
            return;
        }
        MastermindCoach.Evaluation before = evaluations.get(TextIO.toFEN(reviewed.first));
        MastermindCoach.Evaluation after = evaluations.get(controller.getFEN());
        if (before == null || after == null) {
            message("Reviewed move", "Both positions need exact, non-mate analysis at depth 12 or higher. Analyze the position before your move, then the position after it.");
            return;
        }
        try {
            MastermindCoach.Mistake mistake = MastermindCoach.compare(before, after, reviewed.second);
            if (mistake == null) {
                message("Reviewed move", "No estimated loss of at least one pawn recorded for this move.");
            } else {
                boolean added = store.record(mistake);
                message("Reviewed move", (added ? "Saved: " : "Already saved: ") + mistake.move +
                        " instead of " + mistake.recommendation + ". Estimated loss: " +
                        MastermindCoach.lossText(mistake.loss) + " pawns.\n\n" + mistake.category);
            }
        } catch (ChessParseError exception) {
            message("Reviewed move", "The reviewed position could not be read.");
        }
    }

    private void chooseSide() {
        new AlertDialog.Builder(activity).setTitle("Your side in this game")
                .setItems(new String[] { "White", "Black" }, (dialog, selected) -> {
                    try {
                        int added = store.addDrills(MastermindCoach.openingDrills(controller.getPGN(), selected == 0));
                        message("Opening drills", added + " new positions saved.\n\nAnswers are the moves played in your game, not necessarily the engine's best moves.");
                    } catch (ChessParseError exception) {
                        message("Opening drills", "Could not read the loaded game.");
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void practice() {
        List<MastermindCoach.Drill> drills = store.drills();
        if (drills.isEmpty()) {
            message("Opening drills", "No saved opening positions.");
            return;
        }
        Collections.shuffle(drills);
        drillIndex = 0;
        showDrill(drills);
    }

    private void practiceMistakes() {
        List<MastermindCoach.Drill> drills = store.mistakeDrills();
        if (drills.isEmpty()) {
            message("Practice mistakes", "Review a game first. Positions with a verified better move will be saved here.");
            return;
        }
        Collections.shuffle(drills);
        drillIndex = 0;
        showDrill(drills, true);
    }

    private void showDrill(List<MastermindCoach.Drill> drills) {
        showDrill(drills, false);
    }

    void showDrill(List<MastermindCoach.Drill> drills, boolean mistakePractice) {
        String title = mistakePractice ? "Practice mistake" : "Opening";
        MastermindCoach.Drill drill = drills.get(drillIndex % drills.size());
        final Position position;
        try {
            position = TextIO.readFEN(drill.fen);
        } catch (ChessParseError exception) {
            message(title, "This saved position could not be read.");
            return;
        }
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = (int)(16 * activity.getResources().getDisplayMetrics().density);
        content.setPadding(padding, padding, padding, padding);
        ChessBoardPlay board = new ChessBoardPlay(activity, null) {
            @Override
            protected int getMaxWidthPercentage() { return 100; }
            @Override
            protected int getMaxHeightPercentage() { return 100; }
        };
        board.setPgnOptions(new PGNOptions());
        board.setPosition(position);
        board.setFlipped(!position.whiteMove);
        board.setContentDescription(mistakePractice ? "Mistake practice chessboard" : "Opening drill chessboard");
        int boardSize = Math.min(activity.getResources().getDisplayMetrics().widthPixels - padding * 4,
                activity.getResources().getDisplayMetrics().heightPixels / 3);
        LinearLayout.LayoutParams boardLayout = new LinearLayout.LayoutParams(boardSize, boardSize);
        boardLayout.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.addView(board, boardLayout);
        EditText answer = new EditText(activity);
        answer.setSingleLine(true);
        answer.setHint("Move");
        answer.setContentDescription(mistakePractice ? "Your practice move" : "Your opening move");
        answer.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        content.addView(answer);
        TextView feedback = new TextView(activity);
        if (android.os.Build.VERSION.SDK_INT >= 19)
            feedback.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(feedback);
        board.setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                Move move = board.mousePressed(board.eventToSquare(event));
                if (move != null)
                    answer.setText(TextIO.moveToUCIString(move));
                view.performClick();
            }
            return true;
        });
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(title + " " + (drillIndex + 1) + "/" + drills.size() +
                        (position.whiteMove ? " - White" : " - Black"))
                .setView(scroll).setPositiveButton("Check", null)
                .setNeutralButton("Reveal", null).setNegativeButton("Close", null).create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (Boolean.TRUE.equals(feedback.getTag())) {
                    dialog.dismiss();
                    drillIndex = (drillIndex + 1) % drills.size();
                    showDrill(drills, mistakePractice);
                    return;
                }
                try {
                    if (drill.accepts(answer.getText().toString())) {
                        feedback.setText(mistakePractice ? "Correct: this matches the reviewed engine recommendation." :
                            "Correct: this matches your played move.");
                        feedback.setTag(Boolean.TRUE);
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText("Next");
                    } else {
                        feedback.setText(mistakePractice ? "That differs from the reviewed recommendation. Try again." :
                            "That does not match your played move.");
                    }
                } catch (ChessParseError exception) {
                    feedback.setText("This position could not be read.");
                }
            });
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> {
                Move move = TextIO.UCIstringToMove(drill.answer);
                feedback.setText((mistakePractice ? "Reviewed recommendation: " : "Played move: ") +
                    TextIO.moveToString(position, move, false, false));
                feedback.setTag(Boolean.TRUE);
                dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText("Next");
            });
        });
        dialog.show();
    }

    private void confirmClear() {
        new AlertDialog.Builder(activity).setTitle("Clear training data?")
                .setMessage("All saved mistakes and opening drills will be deleted from this app.")
                .setPositiveButton("Delete", (dialog, which) -> {
                    store.clear();
                    clearEvaluations();
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void message(String title, String text) {
        new AlertDialog.Builder(activity).setTitle(title).setMessage(text)
                .setPositiveButton(android.R.string.ok, null).show();
    }
}