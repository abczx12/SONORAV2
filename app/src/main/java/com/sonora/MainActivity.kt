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
import android.media.AudioRecordingConfiguration
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.NoiseSuppressor
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt
import java.util.Locale

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

    private var recordingCallback: AudioManager.AudioRecordingCallback? = null
    private var autoDetectEnabled = false

    private var selectedPreset = 0
    private var outputGainMb = 250

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var track: TextView
    private lateinit var playButton: TextView
    private lateinit var outputGainInfo: TextView
    private lateinit var eqStatus: TextView
    private lateinit var eqControls: LinearLayout
    private lateinit var micInfo: TextView
    private lateinit var micButton: TextView
    private lateinit var meterInfo: TextView
    private lateinit var routeInfo: TextView
    private lateinit var autoMicInfo: TextView
    private lateinit var autoDetectButton: TextView

    private val chooseAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { playUri(it) }
        }

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startMicTest()
            else micInfo.text = "Bạn chưa cấp quyền micro cho SONORA."
        }

    private val requestDetectPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                autoDetectEnabled = true
                autoDetectButton.text = "■  TẮT AUTO MIC DETECT"
                registerRecordingMonitor()
            } else {
                autoMicInfo.text = "Chưa cấp quyền micro nên Android không cho theo dõi phiên thu âm."
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 22)
        window.navigationBarColor = Color.rgb(8, 11, 22)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(30))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF080B16.toInt(), 0xFF18213A.toInt(), 0xFF090E1C.toInt())
            )
        }
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(root)
        })

        buildUi()
        player = ExoPlayer.Builder(this).build().also { exo ->
            exo.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    playButton.text = if (isPlaying) "Ⅱ  TẠM DỪNG" else "▶  PHÁT NHẠC"
                    status.text = if (isPlaying) "ĐANG PHÁT" else "ĐÃ TẠM DỪNG"
                }

                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_ENDED -> status.text = "ĐÃ PHÁT XONG"
                        Player.STATE_READY -> status.text =
                            if (exo.isPlaying) "ĐANG PHÁT" else "SẴN SÀNG"
                    }
                }

                @UnstableApi
                override fun onAudioSessionIdChanged(audioSessionId: Int) {
                    if (audioSessionId != 0) attachOutputEffects(audioSessionId)
                }
            })
        }

        routeInfo.text = getRoutes()
        updateAutoMicStatus(emptyList())
    }

    private fun buildUi() {
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val logo = label("≋", 34f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(0xFF7668F5.toInt(), 22)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        val names = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        names.addView(label("SONORA", 25f, Color.WHITE, true))
        names.addView(label("SOUND EXPERIENCE • V2.3", 10f, 0xFFB7C3E4.toInt()))
        header.addView(logo)
        header.addView(names)
        root.addView(header)

        root.addView(space(18))
        root.addView(label("●  SONORA AUDIO ENGINE     READY", 11f, 0xFFE4E8FF.toInt(), true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(13), dp(16), dp(13))
            background = round(0xA82B3858.toInt(), 28, 0x667F91C9)
        })

        root.addView(space(18))
        root.addView(section("NOW PLAYING"))
        val music = card()
        val art = label("♫", 48f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF655CEB.toInt(), 0xFFB15DCE.toInt(), 0xFF293E78.toInt())
            ).apply { cornerRadius = dp(22).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(86), dp(86))
        }
        track = label("Chưa chọn bài nhạc", 16f, Color.WHITE, true)
        status = label("SẴN SÀNG", 10f, 0xFFAAB7D7.toInt())
        val details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, 0, 0)
        }
        details.addView(track)
        details.addView(space(6))
        details.addView(status)
        music.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(art)
            addView(details)
        })
        music.addView(button("＋  CHỌN NHẠC") {
            chooseAudio.launch(arrayOf("audio/*"))
        })
        playButton = button("▶  PHÁT NHẠC") {
            player?.let { exo ->
                if (exo.isPlaying) exo.pause() else exo.play()
            }
        }
        music.addView(playButton)
        music.addView(button("■  DỪNG") {
            player?.stop()
            playButton.text = "▶  PHÁT NHẠC"
            status.text = "ĐÃ DỪNG"
        })
        root.addView(music)

        root.addView(space(16))
        root.addView(section("OUTPUT ENGINE • REAL AUDIO EFFECTS"))
        val output = card()
        output.addView(label("OUTPUT BOOST • CHỈ ÁP DỤNG CHO NHẠC PHÁT TRONG SONORA", 12f, 0xFFD5DDF4.toInt(), true))
        outputGainInfo = label("Mức tăng: 2.5 dB", 12f, 0xFFAFBDDF.toInt())
        output.addView(space(5))
        output.addView(outputGainInfo)
        output.addView(SeekBar(this).apply {
            max = 1200
            progress = outputGainMb
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, value: Int, fromUser: Boolean) {
                    outputGainMb = value.coerceIn(0, 1200)
                    outputGainInfo.text = "Mức tăng: ${formatDb(outputGainMb)} dB"
                    try { enhancer?.setTargetGain(outputGainMb) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        })

        output.addView(space(8))
        output.addView(label("EQ PRESET", 12f, 0xFFD5DDF4.toInt(), true))
        output.addView(Spinner(this).apply {
            adapter = ArrayAdapter(
                this@MainActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("Flat", "Voice Clear", "Podcast", "Vocal", "Bass Soft")
            )
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) = Unit
                override fun onItemSelected(
                    parent: AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    selectedPreset = position
                    applyPreset(position)
                }
            }
        })
        eqStatus = label("Chọn một bài nhạc để khởi tạo EQ của Android.", 11f, 0xFFAFBDDF.toInt())
        output.addView(space(6))
        output.addView(eqStatus)
        eqControls = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        output.addView(eqControls)
        root.addView(output)

        root.addView(space(16))
        root.addView(section("MIC INPUT • LOCAL TEST"))
        val micCard = card()
        micCard.addView(label("MIC LEVEL METER • ĐO TÍN HIỆU THU BỞI SONORA", 12f, 0xFFD5DDF4.toInt(), true))
        micInfo = label("MIC TEST: ĐANG TẮT", 13f, 0xFFAFBDDF.toInt(), true)
        meterInfo = label("Mức tín hiệu: —", 12f, 0xFFAFBDDF.toInt())
        micCard.addView(space(8))
        micCard.addView(micInfo)
        micCard.addView(meterInfo)
        micButton = button("●  BẮT ĐẦU KIỂM TRA MICRO") { askMic() }
        micCard.addView(micButton)
        micCard.addView(label(
            "AGC / Noise Suppression / Echo Cancellation chỉ hoạt động trên phiên thu âm của SONORA nếu thiết bị hỗ trợ. Android không cho ứng dụng không root thay đổi micro đầu vào của Roblox hoặc ứng dụng khác. Mục này là đồng hồ đo tín hiệu, không giả lập boost.",
            10f, 0xFFFFC58A.toInt()
        ))
        root.addView(micCard)

        root.addView(space(16))
        root.addView(section("AUTO MIC DETECT"))
        val detectCard = card()
        detectCard.addView(label("ANDROID RECORDING SESSION MONITOR", 12f, 0xFFD5DDF4.toInt(), true))
        autoMicInfo = label("ĐANG TẮT • Chưa theo dõi phiên thu âm hệ thống.", 12f, 0xFFAFBDDF.toInt())
        detectCard.addView(space(6))
        detectCard.addView(autoMicInfo)
        autoDetectButton = button("◎  BẬT AUTO MIC DETECT") { toggleAutoDetect() }
        detectCard.addView(autoDetectButton)
        detectCard.addView(label(
            "Chỉ đọc trạng thái ghi âm mà Android công bố. Không tự mở micro, không ghi âm nền và không chuyển âm thanh vào micro của ứng dụng khác.",
            10f, 0xFFFFC58A.toInt()
        ))
        root.addView(detectCard)

        root.addView(space(16))
        root.addView(section("AUDIO ROUTING"))
        val routing = card()
        routeInfo = label("Đang kiểm tra thiết bị…", 12f, 0xFFD5DDF4.toInt())
        routing.addView(routeInfo)
        routing.addView(button("↻  LÀM MỚI THIẾT BỊ") {
            routeInfo.text = getRoutes()
        })
        root.addView(routing)

        root.addView(space(14))
        root.addView(label("SONORA • Made for your sound", 11f, 0xFF8997B9.toInt()).apply {
            gravity = Gravity.CENTER
        })
    }

    private fun attachOutputEffects(sessionId: Int) {
        releaseOutputEffects()
        try {
            eq = Equalizer(0, sessionId).apply { enabled = true }
        } catch (_: Exception) {
            eq = null
        }
        try {
            enhancer = LoudnessEnhancer(sessionId).apply {
                setTargetGain(outputGainMb)
                enabled = true
            }
        } catch (_: Exception) {
            enhancer = null
        }
        applyPreset(selectedPreset)
        rebuildEqControls()
    }

    private fun rebuildEqControls() {
        if (!::eqControls.isInitialized || !::eqStatus.isInitialized) return
        eqControls.removeAllViews()

        val effect = eq
        if (effect == null) {
            eqStatus.text = "EQ phần cứng không khả dụng trên phiên phát hiện tại."
            return
        }

        try {
            val bandCount = effect.numberOfBands.toInt()
            val range = effect.bandLevelRange
            val minLevel = range[0].toInt()
            val maxLevel = range[1].toInt()
            if (bandCount <= 0 || maxLevel <= minLevel) {
                eqStatus.text = "Thiết bị không công bố dải EQ có thể điều chỉnh."
                return
            }

            eqStatus.text = "EQ THỰC • $bandCount DẢI • ÂM THANH SONORA"
            for (index in 0 until bandCount) {
                val band = index.toShort()
                val hz = effect.getCenterFreq(band).coerceAtLeast(0) / 1000
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(0, dp(5), 0, dp(5))
                }
                val initialLevel = effect.getBandLevel(band).toInt()
                val caption = label(
                    "$hz Hz  •  ${formatDb(initialLevel)} dB",
                    11f,
                    0xFFD5DDF4.toInt()
                )
                val slider = SeekBar(this).apply {
                    max = maxLevel - minLevel
                    progress = (initialLevel - minLevel).coerceIn(0, max)
                    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(
                            seekBar: SeekBar?, progress: Int, fromUser: Boolean
                        ) {
                            if (!fromUser) return
                            val level = (minLevel + progress).coerceIn(minLevel, maxLevel)
                            try {
                                effect.setBandLevel(band, level.toShort())
                                caption.text = "$hz Hz  •  ${formatDb(level)} dB"
                            } catch (_: Exception) {
                                caption.text = "$hz Hz  •  EQ không hỗ trợ mức này"
                            }
                        }
                        override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                        override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
                    })
                }
                row.addView(caption)
                row.addView(slider)
                eqControls.addView(row)
            }
        } catch (error: Exception) {
            eqStatus.text = "Không đọc được thông số EQ trên thiết bị."
        }
    }

    private fun applyPreset(index: Int) {
        val effect = eq ?: return
        val profiles = listOf(
            listOf(0, 0, 0, 0, 0),
            listOf(-1, 1, 3, 2, 0),
            listOf(1, 2, 2, 1, 0),
            listOf(-1, 1, 3, 2, 1),
            listOf(3, 2, 0, -1, -2)
        )
        try {
            val bandCount = effect.numberOfBands.toInt()
            if (bandCount <= 0) return
            val range = effect.bandLevelRange
            val profile = profiles[index.coerceIn(0, profiles.lastIndex)]
            for (bandIndex in 0 until bandCount) {
                val profileIndex = (bandIndex * profile.size / bandCount).coerceIn(0, profile.lastIndex)
                val millibels = profile[profileIndex] * 100
                val clamped = millibels.coerceIn(range[0].toInt(), range[1].toInt())
                effect.setBandLevel(bandIndex.toShort(), clamped.toShort())
            }
            if (::eqControls.isInitialized) rebuildEqControls()
        } catch (_: Exception) {
            if (::eqStatus.isInitialized) eqStatus.text = "Không thể áp dụng preset EQ trên thiết bị."
        }
    }

    private fun askMic() {
        if (micRunning) {
            stopMicTest()
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startMicTest()
        } else {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMicTest() {
        if (micRunning) return
        try {
            val sampleRate = 44100
            val minimum = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            check(minimum > 0) { "Thiết bị không hỗ trợ cấu hình thu này." }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minimum * 2, 8192)
            )
            check(recorder.state == AudioRecord.STATE_INITIALIZED) {
                "Không khởi tạo được AudioRecord."
            }

            mic = recorder
            val sessionId = recorder.audioSessionId
            try {
                if (AutomaticGainControl.isAvailable()) {
                    agc = AutomaticGainControl.create(sessionId)?.apply { enabled = true }
                }
            } catch (_: Exception) { agc = null }
            try {
                if (NoiseSuppressor.isAvailable()) {
                    ns = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
                }
            } catch (_: Exception) { ns = null }
            try {
                if (AcousticEchoCanceler.isAvailable()) {
                    aec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
                }
            } catch (_: Exception) { aec = null }

            recorder.startRecording()
            micRunning = true
            micInfo.text = "● MIC TEST ĐANG CHẠY • Session $sessionId"
            micButton.text = "■  DỪNG KIỂM TRA MICRO"
            micThread = Thread {
                val samples = ShortArray(1024)
                while (micRunning) {
                    val count = try {
                        recorder.read(samples, 0, samples.size)
                    } catch (_: Exception) {
                        -1
                    }
                    if (count > 0) {
                        val peak = measurePeak(samples, count)
                        val dbfs = if (peak <= 0.00001f) -90f
                        else (20.0 * log10(peak.toDouble())).toFloat().coerceAtLeast(-90f)
                        runOnUiThread {
                            if (micRunning) {
                                meterInfo.text = "Input: ${formatDb(dbfs)} dBFS  •  Peak ${"%.3f".format(Locale.US, peak)}"
                            }
                        }
                    } else if (count < 0) {
                        break
                    }
                }
            }.apply {
                name = "SonoraMicMeter"
                start()
            }
        } catch (error: Exception) {
            micRunning = false
            releaseMic()
            micInfo.text = "Không mở được micro: ${error.message ?: "lỗi thiết bị"}"
            micButton.text = "●  THỬ LẠI MICRO"
        }
    }

    private fun measurePeak(samples: ShortArray, count: Int): Float {
        var peak = 0f
        for (index in 0 until count) {
            val value = abs(samples[index].toInt()) / 32768f
            if (value > peak) peak = value
        }
        return peak
    }

    private fun stopMicTest() {
        micRunning = false
        try {
            mic?.let { recorder ->
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            }
        } catch (_: Exception) {}
        try { micThread?.join(300) } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        micThread = null
        releaseMic()
        if (::micInfo.isInitialized) micInfo.text = "MIC TEST: ĐÃ DỪNG"
        if (::meterInfo.isInitialized) meterInfo.text = "Mức tín hiệu: —"
        if (::micButton.isInitialized) micButton.text = "●  BẮT ĐẦU KIỂM TRA MICRO"
    }

    private fun releaseMic() {
        try { agc?.release() } catch (_: Exception) {}
        try { ns?.release() } catch (_: Exception) {}
        try { aec?.release() } catch (_: Exception) {}
        agc = null
        ns = null
        aec = null
        try { mic?.release() } catch (_: Exception) {}
        mic = null
    }

    private fun playUri(uri: Uri) {
        try {
            player?.setMediaItem(MediaItem.fromUri(uri))
            player?.prepare()
            player?.play()
            track.text = uri.lastPathSegment ?: "Tệp âm thanh"
            status.text = "ĐANG TẢI…"
            val sessionId = player?.audioSessionId ?: 0
            if (sessionId != 0) attachOutputEffects(sessionId)
        } catch (error: Exception) {
            status.text = "Không mở được tệp nhạc: ${error.message ?: "lỗi"}"
        }
    }

    private fun toggleAutoDetect() {
        if (autoDetectEnabled) {
            autoDetectEnabled = false
            unregisterRecordingMonitor()
            autoDetectButton.text = "◎  BẬT AUTO MIC DETECT"
            autoMicInfo.text = "ĐANG TẮT • SONORA không theo dõi micro hệ thống."
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            autoMicInfo.text = "Android phiên bản này không hỗ trợ API theo dõi phiên ghi âm."
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            autoDetectEnabled = true
            autoDetectButton.text = "■  TẮT AUTO MIC DETECT"
            registerRecordingMonitor()
        } else {
            requestDetectPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun registerRecordingMonitor() {
        if (!autoDetectEnabled || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            val callback = recordingCallback ?: object : AudioManager.AudioRecordingCallback() {
                override fun onRecordingConfigChanged(configs: List<AudioRecordingConfiguration>?) {
                    runOnUiThread { updateAutoMicStatus(configs ?: emptyList()) }
                }
            }.also { recordingCallback = it }

            manager.registerAudioRecordingCallback(callback, Handler(Looper.getMainLooper()))
            updateAutoMicStatus(manager.activeRecordingConfigurations)
        } catch (error: Exception) {
            autoMicInfo.text = "Không thể theo dõi micro: ${error.message ?: "API bị giới hạn"}"
        }
    }

    private fun unregisterRecordingMonitor() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val manager = getSystemService(AUDIO_SERVICE) as AudioManager
            recordingCallback?.let { manager.unregisterAudioRecordingCallback(it) }
        } catch (_: Exception) {}
    }

    private fun updateAutoMicStatus(configs: List<AudioRecordingConfiguration>) {
        if (!::autoMicInfo.isInitialized) return
        autoMicInfo.text = when {
            !autoDetectEnabled ->
                "ĐANG TẮT • SONORA không theo dõi micro hệ thống."
            configs.isEmpty() ->
                "● KHÔNG PHÁT HIỆN phiên ghi âm nào mà Android công bố."
            else ->
                "● ANDROID BÁO CÓ ${configs.size} phiên ghi âm đang hoạt động. Không xác định tên ứng dụng."
        }
    }

    private fun getRoutes(): String {
        val manager = getSystemService(AUDIO_SERVICE) as AudioManager
        val devices = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (devices.isEmpty()) return "Chưa phát hiện thiết bị đầu ra."
        return devices.joinToString("\n") { device ->
            val name = when (device.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa điện thoại"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có dây"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                else -> "Thiết bị âm thanh"
            }
            "• $name"
        }
    }

    private fun card() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xAA293B5B.toInt(), 0x88212D49.toInt(), 0x99232D47.toInt())
        ).apply {
            cornerRadius = dp(24).toFloat()
            setStroke(dp(1), 0x667F91C9)
        }
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(8)
        }
    }

    private fun button(textValue: String, action: () -> Unit) = TextView(this).apply {
        text = textValue
        gravity = Gravity.CENTER
        textSize = 13f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(dp(14), dp(14), dp(14), dp(14))
        background = GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(0xDD655CEB.toInt(), 0xCC895EEB.toInt())
        ).apply {
            cornerRadius = dp(16).toFloat()
            setStroke(dp(1), 0x668F9BFF)
        }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(9)
        }
        setOnClickListener { action() }
    }

    private fun label(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun section(value: String) =
        label(value, 11f, 0xFFAAA0FF.toInt(), true)

    private fun space(height: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, dp(height))
    }

    private fun round(color: Int, radius: Int, stroke: Int = 0) =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != 0) setStroke(dp(1), stroke)
        }

    private fun formatDb(value: Int): String =
        String.format(Locale.US, "%.1f", value / 100f)

    private fun formatDb(value: Float): String =
        String.format(Locale.US, "%.1f", value)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun releaseOutputEffects() {
        try { eq?.release() } catch (_: Exception) {}
        try { enhancer?.release() } catch (_: Exception) {}
        eq = null
        enhancer = null
    }

    override fun onPause() {
        unregisterRecordingMonitor()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (autoDetectEnabled) registerRecordingMonitor()
    }

    override fun onDestroy() {
        autoDetectEnabled = false
        unregisterRecordingMonitor()
        if (micRunning) stopMicTest() else releaseMic()
        releaseOutputEffects()
        try { player?.release() } catch (_: Exception) {}
        player = null
        super.onDestroy()
    }
}
