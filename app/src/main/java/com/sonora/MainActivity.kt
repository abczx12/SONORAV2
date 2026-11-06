package com.sonora

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sign
import kotlin.math.sqrt

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var eq: Equalizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var mic: AudioRecord? = null
    private var micThread: Thread? = null
    @Volatile private var micRunning = false
    private var agc: AutomaticGainControl? = null
    private var ns: NoiseSuppressor? = null
    private var aec: AcousticEchoCanceler? = null
    @Volatile private var micGain = 2f
    @Volatile private var gateEnabled = true
    @Volatile private var compressorEnabled = true

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var track: TextView
    private lateinit var play: TextView
    private lateinit var outputGainInfo: TextView
    private lateinit var micInfo: TextView
    private lateinit var micButton: TextView
    private lateinit var meterInfo: TextView
    private lateinit var routeInfo: TextView

    private val chooseAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { playUri(it) }
    }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startMicTest() else micInfo.text = "Bạn chưa cấp quyền micro cho SONORA."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 22)
        window.navigationBarColor = Color.rgb(8, 11, 22)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF080B16.toInt(), 0xFF13162B.toInt(), 0xFF0A0F1F.toInt()))
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
        buildUi()
        player = ExoPlayer.Builder(this).build().also { p ->
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    play.text = if (isPlaying) "Ⅱ  TẠM DỪNG" else "▶  PHÁT NHẠC"
                    status.text = if (isPlaying) "ĐANG PHÁT" else "ĐÃ TẠM DỪNG"
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) status.text = "ĐÃ PHÁT XONG"
                    if (state == Player.STATE_READY) status.text = if (p.isPlaying) "ĐANG PHÁT" else "SẴN SÀNG"
                }
            })
        }
        routeInfo.text = getRoutes()
    }

    private fun buildUi() {
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val logo = label("≋", 34f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(0xFF7668F5.toInt(), 22)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, 0, 0) }
        names.addView(label("SONORA", 25f, Color.WHITE, true))
        names.addView(label("SOUND EXPERIENCE • V2.1", 10f, 0xFF9DA9C9.toInt()))
        header.addView(logo); header.addView(names); root.addView(header)
        root.addView(space(18))
        root.addView(label("●  SONORA ENGINE     READY", 11f, 0xFFE4E8FF.toInt(), true).apply {
            gravity = Gravity.CENTER; setPadding(dp(16), dp(13), dp(16), dp(13))
            background = round(0xCC252D49.toInt(), 28, 0x557F8DCA)
        })

        root.addView(space(18)); root.addView(section("NOW PLAYING"))
        val music = card()
        val art = label("♫", 50f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF655CEB.toInt(), 0xFFB15DCE.toInt(), 0xFF293E78.toInt())).apply {
                cornerRadius = dp(22).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88))
        }
        track = label("Chưa chọn bài nhạc", 16f, Color.WHITE, true)
        status = label("SẴN SÀNG", 10f, 0xFF9DA9C9.toInt())
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14), 0, 0, 0) }
        info.addView(track); info.addView(space(6)); info.addView(status)
        music.addView(LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; addView(art); addView(info) })
        music.addView(button("＋  CHỌN NHẠC") { chooseAudio.launch(arrayOf("audio/*")) })
        play = button("▶  PHÁT NHẠC") { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        music.addView(play)
        music.addView(button("■  DỪNG") { player?.stop(); play.text = "▶  PHÁT NHẠC"; status.text = "ĐÃ DỪNG" })
        root.addView(music)

        root.addView(space(16)); root.addView(section("OUTPUT ENGINE"))
        val output = card()
        output.addView(label("OUTPUT BOOST", 12f, 0xFFBFC9E8.toInt(), true))
        outputGainInfo = label("Mức tăng: 2.5 dB", 12f, 0xFF9DA9C9.toInt())
        output.addView(outputGainInfo)
        output.addView(SeekBar(this).apply {
            max = 1200; progress = 250
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    outputGainInfo.text = "Mức tăng: %.1f dB".format(v / 100f)
                    try { enhancer?.setTargetGain(v) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        output.addView(label("EQ PRESET", 12f, 0xFFBFC9E8.toInt(), true))
        output.addView(Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Flat", "Voice Clear", "Podcast", "Vocal", "Bass Soft"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { applyPreset(position) }
            }
        })
        root.addView(output)

        root.addView(space(16)); root.addView(section("MIC ENGINE • INPUT TEST"))
        val micCard = card()
        micCard.addView(label("PREAMP / MIC GAIN", 12f, 0xFFBFC9E8.toInt(), true))
        val gainLabel = label("Tăng đầu vào: 2.0×", 12f, 0xFF9DA9C9.toInt())
        micCard.addView(gainLabel)
        micCard.addView(SeekBar(this).apply {
            max = 300; progress = 10
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, v: Int, fromUser: Boolean) {
                    micGain = 1f + v / 10f
                    gainLabel.text = "Tăng đầu vào: %.1f×".format(micGain)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        micCard.addView(Switch(this).apply {
            text = "NOISE GATE"; setTextColor(0xFFBFC9E8.toInt()); isChecked = true
            setOnCheckedChangeListener { _, checked -> gateEnabled = checked }
        })
        micCard.addView(Switch(this).apply {
            text = "COMPRESSOR + LIMITER"; setTextColor(0xFFBFC9E8.toInt()); isChecked = true
            setOnCheckedChangeListener { _, checked -> compressorEnabled = checked }
        })
        micInfo = label("MIC TEST: ĐANG TẮT", 13f, 0xFF9DA9C9.toInt(), true)
        meterInfo = label("Mức tín hiệu: —", 12f, 0xFF9DA9C9.toInt())
        micCard.addView(space(8)); micCard.addView(micInfo); micCard.addView(meterInfo)
        micButton = button("●  BẮT ĐẦU KIỂM TRA MICRO") { askMic() }
        micCard.addView(micButton)
        micCard.addView(label(
            "Chú ý: Preamp, Gate, Compressor và Limiter chỉ xử lý luồng kiểm tra bên trong SONORA. Android không cho APK thông thường thay mic đầu vào của Free Fire; các nút này chưa boost tiếng truyền vào game.",
            10f, 0xFFFFC58A.toInt()))
        root.addView(micCard)

        root.addView(space(16)); root.addView(section("AUDIO ROUTING"))
        val routing = card()
        routeInfo = label("Đang kiểm tra thiết bị…", 12f, 0xFFBFC9E8.toInt())
        routing.addView(routeInfo)
        routing.addView(button("↻  LÀM MỚI THIẾT BỊ") { routeInfo.text = getRoutes() })
        root.addView(routing)
        root.addView(space(14))
        root.addView(label("SONORA • Made for your sound", 11f, 0xFF7885A8.toInt()).apply { gravity = Gravity.CENTER })
    }

    private fun askMic() {
        if (micRunning) stopMicTest()
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) startMicTest()
        else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun startMicTest() {
        try {
            val rate = 44100
            val minSize = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minSize <= 0) error("Cấu hình thu âm không được hỗ trợ")
            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minSize, 4096))
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Không khởi tạo được micro" }
            mic = recorder
            val session = recorder.audioSessionId
            try { if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(session)?.apply { enabled = true } } catch (_: Exception) {}
            try { if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(session)?.apply { enabled = true } } catch (_: Exception) {}
            try { if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(session)?.apply { enabled = true } } catch (_: Exception) {}
            recorder.startRecording()
            micRunning = true
            micInfo.text = "● MIC TEST ĐANG CHẠY • Session $session"
            micButton.text = "■  DỪNG KIỂM TRA MICRO"
            micThread = Thread {
                val data = ShortArray(1024)
                while (micRunning) {
                    val n = try { recorder.read(data, 0, data.size) } catch (_: Exception) { -1 }
                    if (n > 0) {
                        val peak = processForMeter(data, n)
                        val db = if (peak < 0.00001f) -90f else (20.0 * log10(peak.toDouble())).toFloat().coerceAtLeast(-90f)
                        runOnUiThread { if (micRunning) meterInfo.text = "Mức sau xử lý thử: %.1f dBFS • Peak %.3f".format(db, peak) }
                    } else if (n < 0) break
                }
            }.apply { name = "SonoraMicMeter"; start() }
        } catch (e: Exception) {
            micRunning = false; releaseMic()
            micInfo.text = "Không mở được micro: ${e.message ?: "lỗi thiết bị"}"
            micButton.text = "●  THỬ LẠI MICRO"
        }
    }

    // DSP preview for the in-app meter only; it is not routed to Free Fire.
    private fun processForMeter(data: ShortArray, count: Int): Float {
        var sum = 0.0
        var peak = 0f
        for (i in 0 until count) {
            var x = data[i].toFloat() / 32768f * micGain
            if (gateEnabled && abs(x) < 0.012f) x = 0f
            if (compressorEnabled) {
                val a = abs(x)
                if (a > 0.35f) x = sign(x) * (0.35f + (a - 0.35f) * 0.35f)
                x = (x / (1f + abs(x) * 0.35f)).coerceIn(-0.98f, 0.98f)
            } else x = x.coerceIn(-1f, 1f)
            peak = maxOf(peak, abs(x))
            sum += x.toDouble() * x.toDouble()
        }
        val rms = if (count > 0) sqrt(sum / count).toFloat() else 0f
        return maxOf(peak, rms)
    }

    private fun stopMicTest() {
        micRunning = false
        try { micThread?.join(250) } catch (_: Exception) {}
        micThread = null; releaseMic()
        micInfo.text = "MIC TEST: ĐÃ DỪNG"
        meterInfo.text = "Mức tín hiệu: —"
        micButton.text = "●  BẮT ĐẦU KIỂM TRA MICRO"
    }

    private fun releaseMic() {
        try { if (mic?.recordingState == AudioRecord.RECORDSTATE_RECORDING) mic?.stop() } catch (_: Exception) {}
        try { mic?.release() } catch (_: Exception) {}; mic = null
        try { agc?.release() } catch (_: Exception) {}; agc = null
        try { ns?.release() } catch (_: Exception) {}; ns = null
        try { aec?.release() } catch (_: Exception) {}; aec = null
    }

    private fun playUri(uri: Uri) {
        try {
            player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare(); player?.play()
            track.text = uri.lastPathSegment ?: "Tệp âm thanh"
            status.text = "ĐANG TẢI…"
            val session = player?.audioSessionId ?: 0
            if (session != 0) attachOutputEffects(session)
        } catch (_: Exception) { status.text = "Không mở được tệp nhạc" }
    }

    private fun attachOutputEffects(session: Int) {
        releaseOutputEffects()
        try { eq = Equalizer(0, session).apply { enabled = true } } catch (_: Exception) { eq = null }
        try { enhancer = LoudnessEnhancer(session).apply { setTargetGain(250); enabled = true } } catch (_: Exception) { enhancer = null }
    }

    private fun applyPreset(index: Int) {
        val effect = eq ?: return
        val profiles = listOf(
            listOf(0, 0, 0, 0, 0), listOf(-1, 1, 3, 2, 0), listOf(1, 2, 2, 1, 0),
            listOf(-1, 1, 3, 2, 1), listOf(3, 2, 0, -1, -2)
        )
        try {
            val count = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            for (i in 0 until count) {
                val value = profiles[index][(i * 5 / count).coerceIn(0, 4)] * 100
                effect.setBandLevel(i.toShort(), value.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
            }
        } catch (_: Exception) {}
    }

    private fun getRoutes(): String {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) return "Chưa phát hiện thiết bị đầu ra."
        return devices.joinToString("\n") { device ->
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa điện thoại"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có dây"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                else -> "Thiết bị âm thanh"
            }
            "• $name"
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16))
        background = round(0xCC171F36.toInt(), 24, 0x447D8BB8)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }
    private fun button(value: String, action: () -> Unit) = TextView(this).apply {
        text = value; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); setPadding(dp(14), dp(14), dp(14), dp(14))
        background = round(0xFF655CEB.toInt(), 16)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(9) }
        setOnClickListener { action() }
    }
    private fun label(value: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun section(value: String) = label(value, 11f, 0xFF9C91FF.toInt(), true)
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun round(color: Int, radius: Int, stroke: Int = 0) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1), stroke)
    }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun releaseOutputEffects() {
        try { eq?.release() } catch (_: Exception) {}; eq = null
        try { enhancer?.release() } catch (_: Exception) {}; enhancer = null
    }

    override fun onDestroy() {
        if (micRunning) stopMicTest() else releaseMic()
        releaseOutputEffects()
        player?.release(); player = null
        super.onDestroy()
    }
}
