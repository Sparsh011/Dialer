package com.sparsh.dialer.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants as H
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import com.sparsh.dialer.data.Prefs

/**
 * All tactile feedback goes through here. View based effects use the platform's tuned
 * haptic constants (crisp on the S24's linear motor); call-state effects use rich
 * vibration compositions because they fire from a Service with no View.
 */
class Haptics(private val view: View) {
    private fun go(c: Int) {
        if (Prefs.haptics.value) view.performHapticFeedback(c)
    }

    fun key() = go(H.KEYBOARD_TAP)
    fun tick() = go(if (Build.VERSION.SDK_INT >= 34) H.SEGMENT_FREQUENT_TICK else H.CLOCK_TICK)
    fun click() = go(H.CONTEXT_CLICK)
    fun select() = go(if (Build.VERSION.SDK_INT >= 34) H.SEGMENT_TICK else H.CLOCK_TICK)
    fun heavy() = go(H.LONG_PRESS)
    fun confirm() = go(H.CONFIRM)
    fun reject() = go(H.REJECT)
    fun dragStart() = go(H.GESTURE_START)
    fun dragEnd() = go(H.GESTURE_END)
    /**
     * A firm, unmistakable thump for gestures that commit to something (swipe-to-call).
     * Drives the motor directly at full amplitude instead of the platform's light touch ticks.
     */
    fun strong() = pulse(view.context, longArrayOf(0, 38), intArrayOf(0, 255), VibrationEffect.EFFECT_HEAVY_CLICK)

    /** Lighter counterpart, used when a gesture drops back below its threshold. */
    fun release() = pulse(view.context, longArrayOf(0, 16), intArrayOf(0, 140), VibrationEffect.EFFECT_TICK)

    fun toggle(on: Boolean) =
        go(if (Build.VERSION.SDK_INT >= 34) (if (on) H.TOGGLE_ON else H.TOGGLE_OFF) else H.CONTEXT_CLICK)

    companion object {
        private fun vibrator(context: Context): Vibrator =
            context.getSystemService(VibratorManager::class.java).defaultVibrator

        private fun pulse(context: Context, timings: LongArray, amplitudes: IntArray, fallback: Int) {
            if (!Prefs.haptics.value) return
            runCatching {
                val v = vibrator(context)
                if (!v.hasVibrator()) return
                v.vibrate(
                    if (v.hasAmplitudeControl()) VibrationEffect.createWaveform(timings, amplitudes, -1)
                    else VibrationEffect.createPredefined(fallback),
                )
            }
        }

        private fun compose(context: Context, fallback: LongArray, build: VibrationEffect.Composition.() -> Unit, vararg prims: Int) {
            if (!Prefs.haptics.value) return
            runCatching {
                val v = vibrator(context)
                if (!v.hasVibrator()) return
                val effect = if (v.areAllPrimitivesSupported(*prims)) {
                    VibrationEffect.startComposition().apply(build).compose()
                } else {
                    VibrationEffect.createWaveform(fallback, -1)
                }
                v.vibrate(effect)
            }
        }

        /** The other side picked up: a soft rising double pulse. */
        fun callAnswered(context: Context) = compose(
            context, longArrayOf(0, 35, 70, 60),
            {
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, 0.6f)
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 60)
            },
            VibrationEffect.Composition.PRIMITIVE_QUICK_RISE, VibrationEffect.Composition.PRIMITIVE_CLICK,
        )

        /** Call ended: a falling thud. */
        fun callEnded(context: Context) = compose(
            context, longArrayOf(0, 50, 60, 30),
            {
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, 0.8f)
                addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, 70)
            },
            VibrationEffect.Composition.PRIMITIVE_QUICK_FALL, VibrationEffect.Composition.PRIMITIVE_TICK,
        )
    }
}

@Composable
fun rememberHaptics(): Haptics {
    val view = LocalView.current
    return remember(view) { Haptics(view) }
}
