package dev.prayush.obol.alarm

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.MediaPlayer
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dev.prayush.obol.domain.model.DerivedMorning
import dev.prayush.obol.domain.model.MorningPhase
import dev.prayush.obol.domain.model.ObolConstants
import dev.prayush.obol.domain.policy.AlarmSoundChoice
import dev.prayush.obol.domain.policy.BuiltInChime
import dev.prayush.obol.domain.time.ObolClock
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.tanh
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AlarmAudioController(
    context: Context,
    private val clock: ObolClock,
    private val mediaContext: Context = context,
) {
    private val dpContext: Context =
        if (context.isDeviceProtectedStorage) context else context.createDeviceProtectedStorageContext()

    private val audioManager: AudioManager =
        dpContext.getSystemService(AudioManager::class.java)

    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dpContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            dpContext.getSystemService(Vibrator::class.java)
        }
    }.getOrNull()

    private data class ActivePlayerHandle(
        val choice: AlarmSoundChoice,
        val audioTrack: AudioTrack? = null,
        val mediaPlayer: MediaPlayer? = null,
        val ringtone: Ringtone? = null,
        val usedFallback: Boolean = false,
    ) {
        fun setGain(gain: Float) {
            runCatching { audioTrack?.setVolume(gain) }
            runCatching { mediaPlayer?.setVolume(gain, gain) }
            runCatching { ringtone?.volume = gain }
        }

        fun release() {
            runCatching {
                audioTrack?.stop()
                audioTrack?.flush()
                audioTrack?.release()
            }
            runCatching {
                if (mediaPlayer?.isPlaying == true) {
                    mediaPlayer.stop()
                }
                mediaPlayer?.reset()
                mediaPlayer?.release()
            }
            runCatching {
                ringtone?.stop()
            }
        }
    }

    private var alarmPlayer: ActivePlayerHandle? = null
    private var previewPlayer: ActivePlayerHandle? = null

    private var cameraOpenedAtElapsedMs: Long? = null
    private var overrideOpenedAtElapsedMs: Long? = null
    private var lastEscalationSignature: String? = null

    @Volatile
    var configuredSound: AlarmSoundChoice = AlarmSoundChoice.Default
        private set

    private val _previewingChoice = MutableStateFlow<AlarmSoundChoice?>(null)
    val previewingChoice: StateFlow<AlarmSoundChoice?> = _previewingChoice.asStateFlow()

    val isPreviewPlaying: Boolean
        get() = _previewingChoice.value != null

    var isChimeAudible: Boolean = false
        private set

    var isVibrating: Boolean = false
        private set

    var currentGain: Float = 0f
        private set

    var lastEnforcedAlarmVolume: Int = -1
        private set

    internal var usedBuiltInFallbackOnLastPlay: Boolean = false
        private set

    @Synchronized
    fun setAlarmSoundChoice(choice: AlarmSoundChoice) {
        if (configuredSound == choice) return
        configuredSound = choice
        if (isChimeAudible) {
            val gain = currentGain.takeIf { it > 0f } ?: INITIAL_ALARM_GAIN
            stopChime()
            startOrUpdateChime(gain)
        }
    }

    @Synchronized
    fun togglePreview(choice: AlarmSoundChoice) {
        if (isChimeAudible) return
        if (_previewingChoice.value == choice) {
            stopPreviewInternal()
        } else {
            startPreview(choice)
        }
    }

    @Synchronized
    fun startPreview(choice: AlarmSoundChoice) {
        if (isChimeAudible) return
        stopPreviewInternal()
        enforceMinimumAlarmVolume()
        previewPlayer = startLoopingOutput(choice = choice, gain = PREVIEW_GAIN)
        _previewingChoice.value = choice
    }

    @Synchronized
    fun stopPreview() {
        stopPreviewInternal()
    }

    private fun stopPreviewInternal() {
        previewPlayer?.release()
        previewPlayer = null
        _previewingChoice.value = null
    }

    @Synchronized
    fun updateForMorning(morning: DerivedMorning?) {
        if (morning == null || !morning.requiresAlarmService) {
            stopChime()
            stopVibration()
            cameraOpenedAtElapsedMs = null
            overrideOpenedAtElapsedMs = null
            lastEscalationSignature = null
            return
        }

        val nowElapsed = clock.elapsedMs()
        val isCameraViewfinderActive = morning.phase == MorningPhase.BRUSHING

        if (isCameraViewfinderActive) {
            if (cameraOpenedAtElapsedMs == null) {
                cameraOpenedAtElapsedMs = nowElapsed
            }
            if (morning.isInOverrideScreen && overrideOpenedAtElapsedMs == null) {
                overrideOpenedAtElapsedMs = nowElapsed
            }
        } else {
            cameraOpenedAtElapsedMs = null
            overrideOpenedAtElapsedMs = null
        }

        // Reset idle mute timer while brushing is genuinely seen so sight-loss starts a fresh bounded window
        if (morning.phase == MorningPhase.BRUSHING && morning.isBrushingSeen) {
            cameraOpenedAtElapsedMs = nowElapsed
        }

        val baseShouldRing = when (morning.phase) {
            MorningPhase.GRACE_NIGHT_RING,
            MorningPhase.FREE_WINDOW,
            MorningPhase.METERING,
            MorningPhase.CAPPED_RINGING,
            MorningPhase.BRUSHING,
            -> true
            MorningPhase.SNOOZED,
            MorningPhase.POST_CHECK_PAY_OR_ZERO,
            MorningPhase.IDLE_PRE_FIRE,
            MorningPhase.CLOSED_DONE,
            MorningPhase.CLOSED_UNPAID,
            MorningPhase.CANCELLED,
            -> false
        }

        if (!baseShouldRing) {
            stopChime()
            stopVibration()
            return
        }

        val cameraMuteActive = when {
            morning.phase == MorningPhase.BRUSHING && morning.isBrushingSeen -> true
            morning.phase == MorningPhase.BRUSHING && morning.isInOverrideScreen -> {
                val start = overrideOpenedAtElapsedMs ?: nowElapsed
                (nowElapsed - start) < ObolConstants.OVERRIDE_MUTE_MS
            }
            isCameraViewfinderActive -> {
                val start = cameraOpenedAtElapsedMs ?: nowElapsed
                (nowElapsed - start) < ObolConstants.CAMERA_INITIAL_MUTE_MS
            }
            else -> false
        }

        val shouldPlayChime = !cameraMuteActive
        // Opening camera immediately stops vibration and keeps vibration off while camera viewfinder is active
        val shouldVibrate = !isCameraViewfinderActive && (
            morning.reRingCount >= 2 ||
                morning.phase == MorningPhase.CAPPED_RINGING
            )

        val escalationSig = "${morning.phase}:${morning.reRingCount}"
        if (shouldPlayChime && (!isChimeAudible || escalationSig != lastEscalationSignature)) {
            lastEscalationSignature = escalationSig
            enforceMinimumAlarmVolume()
        }

        val targetGain = when {
            morning.reRingCount >= 3 || morning.phase == MorningPhase.CAPPED_RINGING -> 1.0f
            morning.reRingCount == 2 -> 0.93f
            morning.reRingCount == 1 -> 0.86f
            else -> INITIAL_ALARM_GAIN
        }

        if (shouldPlayChime) {
            stopPreviewInternal()
            startOrUpdateChime(targetGain)
        } else {
            stopChime()
        }

        if (shouldVibrate) {
            startVibration()
        } else {
            stopVibration()
        }
    }

    fun enforceMinimumAlarmVolume() {
        runCatching {
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            if (maxVol > 0) {
                val minFloor = (maxVol + 1) / 2
                val cur = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
                if (cur < minFloor) {
                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, minFloor, 0)
                    lastEnforcedAlarmVolume = minFloor
                } else {
                    lastEnforcedAlarmVolume = cur
                }
            }
        }
    }

    private fun startOrUpdateChime(gain: Float) {
        currentGain = gain
        val desired = configuredSound
        if (!isChimeAudible || alarmPlayer?.choice != desired) {
            alarmPlayer?.release()
            alarmPlayer = null
            isChimeAudible = true
            alarmPlayer = startLoopingOutput(choice = desired, gain = gain)
        } else {
            alarmPlayer?.setGain(gain)
        }
    }

    private fun startLoopingOutput(
        choice: AlarmSoundChoice,
        gain: Float,
    ): ActivePlayerHandle {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()

        if (choice is AlarmSoundChoice.SystemAlarm && choice.uriString.isNotBlank()) {
            val uri = runCatching { Uri.parse(choice.uriString) }.getOrNull()
            if (uri != null) {
                var hitPermissionDenial = false
                val mediaHandle = runCatching {
                    val mp = MediaPlayer()
                    try {
                        mp.setAudioAttributes(attrs)
                        mp.setDataSource(mediaContext, uri)
                        mp.isLooping = true
                        mp.prepare()
                        mp.setVolume(gain, gain)
                        mp.start()
                        ActivePlayerHandle(choice = choice, mediaPlayer = mp, usedFallback = false)
                    } catch (sec: SecurityException) {
                        hitPermissionDenial = true
                        runCatching { mp.release() }
                        throw sec
                    } catch (t: Throwable) {
                        runCatching { mp.release() }
                        throw t
                    }
                }.getOrNull()
                if (mediaHandle != null) {
                    usedBuiltInFallbackOnLastPlay = false
                    return mediaHandle
                }
                if (!hitPermissionDenial) {
                    hitPermissionDenial = didContentResolverDenyPermission(mediaContext, uri)
                }
                // On Android 15+, custom external_primary alarm URIs lose transient URI grants across process restarts
                // without READ_MEDIA_AUDIO, and MediaPlayer.setDataSource(Context, Uri) swallows SecurityException
                // internally before failing with IOException in prepare(); Ringtone delegates to SystemUI's IRingtonePlayer.
                if (hitPermissionDenial) {
                    val ringtoneHandle = runCatching {
                        val rt = RingtoneManager.getRingtone(mediaContext, uri)
                        if (rt != null) {
                            rt.audioAttributes = attrs
                            rt.isLooping = true
                            rt.volume = gain
                            rt.play()
                            if (rt.isPlaying) {
                                ActivePlayerHandle(choice = choice, ringtone = rt, usedFallback = false)
                            } else {
                                runCatching { rt.stop() }
                                null
                            }
                        } else {
                            null
                        }
                    }.getOrNull()
                    if (ringtoneHandle != null) {
                        usedBuiltInFallbackOnLastPlay = false
                        return ringtoneHandle
                    }
                }
            }
        }

        val builtIn = (choice as? AlarmSoundChoice.BuiltIn)?.chime ?: BuiltInChime.MARIMBA
        val usedFallback = choice is AlarmSoundChoice.SystemAlarm
        usedBuiltInFallbackOnLastPlay = usedFallback

        val track = runCatching {
            val pcm = pcmForBuiltIn(builtIn)
            val built = AudioTrack.Builder()
                .setAudioAttributes(attrs)
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE_HZ)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setTransferMode(AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            built.write(pcm, 0, pcm.size)
            built.setLoopPoints(0, pcm.size, -1)
            built.setVolume(gain)
            built.play()
            built
        }.getOrNull()

        return ActivePlayerHandle(
            choice = choice,
            audioTrack = track,
            usedFallback = usedFallback,
        )
    }

    private fun stopChime() {
        isChimeAudible = false
        currentGain = 0f
        alarmPlayer?.release()
        alarmPlayer = null
    }

    private fun startVibration() {
        if (isVibrating) return
        isVibrating = true
        runCatching {
            val vib = vibrator ?: return
            if (!vib.hasVibrator()) return
            val effect = VibrationEffect.createWaveform(longArrayOf(0L, 450L, 550L), 0)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                vib.vibrate(
                    effect,
                    VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                )
            } else {
                vib.vibrate(effect)
            }
        }
    }

    private fun stopVibration() {
        if (!isVibrating) return
        isVibrating = false
        runCatching { vibrator?.cancel() }
    }

    @Synchronized
    fun stopAll() {
        stopChime()
        stopPreviewInternal()
        stopVibration()
    }

    companion object {
        internal const val SAMPLE_RATE_HZ = 24_000
        private const val INITIAL_ALARM_GAIN = 0.78f
        private const val PREVIEW_GAIN = 0.82f
        private const val PEAK_NORMALIZED_AMP = 29_200.0

        private val pcmCache = ConcurrentHashMap<BuiltInChime, ShortArray>()

        internal fun didContentResolverDenyPermission(context: Context, uri: Uri): Boolean {
            return try {
                context.contentResolver.openAssetFileDescriptor(uri, "r")?.close()
                false
            } catch (_: SecurityException) {
                true
            } catch (_: Throwable) {
                false
            }
        }

        internal fun pcmForBuiltIn(chime: BuiltInChime): ShortArray =
            pcmCache.getOrPut(chime) { synthesizeChimePcm(chime) }

        internal fun synthesizeChimePcm(chime: BuiltInChime): ShortArray = when (chime) {
            BuiltInChime.MARIMBA -> synthesizeMarimba()
            BuiltInChime.SINGING_BOWL -> synthesizeSingingBowl()
            BuiltInChime.ELECTRIC_PIANO -> synthesizeElectricPiano()
        }

        private fun synthesizeMarimba(): ShortArray {
            // 3.0s loop: two-phrase warm rosewood marimba with strong octave projection for phone speakers
            val totalSamples = SAMPLE_RATE_HZ * 3
            val mix = DoubleArray(totalSamples)

            fun addMarimbaNote(startSec: Double, freqHz: Double, weight: Double, durSec: Double = 0.72) {
                val startIdx = (startSec * SAMPLE_RATE_HZ).toInt()
                val len = (durSec * SAMPLE_RATE_HZ).toInt()
                val attackSamples = max(1, (0.006 * SAMPLE_RATE_HZ).toInt())
                for (k in 0 until len) {
                    val idx = startIdx + k
                    if (idx !in 0 until totalSamples) break
                    val t = k.toDouble() / SAMPLE_RATE_HZ
                    val attack = if (k < attackSamples) {
                        0.5 * (1.0 - cos(PI * k.toDouble() / attackSamples.toDouble()))
                    } else {
                        1.0
                    }
                    val tailFade = if (len - k < 480) (len - k).toDouble() / 480.0 else 1.0
                    val w = 2.0 * PI * freqHz * t
                    val sample = 0.75 * sin(w) * exp(-t / 0.36) +
                        0.85 * sin(2.0 * w) * exp(-t / 0.30) +
                        0.28 * sin(3.0 * w) * exp(-t / 0.16) +
                        0.22 * sin(4.0 * w) * exp(-t / 0.06)
                    mix[idx] += weight * attack * tailFade * sample
                }
            }

            // Phrase 1: rising D-major pentatonic call
            addMarimbaNote(0.02, 293.66, 0.90, 0.70) // D4
            addMarimbaNote(0.21, 369.99, 0.88, 0.70) // F#4
            addMarimbaNote(0.40, 440.00, 0.92, 0.72) // A4
            addMarimbaNote(0.59, 493.88, 0.90, 0.72) // B4
            addMarimbaNote(0.78, 587.33, 0.96, 0.95) // D5
            addMarimbaNote(0.81, 739.99, 0.72, 0.95) // F#5
            // Phrase 2: warm answering cadence
            addMarimbaNote(1.52, 440.00, 0.86, 0.68) // A4
            addMarimbaNote(1.72, 369.99, 0.88, 0.68) // F#4
            addMarimbaNote(1.92, 440.00, 0.90, 0.72) // A4
            addMarimbaNote(2.12, 587.33, 0.95, 0.84) // D5
            addMarimbaNote(2.15, 440.00, 0.65, 0.84) // A4

            return finalizeLoopBuffer(mix, drive = 1.70)
        }

        private fun synthesizeSingingBowl(): ShortArray {
            // 3.6s loop: three felt-struck bronze singing bowl blooms with warm octave shimmer
            val totalSamples = (SAMPLE_RATE_HZ * 36) / 10
            val mix = DoubleArray(totalSamples)

            fun addBowlStrike(startSec: Double, freqHz: Double, weight: Double, durSec: Double) {
                val startIdx = (startSec * SAMPLE_RATE_HZ).toInt()
                val len = (durSec * SAMPLE_RATE_HZ).toInt()
                val attackSamples = max(1, (0.032 * SAMPLE_RATE_HZ).toInt())
                for (k in 0 until len) {
                    val idx = startIdx + k
                    if (idx !in 0 until totalSamples) break
                    val t = k.toDouble() / SAMPLE_RATE_HZ
                    val attack = if (k < attackSamples) {
                        val s = sin(0.5 * PI * k.toDouble() / attackSamples.toDouble())
                        s * s
                    } else {
                        1.0
                    }
                    val tailFade = if (len - k < 1_200) (len - k).toDouble() / 1_200.0 else 1.0
                    val w1 = 2.0 * PI * freqHz * t
                    val wBeat = 2.0 * PI * (freqHz + 2.0) * t
                    val w2 = 2.0 * PI * (2.0 * freqHz) * t
                    val w2Beat = 2.0 * PI * (2.0 * freqHz + 3.1) * t
                    val sample = (0.70 * sin(w1) + 0.45 * sin(wBeat)) * exp(-t / 1.15) +
                        (0.75 * sin(w2) + 0.42 * sin(w2Beat)) * exp(-t / 0.95) +
                        0.24 * sin(2.756 * w1) * exp(-t / 0.55)
                    mix[idx] += weight * attack * tailFade * sample
                }
            }

            addBowlStrike(0.03, 349.23, 0.95, 1.85) // F4
            addBowlStrike(0.05, 523.25, 0.65, 1.80) // C5
            addBowlStrike(1.22, 392.00, 0.95, 1.85) // G4
            addBowlStrike(1.24, 587.33, 0.68, 1.80) // D5
            addBowlStrike(2.40, 440.00, 0.95, 1.18) // A4
            addBowlStrike(2.42, 659.25, 0.65, 1.16) // E5

            return finalizeLoopBuffer(mix, drive = 1.65)
        }

        private fun synthesizeElectricPiano(): ShortArray {
            // 3.2s loop: three rolled suitcase Rhodes morning chords (Cmaj9 -> Dadd9 -> Fmaj7#11)
            val totalSamples = (SAMPLE_RATE_HZ * 32) / 10
            val mix = DoubleArray(totalSamples)

            fun addRhodesNote(startSec: Double, freqHz: Double, weight: Double, durSec: Double) {
                val startIdx = (startSec * SAMPLE_RATE_HZ).toInt()
                val len = (durSec * SAMPLE_RATE_HZ).toInt()
                val attackSamples = max(1, (0.008 * SAMPLE_RATE_HZ).toInt())
                for (k in 0 until len) {
                    val idx = startIdx + k
                    if (idx !in 0 until totalSamples) break
                    val t = k.toDouble() / SAMPLE_RATE_HZ
                    val attack = if (k < attackSamples) {
                        0.5 * (1.0 - cos(PI * k.toDouble() / attackSamples.toDouble()))
                    } else {
                        1.0
                    }
                    val tailFade = if (len - k < 960) (len - k).toDouble() / 960.0 else 1.0
                    val tremolo = 0.90 + 0.10 * sin(2.0 * PI * 4.5 * t)
                    val w = 2.0 * PI * freqHz * t
                    val body = 0.72 * sin(w) * exp(-t / 0.85) + 0.78 * sin(2.0 * w) * exp(-t / 0.60)
                    val tine = 0.30 * sin(3.0 * w) * exp(-t / 0.22) + 0.10 * sin(4.0 * w) * exp(-t / 0.09)
                    mix[idx] += weight * attack * tailFade * tremolo * (body + tine)
                }
            }

            val chord1 = listOf(261.63 to 0.70, 329.63 to 0.85, 392.00 to 0.88, 493.88 to 0.85, 587.33 to 0.95)
            chord1.forEachIndexed { idx, (freq, wt) ->
                addRhodesNote(0.03 + idx * 0.032, freq, wt, 1.18)
            }
            val chord2 = listOf(293.66 to 0.72, 369.99 to 0.85, 440.00 to 0.88, 523.25 to 0.85, 659.25 to 0.92)
            chord2.forEachIndexed { idx, (freq, wt) ->
                addRhodesNote(1.08 + idx * 0.032, freq, wt, 1.15)
            }
            val chord3 = listOf(349.23 to 0.75, 440.00 to 0.86, 523.25 to 0.88, 659.25 to 0.86, 783.99 to 0.80)
            chord3.forEachIndexed { idx, (freq, wt) ->
                addRhodesNote(2.12 + idx * 0.032, freq, wt, 1.06)
            }

            return finalizeLoopBuffer(mix, drive = 1.65)
        }

        private fun finalizeLoopBuffer(mix: DoubleArray, drive: Double): ShortArray {
            val out = ShortArray(mix.size)
            if (mix.isEmpty()) return out
            var sum = 0.0
            for (v in mix) sum += v
            val mean = sum / mix.size.toDouble()

            var rawPeak = 1e-9
            for (v in mix) {
                val a = abs(v - mean)
                if (a > rawPeak) rawPeak = a
            }

            val compressed = DoubleArray(mix.size)
            var compPeak = 1e-9
            for (i in mix.indices) {
                val c = tanh(drive * ((mix[i] - mean) / rawPeak))
                compressed[i] = c
                val a = abs(c)
                if (a > compPeak) compPeak = a
            }

            val scale = PEAK_NORMALIZED_AMP / compPeak
            val fadeInSamples = (SAMPLE_RATE_HZ * 20) / 1000
            val fadeOutSamples = (SAMPLE_RATE_HZ * 40) / 1000
            for (i in compressed.indices) {
                val edgeEnv = when {
                    i < fadeInSamples -> i.toDouble() / fadeInSamples.toDouble()
                    compressed.size - 1 - i < fadeOutSamples ->
                        (compressed.size - 1 - i).toDouble() / fadeOutSamples.toDouble()
                    else -> 1.0
                }
                val scaled = (compressed[i] * scale * edgeEnv).toInt().coerceIn(-32_767, 32_767)
                out[i] = scaled.toShort()
            }
            return out
        }
    }
}
