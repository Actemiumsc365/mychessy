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
import org.petero.droidfish.engine.DroidComputerPlayer;

public class CheckmateAnalysisTest extends TestCase {
    private DroidChessController controller;
    private ExecutorService callbacks;
    private BlockingQueue<GUIInterface.ThinkingInfo> updates;
    private BlockingQueue<Move> computerMoves;

    @Override
    protected void setUp() {
        callbacks = Executors.newSingleThreadExecutor();
        updates = new LinkedBlockingQueue<>();
        computerMoves = new LinkedBlockingQueue<>();
        GUIInterface gui = (GUIInterface)Proxy.newProxyInstance(GUIInterface.class.getClassLoader(),
                new Class<?>[] { GUIInterface.class }, (proxy, method, arguments) -> {
                    if (method.getName().equals("runOnUIThread"))
                        callbacks.execute((Runnable)arguments[0]);
                    if (method.getName().equals("setThinkingInfo"))
                        updates.add((GUIInterface.ThinkingInfo)arguments[0]);
                    if (method.getName().equals("movePlayed") && Boolean.TRUE.equals(arguments[2]))
                        computerMoves.add((Move)arguments[1]);
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
                (mateOnly ? info.mateResult != null || info.mateSearchComplete ||
                    info.evaluation != null && info.evaluation.depth >= 12 : info.evaluation != null))
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

    public void testIndependentGameReviewAnalyzesHistoryWithoutChangingLiveGame() throws Exception {
        String fen = controller.getFEN();
        int mode = controller.getGameMode().getModeNr();
        java.util.Map<String, MastermindCoach.Evaluation> evaluations = new GameReview().analyze(
                "1. f3 e5 2. g4 Qh4# 0-1", java.util.Collections.emptyMap(), (done, total) -> { });
        assertTrue(evaluations.size() >= 5);
        assertTrue(evaluations.get(TextIO.startPosFEN).depth >= MastermindCoach.MIN_DEPTH);
        assertEquals(fen, controller.getFEN());
        assertEquals(mode, controller.getGameMode().getModeNr());
        assertTrue(computerMoves.isEmpty());
    }

    public void testSavedStateRestoresCurrentPositionAndModeAndRejectsTruncation() throws Exception {
        for (String notation : new String[] { "e4", "e5" }) {
            Position position = TextIO.readFEN(controller.getFEN());
            controller.makeHumanMove(TextIO.stringToMove(position, notation), false);
        }
        String fen = controller.getFEN();
        byte[] state = controller.toByteArray();
        controller.undoMove();
        controller.restoreGame(state, 4, new GameMode(GameMode.PLAYER_WHITE));
        assertEquals(fen, controller.getFEN());
        assertEquals(GameMode.PLAYER_WHITE, controller.getGameMode().getModeNr());
        try {
            controller.restoreGame(java.util.Arrays.copyOf(state, state.length / 2), 4, new GameMode(GameMode.ANALYSIS));
            fail("Expected a truncated-save error");
        } catch (java.io.IOException | ChessParseError expected) {
            assertEquals(fen, controller.getFEN());
            assertEquals(GameMode.PLAYER_WHITE, controller.getGameMode().getModeNr());
        }
    }

    public void testGameReviewCanBeCancelledAfterAnalysisStarts() throws Exception {
        String fen = controller.getFEN();
        try {
            new GameReview().analyze("1. e4 e5 2. Nf3 Nc6 *", java.util.Collections.emptyMap(),
                    (done, total) -> Thread.currentThread().interrupt());
            fail("Expected cancellation");
        } catch (InterruptedException expected) {
            assertEquals(fen, controller.getFEN());
            assertTrue(computerMoves.isEmpty());
        } finally {
            Thread.interrupted();
        }
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
        assertFalse(quiet.mateSearchComplete);
        assertNotNull(quiet.evaluation);
        assertFalse(quiet.pvMoves.isEmpty());
        assertEquals("stockfish", controller.getEngine());
        controller.setCheckmateOnly(false);
        GUIInterface.ThinkingInfo normal = await(controller.getFEN(), false);
        assertNotNull(normal.evaluation);
        assertFalse(normal.pvMoves.isEmpty());
    }

    public void testPracticeLevelsReachMoveRequestsAndKeepAnalysisUnrestricted() throws Exception {
        org.petero.droidfish.book.BookOptions bookOptions = new org.petero.droidfish.book.BookOptions();
        bookOptions.filename = "nobook:";
        controller.setBookOptions(bookOptions);
        java.lang.reflect.Field playerField = DroidChessController.class.getDeclaredField("computerPlayer");
        playerField.setAccessible(true);
        java.lang.reflect.Field requestField = DroidComputerPlayer.class.getDeclaredField("searchRequest");
        requestField.setAccessible(true);
        java.lang.reflect.Field eloField = DroidComputerPlayer.SearchRequest.class.getDeclaredField("elo");
        eloField.setAccessible(true);
        String fen = controller.getFEN();
        for (PracticeLevel level : PracticeLevel.values()) {
            controller.setGameMode(new GameMode(GameMode.ANALYSIS));
            controller.setFENOrPGN(fen, false);
            controller.setStrength(level.elo != Integer.MAX_VALUE, level.elo);
            updates.clear();
            await(fen, false);
            assertEquals(level.elo, controller.eloData().getEloToUse());
            DroidComputerPlayer player = (DroidComputerPlayer)playerField.get(controller);
            synchronized (controller) {
                synchronized (player) {
                    DroidComputerPlayer.SearchRequest analysis =
                            (DroidComputerPlayer.SearchRequest)requestField.get(player);
                    assertNotNull(analysis);
                    assertEquals(Integer.MAX_VALUE, eloField.getInt(analysis));
                    controller.setGameMode(new GameMode(GameMode.PLAYER_BLACK));
                    DroidComputerPlayer.SearchRequest move =
                            (DroidComputerPlayer.SearchRequest)requestField.get(player);
                    assertNotNull(move);
                    assertEquals(level.elo, eloField.getInt(move));
                    assertEquals(fen, controller.getFEN());
                }
            }
        }
    }

    public void testStrengthChosenBeforeEngineStartupIsRetained() throws Exception {
        controller.shutdownEngine();
        controller.setStrength(true, PracticeLevel.INTERMEDIATE.elo);
        updates.clear();
        controller.setGameMode(new GameMode(GameMode.ANALYSIS));
        await(controller.getFEN(), false);
        assertEquals(PracticeLevel.INTERMEDIATE.elo, controller.eloData().getEloToUse());
    }

    public void testStockfish19DefaultNetworkMigratesPreviousSetting() throws Exception {
        java.util.List<String> commands = new java.util.ArrayList<>();
        org.petero.droidfish.engine.InternalStockFish engine =
                new org.petero.droidfish.engine.InternalStockFish(message -> fail(message), "") {
                    @Override
                    public void writeLineToEngine(String command) {
                        commands.add(command);
                    }
                };
        String netName = "nn-1a298aa575a0.nnue";
        engine.registerOption(("option name EvalFile type string default " + netName).split(" "));
        java.io.File network = new java.io.File("/engine", netName);
        java.lang.reflect.Field netFiles = engine.getClass().getSuperclass().getDeclaredField("defaultNetFiles");
        netFiles.setAccessible(true);
        ((java.io.File[])netFiles.get(engine))[0] = network;
        for (String setting : new String[] { "nn-b1a57edbea57.nnue", netName, "" }) {
            assertTrue(engine.setOption("EvalFile", setting));
            assertEquals("setoption name EvalFile value " + network.getAbsolutePath(),
                    commands.get(commands.size() - 1));
        }
        assertTrue(org.petero.droidfish.engine.InternalStockFish.keepExeDirFile(network));
        assertFalse(org.petero.droidfish.engine.InternalStockFish.keepExeDirFile(
                new java.io.File("/engine/nn-b1a57edbea57.nnue")));
        assertFalse(org.petero.droidfish.engine.InternalStockFish.keepExeDirFile(
                new java.io.File("/engine/nn-baff1ede1f90.nnue")));
    }

    public void testTerminalPositionCompletesWithoutNormalSuggestion() throws Exception {
        controller.setCheckmateOnly(true);
        controller.setFENOrPGN("7k/6Q1/6K1/8/8/8/8/8 b - - 1 1", false);
        GUIInterface.ThinkingInfo terminal = await(controller.getFEN(), true);
        assertNull(terminal.mateResult);
    }

    public void testOpponentActuallyPlaysMateInOneForEitherColor() throws Exception {
        String[] positions = { "7k/5Q2/6K1/8/8/8/8/8 w - - 0 1",
                "8/8/8/8/8/6k1/5q2/7K b - - 0 1" };
        for (String fen : positions) {
            controller.setGameMode(new GameMode(GameMode.ANALYSIS));
            controller.setFENOrPGN(fen, false);
            Position before = TextIO.readFEN(controller.getFEN());
            computerMoves.clear();
            controller.setGameMode(new GameMode(before.whiteMove ?
                    GameMode.PLAYER_BLACK : GameMode.PLAYER_WHITE));
            Move played = computerMoves.poll(20, TimeUnit.SECONDS);
            assertNotNull("Opponent did not move", played);
            assertTrue("Opponent missed immediate checkmate", TextIO.moveToString(before, played, false, false).endsWith("#"));
            Position after = TextIO.readFEN(controller.getFEN());
            assertTrue(MoveGen.inCheck(after));
            assertTrue(MoveGen.instance.legalMoves(after).isEmpty());
        }
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

    public void testHintsDuringPlayKeepTheOpponentAndRefreshAfterItsReply() throws Exception {
        for (int mode : new int[] { GameMode.PLAYER_WHITE, GameMode.PLAYER_BLACK }) {
            controller.setGameMode(new GameMode(GameMode.ANALYSIS));
            controller.setFENOrPGN(TextIO.startPosFEN, false);
            controller.setCheckmateOnly(true);
            controller.setGameMode(new GameMode(mode));
            if (mode == GameMode.PLAYER_BLACK)
                assertNotNull("Opponent did not make its opening move", computerMoves.poll(20, TimeUnit.SECONDS));
            String fen = controller.getFEN();
            GUIInterface.ThinkingInfo hint = await(fen, true);
            assertFalse(hint.pvMoves.isEmpty());
            assertTrue("Hint search must not play the human move", computerMoves.isEmpty());
            assertEquals(fen, controller.getFEN());
            assertEquals(mode, controller.getGameMode().getModeNr());
            controller.makeHumanMove(hint.pvMoves.get(0).get(0), false);
            assertNotNull("Opponent stopped responding after the hint", computerMoves.poll(20, TimeUnit.SECONDS));
            GUIInterface.ThinkingInfo next = await(controller.getFEN(), true);
            assertNotNull(next.evaluation);
            assertFalse(next.pvMoves.isEmpty());
            assertEquals(mode, controller.getGameMode().getModeNr());
            assertTrue(controller.humansTurn());
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