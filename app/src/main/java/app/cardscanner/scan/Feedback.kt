package app.cardscanner.scan

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.VibratorManager

/** Ton + Vibration, damit man beim Dauerscan nicht aufs Display schauen muss. */
class Feedback(context: Context) {

    private val vibrator = (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    private val tones = ToneGenerator(AudioManager.STREAM_MUSIC, 80)

    fun hit() {
        vibrator.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
        tones.startTone(ToneGenerator.TONE_PROP_ACK, 150)
    }

    fun miss() {
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        tones.startTone(ToneGenerator.TONE_SUP_ERROR, 400)
    }

    fun release() = tones.release()
}
