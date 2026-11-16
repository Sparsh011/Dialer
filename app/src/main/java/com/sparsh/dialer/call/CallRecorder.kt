package com.sparsh.dialer.call

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.Immutable
import com.sparsh.dialer.data.AppDb
import com.sparsh.dialer.data.ContactsRepo
import com.sparsh.dialer.data.RecordingEntity
import com.sparsh.dialer.data.granted
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

@Immutable
data class Recording(
    val callId: Int,
    val startedElapsed: Long,
    /** Android is feeding us digital silence — the capture is being blocked, not just quiet. */
    val silent: Boolean = false,
)

/**
 * Exists only so Android treats this app as an accessibility service while it is enabled.
 * During a phone call the platform mutes the microphone for every ordinary app; accessibility
 * services are the one documented exception. It listens to no events and reads no screen content.
 */
class RecorderAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        runCatching { serviceInfo = serviceInfo?.apply { eventTypes = 0 } }
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
}

/**
 * Call recorder built on AudioRecord → AAC encoder → MP4, instead of MediaRecorder.
 *
 * MediaRecorder dies with an error whenever the audio route changes (speaker ↔ Bluetooth ↔
 * earpiece). Here the capture device is just a replaceable input: if it fails mid-call we open a
 * new one and keep writing into the same file, padding the gap with silence so timing stays true.
 */
object CallRecorder {
    private const val TAG = "CallRecorder"
    private const val RATE = 44_100
    private const val BIT_RATE = 64_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<Recording?>(null)
    val state: StateFlow<Recording?> = _state
    private var session: Session? = null

    // VOICE_CALL is the real two-way call stream but only system apps may open it; trying costs
    // nothing and works on some firmwares. VOICE_RECOGNITION is the source an accessibility
    // service is allowed to keep during a call. MIC is the last resort.
    private val SOURCES = intArrayOf(
        MediaRecorder.AudioSource.VOICE_CALL,
        MediaRecorder.AudioSource.VOICE_RECOGNITION,
        MediaRecorder.AudioSource.MIC,
    )

    /** True when "Dialer call recording" is switched on in Accessibility settings. */
    fun serviceEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val me = ComponentName(context, RecorderAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun dir(context: Context): File =
        File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings").apply { mkdirs() }

    @SuppressLint("MissingPermission")
    private fun open(): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return null
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO).build()
        for (source in SOURCES) {
            val record = runCatching {
                AudioRecord.Builder().setAudioSource(source).setAudioFormat(format)
                    .setBufferSizeInBytes(max(minBuffer * 4, 32_768)).build()
            }.getOrNull() ?: continue
            val ok = record.state == AudioRecord.STATE_INITIALIZED &&
                runCatching { record.startRecording() }.isSuccess &&
                record.recordingState == AudioRecord.RECORDSTATE_RECORDING
            if (ok) {
                Log.i(TAG, "Capturing with audio source $source")
                return record
            }
            runCatching { record.release() }
        }
        return null
    }

    @Synchronized
    fun start(context: Context, call: CallUi): Boolean {
        if (session?.running == true) return true
        val app = context.applicationContext
        if (!app.granted(Manifest.permission.RECORD_AUDIO)) return false
        val record = open() ?: return false
        val contact = ContactsRepo.state.value.byKey[call.key]?.name ?: call.callerDisplayName
        val label = (contact ?: call.number.ifBlank { "Unknown" }).replace(Regex("[^A-Za-z0-9+ _-]"), "").trim().ifEmpty { "Unknown" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir(app), "${label}_$stamp.m4a")
        session = Session(app, call, contact, file, record).also { it.start() }
        _state.value = Recording(call.id, SystemClock.elapsedRealtime())
        return true
    }

    /** Stops capture; the worker thread finishes the file and saves it on its own. */
    @Synchronized
    fun stop() {
        session?.halt()
        session = null
        _state.value = null
    }

    private class Session(
        val context: Context, val call: CallUi, val contactName: String?, val file: File, first: AudioRecord,
    ) : Thread("call-recorder") {
        @Volatile var running = true
        @Volatile private var record: AudioRecord? = first

        /** Ends the session; stopping the input also wakes the worker if it is blocked in read(). */
        fun halt() {
            running = false
            runCatching { record?.stop() }
        }
        private val startedWall = System.currentTimeMillis()

        override fun run() {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            var codec: MediaCodec? = null
            var muxer: MediaMuxer? = null
            var muxing = false
            var samples = 0L
            try {
                val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
                }
                val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).also { codec = it }
                enc.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                enc.start()
                val mux = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4).also { muxer = it }
                val info = MediaCodec.BufferInfo()
                var track = -1

                fun drain(end: Boolean) {
                    var idle = 0
                    while (true) {
                        val out = enc.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                        when {
                            out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end || ++idle > 200) return
                            out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                                track = mux.addTrack(enc.outputFormat)
                                mux.start()
                                muxing = true
                            }
                            out >= 0 -> {
                                val buffer = enc.getOutputBuffer(out)
                                val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                                if (buffer != null && info.size > 0 && muxing && !config) mux.writeSampleData(track, buffer, info)
                                enc.releaseOutputBuffer(out, false)
                                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                            }
                        }
                    }
                }

                fun feed(data: ByteArray, length: Int) {
                    var offset = 0
                    var stalls = 0
                    while (offset < length) {
                        val index = enc.dequeueInputBuffer(20_000)
                        if (index < 0) {
                            drain(false)
                            if (++stalls > 100) return
                            continue
                        }
                        val buffer = enc.getInputBuffer(index) ?: continue
                        buffer.clear()
                        val count = min(buffer.remaining(), length - offset) and 1.inv()
                        buffer.put(data, offset, count)
                        enc.queueInputBuffer(index, 0, count, samples * 1_000_000L / RATE, 0)
                        samples += count / 2
                        offset += count
                    }
                    drain(false)
                }

                val pcm = ByteArray(4096)
                val zeros = ByteArray(4096)
                val startNanos = System.nanoTime()
                var lastSound = SystemClock.elapsedRealtime()
                var silent = false

                while (running) {
                    val input = record
                    val read = input?.read(pcm, 0, pcm.size) ?: -1
                    if (read > 0) {
                        // Real microphones always carry some noise. Seconds of exact zeros means the
                        // platform is muting the capture, which the UI should say out loud.
                        var loud = false
                        var i = 0
                        while (i < read) {
                            if (pcm[i] != 0.toByte()) { loud = true; break }
                            i++
                        }
                        val now = SystemClock.elapsedRealtime()
                        if (loud) lastSound = now
                        val nowSilent = now - lastSound > 3_500
                        if (nowSilent != silent) {
                            silent = nowSilent
                            _state.update { if (it?.callId == call.id) it.copy(silent = nowSilent) else it }
                        }
                        feed(pcm, read)
                    } else if (read == 0) {
                        SystemClock.sleep(10)
                    } else {
                        // The input device went away (route switched to Bluetooth, speaker…).
                        // Swap in a fresh one and carry on in the same file.
                        Log.w(TAG, "Capture interrupted ($read); reopening input")
                        runCatching { input?.stop() }
                        runCatching { input?.release() }
                        record = null
                        SystemClock.sleep(150)
                        if (!running) break
                        record = open()
                        val expected = (System.nanoTime() - startNanos) / 1_000L * RATE / 1_000_000L
                        var missing = (expected - samples).coerceIn(0, RATE * 60L)
                        while (missing > 0 && running) {
                            val chunk = min(missing * 2, zeros.size.toLong()).toInt()
                            feed(zeros, chunk)
                            missing -= chunk / 2
                        }
                    }
                }

                val index = enc.dequeueInputBuffer(200_000)
                if (index >= 0) {
                    enc.queueInputBuffer(index, 0, 0, samples * 1_000_000L / RATE, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    drain(true)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Recording failed", t)
            } finally {
                runCatching { record?.stop() }
                runCatching { record?.release() }
                val closed = runCatching { if (muxing) muxer?.stop() }.isSuccess
                runCatching { muxer?.release() }
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                val durationMs = samples * 1000L / RATE
                if (closed && muxing && durationMs >= 1000 && file.length() > 1024) {
                    val entity = RecordingEntity(
                        number = call.number, numKey = call.key, name = contactName,
                        startedAt = startedWall, durationMs = durationMs, path = file.absolutePath,
                    )
                    scope.launch { runCatching { AppDb.get(context).calls().insertRecording(entity) } }
                } else {
                    file.delete()
                }
                // If the worker died on its own (not via stop()), clear the UI state too.
                _state.update { if (it?.callId == call.id && running) null else it }
                running = false
            }
        }
    }
}
