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

final class MastermindPanel {
    private final Activity activity;
    private final DroidChessController controller;
    private final MastermindStore store;
    private final LinkedHashMap<String, MastermindCoach.Evaluation> evaluations = new LinkedHashMap<>();
    private MastermindCoach.Evaluation latest;
    private int drillIndex;

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
    }

    void clearEvaluations() {
        evaluations.clear();
        latest = null;
    }

    void show() {
        if (!controller.analysisMode()) {
            message("Mastermind Training", "Training is available in analysis mode only.");
            return;
        }
        String[] actions = { "Explain recommended move", "Record reviewed move", "Recurring mistakes",
                "Add opening drills from this game", "Practice openings", "Clear training data" };
        new AlertDialog.Builder(activity).setTitle(R.string.mastermind_training)
                .setItems(actions, (dialog, selected) -> {
                    switch (selected) {
                    case 0: explain(); break;
                    case 1: record(); break;
                    case 2: message("Recurring mistakes", store.summary()); break;
                    case 3: chooseSide(); break;
                    case 4: practice(); break;
                    case 5: confirmClear(); break;
                    }
                }).setNegativeButton(android.R.string.cancel, null).show();
    }

    private void explain() {
        if (latest == null || !latest.fen.equals(controller.getFEN())) {
            message("Move explanation", "No engine analysis for this position yet.");
            return;
        }
        try {
            message("Move explanation", MastermindCoach.explain(TextIO.readFEN(latest.fen), latest.bestMove));
        } catch (ChessParseError exception) {
            message("Move explanation", "This analysis position is no longer available.");
        }
    }

    private void record() {
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

    private void showDrill(List<MastermindCoach.Drill> drills) {
        MastermindCoach.Drill drill = drills.get(drillIndex % drills.size());
        final Position position;
        try {
            position = TextIO.readFEN(drill.fen);
        } catch (ChessParseError exception) {
            message("Opening drills", "A saved position is invalid. Clear training data to remove it.");
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
        board.setContentDescription("Opening drill chessboard");
        int boardSize = Math.min(activity.getResources().getDisplayMetrics().widthPixels - padding * 4,
                activity.getResources().getDisplayMetrics().heightPixels / 3);
        LinearLayout.LayoutParams boardLayout = new LinearLayout.LayoutParams(boardSize, boardSize);
        boardLayout.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        content.addView(board, boardLayout);
        EditText answer = new EditText(activity);
        answer.setSingleLine(true);
        answer.setHint("Move");
        answer.setContentDescription("Your opening move");
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
                .setTitle("Opening " + (drillIndex + 1) + "/" + drills.size() +
                        (position.whiteMove ? " - White" : " - Black"))
                .setView(scroll).setPositiveButton("Check", null)
                .setNeutralButton("Reveal", null).setNegativeButton("Close", null).create();
        dialog.setOnShowListener(unused -> {
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(view -> {
                if (Boolean.TRUE.equals(feedback.getTag())) {
                    dialog.dismiss();
                    drillIndex = (drillIndex + 1) % drills.size();
                    showDrill(drills);
                    return;
                }
                try {
                    if (drill.accepts(answer.getText().toString())) {
                        feedback.setText("Correct: this matches your played move.");
                        feedback.setTag(Boolean.TRUE);
                        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setText("Next");
                    } else {
                        feedback.setText("That does not match your played move.");
                    }
                } catch (ChessParseError exception) {
                    feedback.setText("This position could not be read.");
                }
            });
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener(view -> {
                Move move = TextIO.UCIstringToMove(drill.answer);
                feedback.setText("Played move: " + TextIO.moveToString(position, move, false, false));
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