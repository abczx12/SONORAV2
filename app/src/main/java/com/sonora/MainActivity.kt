package com.sonora

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.audiofx.AcousticEchoCanceler
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class MainActivity : AppCompatActivity() {
    private var player: ExoPlayer? = null
    private var eq: Equalizer? = null
    private var enhancer: LoudnessEnhancer? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null
    private var recording = false
    private var voiceAgc: AutomaticGainControl? = null
    private var voiceNs: NoiseSuppressor? = null
    private var voiceAec: AcousticEchoCanceler? = null
    private lateinit var status: TextView
    private lateinit var track: TextView
    private lateinit var play: TextView
    private lateinit var routeInfo: TextView
    private lateinit var gainInfo: TextView
    private lateinit var voiceInfo: TextView
    private lateinit var root: LinearLayout
    private var gainValue = 250

    private val chooseAudio = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { playUri(it) }
    }
    private val requestMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) toggleVoiceRecording()
        else voiceInfo.text = "Bạn chưa cấp quyền micro cho SONORA."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(8, 11, 22)
        window.navigationBarColor = Color.rgb(8, 11, 22)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(28))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.rgb(8,11,22), Color.rgb(19,22,43), Color.rgb(10,15,31)))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
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
        val logo = TextView(this).apply {
            text = "≋"; textSize = 34f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = round(0xFF7668F5.toInt(), 22)
            layoutParams = LinearLayout.LayoutParams(dp(58), dp(58))
        }
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12),0,0,0) }
        names.addView(label("SONORA", 25f, Color.WHITE, true))
        names.addView(label("SOUND EXPERIENCE  •  V2.0", 10f, 0xFF9DA9C9.toInt()))
        header.addView(logo); header.addView(names)
        root.addView(header)
        root.addView(space(18))
        val island = TextView(this).apply {
            text = "●  SONORA ENGINE     READY"
            textSize = 11f; letterSpacing = .12f; gravity = Gravity.CENTER
            setTextColor(0xFFE4E8FF.toInt()); setPadding(dp(16),dp(13),dp(16),dp(13))
            background = round(0xCC252D49.toInt(), 28, 0x557F8DCA)
        }
        root.addView(island)
        root.addView(space(18))
        root.addView(section("NOW PLAYING"))
        val playerCard = card()
        val art = TextView(this).apply {
            text = "♫"; textSize = 52f; gravity = Gravity.CENTER
            setTextColor(Color.WHITE); background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF655CEB.toInt(),0xFFB15DCE.toInt(),0xFF293E78.toInt())).apply { cornerRadius = dp(22).toFloat() }
            layoutParams = LinearLayout.LayoutParams(dp(94), dp(94))
        }
        track = label("Chưa chọn bài nhạc", 17f, Color.WHITE, true)
        status = label("SẴN SÀNG", 10f, 0xFF9DA9C9.toInt())
        val info = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(14),0,0,0) }
        info.addView(track); info.addView(space(7)); info.addView(status)
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        row.addView(art); row.addView(info)
        playerCard.addView(row)
        playerCard.addView(space(16))
        val choose = button("＋  CHỌN NHẠC") { chooseAudio.launch(arrayOf("audio/*")) }
        play = button("▶  PHÁT NHẠC") { player?.let { if (it.isPlaying) it.pause() else it.play() } }
        val stop = button("■  DỪNG") { player?.stop(); play.text = "▶  PHÁT NHẠC"; status.text = "ĐÃ DỪNG" }
        playerCard.addView(choose); playerCard.addView(play); playerCard.addView(stop)
        root.addView(playerCard)
        root.addView(space(16))
        root.addView(section("AUDIO ENGINE"))
        val engine = card()
        engine.addView(label("OUTPUT BOOST", 12f, 0xFFBFC9E8.toInt(), true))
        gainInfo = label("Mức tăng: 2.5 dB", 12f, 0xFF9DA9C9.toInt())
        engine.addView(gainInfo)
        val gain = SeekBar(this).apply {
            max = 1200; progress = gainValue
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, value: Int, fromUser: Boolean) {
                    gainValue = value
                    gainInfo.text = "Mức tăng: %.1f dB".format(value / 100f)
                    try { enhancer?.setTargetGain(value) } catch (_: Exception) {}
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        engine.addView(gain)
        engine.addView(space(8))
        engine.addView(label("EQ PRESET", 12f, 0xFFBFC9E8.toInt(), true))
        val preset = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("Flat", "Voice Clear", "Podcast", "Vocal", "Bass Soft"))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onNothingSelected(parent: AdapterView<*>?) {}
                override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { applyPreset(position) }
            }
        }
        engine.addView(preset)
        root.addView(engine)
        root.addView(space(16))
        root.addView(section("VOICE STUDIO"))
        val voice = card()
        voice.addView(label("MIC PROCESSING", 12f, 0xFFBFC9E8.toInt(), true))
        voice.addView(label("Tăng cường giọng nói khi ghi âm trong SONORA bằng xử lý của Android.", 12f, 0xFF9DA9C9.toInt()))
        voiceInfo = label("Sẵn sàng • Chưa ghi âm", 12f, 0xFF9DA9C9.toInt())
        voice.addView(space(8)); voice.addView(voiceInfo)
        voice.addView(button("●  BẮT ĐẦU / DỪNG GHI ÂM") { askMicAndRecord() })
        voice.addView(button("▶  NGHE BẢN GHI GẦN NHẤT") { playRecording() })
        root.addView(voice)
        root.addView(space(16))
        root.addView(section("AUDIO ROUTING"))
        val routing = card()
        routeInfo = label("Đang kiểm tra thiết bị…", 12f, 0xFFBFC9E8.toInt())
        routing.addView(routeInfo)
        routing.addView(button("↻  LÀM MỚI THIẾT BỊ") { routeInfo.text = getRoutes() })
        routing.addView(label("Thông tin đầu ra không đồng nghĩa với micro ảo. Android không cho APK thường thay micro của ứng dụng khác khi không có quyền hệ thống.", 10f, 0xFF8794B7.toInt()))
        root.addView(routing)
        root.addView(space(14))
        root.addView(label("SONORA • Made for your sound", 11f, 0xFF7885A8.toInt()).apply { gravity = Gravity.CENTER })
    }

    private fun askMicAndRecord() {
        if (recording) { stopRecording(); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            toggleVoiceRecording()
        } else requestMic.launch(Manifest.permission.RECORD_AUDIO)
    }

    private fun toggleVoiceRecording() {
        try {
            val sessionId = r.audioSessionId
try { if (AutomaticGainControl.isAvailable()) voiceAgc = AutomaticGainControl.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
try { if (NoiseSuppressor.isAvailable()) voiceNs = NoiseSuppressor.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
try { if (AcousticEchoCanceler.isAvailable()) voiceAec = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true } } catch (_: Exception) {}
val active = listOfNotNull(voiceAgc, voiceNs, voiceAec).size
voiceInfo.text = "● ĐANG GHI ÂM • Voice Communication • $active/3 bộ xử lý khả dụng"
        } catch (e: Exception) {
            recording = false
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            voiceInfo.text = "Không thể khởi động micro: ${e.message ?: "lỗi thiết bị"}"
        }
    }

    private fun stopRecording() {
        try { recorder?.stop(); voiceInfo.text = "Đã lưu bản ghi trong phiên SONORA." }
        catch (_: Exception) { voiceInfo.text = "Bản ghi quá ngắn hoặc không hoàn tất." }
        finally {
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null; recording = false
            try { voiceAgc?.release() } catch (_: Exception) {}; voiceAgc = null
            try { voiceNs?.release() } catch (_: Exception) {}; voiceNs = null
            try { voiceAec?.release() } catch (_: Exception) {}; voiceAec = null
        }
    }

    private fun playRecording() {
        val file = recordingFile
        if (file == null || !file.exists()) { voiceInfo.text = "Chưa có bản ghi để nghe."; return }
        player?.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
        player?.prepare(); player?.play()
        voiceInfo.text = "Đang phát bản ghi đã xử lý bởi đường thu của Android."
    }

    private fun playUri(uri: Uri) {
        try {
            player?.setMediaItem(MediaItem.fromUri(uri)); player?.prepare(); player?.play()
            track.text = uri.lastPathSegment ?: "Tệp âm thanh"
            status.text = "ĐANG TẢI…"
            val session = player?.audioSessionId ?: 0
            if (session != 0) attachEffects(session)
        } catch (e: Exception) { status.text = "Không mở được tệp nhạc" }
    }

    private fun attachEffects(sessionId: Int) {
        releaseEffects()
        try { eq = Equalizer(0, sessionId).apply { enabled = true } } catch (_: Exception) { eq = null }
        try { enhancer = LoudnessEnhancer(sessionId).apply { setTargetGain(gainValue); enabled = true } } catch (_: Exception) { enhancer = null }
    }

    private fun applyPreset(index: Int) {
        val e = eq ?: return
        val profiles = listOf(
            listOf(0,0,0,0,0), listOf(-1,1,3,2,0), listOf(1,2,2,1,0),
            listOf(-1,1,3,2,1), listOf(3,2,0,-1,-2)
        )
        try {
            val n = e.numberOfBands.toInt(); val range = e.bandLevelRange
            for (i in 0 until n) {
                val v = profiles[index][(i * 5 / n).coerceIn(0,4)] * 100
                e.setBandLevel(i.toShort(), v.coerceIn(range[0].toInt(),range[1].toInt()).toShort())
            }
        } catch (_: Exception) {}
    }

    private fun getRoutes(): String {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val ds = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        if (ds.isEmpty()) return "Chưa phát hiện thiết bị đầu ra."
        return ds.joinToString("\n") { d ->
            val n = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Loa điện thoại"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Tai nghe có dây"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB Audio"
                else -> "Thiết bị âm thanh"
            }
            "• $n"
        } + "\n\nChỉ liệt kê đầu ra đang được Android nhận diện."
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(16),dp(16),dp(16),dp(16))
        background = round(0xCC171F36.toInt(), 24, 0x447D8BB8)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(8) }
    }
    private fun button(textValue: String, action: () -> Unit): TextView = TextView(this).apply {
        text = textValue; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE); setPadding(dp(14),dp(14),dp(14),dp(14))
        background = round(0xFF655CEB.toInt(), 16)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(9) }
        setOnClickListener { alpha = .75f; postDelayed({ alpha = 1f },100); action() }
    }
    private fun label(textValue: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = textValue; textSize = size; setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun section(t: String) = label(t,11f,0xFF9C91FF.toInt(),true).apply { letterSpacing = .16f }
    private fun space(h: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1,dp(h)) }
    private fun round(color: Int, radius: Int, stroke: Int = 0): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat(); if (stroke != 0) setStroke(dp(1),stroke) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun releaseEffects() {
        try { eq?.release() } catch (_: Exception) {}; eq = null
        try { enhancer?.release() } catch (_: Exception) {}; enhancer = null
    }
    override fun onDestroy() {
        if (recording) stopRecording()
        releaseEffects()
        player?.release(); player = null
        super.onDestroy()
    }
}
