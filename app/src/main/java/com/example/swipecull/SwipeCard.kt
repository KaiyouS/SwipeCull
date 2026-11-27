package com.example.swipecull

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs

val DeleteRed = Color(0xFFE53935)
val KeepGreen = Color(0xFF43A047)

private const val SWIPE_THRESHOLD_FRACTION = 0.25f

@Stable
class SwipeCardState {
    val offsetX = Animatable(0f)
    var widthPx by mutableFloatStateOf(0f)
    var locked by mutableStateOf(false)
    /** Whether the drag is currently past the point where letting go commits the swipe. */
    var pastThreshold = false

    /** Animates the card off-screen. Returns false if it was already flying away. */
    suspend fun flingOut(direction: Int): Boolean {
        if (locked) return false
        locked = true
        offsetX.animateTo(direction * widthPx.coerceAtLeast(1f) * 1.3f, tween(180))
        return true
    }
}

/** One state per card; pass something unique to the item (e.g. its URI) as the key. */
@Composable
fun rememberSwipeCardState(key: Any): SwipeCardState = remember(key) { SwipeCardState() }

/**
 * A card you can drag horizontally. Past 25% of its width on release it flies off and
 * [onSwiped] is called with -1 (left) or +1 (right). Otherwise it springs back.
 */
@Composable
fun SwipeCard(
    state: SwipeCardState,
    enabled: Boolean,
    onSwiped: (direction: Int) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
    /** Neutral NEXT / BACK overlay instead of KEEP / DELETE. */
    browse: Boolean = false,
    /** Return false to make a direction spring back (e.g. nothing earlier to go back to). */
    canSwipe: (direction: Int) -> Boolean = { true },
    /** Called with the horizontal position (0..1) of a double-tap; null leaves double-tap alone. */
    onDoubleTap: ((fraction: Float) -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val scope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    val currentOnSwiped by rememberUpdatedState(onSwiped)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentEnabled by rememberUpdatedState(enabled)
    val currentCanSwipe by rememberUpdatedState(canSwipe)
    val currentDoubleTap by rememberUpdatedState(onDoubleTap)
    val hasDoubleTap = onDoubleTap != null

    Box(
        modifier
            .onSizeChanged { state.widthPx = it.width.toFloat() }
            .graphicsLayer {
                translationX = state.offsetX.value
                rotationZ = if (state.widthPx > 0f) state.offsetX.value / state.widthPx * 10f else 0f
            }
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black)
            .pointerInput(state, hasDoubleTap) {
                detectTapGestures(
                    onTap = { if (currentEnabled) currentOnTap() },
                    onDoubleTap = if (hasDoubleTap) {
                        { p -> if (currentEnabled) currentDoubleTap?.invoke(p.x / size.width.coerceAtLeast(1)) }
                    } else null,
                )
            }
            .pointerInput(state) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        state.pastThreshold = false
                        scope.launch {
                            val x = state.offsetX.value
                            val threshold = state.widthPx * SWIPE_THRESHOLD_FRACTION
                            when {
                                state.locked -> {}
                                x > threshold && currentCanSwipe(1) -> { if (state.flingOut(1)) currentOnSwiped(1) }
                                x < -threshold && currentCanSwipe(-1) -> { if (state.flingOut(-1)) currentOnSwiped(-1) }
                                else -> state.offsetX.animateTo(0f, spring())
                            }
                        }
                    },
                    onDragCancel = {
                        state.pastThreshold = false
                        scope.launch { if (!state.locked) state.offsetX.animateTo(0f, spring()) }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        if (currentEnabled && !state.locked) {
                            change.consume()
                            val target = state.offsetX.value + dragAmount
                            scope.launch { state.offsetX.snapTo(target) }
                            // A light tick when the swipe becomes "committed" (and again if you back off).
                            val past = abs(target) > state.widthPx * SWIPE_THRESHOLD_FRACTION
                            if (past != state.pastThreshold) {
                                state.pastThreshold = past
                                if (past) haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    },
                )
            },
    ) {
        content()
        SwipeOverlay(state, browse)
    }
}

@Composable
private fun BoxScope.SwipeOverlay(state: SwipeCardState, browse: Boolean) {
    val threshold = (state.widthPx * SWIPE_THRESHOLD_FRACTION).coerceAtLeast(1f)
    val p = (state.offsetX.value / threshold).coerceIn(-1f, 1f)
    if (p == 0f) return

    val color = if (browse) Color.White else if (p > 0) KeepGreen else DeleteRed
    val strength = abs(p)
    val shape = RoundedCornerShape(24.dp)

    Box(
        Modifier
            .matchParentSize()
            .background(color.copy(alpha = 0.18f * strength), shape)
            .border(4.dp, color.copy(alpha = strength), shape),
    )
    Text(
        text = if (browse) (if (p > 0) "BACK" else "NEXT") else if (p > 0) "KEEP" else "DELETE",
        color = color.copy(alpha = strength),
        fontSize = 36.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .align(if (p > 0) Alignment.TopStart else Alignment.TopEnd)
            .padding(24.dp)
            .graphicsLayer { rotationZ = if (p > 0) -12f else 12f },
    )
}
