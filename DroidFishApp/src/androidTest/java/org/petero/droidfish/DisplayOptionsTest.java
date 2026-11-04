package org.petero.droidfish;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.preference.PreferenceManager;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;

import androidx.core.graphics.ColorUtils;
import androidx.test.platform.app.InstrumentationRegistry;

import junit.framework.TestCase;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.Collections;

import org.petero.droidfish.gamelogic.DroidChessController;
import org.petero.droidfish.gamelogic.Position;
import org.petero.droidfish.gamelogic.TextIO;

public class DisplayOptionsTest extends TestCase {
    private DroidFish activity;

    private android.app.Instrumentation getInstrumentation() {
        return InstrumentationRegistry.getInstrumentation();
    }

    private DroidFish getActivity() {
        if (activity == null) {
            Intent intent = new Intent(getInstrumentation().getTargetContext(), DroidFish.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity = (DroidFish)getInstrumentation().startActivitySync(intent);
        }
        return activity;
    }

    @Override
    protected void tearDown() throws Exception {
        if (activity != null)
            getInstrumentation().runOnMainSync(() -> activity.finish());
        super.tearDown();
    }

    public void testFullscreenHidesBothSystemBarsAndCanBeDisabled() {
        DroidFish activity = getActivity();
        SharedPreferences preferences = activity.getSharedPreferences("display_instrumentation", Context.MODE_PRIVATE);
        getInstrumentation().runOnMainSync(() -> {
            try {
                preferences.edit().putBoolean("fullScreenMode", true).commit();
                Util.setFullScreenMode(activity, preferences);
                assertTrue((activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0);
                assertTrue((activity.getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION) != 0);
                preferences.edit().putBoolean("fullScreenMode", false).commit();
                Util.setFullScreenMode(activity, preferences);
                assertEquals(0, activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_FULLSCREEN);
                assertEquals(0, activity.getWindow().getDecorView().getSystemUiVisibility() & View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
            } finally {
                Util.setFullScreenMode(activity, PreferenceManager.getDefaultSharedPreferences(activity));
                preferences.edit().clear().commit();
            }
        });
    }

    public void testThemesPersistAndHaveReadableText() {
        DroidFish activity = getActivity();
        SharedPreferences preferences = activity.getSharedPreferences("display_instrumentation", Context.MODE_PRIVATE);
        getInstrumentation().runOnMainSync(() -> {
            try {
                for (int theme : new int[] { ColorTheme.ROYAL_WOOD, 5, 6, 3, 7 }) {
                    ColorTheme.instance().setTheme(preferences, theme);
                    ColorTheme.instance().readColors(preferences);
                    assertEquals(theme, preferences.getInt("selectedColorTheme", -1));
                    int foreground = ColorTheme.instance().getColor(ColorTheme.FONT_FOREGROUND);
                    int background = ColorTheme.instance().getColor(ColorTheme.GENERAL_BACKGROUND);
                    assertTrue("Unreadable theme " + theme, ColorUtils.calculateContrast(foreground, background) >= 4.5);
                    assertTrue(ColorTheme.instance().getColor(ColorTheme.DARK_SQUARE) !=
                            ColorTheme.instance().getColor(ColorTheme.BRIGHT_SQUARE));
                }
            } finally {
                ColorTheme.instance().readColors(PreferenceManager.getDefaultSharedPreferences(activity));
                preferences.edit().clear().commit();
            }
        });
    }

    public void testFoldedAndUnfoldedLayoutsKeepBoardAndHintsVisible() throws Exception {
        DroidFish activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            for (int[] dimensions : new int[][] { { 360, 900 }, { 600, 720 }, { 800, 900 }, { 900, 400 } }) {
                int widthDp = dimensions[0];
                int heightDp = dimensions[1];
                Configuration configuration = new Configuration(activity.getResources().getConfiguration());
                configuration.orientation = widthDp > heightDp ? Configuration.ORIENTATION_LANDSCAPE : Configuration.ORIENTATION_PORTRAIT;
                configuration.screenWidthDp = widthDp;
                configuration.screenHeightDp = heightDp;
                configuration.smallestScreenWidthDp = Math.min(widthDp, heightDp);
                configuration.densityDpi = 160;
                Context context = new ContextThemeWrapper(activity.createConfigurationContext(configuration), R.style.MainActivityTheme);
                View root = LayoutInflater.from(context).inflate(R.layout.main, null);
                LinearLayout main = root.findViewById(R.id.main);
                Util.overrideViewAttribs(main);
                for (int viewId : new int[] { R.id.white_clock, R.id.title_text, R.id.black_clock,
                        R.id.white_pieces, R.id.title_text_summary, R.id.black_pieces, R.id.status }) {
                    android.widget.TextView source = activity.findViewById(viewId);
                    android.widget.TextView target = root.findViewById(viewId);
                    target.setText(source.getText());
                    target.setTextColor(ColorTheme.instance().getColor(ColorTheme.FONT_FOREGROUND));
                }
                android.widget.TextView thinking = root.findViewById(R.id.thinking);
                thinking.setText("Play: e4\nBest reply: e5\nContinue: Nf3");
                for (int viewId : new int[] { R.id.custom1Button, R.id.custom2Button, R.id.custom3Button,
                        R.id.modeButton, R.id.undoButton, R.id.redoButton }) {
                    android.widget.ImageButton source = activity.findViewById(viewId);
                    android.widget.ImageButton target = root.findViewById(viewId);
                    target.setImageDrawable(source.getDrawable());
                    target.setVisibility(source.getVisibility());
                }
                assertEquals(widthDp >= 600 ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL, main.getOrientation());
                ChessBoardPlay board = root.findViewById(R.id.chessboard);
                board.setPgnOptions(new PGNOptions());
                try {
                    Position position = TextIO.readFEN(TextIO.startPosFEN);
                    board.setPosition(position);
                    board.setMoveHints(Collections.singletonList(TextIO.stringToMove(position, "e4")));
                    root.measure(View.MeasureSpec.makeMeasureSpec(widthDp, View.MeasureSpec.EXACTLY),
                            View.MeasureSpec.makeMeasureSpec(heightDp, View.MeasureSpec.EXACTLY));
                        root.layout(0, 0, widthDp, heightDp);
                    assertTrue(board.getWidth() > 0);
                    assertTrue(board.getHeight() > 0);
                    View hints = root.findViewById(R.id.scrollViewBot);
                    assertTrue("Hints have no space", hints.getHeight() > 0);
                    assertTrue("Hints extend beyond window", hints.getBottom() <= hints.getRootView().getHeight());
                    if (widthDp >= 600) {
                        View sidebar = main.getChildAt(1);
                        assertTrue("Sidebar overlaps board", sidebar.getLeft() >= board.getRight());
                        assertTrue("Sidebar is too narrow", sidebar.getWidth() >= 240);
                    }
                        Bitmap image = Bitmap.createBitmap(widthDp, heightDp, Bitmap.Config.ARGB_8888);
                    root.draw(new Canvas(image));
                        int squareSize = Math.min(board.getWidth(), board.getHeight()) / 8;
                        int originY = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ? 0 :
                            (board.getHeight() - squareSize * 8) / 2;
                        int squareColor = image.getPixel(board.getLeft() + squareSize / 2,
                            board.getTop() + originY + squareSize * 4 + squareSize / 2);
                        assertEquals("Board did not render", ColorTheme.instance().getColor(ColorTheme.BRIGHT_SQUARE), squareColor);
                    File output = new File(activity.getExternalFilesDir(null), "display-layout-" + widthDp + ".png");
                    try (FileOutputStream stream = new FileOutputStream(output)) {
                        assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, stream));
                    }
                    image.recycle();
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
            }
        });
    }

    public void testLayoutRebuildKeepsGameModePositionAndSelection() throws Exception {
        DroidFish activity = getActivity();
        Field controllerField = DroidFish.class.getDeclaredField("ctrl");
        controllerField.setAccessible(true);
        DroidChessController controller = (DroidChessController)controllerField.get(activity);
        getInstrumentation().runOnMainSync(() -> {
            String fen = controller.getFEN();
            int mode = controller.getGameMode().getModeNr();
            ChessBoardPlay previous = activity.findViewById(R.id.chessboard);
            previous.setSelection(12);
            activity.onConfigurationChanged(new Configuration(activity.getResources().getConfiguration()));
            ChessBoardPlay rebuilt = activity.findViewById(R.id.chessboard);
            assertNotSame(previous, rebuilt);
            assertEquals(fen, controller.getFEN());
            assertEquals(mode, controller.getGameMode().getModeNr());
            assertEquals(12, rebuilt.selectedSquare);
        });
    }

    public void testOnDemandHintsRevealWithoutMovingAndResetAfterPlaying() throws Exception {
        DroidFish activity = getActivity();
        Field controllerField = DroidFish.class.getDeclaredField("ctrl");
        controllerField.setAccessible(true);
        DroidChessController controller = (DroidChessController)controllerField.get(activity);
        Field modeField = DroidFish.class.getDeclaredField("gameMode");
        modeField.setAccessible(true);
        getInstrumentation().runOnMainSync(() -> {
            boolean automatic = PreferenceManager.getDefaultSharedPreferences(activity).getBoolean("automaticHints", true);
            byte[] state = controller.toByteArray();
            GameMode mode = controller.getGameMode();
            try {
                modeField.set(activity, new GameMode(GameMode.PLAYER_WHITE));
                controller.setGameMode(new GameMode(GameMode.ANALYSIS));
                controller.setFENOrPGN(TextIO.startPosFEN, false);
                controller.setCheckmateOnly(true);
                controller.setGameMode(new GameMode(GameMode.PLAYER_WHITE));
                activity.setAutomaticHints(false);
                assertFalse(activity.hintsVisible());
                android.widget.TextView thinking = activity.findViewById(R.id.thinking);
                assertEquals(activity.getString(R.string.hint), thinking.getText().toString());
                String fen = controller.getFEN();
                activity.requestHint();
                assertTrue(activity.hintsVisible());
                assertEquals(fen, controller.getFEN());
                assertEquals(GameMode.PLAYER_WHITE, controller.getGameMode().getModeNr());
                Position position = TextIO.readFEN(fen);
                controller.makeHumanMove(TextIO.stringToMove(position, "e4"), false);
                assertFalse(activity.hintsVisible());
            } catch (Exception exception) {
                throw new AssertionError(exception);
            } finally {
                try {
                    modeField.set(activity, mode);
                    controller.restoreGame(state, 4, mode);
                } catch (Exception exception) {
                    throw new AssertionError(exception);
                }
                activity.setAutomaticHints(automatic);
            }
        });
    }

    public void testCoachMenuShowsCompactActionsAndHintSwitchWithoutChangingMode() throws Exception {
        DroidFish activity = getActivity();
        Field controllerField = DroidFish.class.getDeclaredField("ctrl");
        controllerField.setAccessible(true);
        DroidChessController controller = (DroidChessController)controllerField.get(activity);
        int mode = controller.getGameMode().getModeNr();
        getInstrumentation().runOnMainSync(() -> new MastermindPanel(activity, controller).showTools());
        for (String label : new String[] { "Explain my last move", "Review game", "Practice mistakes", "Save game", "Saved games", "Share game", "Automatic hints" })
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText(label))
                    .check(androidx.test.espresso.assertion.ViewAssertions.matches(
                            androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        androidx.test.espresso.Espresso.pressBack();
        assertEquals(mode, controller.getGameMode().getModeNr());
    }

        public void testMistakePracticeChecksRevealsAndKeepsLiveGameUnchanged() throws Exception {
        DroidFish activity = getActivity();
        Field controllerField = DroidFish.class.getDeclaredField("ctrl");
        controllerField.setAccessible(true);
        DroidChessController controller = (DroidChessController)controllerField.get(activity);
        String fen = controller.getFEN();
        int mode = controller.getGameMode().getModeNr();
        getInstrumentation().runOnMainSync(() -> new MastermindPanel(activity, controller).showDrill(
            Collections.singletonList(new org.petero.droidfish.gamelogic.MastermindCoach.Drill(
                TextIO.startPosFEN, "e2e4")), true));
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Your practice move"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.replaceText("f3"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Check"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("That differs from the reviewed recommendation. Try again."))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withContentDescription("Your practice move"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.replaceText("e4"), androidx.test.espresso.action.ViewActions.closeSoftKeyboard());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Check"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Correct: this matches the reviewed engine recommendation."))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Next"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Reveal"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Reviewed recommendation: e4"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .check(androidx.test.espresso.assertion.ViewAssertions.matches(androidx.test.espresso.matcher.ViewMatchers.isDisplayed()));
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.withText("Close"))
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.click());
        assertEquals(fen, controller.getFEN());
        assertEquals(mode, controller.getGameMode().getModeNr());
        }

        public void testPgnSharingUsesReadableAttachmentAndKeepsCurrentGame() throws Exception {
        DroidFish activity = getActivity();
        Field controllerField = DroidFish.class.getDeclaredField("ctrl");
        controllerField.setAccessible(true);
        DroidChessController controller = (DroidChessController)controllerField.get(activity);
        android.content.Intent[] exported = new android.content.Intent[1];
        String[] snapshot = new String[2];
        getInstrumentation().runOnMainSync(() -> {
            try {
            snapshot[0] = controller.getPGN();
            snapshot[1] = controller.getFEN();
            exported[0] = activity.createShareIntent(true);
            } catch (java.io.IOException exception) {
            throw new AssertionError(exception);
            }
        });
        assertEquals(android.content.Intent.ACTION_SEND, exported[0].getAction());
        assertEquals("application/x-chess-pgn", exported[0].getType());
        assertTrue((exported[0].getFlags() & android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        android.net.Uri uri = exported[0].getParcelableExtra(android.content.Intent.EXTRA_STREAM);
        assertEquals("content", uri.getScheme());
        assertEquals(uri, exported[0].getClipData().getItemAt(0).getUri());
        StringBuilder text = new StringBuilder();
        try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(
            activity.getContentResolver().openInputStream(uri), "UTF-8"))) {
            String line;
            while ((line = reader.readLine()) != null)
            text.append(line).append('\n');
        }
        assertEquals(snapshot[0].trim(), text.toString().trim());
        org.petero.droidfish.gamelogic.GameTree parsed = new org.petero.droidfish.gamelogic.GameTree(null);
        assertTrue(parsed.readPGN(text.toString(), new PGNOptions()));
        assertEquals(snapshot[1], controller.getFEN());
        }
}