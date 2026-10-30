package org.petero.droidfish.gamelogic;

import java.lang.reflect.Proxy;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import junit.framework.TestCase;
import org.petero.droidfish.GUIInterface;
import org.petero.droidfish.GameMode;
import org.petero.droidfish.PGNOptions;

public class CheckmateAnalysisTest extends TestCase {
    private DroidChessController controller;
    private ExecutorService callbacks;
    private BlockingQueue<GUIInterface.ThinkingInfo> updates;

    @Override
    protected void setUp() {
        callbacks = Executors.newSingleThreadExecutor();
        updates = new LinkedBlockingQueue<>();
        GUIInterface gui = (GUIInterface)Proxy.newProxyInstance(GUIInterface.class.getClassLoader(),
                new Class<?>[] { GUIInterface.class }, (proxy, method, arguments) -> {
                    if (method.getName().equals("runOnUIThread"))
                        callbacks.execute((Runnable)arguments[0]);
                    if (method.getName().equals("setThinkingInfo"))
                        updates.add((GUIInterface.ThinkingInfo)arguments[0]);
                    if (method.getReturnType() == boolean.class)
                        return method.getName().equals("whiteBasedScores");
                    if (method.getReturnType() == String.class)
                        return "Test player";
                    return null;
                });
        PgnToken.PgnTokenReceiver tokens = (PgnToken.PgnTokenReceiver)Proxy.newProxyInstance(
                PgnToken.PgnTokenReceiver.class.getClassLoader(),
                new Class<?>[] { PgnToken.PgnTokenReceiver.class }, (proxy, method, arguments) ->
                        method.getReturnType() == boolean.class ? Boolean.TRUE : null);
        controller = new DroidChessController(gui, tokens, new PGNOptions());
        controller.setEngine("stockfish");
        controller.newGame(new GameMode(GameMode.ANALYSIS), new TimeControlData());
    }

    @Override
    protected void tearDown() {
        if (controller != null)
            controller.shutdownEngine();
        callbacks.shutdownNow();
    }

    private GUIInterface.ThinkingInfo await(String fen, boolean mateOnly) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            GUIInterface.ThinkingInfo info = updates.poll(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            if (info == null)
                break;
            if (fen.equals(info.positionFen) && info.checkmateOnly == mateOnly &&
                (mateOnly ? info.mateSearchComplete : info.evaluation != null))
                return info;
        }
        fail("No completed analysis callback for " + fen + " (mate-only=" + mateOnly + ")");
        return null;
    }

    public void testPreferenceCallbackBeforeNewGameDoesNotCrash() {
        GUIInterface gui = (GUIInterface)Proxy.newProxyInstance(GUIInterface.class.getClassLoader(),
                new Class<?>[] { GUIInterface.class }, (proxy, method, arguments) -> {
                    if (method.getName().equals("runOnUIThread"))
                        ((Runnable)arguments[0]).run();
                    if (method.getReturnType() == boolean.class)
                        return false;
                    return null;
                });
        PgnToken.PgnTokenReceiver tokens = (PgnToken.PgnTokenReceiver)Proxy.newProxyInstance(
                PgnToken.PgnTokenReceiver.class.getClassLoader(),
                new Class<?>[] { PgnToken.PgnTokenReceiver.class }, (proxy, method, arguments) ->
                        method.getReturnType() == boolean.class ? Boolean.TRUE : null);
        DroidChessController initializingController = new DroidChessController(gui, tokens, new PGNOptions());

        initializingController.prefsChanged(false);
    }

    public void testNativeMateSearchAndNormalAnalysisRestoration() throws Exception {
        assertFalse(controller.checkmateOnly());
        controller.setMultiPVMode(3);
        controller.setCheckmateOnly(true);
        String mateFen = "7k/5Q2/6K1/8/8/8/8/8 w - - 0 1";
        controller.setFENOrPGN(mateFen, false);
        GUIInterface.ThinkingInfo mate = await(controller.getFEN(), true);
        assertNotNull(mate.mateResult);
        assertEquals(1, mate.mateResult.distance);
        assertTrue(mate.mateResult.line.endsWith("#"));
        controller.setFENOrPGN(TextIO.startPosFEN, false);
        GUIInterface.ThinkingInfo quiet = await(controller.getFEN(), true);
        assertNull(quiet.mateResult);
        assertEquals("stockfish", controller.getEngine());
        controller.setCheckmateOnly(false);
        GUIInterface.ThinkingInfo normal = await(controller.getFEN(), false);
        assertNotNull(normal.evaluation);
        assertFalse(normal.pvMoves.isEmpty());
    }

    public void testTerminalPositionCompletesWithoutNormalSuggestion() throws Exception {
        controller.setCheckmateOnly(true);
        controller.setFENOrPGN("7k/6Q1/6K1/8/8/8/8/8 b - - 1 1", false);
        GUIInterface.ThinkingInfo terminal = await(controller.getFEN(), true);
        assertNull(terminal.mateResult);
    }

    public void testNormalAnalysisRestartsAfterSeveralPlayedMoves() throws Exception {
        String[] moves = { "e4", "e5", "Nf3", "Nc6", "Bb5" };
        for (String moveText : moves) {
            Position position = TextIO.readFEN(controller.getFEN());
            Move move = TextIO.stringToMove(position, moveText);
            assertNotNull("Expected legal move " + moveText, move);
            controller.makeHumanMove(move, false);
            GUIInterface.ThinkingInfo info = await(controller.getFEN(), false);
            assertNotNull("Expected a fresh engine evaluation after " + moveText, info.evaluation);
            assertFalse("Expected a fresh principal variation after " + moveText, info.pvMoves.isEmpty());
        }
    }

    public void testMateModeTemporarilyUsesStockfishWithoutReplacingSelectedEngine() throws Exception {
        controller.setEngine("cuckoochess");
        controller.setCheckmateOnly(true);
        controller.setFENOrPGN("7k/5Q2/6K1/8/8/8/8/8 w - - 0 1", false);
        assertNotNull(await(controller.getFEN(), true).mateResult);
        assertEquals("cuckoochess", controller.getEngine());
        controller.setCheckmateOnly(false);
        assertNotNull(await(controller.getFEN(), false).evaluation);
        assertEquals("cuckoochess", controller.getEngine());
    }
}