package org.petero.droidfish.activities.util;

import org.petero.droidfish.gamelogic.Piece;

import android.content.Context;
import android.graphics.Canvas;

/** Edit board that draws a piece following the finger during drag and drop. */
public class ChessBoardYour extends ChessBoardEdit {
    private int floatPiece = Piece.EMPTY;
    private int floatX;
    private int floatY;

    public ChessBoardYour(Context context) {
        super(context, null);
    }

    /** Show piece centered at pixel (x,y), or hide it if piece is EMPTY. */
    public void setFloatingPiece(int piece, int x, int y) {
        floatPiece = piece;
        floatX = x;
        floatY = y;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if ((floatPiece != Piece.EMPTY) && (sqSize > 0))
            drawPiece(canvas, floatX - sqSize / 2, floatY - sqSize / 2, floatPiece);
    }
}
