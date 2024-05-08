/*
 * Copyright 2023 Calvin Liang
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package sh.calvin.reorderable

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * A utility to programmatically scroll a [ScrollableState].
 *
 * @param scrollableState The [ScrollableState] to scroll.
 * @param pixelAmount The amount of pixels to scroll per duration.
 * @param duration The duration of each scroll in milliseconds.
 */
@Composable
fun rememberScroller(
    scrollableState: ScrollableState,
    pixelAmount: Float,
    duration: Long = 100,
): Scroller {
    return rememberScroller(scrollableState, { pixelAmount }, duration)
}

/**
 * A utility to programmatically scroll a [ScrollableState].
 *
 * @param scrollableState The [ScrollableState] to scroll.
 * @param pixelPerSecond The amount of pixels to scroll per second.
 */
@Composable
fun rememberScroller(
    scrollableState: ScrollableState,
    pixelPerSecond: Float,
): Scroller {
    val scope = rememberCoroutineScope()
    val pixelPerSecondUpdated = rememberUpdatedState(pixelPerSecond)

    return remember(scrollableState, scope) {
        Scroller(
            scrollableState,
            scope,
            pixelPerSecondProvider = { pixelPerSecondUpdated.value },
        )
    }
}

/**
 * A utility to programmatically scroll a [ScrollableState].
 *
 * @param scrollableState The [ScrollableState] to scroll.
 * @param pixelAmountProvider A function that returns the amount of pixels to scroll per duration.
 * @param duration The duration of each scroll in milliseconds.
 */
@Composable
fun rememberScroller(
    scrollableState: ScrollableState,
    pixelAmountProvider: () -> Float,
    duration: Long = 100,
): Scroller {
    val scope = rememberCoroutineScope()
    val pixelAmountProviderUpdated = rememberUpdatedState(pixelAmountProvider)
    val durationUpdated = rememberUpdatedState(duration)

    return remember(scrollableState, scope, duration) {
        Scroller(
            scrollableState,
            scope,
            pixelPerSecondProvider = {
                pixelAmountProviderUpdated.value() / (durationUpdated.value / 1000f)
            },
        )
    }
}

@Stable
class Scroller internal constructor(
    private val scrollableState: ScrollableState,
    private val scope: CoroutineScope,
    private val pixelPerSecondProvider: () -> Float,
) {
    companion object {
        // The maximum duration for a scroll animation in milliseconds.
        private const val MaxScrollDuration = 100L
        private const val ZeroScrollWaitDuration = 100L
    }

    internal enum class Direction {
        BACKWARD, FORWARD
    }

    private data class ScrollJobInfo(
        val direction: Direction,
        val speedMultiplier: Float,
    )

    private var programmaticScrollJobInfo: ScrollJobInfo? = null
    private var programmaticScrollJob: Job? = null
    val isScrolling: Boolean
        get() = programmaticScrollJobInfo != null

    internal fun start(
        direction: Direction,
        speedMultiplier: Float = 1f,
        maxScrollDistanceProvider: () -> Float = { Float.MAX_VALUE },
        onScroll: suspend CoroutineScope.() -> Unit = {},
    ) {
        val scrollJobInfo = ScrollJobInfo(direction, speedMultiplier)

        if (programmaticScrollJobInfo == scrollJobInfo) return

        val pixelPerSecond = pixelPerSecondProvider() * speedMultiplier
        val pixelPerMs = pixelPerSecond / 1000f

        programmaticScrollJob?.cancel()
        programmaticScrollJobInfo = null

        if (!canScroll(direction)) return

        programmaticScrollJobInfo = scrollJobInfo
        programmaticScrollJob = scope.launch {
            while (true) {
                try {
                    onScroll()

                    if (!canScroll(direction)) break

                    val maxScrollDistance = maxScrollDistanceProvider()
                    if (maxScrollDistance == 0f) {
                        delay(ZeroScrollWaitDuration)
                        continue
                    }
                    val maxScrollDistanceDuration = maxScrollDistance / pixelPerMs
                    val duration = maxScrollDistanceDuration.toLong().coerceIn(1L, MaxScrollDuration)
                    val scrollDistance = maxScrollDistance * (duration / maxScrollDistanceDuration)
                    val diff = scrollDistance.let {
                            when (direction) {
                                Direction.BACKWARD -> -it
                                Direction.FORWARD -> it
                            }
                        }

                    scrollableState.animateScrollBy(
                        diff, tween(durationMillis = duration.toInt(), easing = LinearEasing)
                    )
                } catch (e: Exception) {
                    break
                }
            }
        }
    }

    private fun canScroll(direction: Direction): Boolean {
        return when (direction) {
            Direction.BACKWARD -> scrollableState.canScrollBackward
            Direction.FORWARD -> scrollableState.canScrollForward
        }
    }

    internal fun stop() {
        programmaticScrollJob?.cancel()
        programmaticScrollJobInfo = null
    }
}
