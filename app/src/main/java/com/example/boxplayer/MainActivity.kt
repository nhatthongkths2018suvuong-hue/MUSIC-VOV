package com.example.boxplayer

import android.Manifest
import android.animation.ValueAnimator
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class Entry(val file: File?, val label: String, val kind: Int, val url: String? = null)

class MainActivity : AppCompatActivity() {

    companion object {
        val EXT = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "mid")
        const val OFF = 0
        const val ONE = 1
        const val FOLDER = 2
        const val ALL = 3
        const val KIND_UP = 0
        const val KIND_DIR = 1
        const val KIND_FILE = 2
        const val KIND_VOV = 3      // dòng "Nghe đài VOV" trên cùng
        const val KIND_STATION = 4  // một kênh đài
        const val KIND_INFO = 5     // dòng thông báo, không bấm được
        const val VOV_FILE = "vov.txt"
    }

    private lateinit var prefs: SharedPreferences
    private lateinit var wave: WaveView
    private lateinit var titleV: TextView
    private lateinit var artistV: TextView
    private lateinit var timeV: TextView
    private lateinit var repeatLabel: TextView
    private lateinit var pathV: TextView
    private lateinit var seek: SeekBar
    private lateinit var btnPlay: ImageButton
    private lateinit var btnRepeat: ImageButton
    private lateinit var rv: RecyclerView

    private val adapter = Adapter()
    private val ui = Handler(Looper.getMainLooper())
    private var mp: MediaPlayer? = null
    private var ready = false
    private var current: File? = null
    private var currentDir: File? = null
    private var queue: List<File> = emptyList()
    private var queueDir: File? = null
    private var browseDir: File? = null
    private var browseTracks: List<File> = emptyList()
    private var repeatMode = OFF
    private var metaToken = 0

    // ---- Đài VOV (phát trực tuyến) ----
    private var inVov = false                    // đang mở danh sách kênh
    private var stations: List<Entry> = emptyList()
    private var currentStation: Entry? = null    // != null nghĩa là đang ở chế độ nghe đài
    private val live get() = currentStation != null
    private var retryCount = 0
    private var retryToken = 0
    private var exo: ExoPlayer? = null           // bộ phát riêng cho đài trực tuyến (HLS)
    private var loadToken = 0
    private val allCache = ConcurrentHashMap<String, List<File>>()
    private val scanning = ConcurrentHashMap<String, Boolean>()

    private val bg = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF1A1A2E.toInt(), 0xFF121212.toInt()))
    private var hueAnim: ValueAnimator? = null
    private var lastHue = -10f
    private var blackout = false
    private var blackoutAt = 0L
    private lateinit var overlay: View
    private var swallowKey = -1
    private var focusBeforeBlackout: View? = null

    private val ticker = object : Runnable {
        override fun run() {
            val p = mp
            if (p != null && ready && !live) {
                try {
                    seek.progress = p.currentPosition
                    updateTime()
                } catch (e: Exception) {
                }
            }
            ui.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        // Lớp phủ đen toàn màn hình (nằm trên cùng), mặc định ẩn
        overlay = View(this).apply {
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        addContentView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        prefs = getSharedPreferences("boxplayer", MODE_PRIVATE)
        wave = findViewById(R.id.wave)
        titleV = findViewById(R.id.title)
        artistV = findViewById(R.id.artist)
        timeV = findViewById(R.id.time)
        repeatLabel = findViewById(R.id.repeatLabel)
        pathV = findViewById(R.id.path)
        seek = findViewById(R.id.seek)
        btnPlay = findViewById(R.id.btnPlay)
        btnRepeat = findViewById(R.id.btnRepeat)
        rv = findViewById(R.id.rv)

        // Nền chuyển màu liên tục (một vòng màu khoảng 14 giây)
        findViewById<View>(R.id.root).background = bg
        hueAnim = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 14000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { a ->
                val h = a.animatedValue as Float
                if (h >= lastHue && h - lastHue < 1f) return@addUpdateListener
                lastHue = h
                val c1 = Color.HSVToColor(floatArrayOf(h, 0.65f, 0.32f))
                val c2 = Color.HSVToColor(floatArrayOf((h + 70f) % 360f, 0.65f, 0.20f))
                bg.setColors(intArrayOf(c1, c2))
                wave.tint = Color.HSVToColor(floatArrayOf((h + 180f) % 360f, 0.45f, 1f))
            }
            start()
        }

        repeatMode = prefs.getInt("repeat", OFF)
        rv.layoutManager = LinearLayoutManager(this)
        rv.adapter = adapter
        rv.setHasFixedSize(true)
        (rv.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false

        btnPlay.setOnClickListener { togglePlay() }
        findViewById<ImageButton>(R.id.btnScreen).setOnClickListener { setBlackout(true) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { step(1) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { prev() }
        btnRepeat.setOnClickListener {
            repeatMode = (repeatMode + 1) % 4
            prefs.edit().putInt("repeat", repeatMode).apply()
            updateRepeatUi()
            // Chỉ quét toàn bộ ổ lưu trữ khi người dùng chọn chế độ này
            if (repeatMode == ALL) (current ?: currentDir)?.let { ensureScan(it) }
        }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                if (fromUser && ready && !live) {
                    try { mp?.seekTo(p) } catch (e: Exception) {}
                    updateTime()
                }
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        updateRepeatUi()
        ui.post(ticker)

        val perm = Manifest.permission.READ_EXTERNAL_STORAGE
        if (ContextCompat.checkSelfPermission(this, perm) == PackageManager.PERMISSION_GRANTED) {
            startBrowsing()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(perm), 1)
        }
    }

    override fun onRequestPermissionsResult(rc: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(rc, perms, res)
        if (res.isNotEmpty() && res[0] == PackageManager.PERMISSION_GRANTED) startBrowsing()
        else toast("Cần cấp quyền đọc bộ nhớ để duyệt nhạc")
    }

    override fun onPause() {
        hueAnim?.pause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!blackout) hueAnim?.resume()
    }

    override fun onDestroy() {
        hueAnim?.cancel()
        ui.removeCallbacksAndMessages(null)
        mp?.release()
        mp = null
        cancelSniffer()
        releaseExo()
        super.onDestroy()
    }

    // ---------- Tắt màn hình (đen hoàn toàn, nhạc vẫn phát) ----------

    private fun setBlackout(on: Boolean) {
        if (blackout == on) return
        blackout = on
        if (on) {
            focusBeforeBlackout = currentFocus
            blackoutAt = SystemClock.uptimeMillis()
            overlay.visibility = View.VISIBLE
            overlay.bringToFront()
            overlay.requestLayout()
            overlay.invalidate()
            findViewById<View>(R.id.root).visibility = View.INVISIBLE
            hueAnim?.pause()
        } else {
            overlay.visibility = View.GONE
            findViewById<View>(R.id.root).visibility = View.VISIBLE
            hueAnim?.resume()
            ui.post { (focusBeforeBlackout ?: btnPlay).requestFocus() }
        }
    }

    // Đang tắt màn hình: phím bất kỳ chỉ để bật lại, không thực hiện chức năng của phím đó
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (blackout) {
            if (e.action == KeyEvent.ACTION_DOWN && SystemClock.uptimeMillis() - blackoutAt > 800) {
                swallowKey = e.keyCode
                setBlackout(false)
            }
            return true
        }
        if (e.keyCode == swallowKey) {
            if (e.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (blackout) {
            if (ev.action == MotionEvent.ACTION_DOWN && SystemClock.uptimeMillis() - blackoutAt > 800) setBlackout(false)
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onKeyDown(code: Int, e: KeyEvent): Boolean {
        when (code) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> step(1)
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> prev()
            else -> return super.onKeyDown(code, e)
        }
        return true
    }

    // ---------- Duyệt thư mục (chỉ đọc thư mục đang mở, chạy nền) ----------

    private fun internalRoot(): File = Environment.getExternalStorageDirectory()

    private fun roots(): List<Pair<File, String>> {
        val res = ArrayList<Pair<File, String>>()
        val internal = internalRoot()
        res.add(internal to "Bộ nhớ trong")
        val internalCanon = try { internal.canonicalPath } catch (e: Exception) { internal.path }
        File("/storage").listFiles()?.forEach {
            val canon = try { it.canonicalPath } catch (e: Exception) { it.path }
            if (it.isDirectory && it.name != "emulated" && it.name != "self" &&
                canon != internalCanon && it.listFiles() != null
            ) res.add(it to "USB / thẻ nhớ (${it.name})")
        }
        return res
    }

    private fun startBrowsing() {
        val last = prefs.getString("last_dir", null)?.let { File(it) }
        val music = File(internalRoot(), "Music")
        val start = when {
            last != null && last.isDirectory && last.canRead() -> last
            music.isDirectory -> music
            else -> internalRoot()
        }
        loadDir(start)
    }

    private fun tracksIn(dir: File?): List<File> {
        val fs = dir?.listFiles() ?: return emptyList()
        return fs.filter { it.isFile && it.extension.lowercase() in EXT }.sortedBy { it.name.lowercase() }
    }

    private fun loadDir(dir: File?, focusFile: File? = null) {
        inVov = false
        currentDir = dir
        val token = ++loadToken
        pathV.text = if (dir == null) "Chọn ổ lưu trữ" else dir.absolutePath
        if (dir != null) prefs.edit().putString("last_dir", dir.absolutePath).apply()
        Thread {
            val items = ArrayList<Entry>()
            var tr: List<File> = emptyList()
            if (dir == null) {
                items.add(Entry(null, "Nghe đài VOV", KIND_VOV))
                roots().forEach { items.add(Entry(it.first, it.second, KIND_DIR)) }
            } else {
                items.add(Entry(null, ".. (lên thư mục cha)", KIND_UP))
                val all = dir.listFiles()?.filter { !it.name.startsWith(".") && it.canRead() } ?: emptyList()
                all.filter { it.isDirectory }.sortedBy { it.name.lowercase() }
                    .forEach { items.add(Entry(it, it.name, KIND_DIR)) }
                tr = tracksIn(dir)
                tr.forEach { items.add(Entry(it, it.name, KIND_FILE)) }
            }
            runOnUiThread {
                if (token == loadToken) {
                    browseDir = dir
                    browseTracks = tr
                    adapter.items = items
                    adapter.notifyDataSetChanged()
                    var idx = if (focusFile != null) items.indexOfFirst { it.file == focusFile } else -1
                    if (idx < 0) idx = if (items.size > 1 && dir != null) 1 else 0
                    (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
                    rv.postDelayed({ rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }, 80)
                }
            }
        }.start()
    }

    private fun goUp() {
        if (inVov) { loadDir(null); return }
        val d = currentDir ?: return
        val p = d.parentFile
        if (roots().any { it.first == d } || p == null || !p.canRead()) loadDir(null, d) else loadDir(p, d)
    }

    private fun onEntry(e: Entry) {
        when (e.kind) {
            KIND_UP -> goUp()
            KIND_DIR -> loadDir(e.file)
            KIND_VOV -> loadVov()
            KIND_STATION -> playStation(e)
            KIND_INFO -> {}
            else -> e.file?.let { queueDir = null; play(it) }
        }
    }

    // ---------- Phát nhạc ----------

    private fun play(f: File) {
        val old = current
        leaveLive()
        current = f
        if (repeatMode == ALL) ensureScan(f)
        ready = false
        seek.progress = 0
        try {
            mp?.release()
            val p = MediaPlayer()
            p.setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            p.setDataSource(f.absolutePath)
            p.setOnPreparedListener { m ->
                ready = true
                seek.max = m.duration
                seek.keyProgressIncrement = 5000
                m.start()
                updatePlayIcon()
                updateTime()
            }
            p.setOnCompletionListener { onComplete() }
            p.setOnErrorListener { _, _, _ ->
                ready = false
                toast("Không phát được: " + f.name)
                updatePlayIcon()
                true
            }
            p.prepareAsync()
            mp = p
        } catch (e: Exception) {
            toast("Lỗi mở file: " + f.name)
        }
        showMeta(f)
        updatePlayIcon()
        adapter.refresh(old, f)
    }

    private fun onComplete() {
        if (live) { scheduleRetry(); return }
        when (repeatMode) {
            ONE -> { mp?.seekTo(0); mp?.start() }
            FOLDER, ALL -> step(1)
            else -> updatePlayIcon()
        }
    }

    private fun trackList(cur: File): List<File> =
        if (repeatMode == ALL) (rootOf(cur)?.let { allCache[it.path] } ?: folderQueue(cur.parentFile))
        else folderQueue(cur.parentFile)

    private fun folderQueue(dir: File?): List<File> {
        if (dir != null && dir == queueDir) return queue
        queue = if (dir != null && dir == browseDir) browseTracks else tracksIn(dir)
        queueDir = dir
        return queue
    }

    private fun step(dir: Int) {
        if (live) {
            val list = stations.filter { it.kind == KIND_STATION }
            if (list.isEmpty()) return
            val i = list.indexOfFirst { it.url == currentStation?.url }.let { if (it < 0) 0 else it }
            playStation(list[(i + dir + list.size) % list.size])
            return
        }
        val cur = current ?: return
        val list = trackList(cur)
        if (list.isEmpty()) return
        val i = list.indexOf(cur).let { if (it < 0) 0 else it }
        play(list[(i + dir + list.size) % list.size])
    }

    private fun prev() {
        if (live) { step(-1); return }
        val p = mp
        val far = try { p != null && ready && p.currentPosition > 3000 } catch (e: Exception) { false }
        if (far) p?.seekTo(0) else step(-1)
    }

    private fun togglePlay() {
        val st = currentStation
        if (st != null) {
            // Đài trực tuyến: "tạm dừng" = ngắt luồng, bấm lại = kết nối lại (nghe đúng thời điểm hiện tại)
            if (exo != null || resolving) stopLive() else playStation(st)
            return
        }
        val p = mp
        if (p == null || !ready) {
            if (current == null) toast("Chọn một bài ở danh sách bên phải")
            return
        }
        try { if (p.isPlaying) p.pause() else p.start() } catch (e: Exception) {}
        updatePlayIcon()
    }

    private fun updatePlayIcon() {
        val playing = try {
            if (live) exo?.let { it.playWhenReady && it.playbackState != Player.STATE_ENDED && it.playbackState != Player.STATE_IDLE } == true
            else mp?.isPlaying == true
        } catch (e: Exception) { false }
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        wave.active = playing
    }

    private fun updateTime() {
        val p = mp ?: return
        if (!ready || live) return
        try { timeV.text = fmt(p.currentPosition) + " / " + fmt(p.duration) } catch (e: Exception) {}
    }

    private fun fmt(ms: Int) = "%d:%02d".format(ms / 60000, ms / 1000 % 60)

    private fun updateRepeatUi() {
        val (txt, color) = when (repeatMode) {
            ONE -> "Lặp lại 1 bài" to 0xFF82B1FF.toInt()
            FOLDER -> "Lặp lại cả thư mục" to 0xFFFFD54F.toInt()
            ALL -> "Lặp lại tất cả các thư mục" to 0xFF69F0AE.toInt()
            else -> "Không lặp (hết bài thì dừng)" to 0xFFBDBDBD.toInt()
        }
        btnRepeat.setImageResource(if (repeatMode == ONE) R.drawable.ic_repeat_one else R.drawable.ic_repeat)
        btnRepeat.setColorFilter(color)
        repeatLabel.text = txt
        repeatLabel.setTextColor(color)
    }

    // ---------- Đài VOV (phát trực tuyến) ----------

    // /storage/emulated/0/Android/data/<tên gói app>/files/vov.txt  (không cần cấp quyền)
    private fun vovFile(): File? = getExternalFilesDir(null)?.let { File(it, VOV_FILE) }

    // Lần đầu chưa có file thì chép file mẫu trong app ra để người dùng sửa
    private fun ensureVovFile(): File? {
        val f = vovFile() ?: return null
        try {
            f.parentFile?.mkdirs()
            if (!f.exists()) assets.open(VOV_FILE).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        } catch (e: Exception) {
        }
        return f
    }

    // Mỗi dòng: "Tên|link" (hoặc ngăn cách bằng dấu , ; tab) hoặc chỉ "link".
    private fun parseStations(text: String): List<Entry> {
        val out = ArrayList<Entry>()
        for (raw in text.lines()) {
            val line = raw.replace("\uFEFF", "").trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val i1 = line.indexOf("http://", ignoreCase = true)
            val i2 = line.indexOf("https://", ignoreCase = true)
            val idx = listOf(i1, i2).filter { it >= 0 }.minOrNull() ?: continue
            val url = line.substring(idx).trim()
            var name = line.substring(0, idx).trim().trimEnd('|', ',', ';', '=', '\t', ' ').trim()
            if (name.isEmpty()) {
                // Chỉ có link: lấy tên từ cuối đường dẫn, vd .../radio/vov4-tay-nguyen -> "VOV4 TAY NGUYEN"
                val slug = url.substringBefore('?').trimEnd('/').substringAfterLast('/')
                name = if (url.contains("/radio/") && slug.isNotEmpty()) slug.replace('-', ' ').uppercase() else "Kênh " + (out.size + 1)
            }
            out.add(Entry(null, name, KIND_STATION, url))
        }
        return out
    }

    private fun loadVov() {
        inVov = true
        currentDir = null
        val token = ++loadToken
        pathV.text = "Đài VOV"
        Thread {
            var text: String? = null
            val vf = ensureVovFile()
            try { if (vf != null && vf.isFile) text = vf.readText(Charsets.UTF_8) } catch (e: Exception) {}
            if (text == null) {
                try { text = assets.open(VOV_FILE).bufferedReader(Charsets.UTF_8).use { it.readText() } } catch (e: Exception) {}
            }
            val list = if (text == null) emptyList() else parseStations(text)
            val items = ArrayList<Entry>()
            items.add(Entry(null, ".. (lên thư mục cha)", KIND_UP))
            items.addAll(list)
            if (list.isEmpty()) items.add(Entry(null, "Chưa có kênh. Sửa file: ${vf?.absolutePath ?: VOV_FILE}", KIND_INFO))
            runOnUiThread {
                if (token == loadToken && inVov) {
                    stations = list
                    browseDir = null
                    browseTracks = emptyList()
                    adapter.items = items
                    adapter.notifyDataSetChanged()
                    // Ưu tiên đưa tiêu điểm vào kênh đang phát, nếu không thì kênh đầu tiên
                    var idx = items.indexOfFirst { it.kind == KIND_STATION && it.url == currentStation?.url }
                    if (idx < 0) idx = if (items.size > 1) 1 else 0
                    (rv.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(idx, 0)
                    rv.postDelayed({ rv.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() }, 80)
                }
            }
        }.start()
    }

    private fun playStation(st: Entry, auto: Boolean = false) {
        val url = st.url ?: return
        ui.removeCallbacks(retryRunnable)
        cancelSniffer()
        val token = ++retryToken
        if (!auto) retryCount = 0   // người dùng chủ động chọn/bấm Play: tính lại từ đầu
        currentStation = st
        current = null
        ++metaToken
        ready = false
        try { mp?.release() } catch (e: Exception) {}
        mp = null
        releaseExo()
        // Giao diện giữ nguyên: dùng chính các ô tên bài / nghệ sĩ / thanh tua / thời gian
        titleV.text = st.label
        artistV.text = "VOV - Trực tuyến"
        seek.isEnabled = false
        seek.max = 1
        seek.progress = 0
        if (isDirectStream(url)) {
            timeV.text = "Đang kết nối..."
            startStream(st, url, null, token)
        } else {
            // Link là trang web (vd https://vovmedia.vn/radio/vov4-tay-nguyen): app tự tìm link m3u8
            val key = "res_" + url
            val cached = if (auto) null else prefs.getString(key, null)
            if (auto) prefs.edit().remove(key).apply()   // kết nối lại: bỏ link cũ, lấy link mới
            if (cached != null) {
                timeV.text = "Đang kết nối..."
                startStream(st, cached, prefs.getString("ua", null), token, url)
            } else {
                timeV.text = "Đang tìm link đài..."
                resolving = true
                resolveStream(url) { real, ua ->
                    resolving = false
                    if (token != retryToken) return@resolveStream
                    if (real == null) {
                        scheduleRetry()
                    } else {
                        prefs.edit().putString(key, real).putString("ua", ua).apply()
                        timeV.text = "Đang kết nối..."
                        startStream(st, real, ua, token, url)
                    }
                }
            }
        }
        updatePlayIcon()
        adapter.notifyDataSetChanged()
    }

    private fun isDirectStream(u: String): Boolean {
        val l = u.lowercase()
        return l.contains(".m3u8") || l.contains(".mp3") || l.contains(".aac") || l.contains(".m4a") || l.contains(".ogg")
    }

    private fun startStream(st: Entry, url: String, ua: String?, token: Int, referer: String? = null) {
        try {
            val http = DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)   // link VOV chuyển hướng sang CDN khác
                .setConnectTimeoutMs(15000)
                .setReadTimeoutMs(15000)
            if (!ua.isNullOrEmpty()) http.setUserAgent(ua)
            http.setDefaultRequestProperties(mapOf("Referer" to (referer ?: "https://vovmedia.vn/")))
            val player = ExoPlayer.Builder(this)
                .setMediaSourceFactory(DefaultMediaSourceFactory(http))
                .setLoadControl(DefaultLoadControl())
                .build()
            player.setAudioAttributes(
                androidx.media3.common.AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).setUsage(C.USAGE_MEDIA).build(), true)
            player.setWakeMode(C.WAKE_MODE_NETWORK)     // giữ CPU + Wi-Fi khi tắt màn hình
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (token != retryToken) return
                    when (state) {
                        Player.STATE_READY -> { ready = true; retryCount = 0; timeV.text = "Trực tiếp" }
                        Player.STATE_BUFFERING -> timeV.text = if (ready) "Đang tải..." else "Đang kết nối..."
                        Player.STATE_ENDED -> scheduleRetry()
                        else -> {}
                    }
                    updatePlayIcon()
                }
                override fun onPlayerError(error: PlaybackException) {
                    if (token != retryToken) return
                    ready = false
                    scheduleRetry()
                }
            })
            player.setMediaItem(MediaItem.fromUri(url))
            player.playWhenReady = true
            player.prepare()
            exo = player
        } catch (e: Throwable) {
            toast("Lỗi mở kênh: " + st.label)
            timeV.text = "Lỗi kết nối"
        }
        updatePlayIcon()
    }

    // ---- Tự tìm link m3u8: mở trang VOV trong WebView ẩn, bắt request .m3u8 do trình phát của trang gửi ----
    private var sniffer: WebView? = null
    private var resolving = false
    private var sniffToken = 0

    private fun cancelSniffer() {
        ++sniffToken
        resolving = false
        val w = sniffer ?: return
        sniffer = null
        try { w.stopLoading(); w.loadUrl("about:blank"); w.destroy() } catch (e: Exception) {}
    }

    private fun resolveStream(pageUrl: String, done: (String?, String?) -> Unit) {
        val my = ++sniffToken
        var finished = false
        fun finish(real: String?, ua: String?) {
            if (finished || my != sniffToken) return
            finished = true
            cancelSniffer()
            done(real, ua)
        }
        try {
            val w = WebView(this)
            sniffer = w
            val st = w.settings
            st.javaScriptEnabled = true
            st.domStorageEnabled = true
            st.mediaPlaybackRequiresUserGesture = false
            st.cacheMode = WebSettings.LOAD_NO_CACHE
            val ua = st.userAgentString
            val clickJs = "(function(){var best=null,bl=1e9;var els=document.querySelectorAll('a,button,div,span,i,li');" +
                "for(var i=0;i<els.length;i++){var t=(els[i].innerText||'').trim().toLowerCase();" +
                "if(t.length<40&&(t.indexOf('nghe ngay')>=0||t.indexOf('nghe trực tuyến')>=0||t.indexOf('nghe radio')>=0||t.indexOf('nghe đài')>=0)){if(t.length<bl){best=els[i];bl=t.length;}}}" +
                "if(!best){best=document.querySelector('[class*=play],[id*=play],[aria-label*=play],[title*=Play]');}" +
                "if(best){best.click();}" +
                "var m=document.querySelectorAll('audio,video');for(var j=0;j<m.length;j++){try{m[j].muted=true;m[j].play();}catch(e){}}})();"
            w.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, req: WebResourceRequest): WebResourceResponse? {
                    val u = req.url.toString()
                    val l = u.lowercase()
                    if (l.contains(".m3u8")) ui.post { finish(u, ua) }
                    // Chặn tải luồng thật để trang không phát tiếng song song với app
                    if (l.contains(".m3u8") || l.contains(".ts?") || l.endsWith(".ts") || l.contains(".aac")) {
                        return WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    return null
                }
                override fun onPageFinished(view: WebView, url: String?) {
                    // Thử bấm "Nghe ngay" nhiều lần phòng khi nút hiện chậm
                    for (i in 0..8) ui.postDelayed({ if (!finished && my == sniffToken) try { view.evaluateJavascript(clickJs, null) } catch (e: Exception) {} }, 1000L * i + 500)
                }
            }
            w.loadUrl(pageUrl)
            ui.postDelayed({ finish(null, null) }, 30000)
        } catch (e: Throwable) {
            toast("Máy không mở được WebView để lấy link đài")
            finish(null, null)
        }
    }

    private fun releaseExo() {
        val e = exo ?: return
        exo = null
        try { e.release() } catch (x: Throwable) {}
    }

    // Mất mạng / luồng bị ngắt: tự kết nối lại tối đa 5 lần, cách nhau 4 giây
    private val retryRunnable = Runnable {
        currentStation?.let { playStation(it, true) }
    }

    private fun scheduleRetry() {
        if (currentStation == null) return
        if (retryCount >= 5) {
            timeV.text = "Mất kết nối - bấm Play để thử lại"
            releaseExo()
            ready = false
            updatePlayIcon()
            return
        }
        retryCount++
        timeV.text = "Mất kết nối, thử lại ($retryCount/5)..."
        ui.removeCallbacks(retryRunnable)
        ui.postDelayed(retryRunnable, 4000)
    }

    private fun stopLive() {
        cancelSniffer()
        ++retryToken
        ui.removeCallbacks(retryRunnable)
        retryCount = 0
        releaseExo()
        ready = false
        timeV.text = "Đã dừng"
        updatePlayIcon()
    }

    // Chuyển từ đài sang nhạc trong máy: trả lại thanh tua / thời gian như cũ
    private fun leaveLive() {
        if (currentStation == null) return
        cancelSniffer()
        ++retryToken
        ui.removeCallbacks(retryRunnable)
        releaseExo()
        currentStation = null
        retryCount = 0
        seek.isEnabled = true
        timeV.text = "0:00 / 0:00"
        adapter.notifyDataSetChanged()
    }

    // ---------- Quét toàn bộ ổ lưu trữ (chỉ khi chọn "Lặp lại tất cả các thư mục") ----------

    private fun rootOf(f: File): File? =
        roots().map { it.first }.firstOrNull { f.path == it.path || f.path.startsWith(it.path + "/") }

    private fun ensureScan(f: File) {
        val root = rootOf(f) ?: return
        if (allCache.containsKey(root.path)) return
        if (scanning.putIfAbsent(root.path, true) != null) return
        Thread {
            val out = ArrayList<File>()
            scan(root, 0, out)
            allCache[root.path] = out.sortedBy { it.path.lowercase() }
            scanning.remove(root.path)
        }.apply { priority = Thread.MIN_PRIORITY }.start()
    }

    private fun scan(d: File, depth: Int, out: MutableList<File>) {
        if (depth > 12) return
        val fs = d.listFiles() ?: return
        for (x in fs) {
            if (x.name.startsWith(".")) continue
            if (x.isDirectory) {
                if (x.name != "Android") scan(x, depth + 1, out)
            } else if (x.extension.lowercase() in EXT) out.add(x)
        }
    }

    // ---------- Tên bài / nghệ sĩ từ tag ----------

    private fun showMeta(f: File) {
        titleV.text = f.nameWithoutExtension
        artistV.text = f.parentFile?.name ?: ""
        val token = ++metaToken
        Thread {
            var title: String? = null
            var artist: String? = null
            val r = MediaMetadataRetriever()
            try {
                r.setDataSource(f.absolutePath)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            } catch (e: Exception) {
            } finally {
                try { r.release() } catch (e: Exception) {}
            }
            val t = title
            val a = artist
            runOnUiThread {
                if (token == metaToken) {
                    if (!t.isNullOrBlank()) titleV.text = t
                    if (!a.isNullOrBlank()) artistV.text = a
                }
            }
        }.start()
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------- Adapter ----------

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.icon)
        val text: TextView = v.findViewById(R.id.text)
    }

    inner class Adapter : RecyclerView.Adapter<VH>() {
        var items: List<Entry> = emptyList()

        override fun onCreateViewHolder(p: ViewGroup, t: Int) =
            VH(LayoutInflater.from(p.context).inflate(R.layout.item_entry, p, false))

        override fun getItemCount() = items.size

        override fun onBindViewHolder(h: VH, pos: Int) {
            val e = items[pos]
            h.text.text = e.label
            h.icon.setImageResource(when (e.kind) {
                KIND_FILE -> R.drawable.ic_music
                KIND_VOV, KIND_STATION -> R.drawable.ic_radio
                else -> R.drawable.ic_folder
            })
            val playing = (e.kind == KIND_FILE && e.file == current) ||
                (e.kind == KIND_STATION && e.url == currentStation?.url)
            val c = when {
                playing -> 0xFFFFFFFF.toInt()
                e.kind == KIND_FILE || e.kind == KIND_STATION -> 0xFFB0BEC5.toInt()
                e.kind == KIND_VOV -> 0xFFFF8A80.toInt()
                e.kind == KIND_INFO -> 0xFF9E9E9E.toInt()
                else -> 0xFFFFD54F.toInt()
            }
            h.text.setTextColor(c)
            h.text.setTypeface(null, if (playing) Typeface.BOLD else Typeface.NORMAL)
            h.icon.setColorFilter(c)
            h.itemView.setOnClickListener { onEntry(e) }
        }

        fun refresh(a: File?, b: File?) {
            items.forEachIndexed { i, e ->
                if (e.file != null && (e.file == a || e.file == b)) notifyItemChanged(i)
            }
        }
    }
}
