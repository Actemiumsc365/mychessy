package org.petero.droidfish.online;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.json.JSONObject;
import org.petero.droidfish.ChessBoardPlay;
import org.petero.droidfish.DroidFishApp;
import org.petero.droidfish.R;
import org.petero.droidfish.gamelogic.ChessParseError;
import org.petero.droidfish.gamelogic.Move;
import org.petero.droidfish.gamelogic.Piece;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;
import org.petero.droidfish.gamelogic.UndoInfo;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

/** Play real people over the internet through the Lichess Board API. */
@SuppressLint("ClickableViewAccessibility")
public class OnlinePlay extends Activity {
    private static final String PREFS = "afg_online";
    private static final String TOKEN_URL =
            "https://lichess.org/account/oauth/token/create?scopes%5B%5D=board:play&description=AFGChess";
    private static final int[][] TIME_CONTROLS = { { 10, 0 }, { 10, 5 }, { 15, 10 }, { 30, 0 } };

    private enum UiState { SIGNED_OUT, LOBBY, WAITING, PLAYING }

    private SharedPreferences prefs;
    private LichessClient client;
    private String userId = "";
    private String username = "";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean destroyed;
    private LichessClient.Stream eventStream;
    private LichessClient.Stream gameStream;
    private LichessClient.Stream waitStream;
    private boolean waitingForSeek;

    private ChessBoardPlay board;
    private TextView status;
    private TextView topName, topClock, bottomName, bottomClock;
    private Button action1, action2, action3;
    private UiState uiState = UiState.SIGNED_OUT;

    // Current game, only touched on the UI thread.
    private String gameId;
    private boolean iAmWhite = true;
    private String initialFen = "startpos";
    private String whiteLabel = "";
    private String blackLabel = "";
    private String serverMoves = "";
    private Position pos;
    private int plies;
    private long wtime, btime, stateTime;
    private boolean clockRunning;
    private boolean drawDialogShown;
    private final Set<String> answeredChallenges = new HashSet<>();

    private int touchSq = -1;
    private int dragFrom = -1;
    private float downX, downY;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setContentView(R.layout.online_play);
        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);

        board = findViewById(R.id.online_board);
        status = findViewById(R.id.online_status);
        topName = findViewById(R.id.online_top_name);
        topClock = findViewById(R.id.online_top_clock);
        bottomName = findViewById(R.id.online_bottom_name);
        bottomClock = findViewById(R.id.online_bottom_clock);
        action1 = findViewById(R.id.online_action1);
        action2 = findViewById(R.id.online_action2);
        action3 = findViewById(R.id.online_action3);

        board.setColors();
        showStartPosition();
        board.setOnTouchListener(this::onBoardTouch);

        String token = prefs.getString("token", null);
        if (LichessGame.isValidToken(token)) {
            client = new LichessClient(token);
            userId = prefs.getString("userId", "");
            username = prefs.getString("username", "");
            enterLobby(getString(R.string.online_welcome, username));
        } else {
            setUiState(UiState.SIGNED_OUT);
            status.setText(R.string.online_sign_in_info);
        }
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(DroidFishApp.setLanguage(newBase, false));
    }

    @Override
    protected void onStart() {
        super.onStart();
        ui.removeCallbacks(clockTicker);
        ui.post(clockTicker);
        if (client != null) {
            startEventStream();
            if ((gameId != null) && (uiState == UiState.PLAYING) && (gameStream == null))
                startGame(gameId);
        }
    }

    /** Nothing runs while the screen is hidden; Lichess replays the game state on return. */
    @Override
    protected void onStop() {
        ui.removeCallbacks(clockTicker);
        cancel(eventStream);
        eventStream = null;
        cancel(gameStream);
        gameStream = null;
        if (waitingForSeek) {
            cancel(waitStream); // A seek left open could pair us into an unseen game.
            waitStream = null;
            waitingForSeek = false;
            enterLobby(getString(R.string.online_cancelled));
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        ui.removeCallbacksAndMessages(null);
        cancel(eventStream);
        cancel(gameStream);
        cancel(waitStream);
        super.onDestroy();
    }

    private static void cancel(LichessClient.Stream s) {
        if (s != null)
            s.cancel();
    }

    // ---------------------------------------------------------------- UI state

    private void setUiState(UiState state) {
        uiState = state;
        action1.setVisibility(View.VISIBLE);
        action2.setVisibility(View.VISIBLE);
        action3.setVisibility(View.VISIBLE);
        switch (state) {
        case SIGNED_OUT:
            action1.setText(R.string.online_sign_in);
            action1.setOnClickListener(v -> showSignInDialog());
            action2.setText(R.string.online_get_token);
            action2.setOnClickListener(v -> openTokenPage());
            action3.setVisibility(View.GONE);
            break;
        case LOBBY:
            action1.setText(R.string.online_quick_game);
            action1.setOnClickListener(v -> chooseTimeControl(this::startSeek));
            action2.setText(R.string.online_play_friend);
            action2.setOnClickListener(v -> showFriendDialog());
            action3.setText(R.string.online_sign_out);
            action3.setOnClickListener(v -> confirmSignOut());
            break;
        case WAITING:
            action1.setText(R.string.cancel);
            action1.setOnClickListener(v -> cancelWaiting());
            action2.setVisibility(View.GONE);
            action3.setVisibility(View.GONE);
            break;
        case PLAYING:
            action1.setText(R.string.online_offer_draw);
            action1.setOnClickListener(v -> confirm(R.string.online_offer_draw_q,
                    () -> gameAction("/draw/yes")));
            action2.setText(R.string.online_resign);
            action2.setOnClickListener(v -> confirm(R.string.online_resign_q,
                    () -> gameAction(plies < 2 ? "/abort" : "/resign")));
            action3.setVisibility(View.GONE);
            break;
        }
    }

    private void enterLobby(String message) {
        waitingForSeek = false;
        setUiState(UiState.LOBBY);
        status.setText(message);
    }

    private void showStartPosition() {
        try {
            pos = TextIO.readFEN(TextIO.startPosFEN);
        } catch (ChessParseError ignore) {
        }
        board.setPosition(pos);
        board.setSelection(-1);
        topName.setText("");
        bottomName.setText(username);
        topClock.setText("");
        bottomClock.setText("");
    }

    private void confirm(int messageId, Runnable action) {
        new AlertDialog.Builder(this)
            .setMessage(messageId)
            .setPositiveButton(android.R.string.yes, (d, w) -> action.run())
            .setNegativeButton(android.R.string.no, null)
            .show();
    }

    private void toast(String msg) {
        DroidFishApp.toast(msg, Toast.LENGTH_LONG);
    }

    /** Run network work in the background, errors are shown as toasts. */
    private void background(NetTask task) {
        new Thread(() -> {
            try {
                task.run();
            } catch (IOException e) {
                ui.post(() -> {
                    if (!destroyed)
                        toast(getString(R.string.online_error, e.getMessage()));
                });
            }
        }).start();
    }

    private interface NetTask {
        void run() throws IOException;
    }

    // ---------------------------------------------------------------- Sign in

    private void openTokenPage() {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(TOKEN_URL)));
        } catch (ActivityNotFoundException e) {
            toast(e.getMessage());
        }
    }

    private void showSignInDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint(R.string.online_token_hint);
        new AlertDialog.Builder(this)
            .setTitle(R.string.online_sign_in)
            .setMessage(R.string.online_sign_in_info)
            .setView(input)
            .setPositiveButton(R.string.online_sign_in, (d, w) -> signIn(input.getText().toString().trim()))
            .setNeutralButton(R.string.online_get_token, (d, w) -> openTokenPage())
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void signIn(String token) {
        if (!LichessGame.isValidToken(token)) {
            toast(getString(R.string.online_bad_token));
            return;
        }
        status.setText(R.string.online_checking);
        final LichessClient c = new LichessClient(token);
        background(() -> {
            JSONObject account = c.get("/api/account");
            String id = account.optString("id", "");
            String name = account.optString("username", id);
            ui.post(() -> {
                if (destroyed)
                    return;
                if (id.isEmpty()) {
                    status.setText(R.string.online_bad_token);
                    return;
                }
                prefs.edit().putString("token", token).putString("userId", id)
                     .putString("username", name).apply();
                client = c;
                userId = id;
                username = name;
                bottomName.setText(username);
                enterLobby(getString(R.string.online_welcome, username));
                startEventStream();
            });
        });
    }

    private void confirmSignOut() {
        confirm(R.string.online_sign_out_q, () -> {
            final LichessClient c = client;
            cancel(eventStream);
            eventStream = null;
            client = null;
            prefs.edit().clear().apply();
            userId = "";
            username = "";
            showStartPosition();
            setUiState(UiState.SIGNED_OUT);
            status.setText(R.string.online_sign_in_info);
            new Thread(() -> {
                try {
                    c.delete("/api/token"); // Revoke the token on the server too.
                } catch (IOException ignore) {
                }
            }).start();
        });
    }

    // ---------------------------------------------------------------- Event stream

    private void startEventStream() {
        if (eventStream != null)
            return;
        final LichessClient c = client;
        final LichessClient.Stream s = new LichessClient.Stream();
        eventStream = s;
        new Thread(() -> {
            while (!destroyed && !s.isCancelled()) {
                try {
                    c.stream("GET", "/api/stream/event", null, s,
                             obj -> ui.post(() -> { if (!destroyed && eventStream == s) onEvent(obj); }));
                } catch (IOException e) {
                    if (e.getMessage() != null && e.getMessage().startsWith("Not authorized")) {
                        ui.post(() -> {
                            if (!destroyed)
                                status.setText(e.getMessage());
                        });
                        return;
                    }
                }
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }).start();
    }

    private void onEvent(JSONObject obj) {
        String type = obj.optString("type");
        if ("gameStart".equals(type)) {
            JSONObject g = obj.optJSONObject("game");
            if (g == null)
                return;
            String id = g.optString("gameId", g.optString("id", ""));
            JSONObject variant = g.optJSONObject("variant");
            if ((variant != null) && !"standard".equals(variant.optString("key", "standard"))) {
                toast(getString(R.string.online_variant_unsupported));
                return;
            }
            if (LichessGame.isValidId(id) && ((gameId == null) || !gameId.equals(id)) &&
                (uiState != UiState.PLAYING))
                startGame(id);
        } else if ("challenge".equals(type)) {
            onChallenge(obj.optJSONObject("challenge"));
        } else if ("challengeDeclined".equals(type)) {
            if (uiState == UiState.WAITING) {
                cancel(waitStream);
                waitStream = null;
                enterLobby(getString(R.string.online_challenge_declined));
            }
        }
    }

    private void onChallenge(JSONObject c) {
        if (c == null)
            return;
        String id = c.optString("id", "");
        JSONObject challenger = c.optJSONObject("challenger");
        if (!LichessGame.isValidId(id) || (challenger == null) ||
            userId.equalsIgnoreCase(challenger.optString("id")) || !answeredChallenges.add(id))
            return;
        JSONObject variant = c.optJSONObject("variant");
        if ((variant != null) && !"standard".equals(variant.optString("key", "standard"))) {
            background(() -> client.post("/api/challenge/" + id + "/decline", form("reason", "standard")));
            return;
        }
        if (uiState == UiState.PLAYING) {
            background(() -> client.post("/api/challenge/" + id + "/decline", form("reason", "later")));
            return;
        }
        String who = challenger.optString("name", "?");
        int rating = challenger.optInt("rating", 0);
        if (rating > 0)
            who += " (" + rating + ")";
        JSONObject tc = c.optJSONObject("timeControl");
        String time = (tc != null) ? tc.optString("show", "") : "";
        String rated = c.optBoolean("rated") ? getString(R.string.online_rated) : getString(R.string.online_casual);
        new AlertDialog.Builder(this)
            .setTitle(R.string.online_challenge_received)
            .setMessage(getString(R.string.online_challenge_text, who, time, rated))
            .setPositiveButton(R.string.online_accept,
                    (d, w) -> background(() -> client.post("/api/challenge/" + id + "/accept", null)))
            .setNegativeButton(R.string.online_decline,
                    (d, w) -> background(() -> client.post("/api/challenge/" + id + "/decline", null)))
            .setCancelable(false)
            .show();
    }

    private static Map<String, String> form(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
            m.put(kv[i], kv[i + 1]);
        return m;
    }

    // ---------------------------------------------------------------- Seek / challenge

    private interface TimeChoice {
        void chosen(int minutes, int increment);
    }

    private void chooseTimeControl(TimeChoice choice) {
        String[] items = new String[TIME_CONTROLS.length];
        for (int i = 0; i < items.length; i++)
            items[i] = getString(R.string.online_time_item, TIME_CONTROLS[i][0], TIME_CONTROLS[i][1]);
        new AlertDialog.Builder(this)
            .setTitle(R.string.online_choose_time)
            .setItems(items, (d, which) -> choice.chosen(TIME_CONTROLS[which][0], TIME_CONTROLS[which][1]))
            .show();
    }

    private void startSeek(int minutes, int increment) {
        setUiState(UiState.WAITING);
        waitingForSeek = true;
        status.setText(getString(R.string.online_searching, minutes, increment));
        final LichessClient c = client;
        final LichessClient.Stream s = new LichessClient.Stream();
        waitStream = s;
        new Thread(() -> {
            String error = null;
            try {
                c.stream("POST", "/api/board/seek",
                         form("rated", "false", "time", Integer.toString(minutes),
                              "increment", Integer.toString(increment), "variant", "standard"),
                         s, obj -> { });
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String err = error;
            // Give the event stream a moment to deliver gameStart before reporting failure.
            ui.postDelayed(() -> {
                if (destroyed || waitStream != s)
                    return;
                waitStream = null;
                if (uiState == UiState.WAITING)
                    enterLobby(err != null ? getString(R.string.online_error, err)
                                           : getString(R.string.online_no_opponent));
            }, 1500);
        }).start();
    }

    private void showFriendDialog() {
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.online_friend_hint);
        new AlertDialog.Builder(this)
            .setTitle(R.string.online_play_friend)
            .setView(input)
            .setPositiveButton(android.R.string.ok, (d, w) -> {
                String friend = input.getText().toString().trim();
                if (!LichessGame.isValidId(friend)) {
                    toast(getString(R.string.online_bad_username));
                    return;
                }
                chooseTimeControl((min, inc) -> startChallenge(friend, min, inc));
            })
            .setNegativeButton(R.string.cancel, null)
            .show();
    }

    private void startChallenge(String friend, int minutes, int increment) {
        setUiState(UiState.WAITING);
        status.setText(getString(R.string.online_challenge_sending, friend));
        final LichessClient c = client;
        final LichessClient.Stream s = new LichessClient.Stream();
        waitStream = s;
        new Thread(() -> {
            String error = null;
            try {
                c.stream("POST", "/api/challenge/" + friend,
                         form("rated", "false", "clock.limit", Integer.toString(minutes * 60),
                              "clock.increment", Integer.toString(increment),
                              "color", "random", "variant", "standard", "keepAliveStream", "true"),
                         s, obj -> ui.post(() -> onChallengeStreamLine(s, friend, obj)));
            } catch (IOException e) {
                error = e.getMessage();
            }
            final String err = error;
            ui.postDelayed(() -> {
                if (destroyed || waitStream != s)
                    return;
                waitStream = null;
                if (uiState == UiState.WAITING)
                    enterLobby(err != null ? getString(R.string.online_error, err)
                                           : getString(R.string.online_challenge_ended));
            }, 1500);
        }).start();
    }

    private void onChallengeStreamLine(LichessClient.Stream s, String friend, JSONObject obj) {
        if (destroyed || waitStream != s)
            return;
        JSONObject ch = obj.has("challenge") ? obj.optJSONObject("challenge") : obj;
        String id = (ch != null) ? ch.optString("id", "") : "";
        if (LichessGame.isValidId(id))
            status.setText(getString(R.string.online_challenge_sent, friend, "https://lichess.org/" + id));
        String done = obj.optString("done", "");
        if ("declined".equals(done))
            status.setText(R.string.online_challenge_declined);
    }

    private void cancelWaiting() {
        LichessClient.Stream s = waitStream;
        waitStream = null;
        cancel(s);
        enterLobby(getString(R.string.online_cancelled));
    }

    // ---------------------------------------------------------------- Game

    private void startGame(String id) {
        cancel(waitStream);
        waitStream = null;
        waitingForSeek = false;
        cancel(gameStream);
        gameId = id;
        serverMoves = "";
        initialFen = "startpos";
        drawDialogShown = false;
        setUiState(UiState.PLAYING);
        status.setText(R.string.online_game_starting);
        final LichessClient c = client;
        final LichessClient.Stream s = new LichessClient.Stream();
        gameStream = s;
        new Thread(() -> {
            int failures = 0;
            while (!destroyed && !s.isCancelled() && failures < 5) {
                try {
                    c.stream("GET", "/api/board/game/stream/" + id, null, s,
                             obj -> ui.post(() -> { if (!destroyed && gameStream == s) onGameLine(obj); }));
                    return; // Server closes the stream when the game ends.
                } catch (IOException e) {
                    failures++;
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ie) {
                        return;
                    }
                }
            }
        }).start();
    }

    private void onGameLine(JSONObject obj) {
        String type = obj.optString("type");
        if ("gameFull".equals(type)) {
            JSONObject w = obj.optJSONObject("white");
            JSONObject b = obj.optJSONObject("black");
            whiteLabel = playerLabel(w);
            blackLabel = playerLabel(b);
            iAmWhite = (w != null) && userId.equalsIgnoreCase(w.optString("id"));
            initialFen = obj.optString("initialFen", "startpos");
            board.setFlipped(!iAmWhite);
            topName.setText(iAmWhite ? blackLabel : whiteLabel);
            bottomName.setText(iAmWhite ? whiteLabel : blackLabel);
            JSONObject state = obj.optJSONObject("state");
            if (state != null)
                applyState(state);
        } else if ("gameState".equals(type)) {
            applyState(obj);
        } else if ("opponentGone".equals(type)) {
            if (obj.optBoolean("gone"))
                status.setText(R.string.online_opponent_gone);
            else
                updateTurnStatus();
        }
    }

    private String playerLabel(JSONObject p) {
        if (p == null)
            return "?";
        if (p.has("aiLevel"))
            return getString(R.string.online_ai, p.optInt("aiLevel"));
        String name = p.optString("name", "?");
        String title = p.optString("title", "");
        if (!title.isEmpty() && !"null".equals(title))
            name = title + " " + name;
        int rating = p.optInt("rating", 0);
        return rating > 0 ? name + " (" + rating + ")" : name;
    }

    private void applyState(JSONObject s) {
        serverMoves = s.optString("moves", "");
        if (!setPositionFromMoves(serverMoves))
            return;
        wtime = s.optLong("wtime", 0);
        btime = s.optLong("btime", 0);
        stateTime = SystemClock.elapsedRealtime();
        String st = s.optString("status", "started");
        clockRunning = plies >= 2 && ("started".equals(st));
        updateClocks();

        if (!"started".equals(st) && !"created".equals(st)) {
            endGame(st, s.optString("winner", ""));
            return;
        }
        boolean opponentOffersDraw = iAmWhite ? s.optBoolean("bdraw") : s.optBoolean("wdraw");
        if (opponentOffersDraw && !drawDialogShown) {
            drawDialogShown = true;
            new AlertDialog.Builder(this)
                .setMessage(R.string.online_draw_offered)
                .setPositiveButton(R.string.online_accept, (d, w) -> gameAction("/draw/yes"))
                .setNegativeButton(R.string.online_decline, (d, w) -> gameAction("/draw/no"))
                .show();
        } else if (!opponentOffersDraw) {
            drawDialogShown = false;
        }
        updateTurnStatus();
    }

    private boolean setPositionFromMoves(String moves) {
        try {
            LichessGame.Replay r = LichessGame.replay(initialFen, moves);
            pos = r.pos;
            plies = r.plies;
            board.setPosition(pos);
            board.setSelection(r.lastMove != null ? r.lastMove.to : -1);
            board.userSelectedSquare = false;
            return true;
        } catch (ChessParseError e) {
            status.setText(getString(R.string.online_error, e.getMessage()));
            return false;
        }
    }

    private boolean myTurn() {
        return (uiState == UiState.PLAYING) && (pos != null) && (pos.whiteMove == iAmWhite);
    }

    private void updateTurnStatus() {
        if (uiState == UiState.PLAYING)
            status.setText(myTurn() ? R.string.online_your_move : R.string.online_their_move);
    }

    private void endGame(String st, String winner) {
        cancel(gameStream);
        gameStream = null;
        clockRunning = false;
        String result;
        if (winner.isEmpty())
            result = getString(R.string.online_result_draw);
        else if ("white".equals(winner) == iAmWhite)
            result = getString(R.string.online_result_win);
        else
            result = getString(R.string.online_result_loss);
        String msg = result + " (" + st + ")";
        if ("aborted".equals(st) || "noStart".equals(st))
            msg = getString(R.string.online_result_aborted);
        enterLobby(msg);
        gameId = null;
        new AlertDialog.Builder(this)
            .setTitle(R.string.online_game_over)
            .setMessage(msg)
            .setPositiveButton(android.R.string.ok, null)
            .show();
    }

    private void gameAction(String suffix) {
        final String id = gameId;
        if (id == null)
            return;
        background(() -> client.post("/api/board/game/" + id + suffix, null));
    }

    // ---------------------------------------------------------------- Moves

    private boolean onBoardTouch(View v, MotionEvent event) {
        if (!myTurn())
            return true;
        int x = (int) event.getX();
        int y = (int) event.getY();
        switch (event.getActionMasked()) {
        case MotionEvent.ACTION_DOWN:
            touchSq = board.eventToSquare(event);
            downX = event.getX();
            downY = event.getY();
            dragFrom = -1;
            break;
        case MotionEvent.ACTION_MOVE:
            if ((dragFrom < 0) && (touchSq >= 0) && ownPiece(touchSq)) {
                float slop = ViewConfiguration.get(this).getScaledTouchSlop();
                if (Math.hypot(event.getX() - downX, event.getY() - downY) > slop)
                    dragFrom = touchSq;
            }
            if (dragFrom >= 0)
                board.setDragState(dragFrom, x, y);
            break;
        case MotionEvent.ACTION_UP: {
            int sq = board.eventToSquare(event);
            if (dragFrom >= 0) {
                board.setDragState(-1, 0, 0);
                if ((sq >= 0) && (sq != dragFrom))
                    tryMove(dragFrom, sq);
                dragFrom = -1;
            } else if ((sq >= 0) && (sq == touchSq)) {
                Move m = board.mousePressed(sq);
                if (m != null)
                    tryMove(m.from, m.to);
            }
            break;
        }
        case MotionEvent.ACTION_CANCEL:
            board.setDragState(-1, 0, 0);
            dragFrom = -1;
            break;
        }
        return true;
    }

    private boolean ownPiece(int sq) {
        int p = pos.getPiece(sq);
        return (p != Piece.EMPTY) && (Piece.isWhite(p) == iAmWhite);
    }

    private void tryMove(int from, int to) {
        ArrayList<Move> moves = LichessGame.matchingMoves(pos, from, to);
        if (moves.isEmpty()) {
            board.setSelection(ownPiece(to) ? to : -1);
            return;
        }
        if (moves.size() == 1) {
            playMove(moves.get(0));
            return;
        }
        final String[] names = { getString(R.string.queen), getString(R.string.rook),
                                 getString(R.string.bishop), getString(R.string.knight) };
        final int[] white = { Piece.WQUEEN, Piece.WROOK, Piece.WBISHOP, Piece.WKNIGHT };
        final int[] black = { Piece.BQUEEN, Piece.BROOK, Piece.BBISHOP, Piece.BKNIGHT };
        new AlertDialog.Builder(this)
            .setTitle(R.string.promote_pawn_to)
            .setItems(names, (d, which) -> {
                int promo = pos.whiteMove ? white[which] : black[which];
                for (Move m : moves)
                    if (m.promoteTo == promo)
                        playMove(m);
            })
            .show();
    }

    private void playMove(Move m) {
        final String id = gameId;
        if (id == null)
            return;
        final String uci = TextIO.moveToUCIString(m);
        // Show the move immediately; the server confirms it with the next gameState.
        if (pos.whiteMove)
            wtime = currentClock(true);
        else
            btime = currentClock(false);
        stateTime = SystemClock.elapsedRealtime();
        pos.makeMove(m, new UndoInfo());
        plies++;
        board.setPosition(pos);
        board.setSelection(m.to);
        board.userSelectedSquare = false;
        updateTurnStatus();
        new Thread(() -> {
            try {
                client.post("/api/board/game/" + id + "/move/" + uci, null);
            } catch (IOException e) {
                ui.post(() -> {
                    if (destroyed || !id.equals(gameId))
                        return;
                    toast(getString(R.string.online_move_rejected, e.getMessage()));
                    setPositionFromMoves(serverMoves);
                    updateTurnStatus();
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- Clocks

    private long currentClock(boolean white) {
        long t = white ? wtime : btime;
        if (clockRunning && (pos != null) && (pos.whiteMove == white))
            t -= SystemClock.elapsedRealtime() - stateTime;
        return Math.max(0, t);
    }

    private void updateClocks() {
        if (gameId == null)
            return;
        long mine = currentClock(iAmWhite);
        long theirs = currentClock(!iAmWhite);
        bottomClock.setText(LichessGame.formatClock(mine));
        topClock.setText(LichessGame.formatClock(theirs));
        bottomClock.setTextColor(myTurn() ? 0xFFFFD866 : 0xFFFFF8EC);
        topClock.setTextColor(!myTurn() && uiState == UiState.PLAYING ? 0xFFFFD866 : 0xFFFFF8EC);
    }

    private final Runnable clockTicker = new Runnable() {
        @Override
        public void run() {
            if (destroyed)
                return;
            updateClocks();
            ui.postDelayed(this, 100);
        }
    };
}
