package org.petero.droidfish.activities;

import java.util.Random;

import org.petero.droidfish.DroidFishApp;
import org.petero.droidfish.R;
import org.petero.droidfish.Util;
import org.petero.droidfish.Util.MaterialDiff;
import org.petero.droidfish.activities.util.ChessBoardYour;
import org.petero.droidfish.activities.util.YourBoardRandom;
import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.drawerlayout.widget.DrawerLayout;

/**
 * Your Board: build any position (add, remove or randomly place pieces),
 * then return it to the main screen for engine analysis.
 */
@SuppressLint("ClickableViewAccessibility")
public class YourBoard extends Activity {
    private static final int NO_DRAG = -1;

    private ChessBoardYour cb;
    private TextView status;
    private TextView whiteFigText;
    private TextView blackFigText;
    private Typeface figNotation;
    private boolean boardFlipped;

    private DrawerLayout drawerLayout;
    private ListView leftDrawer;

    private final Random rnd = new Random();

    /** Board square or palette square (negative) the dragged piece came from. */
    private int dragFrom = NO_DRAG;
    private int dragPiece = Piece.EMPTY;
    private Position dragOrigPos;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        figNotation = Typeface.createFromAsset(getAssets(), "fonts/DroidFishChessNotationDark.otf");

        SharedPreferences settings = PreferenceManager.getDefaultSharedPreferences(this);
        boardFlipped = settings.getBoolean("boardFlipped", false);

        initUI();
        Util.setFullScreenMode(this, settings);

        try {
            cb.setPosition(TextIO.readFEN(TextIO.startPosFEN));
        } catch (ChessParseError ignore) {
        }
        checkValid();
        DroidFishApp.toast(R.string.your_board_help, Toast.LENGTH_LONG);
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(DroidFishApp.setLanguage(newBase, false));
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Position pos = (dragOrigPos != null) ? dragOrigPos : cb.pos;
        int selected = cb.selectedSquare;
        endDrag();
        initUI();
        cb.setPosition(pos);
        cb.setSelection(selected);
        checkValid();
    }

    private void initUI() {
        setContentView(R.layout.editboard);
        // Swap in the drag-capable board so the shared editboard layout stays unchanged.
        View placeholder = findViewById(R.id.eb_chessboard);
        ViewGroup parent = (ViewGroup) placeholder.getParent();
        int index = parent.indexOfChild(placeholder);
        cb = new ChessBoardYour(this);
        cb.setId(R.id.eb_chessboard);
        parent.removeViewAt(index);
        parent.addView(cb, index, placeholder.getLayoutParams());
        Util.overrideViewAttribs(findViewById(R.id.main));

        cb.setFlipped(boardFlipped);
        status = findViewById(R.id.eb_status);
        Button analyzeButton = findViewById(R.id.eb_ok);
        analyzeButton.setText(R.string.your_board_analyze);
        Button cancelButton = findViewById(R.id.eb_cancel);

        TextView whiteTitleText = findViewById(R.id.white_clock);
        whiteTitleText.setVisibility(View.GONE);
        TextView blackTitleText = findViewById(R.id.black_clock);
        blackTitleText.setVisibility(View.GONE);
        findViewById(R.id.title_text).setVisibility(View.GONE);
        whiteFigText = findViewById(R.id.white_pieces);
        whiteFigText.setTypeface(figNotation);
        whiteFigText.setTextColor(whiteTitleText.getTextColors());
        blackFigText = findViewById(R.id.black_pieces);
        blackFigText.setTypeface(figNotation);
        blackFigText.setTextColor(blackTitleText.getTextColors());
        TextView summaryTitleText = findViewById(R.id.title_text_summary);
        summaryTitleText.setText(R.string.your_board);

        initDrawer();

        View.OnClickListener openDrawer = v -> drawerLayout.openDrawer(Gravity.LEFT);
        findViewById(R.id.first_title_line).setOnClickListener(openDrawer);
        findViewById(R.id.second_title_line).setOnClickListener(openDrawer);

        analyzeButton.setOnClickListener(v -> startAnalysis());
        cancelButton.setOnClickListener(v -> {
            setResult(RESULT_CANCELED);
            finish();
        });

        status.setFocusable(false);
        cb.setFocusable(true);
        cb.requestFocus();
        cb.setClickable(true);
        cb.setOnTouchListener((v, event) -> {
            int x = (int) event.getX();
            int y = (int) event.getY();
            switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                startDrag(cb.eventToSquare(event), x, y);
                break;
            case MotionEvent.ACTION_MOVE:
                if (dragFrom != NO_DRAG)
                    cb.setFloatingPiece(dragPiece, x, y);
                break;
            case MotionEvent.ACTION_UP:
                drop(cb.eventToSquare(event));
                break;
            case MotionEvent.ACTION_CANCEL:
                if (dragOrigPos != null)
                    cb.setPosition(dragOrigPos);
                endDrag();
                break;
            }
            return true;
        });
    }

    private static int paletteToPiece(int sq) {
        return (sq < -1) ? -(sq + 2) : Piece.EMPTY;
    }

    private void startDrag(int sq, int x, int y) {
        endDrag();
        int piece = (sq >= 0) ? cb.pos.getPiece(sq) : paletteToPiece(sq);
        if (piece == Piece.EMPTY) {
            dragFrom = sq;
            return;
        }
        dragFrom = sq;
        dragPiece = piece;
        if (sq >= 0) {
            dragOrigPos = new Position(cb.pos);
            Position lifted = new Position(cb.pos);
            lifted.setPiece(sq, Piece.EMPTY);
            cb.setPosition(lifted);
        }
        cb.setFloatingPiece(piece, x, y);
    }

    /** Finish a drag. Dropping off the board removes a board piece. */
    private void drop(int target) {
        int from = dragFrom;
        int piece = dragPiece;
        Position orig = dragOrigPos;
        endDrag();
        if (from == NO_DRAG)
            return;

        if (target == from) {
            // A tap: toggle palette selection, or put the selected palette piece on the square.
            if (orig != null)
                cb.setPosition(orig);
            if (from < -1) {
                if (piece != Piece.EMPTY)
                    cb.setSelection((cb.selectedSquare == from) ? -1 : from);
            } else if (from >= 0) {
                int selectedPiece = paletteToPiece(cb.selectedSquare);
                if (selectedPiece != Piece.EMPTY) {
                    Position pos = new Position(cb.pos);
                    pos.setPiece(from, (pos.getPiece(from) == selectedPiece) ? Piece.EMPTY : selectedPiece);
                    cb.setPosition(pos);
                    checkValid();
                }
            }
            return;
        }

        if (piece == Piece.EMPTY)
            return;
        Position pos = new Position(cb.pos);
        if (target >= 0)
            pos.setPiece(target, piece);
        cb.setPosition(pos);
        checkValid();
    }

    private void endDrag() {
        dragFrom = NO_DRAG;
        dragPiece = Piece.EMPTY;
        dragOrigPos = null;
        if (cb != null)
            cb.setFloatingPiece(Piece.EMPTY, 0, 0);
    }

    private void initDrawer() {
        drawerLayout = findViewById(R.id.drawer_layout);
        leftDrawer = findViewById(R.id.left_drawer);

        final int[] itemIds = {
            R.string.random_position,
            R.string.shuffle_pieces,
            R.string.clear_board,
            R.string.initial_position,
            R.string.side_to_move,
            R.string.flip_board,
        };
        String[] labels = new String[itemIds.length];
        for (int i = 0; i < itemIds.length; i++)
            labels[i] = getString(itemIds[i]);

        leftDrawer.setAdapter(new ArrayAdapter<>(this, R.layout.drawer_list_item, labels));
        leftDrawer.setOnItemClickListener((parent, view, position, id) -> {
            drawerLayout.closeDrawer(Gravity.LEFT);
            leftDrawer.clearChoices();
            int item = itemIds[position];
            if (item == R.string.random_position) {
                setRandomPosition(YourBoardRandom.randomPosition(rnd, cb.pos.whiteMove));
            } else if (item == R.string.shuffle_pieces) {
                setRandomPosition(YourBoardRandom.shuffle(cb.pos, rnd));
            } else if (item == R.string.clear_board) {
                setPosition(new Position());
            } else if (item == R.string.initial_position) {
                try {
                    setPosition(TextIO.readFEN(TextIO.startPosFEN));
                } catch (ChessParseError ignore) {
                }
            } else if (item == R.string.side_to_move) {
                showSideToMoveDialog();
            } else if (item == R.string.flip_board) {
                boardFlipped = !boardFlipped;
                cb.setFlipped(boardFlipped);
            }
        });
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        drawerLayout.openDrawer(Gravity.LEFT);
        return false;
    }

    private void showSideToMoveDialog() {
        new AlertDialog.Builder(this)
            .setTitle(R.string.select_side_to_move_first)
            .setSingleChoiceItems(new String[]{ getString(R.string.white), getString(R.string.black) },
                                  cb.pos.whiteMove ? 0 : 1, (dialog, which) -> {
                cb.pos.setWhiteMove(which == 0);
                cb.pos.setEpSquare(-1);
                checkValid();
                dialog.dismiss();
            })
            .show();
    }

    private void setRandomPosition(Position pos) {
        if (pos == null) {
            DroidFishApp.toast(R.string.your_board_random_failed, Toast.LENGTH_SHORT);
            return;
        }
        setPosition(pos);
    }

    private void setPosition(Position pos) {
        cb.setPosition(pos);
        cb.setSelection(-1);
        checkValid();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            setResult(RESULT_CANCELED);
            finish();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void startAnalysis() {
        if (!checkValid())
            return;
        TextIO.removeBogusCastleFlags(cb.pos);
        if (!YourBoardRandom.isPlayable(cb.pos)) {
            DroidFishApp.toast(R.string.your_board_no_moves, Toast.LENGTH_SHORT);
            return;
        }
        setResult(RESULT_OK, new Intent().setAction(TextIO.toFEN(cb.pos)));
        finish();
    }

    /** Validate the position and update the material display. */
    private boolean checkValid() {
        MaterialDiff md = Util.getMaterialDiff(cb.pos);
        whiteFigText.setText(md.white);
        blackFigText.setText(md.black);
        try {
            TextIO.readFEN(TextIO.toFEN(cb.pos));
            status.setText(cb.pos.whiteMove ? R.string.your_board_white_to_move
                                            : R.string.your_board_black_to_move);
            return true;
        } catch (ChessParseError e) {
            status.setText(e.resourceId == -1 ? e.getMessage() : getString(e.resourceId));
            return false;
        }
    }
}
