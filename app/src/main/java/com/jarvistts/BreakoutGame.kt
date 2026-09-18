package com.jarvistts

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.abs

private val BrickColor = Color(0xFF4FD6C4)
private val PaddleColor = Color(0xFFE7ECEF)

private const val ROWS = 4
private const val COLS = 7
private const val PADDLE_FRACTION = 0.22f
private const val BALL_RADIUS = 6f
private const val PADDLE_THICKNESS = 10f
private const val BRICK_GAP = 4f
private const val BRICK_ROW_HEIGHT = 18f
private const val BALL_SPEED = 260f // px/sec

/** A minimal Arkanoid clone, purely to give the user's thumb something to do
 *  during a cold-install warmup (models downloading + loading can run
 *  20-30s the first time). Not meant to be a full game: no score, no lives,
 *  a dropped ball just respawns and clearing the board just refills it, so
 *  it never demands attention or interrupts the warmup it's sitting on top
 *  of -- glance away and it just keeps going.
 */
private class GameField {
    var w = 0f
    var h = 0f

    // These three are read inside the Canvas draw lambda, which Compose only
    // re-invokes when a *State* read inside it changes -- plain vars here
    // would silently never trigger a redraw of their own, leaving the canvas
    // to repaint only when something else incidentally did, which is what
    // produced the "5fps" stutter.
    var paddleX by mutableFloatStateOf(0f)
    var ballX by mutableFloatStateOf(0f)
    var ballY by mutableFloatStateOf(0f)
    var vx = BALL_SPEED
    var vy = -BALL_SPEED
    var bricks = Array(ROWS) { BooleanArray(COLS) { true } }
    var ready = false

    fun ensureSized(
        width: Float,
        height: Float,
    ) {
        if (ready && w == width && h == height) return
        w = width
        h = height
        paddleX = width / 2f
        bricks = Array(ROWS) { BooleanArray(COLS) { true } }
        launchBall()
        ready = true
    }

    fun launchBall() {
        ballX = w / 2f
        ballY = h - 40f
        vx = if ((0..1).random() == 0) BALL_SPEED else -BALL_SPEED
        vy = -BALL_SPEED
    }

    fun paddleWidth() = w * PADDLE_FRACTION

    fun step(dtSeconds: Float) {
        if (!ready) return
        ballX += vx * dtSeconds
        ballY += vy * dtSeconds

        if (ballX - BALL_RADIUS < 0f) {
            ballX = BALL_RADIUS
            vx = abs(vx)
        }
        if (ballX + BALL_RADIUS > w) {
            ballX = w - BALL_RADIUS
            vx = -abs(vx)
        }
        if (ballY - BALL_RADIUS < 0f) {
            ballY = BALL_RADIUS
            vy = abs(vy)
        }

        val paddleY = h - PADDLE_THICKNESS - 4f
        val pw = paddleWidth()
        if (vy > 0 &&
            ballY + BALL_RADIUS >= paddleY &&
            ballY + BALL_RADIUS <= paddleY + PADDLE_THICKNESS + 14f &&
            ballX >= paddleX - pw / 2f &&
            ballX <= paddleX + pw / 2f
        ) {
            vy = -abs(vy)
            // where it hit the paddle steers the bounce, same as the genre convention
            val offset = ((ballX - paddleX) / (pw / 2f)).coerceIn(-1f, 1f)
            vx = if (offset == 0f) vx else BALL_SPEED * offset
        }

        if (ballY - BALL_RADIUS < ROWS * BRICK_ROW_HEIGHT) {
            val brickWidth = w / COLS
            val col = (ballX / brickWidth).toInt().coerceIn(0, COLS - 1)
            val row = (ballY / BRICK_ROW_HEIGHT).toInt().coerceIn(0, ROWS - 1)
            if (bricks[row][col]) {
                bricks[row][col] = false
                vy = abs(vy)
            }
        }

        if (ballY - BALL_RADIUS > h) {
            launchBall()
        }

        if (bricks.all { row -> row.none { it } }) {
            bricks = Array(ROWS) { BooleanArray(COLS) { true } }
        }
    }
}

@Composable
fun BreakoutGame(modifier: Modifier = Modifier) {
    val field = remember { GameField() }

    LaunchedEffect(Unit) {
        var lastFrameNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (lastFrameNanos != 0L && field.ready) {
                    val dtSeconds = ((now - lastFrameNanos) / 1_000_000_000f).coerceAtMost(0.05f)
                    field.step(dtSeconds)
                }
                lastFrameNanos = now
            }
        }
    }

    Canvas(
        modifier =
            modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    detectDragGestures { change, _ ->
                        change.consume()
                        field.paddleX = change.position.x.coerceIn(0f, field.w)
                    }
                },
    ) {
        field.ensureSized(size.width, size.height)

        val brickWidth = size.width / COLS
        for (row in 0 until ROWS) {
            for (col in 0 until COLS) {
                if (!field.bricks[row][col]) continue
                drawRect(
                    color = BrickColor.copy(alpha = 0.9f - row * 0.15f),
                    topLeft = Offset(col * brickWidth + BRICK_GAP / 2f, row * BRICK_ROW_HEIGHT + BRICK_GAP / 2f),
                    size = Size(brickWidth - BRICK_GAP, BRICK_ROW_HEIGHT - BRICK_GAP),
                )
            }
        }

        val paddleY = size.height - PADDLE_THICKNESS - 4f
        drawRect(
            color = PaddleColor,
            topLeft = Offset(field.paddleX - field.paddleWidth() / 2f, paddleY),
            size = Size(field.paddleWidth(), PADDLE_THICKNESS),
        )

        drawCircle(color = PaddleColor, radius = BALL_RADIUS, center = Offset(field.ballX, field.ballY))
    }
}
