package com.xmgo.music.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.compose.ui.graphics.toArgb
import com.xmgo.music.R
import com.xmgo.music.lyrics.LrcParser
import com.xmgo.music.lyrics.LyricLine
import com.xmgo.music.presentation.theme.AppThemePalette

/**
 * 悬浮歌词系统窗（对齐 qplayer OverlayLyricWindow）：
 * SYSTEM_ALERT_WINDOW + TYPE_APPLICATION_OVERLAY，Canvas 自绘三行歌词、
 * 换行缓出动画、当前句进度扫色、拖动定位、点击回主界面、锁定穿透。
 *
 * 数据源是 [OverlayLyricFrame]（由 [OverlayLyricManager] 每 100ms 推入），
 * 与主窗口解耦；本类只做窗口生命周期 + 绘制。
 */
object OverlayLyricWindow {

    data class Frame(
        val playing: Boolean = false,
        val title: String = "",
        val artist: String = "",
        val lines: List<LyricLine> = emptyList(),
        val positionMs: Long = 0L,
        /** [System.nanoTime] 于帧推入时刻；绘制时按 playing 外推，扫色不再按 tick 跳步。 */
        val receivedAtNs: Long = 0L,
        /** 当前曲是否已收藏 / 是否可收藏（本地歌不可）。 */
        val favorite: Boolean = false,
        val canFavorite: Boolean = false
    )

    /** 操作栏动作回调（由 [OverlayLyricManager] 实现）。 */
    interface Actions {
        fun onPrevious()
        fun onPlayPause()
        fun onNext()
        fun onToggleFavorite()
        fun onFontDelta(delta: Int)
        fun onLock()
        fun onOpenApp()
        fun onClose()

        /** 点击上一句/下一句歌词行 → 跳到那一句。 */
        fun onSeek(timeMs: Long)
    }

    @Volatile private var wanted = false
    @Volatile private var frame: Frame = Frame()
    @Volatile private var style: OverlayStyle = OverlayStyle()
    @Volatile private var actions: Actions? = null

    private var instance: OverlayWindowInstance? = null
    private val main = Handler(Looper.getMainLooper())

    data class OverlayStyle(
        val fontPx: Int = 16,
        val widthPct: Int = 90,
        val bgOpacity: Float = 0.63f,
        val dark: Boolean = true,
        val locked: Boolean = false,
        val outline: Boolean = true,
        /** 行高 = 字号 × pct/100。调大窗口整体变高（高度按行高 × 可见行数算）。 */
        val lineSpacingPct: Int = 210,
        /** 译文与主行的间距（dp，可为负 = 紧贴主行）。 */
        val transGapDp: Int = -3,
        /** 1 = 只看当前句（沉浸、不挡内容），3 = 上/当前/下。 */
        val lineCount: Int = 3,
        /** 0 = 双语，1 = 只看原文，2 = 只看译文。 */
        val transMode: Int = 0
    )

    fun setWanted(ctx: Context, on: Boolean) {
        wanted = on
        val live = instance
        if (live != null) main.post(live::applyWanted)
        else if (on) attach(ctx)
    }

    fun isWanted(): Boolean = wanted

    fun setActions(a: Actions?) {
        actions = a
    }

    fun restyle(newStyle: OverlayStyle) {
        style = newStyle
        val live = instance
        if (live != null) main.post(live::applyStyle)
    }

    fun canDraw(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(ctx)

    fun attach(ctx: Context) {
        if (instance != null) return
        instance = OverlayWindowInstance(ctx.applicationContext)
        instance?.applyWanted()
    }

    fun detach() {
        val live = instance
        instance = null
        if (live != null) main.post(live::hide)
    }

    fun tick(newFrame: Frame) {
        frame = newFrame
        val live = instance
        if (live != null) main.post(live::update)
    }

    fun toggleLocked(ctx: Context, on: Boolean) {
        restyle(style.copy(locked = on))
        // 持久化由 manager 负责，这里只刷新窗
        @Suppress("UNUSED_EXPRESSION")
        ctx
    }

    // ---------------------------------------------------------------- 窗口实例

    private class OverlayWindowInstance(private val app: Context) {
        private val wm = app.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        private val prefs: SharedPreferences =
            app.getSharedPreferences("overlay.lyric", Context.MODE_PRIVATE)

        private var view: LyricView? = null
        private var root: LinearLayout? = null
        private var bar: ControlBar? = null
        private val autoHide = Runnable { setBarVisible(false) }
        private var params: WindowManager.LayoutParams? = null
        private var attached = false
        private var downRawX = 0f
        private var downRawY = 0f
        private var downX = 0
        private var downY = 0
        private var dragging = false

        fun applyWanted() {
            if (wanted && canDraw(app)) show() else hide()
        }

        private fun show() {
            if (attached) return
            val dm = app.resources.displayMetrics
            val d = dm.density

            val v = LyricView(app)
            v.setOnTouchListener { _, event -> onTouch(event) }
            val cb = ControlBar(app) { scheduleAutoHide() }
            cb.visibility = View.GONE
            val container = LinearLayout(app).apply {
                orientation = LinearLayout.VERTICAL
                addView(v, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ))
                addView(cb, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = Math.round(6 * d) })
            }

            @Suppress("DEPRECATION")
            val type = if (Build.VERSION.SDK_INT >= 26) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                WindowManager.LayoutParams.TYPE_PHONE
            }
            val p = WindowManager.LayoutParams(
                Math.round(dm.widthPixels - 32 * d),
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            p.gravity = Gravity.TOP or Gravity.START
            p.x = prefs.getInt("x", Math.round(16 * d))
            p.y = prefs.getInt("y", Math.round(dm.heightPixels * 0.72f))
            params = p
            try {
                wm.addView(container, p)
                view = v
                root = container
                bar = cb
                attached = true
                // 必须在 view 赋值后调：applyStyle 首行 view ?: return，
                // 提前调会被 null 挡掉 → 首次/重启用走 LyricView 字段默认值（样式不对），
                // 只有后续 restyle（调参数）才修正
                applyStyle()
            } catch (_: Throwable) {
                attached = false
                view = null
                root = null
                bar = null
            }
        }

        fun applyStyle() {
            val v = view ?: return
            val p = params ?: return
            val s = style
            v.configure(s)
            val dm = app.resources.displayMetrics
            p.width = Math.round(dm.widthPixels * s.widthPct.coerceIn(50, 100) / 100f)
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            if (s.locked) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            p.flags = flags
            if (attached) {
                try {
                    wm.updateViewLayout(root ?: v, p)
                } catch (_: Throwable) {
                }
                bar?.applyPalette()
            }
            if (s.locked) setBarVisible(false)
        }

        fun hide() {
            val v = view
            if (!attached || v == null) {
                attached = false
                view = null
                return
            }
            v.stop()
            main.removeCallbacks(autoHide)
            try {
                wm.removeView(root ?: v)
            } catch (_: Throwable) {
            }
            attached = false
            view = null
            root = null
            bar = null
        }

        @SuppressLint("ClickableViewAccessibility")
        private fun onTouch(event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downX = p.x
                    downY = p.y
                    dragging = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && dx * dx + dy * dy > 64) dragging = true
                    if (dragging && attached) {
                        p.x = downX + Math.round(dx)
                        p.y = downY + Math.round(dy)
                        try {
                            wm.updateViewLayout(root ?: view, p)
                        } catch (_: Throwable) {
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        prefs.edit().putInt("x", p.x).putInt("y", p.y).apply()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) {
                        // 点上一句/下一句 = 跳到那一句并唤出操作栏；
                        // 点当前行或空白 = 保持老行为：单击切换操作栏显隐（打开 App 由 ↗ 承担）
                        val target = view?.hitLineIndex(event.y) ?: -1
                        val line = if (target >= 0) frame.lines.getOrNull(target) else null
                        if (line != null) {
                            actions?.onSeek(line.timeMs)
                            setBarVisible(true)
                        } else {
                            setBarVisible(bar?.visibility != View.VISIBLE)
                        }
                    }
                    dragging = false
                    return true
                }
                else -> return false
            }
        }

        private fun setBarVisible(show: Boolean) {
            val b = bar ?: return
            main.removeCallbacks(autoHide)
            if (show && style.locked) return
            b.visibility = if (show) View.VISIBLE else View.GONE
            if (show) {
                b.applyPalette()
                b.refresh(frame)
                scheduleAutoHide()
            }
            // 高度变化后刷新窗口，防止操作栏被屏幕底部截断
            val p = params
            val r = root
            if (attached && p != null && r != null) {
                try { wm.updateViewLayout(r, p) } catch (_: Throwable) {}
            }
        }

        private fun scheduleAutoHide() {
            main.removeCallbacks(autoHide)
            main.postDelayed(autoHide, BAR_AUTO_HIDE_MS)
        }

        fun update() {
            if (!wanted) {
                hide()
                return
            }
            if (!attached) {
                if (canDraw(app)) show() else return
            }
            view?.bind()
            val b = bar
            if (b != null && b.visibility == View.VISIBLE) b.refresh(frame)
        }
    }

    private const val BAR_AUTO_HIDE_MS = 3500L

    // ---------------------------------------------------------------- 操作栏

    /** 单击歌词后在下方出现的操作栏：上一首/播放暂停/下一首/收藏/字号/锁定/打开应用/关闭。 */
    private class ControlBar(context: Context, private val onInteract: () -> Unit) :
        LinearLayout(context) {

        private val density = context.resources.displayMetrics.density
        private val bg = android.graphics.drawable.GradientDrawable()

        // buttons 必须先于 playBtn/favBtn 初始化：button() 会往 buttons.add，
        // Kotlin 属性按声明顺序初始化，放在后面会在构造 playBtn 时 NPE
        private val buttons = mutableListOf<ImageView>()
        private val playBtn = button(R.drawable.ic_play_arrow, "播放/暂停") { actions?.onPlayPause() }
        private val favBtn = button(R.drawable.ic_favorite_border, "收藏") { actions?.onToggleFavorite() }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER
            val pad = Math.round(6 * density)
            setPadding(pad, pad, pad, pad)
            bg.cornerRadius = 16f * density
            background = bg
            add(button(R.drawable.ic_skip_previous, "上一首") { actions?.onPrevious() })
            add(playBtn)
            add(button(R.drawable.ic_skip_next, "下一首") { actions?.onNext() })
            add(favBtn)
            add(button(R.drawable.ic_text_decrease, "字号减小") { actions?.onFontDelta(-1) })
            add(button(R.drawable.ic_text_increase, "字号增大") { actions?.onFontDelta(1) })
            add(button(R.drawable.ic_lock, "锁定") { actions?.onLock() })
            add(button(R.drawable.ic_open_in_new, "打开应用") { actions?.onOpenApp() })
            add(button(R.drawable.ic_close, "关闭") { actions?.onClose() })
            applyPalette()
        }

        private fun add(b: ImageView) {
            addView(b, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }

        private fun button(iconRes: Int, desc: String, onClick: () -> Unit): ImageView =
            ImageView(context).apply {
                setImageResource(iconRes)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = desc
                val v = Math.round(8 * density)
                setPadding(0, v, 0, v)
                setOnClickListener {
                    onInteract()
                    onClick()
                }
                buttons.add(this)
            }

        fun applyPalette() {
            val scheme = AppThemePalette.scheme
            val dark = style.dark
            val surface = scheme?.surfaceContainerHigh?.toArgb()
                ?: if (dark) 0xFF28282B.toInt() else 0xFFE9E7EC.toInt()
            val fg = scheme?.onSurface?.toArgb()
                ?: if (dark) 0xFFE6E0E9.toInt() else 0xFF1D1B20.toInt()
            bg.setColor(surface)
            bg.alpha = Math.round((style.bgOpacity.coerceAtLeast(0.7f)) * 255f)
            buttons.forEach { it.imageTintList = ColorStateList.valueOf(fg) }
        }

        fun refresh(f: Frame) {
            playBtn.setImageResource(if (f.playing) R.drawable.ic_pause else R.drawable.ic_play_arrow)
            favBtn.visibility = if (f.canFavorite) View.VISIBLE else View.GONE
            favBtn.setImageResource(
                if (f.favorite) R.drawable.ic_favorite else R.drawable.ic_favorite_border
            )
        }
    }

    // ---------------------------------------------------------------- 自绘 View

    /** 三行歌词 Canvas 绘制：播放中自驱动到 vsync，暂停且无动画时停。 */
    private class LyricView(context: Context) : View(context) {

        private companion object {
            const val SIDE_RATIO = 0.72f
            const val TRANSLATION_RATIO = 0.52f
            const val LINE_TRANSITION_NANOS = 380_000_000L
            const val SHADOW_OPACITY = 0.32f

            /** 译文显示方式：0 = 双语，1 = 只看原文，2 = 只看译文。 */
            const val TRANS_BOTH = 0
            const val TRANS_SOURCE_ONLY = 1
            const val TRANS_TRANSLATION_ONLY = 2
        }

        private val surfacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (Build.VERSION.SDK_INT >= 28) {
                typeface = Typeface.create(Typeface.DEFAULT, 500, false)
            }
        }
        private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
        }
        private val rect = RectF()
        private val density = context.resources.displayMetrics.density

        private var fontPx = 16
        private var bgOpacity = 0.63f
        private var dark = true
        private var textOutline = true
        private var lineSpacingPct = 210
        private var transGapDp = -3
        private var lineCount = 3
        private var transMode = TRANS_BOTH
        private var surface = 0xFF28282B.toInt()
        private var outlineColor = 0xFF49454F.toInt()
        private var cSung = 0xFFD0BCFF.toInt()
        private var cPrev = 0xFFCAC4D0.toInt()
        private var cNext = 0xFFCCC2DC.toInt()
        private val cShadow = Color.BLACK

        private var current: Visual? = null
        private var outgoing: Visual? = null
        private var transitionStart = 0L
        private var transitionDir = 1
        private var running = true
        private var lastLines: List<LyricLine>? = null

        fun configure(s: OverlayStyle) {
            fontPx = s.fontPx.coerceIn(12, 28)
            bgOpacity = s.bgOpacity.coerceIn(0f, 1f)
            dark = s.dark
            textOutline = s.outline
            lineSpacingPct = s.lineSpacingPct.coerceIn(160, 320)
            transGapDp = s.transGapDp.coerceIn(-8, 24)
            lineCount = if (s.lineCount <= 1) 1 else 3
            transMode = when (s.transMode) {
                TRANS_SOURCE_ONLY -> TRANS_SOURCE_ONLY
                TRANS_TRANSLATION_ONLY -> TRANS_TRANSLATION_ONLY
                else -> TRANS_BOTH
            }
            resolvePalette()
            current = null
            outgoing = null
            requestLayout()
            invalidate()
        }

        fun bind() {
            if (running) postInvalidateOnAnimation()
        }

        fun stop() {
            running = false
        }

        /**
         * 点击命中检测：点在**上一句/下一句**上返回那一句的索引，
         * 点在当前行或空白处返回 -1（沿用「单击切换操作栏」的老行为）。
         *
         * 单行模式只有当前句可见，永远返回 -1——否则用户点哪儿都跳走，没法唤出操作栏。
         */
        fun hitLineIndex(y: Float): Int {
            if (lineCount <= 1) return -1
            val f = frame
            if (f.lines.isEmpty() || !f.playing) return -1
            val idx = LrcParser.currentIndex(f.lines, livePosMs(f, System.nanoTime()))
            if (idx < 0) return -1
            val slot = slotSize()
            val delta = y - height / 2f
            return when {
                delta < -slot * 0.5f && idx > 0 -> idx - 1
                delta > slot * 0.5f && idx < f.lines.size - 1 -> idx + 1
                else -> -1
            }
        }

        /** 播放中按帧龄外推到当前绘制时刻的位置（≤500ms 兜底，防 tick 断流时漂移）。 */
        private fun livePosMs(f: Frame, nowNs: Long): Long {
            var pos = f.positionMs
            if (f.playing && f.receivedAtNs != 0L) {
                val drift = (nowNs - f.receivedAtNs) / 1_000_000L
                if (drift in 0..500L) pos += drift
            }
            return pos.coerceAtLeast(0L)
        }

        private fun resolvePalette() {
            // 跟随 app 当前主题色（对齐 qplayer 从运行时 scheme 取色）；未就绪时回退默认
            val scheme = AppThemePalette.scheme
            if (scheme != null) {
                surface = scheme.surfaceContainerHigh.toArgb()
                outlineColor = scheme.outlineVariant.toArgb()
                cSung = scheme.primary.toArgb()
                cPrev = scheme.onSurfaceVariant.toArgb()
                cNext = scheme.secondary.toArgb()
                return
            }
            surface = if (dark) 0xFF28282B.toInt() else 0xFFE9E7EC.toInt()
            outlineColor = if (dark) 0xFF49454F.toInt() else 0xFFCAC4D0.toInt()
            cSung = if (dark) 0xFFD0BCFF.toInt() else 0xFF6750A4.toInt()
            cPrev = if (dark) 0xFFCAC4D0.toInt() else 0xFF49454F.toInt()
            cNext = if (dark) 0xFFCCC2DC.toInt() else 0xFF625B71.toInt()
        }

        private fun mainSize(): Float =
            fontPx * resources.displayMetrics.scaledDensity * 1.15f

        private fun transSize(): Float =
            maxOf(12f * density, mainSize() * TRANSLATION_RATIO)

        /** 一行歌词占的高度（= 字号 × 行距倍数），也就是上下句的中心间距。 */
        private fun slotSize(): Float = mainSize() * lineSpacingPct / 100f

        /**
         * 主行 + 译文实际需要的最小高度。
         * 单行模式按「译文可能存在」算：否则换到带译文的句时高度会跳，看着很晃。
         */
        private fun minContentHeight(): Float =
            spacing(mainSize()) + spacing(transSize()) + transGapDp * density + 12f * density

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val slots = if (lineCount <= 1) 1 else 3
            var h = slotSize() * slots
            if (slots == 1) h = maxOf(h, minContentHeight())
            setMeasuredDimension(MeasureSpec.getSize(widthSpec), Math.round(h))
        }

        override fun onDetachedFromWindow() {
            super.onDetachedFromWindow()
            running = false
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val now = System.nanoTime()
            val f = frame

            rect.set(0f, 0f, w, h)
            val radius = 16f * density
            surfacePaint.color = surface
            surfacePaint.alpha = Math.round(bgOpacity * 255f)
            canvas.drawRoundRect(rect, radius, radius, surfacePaint)
            if (bgOpacity > 0.02f) {
                outlinePaint.color = outlineColor
                outlinePaint.alpha = Math.round(bgOpacity * 255f)
                rect.inset(0.5f * density, 0.5f * density)
                canvas.drawRoundRect(rect, radius, radius, outlinePaint)
            }

            val snapshot = readFrame(f, now)
            val cur = current
            if (cur == null || !cur.matches(snapshot)) {
                val replacement = Visual(snapshot, mainSize())
                if (cur != null) {
                    outgoing = cur
                    transitionDir = if (replacement.groupIndex in 0..Int.MAX_VALUE &&
                        cur.groupIndex >= 0 && replacement.groupIndex < cur.groupIndex
                    ) -1 else 1
                    transitionStart = now
                }
                current = replacement
            }
            current?.update(snapshot)

            val padX = 20f * density
            val left = padX
            val width = maxOf(1f, w - padX * 2f)
            val slot = slotSize()
            val save = canvas.save()
            canvas.clipRect(left, 0f, left + width, h)
            val out = outgoing
            if (out != null) {
                val raw = (now - transitionStart) / LINE_TRANSITION_NANOS.toFloat()
                if (raw >= 1f) {
                    outgoing = null
                    current?.let { drawVisual(canvas, it, left, width, h, 0f, 1f, false) }
                } else {
                    val inv = 1f - raw.coerceAtLeast(0f)
                    val eased = 1f - inv * inv * inv
                    drawVisual(canvas, out, left, width, h, transitionDir * -slot * eased, 1f - eased, true)
                    current?.let {
                        drawVisual(canvas, it, left, width, h, transitionDir * slot * (1f - eased), eased, false)
                    }
                }
            } else {
                current?.let { drawVisual(canvas, it, left, width, h, 0f, 1f, false) }
            }
            canvas.restoreToCount(save)

            if (running && (snapshot.playing || outgoing != null)) postInvalidateOnAnimation()
        }

        private fun readFrame(f: Frame, nowNs: Long): FrameSnapshot {
            val out = FrameSnapshot()
            out.playing = f.playing
            if (!f.playing) {
                out.fallback = "未在播放"
                return out
            }
            val title = f.title.trim()
            val artist = f.artist.trim()
            if (title.isNotEmpty()) {
                out.fallback = if (artist.isEmpty()) title else "$title  ·  $artist"
            }
            val lines = f.lines
            if (lines !== lastLines) {
                lastLines = lines
            }
            // 播放中按帧龄外推到当前绘制时刻（≤500ms 兜底防 tick 断流时漂移）
            val pos = livePosMs(f, nowNs)
            out.positionMs = pos
            if (lines.isNotEmpty()) {
                val idx = LrcParser.currentIndex(lines, pos)
                if (idx >= 0) {
                    val line = lines[idx]
                    out.groupIndex = idx
                    out.currentText = line.text
                    out.translationText = line.translation.orEmpty()
                    out.previousText = lines.getOrNull(idx - 1)?.text.orEmpty()
                    out.nextText = lines.getOrNull(idx + 1)?.text.orEmpty()
                    val nextStart = lines.getOrNull(idx + 1)?.timeMs
                    val span = if (nextStart != null && nextStart > line.timeMs) {
                        (nextStart - line.timeMs).toFloat()
                    } else {
                        3000f
                    }
                    out.progress = ((pos - line.timeMs) / span).coerceIn(0f, 1f)
                } else {
                    out.nextText = lines.firstOrNull()?.text.orEmpty()
                }
            }
            return out
        }

        private fun drawVisual(
            canvas: Canvas,
            v: Visual,
            left: Float,
            width: Float,
            height: Float,
            dy: Float,
            alpha: Float,
            complete: Boolean
        ) {
            if (alpha <= 0.001f) return
            // 行高与可见行数解耦：单行模式高度只够放下主行，但上下句仍按 slot 排在两侧（被裁掉）
            val slot = slotSize()
            val side = v.mainSize * SIDE_RATIO
            val trans = transSizeOf(v.mainSize)
            // 当前行永远居中：三行模式 = 高度/2 = 1.5×slot；单行模式 = 高度/2
            val base = height / 2f + dy

            drawStatic(canvas, v.previous, side, left, base - slot, width, 1f, cPrev, alpha)

            // 「只看译文」但这一句没有译文时，退回显示原文，避免中间空一格
            val hasTranslation = v.translation.isNotEmpty() && transMode != TRANS_SOURCE_ONLY
            val showMain = transMode != TRANS_TRANSLATION_ONLY || !hasTranslation
            var mainCenter = base
            var transCenter = base
            if (hasTranslation && showMain) {
                mainCenter -= (spacing(trans) + transGapDp * density) * 0.5f
                transCenter += (spacing(v.mainSize) + transGapDp * density) * 0.5f
            }
            if (showMain) drawCurrent(canvas, v, left, mainCenter, width, alpha, complete)
            if (hasTranslation) {
                drawStatic(canvas, v.translation, trans, left, transCenter, width, v.progress, cNext, alpha * 0.88f)
            }
            drawStatic(canvas, v.next, side, left, base + slot, width, 0f, cNext, alpha)
        }

        private fun transSizeOf(mainSize: Float): Float =
            maxOf(12f * density, mainSize * TRANSLATION_RATIO)

        private fun spacing(size: Float): Float {
            textPaint.textSize = size
            val fm = textPaint.fontMetrics
            return fm.descent - fm.ascent + fm.leading
        }

        private fun drawCurrent(
            canvas: Canvas,
            v: Visual,
            left: Float,
            centerY: Float,
            viewport: Float,
            alpha: Float,
            complete: Boolean
        ) {
            if (v.current.isEmpty()) return
            textPaint.textSize = v.mainSize
            val rowWidth = textPaint.measureText(v.current)
            val baseline = baseline(centerY)
            val x = textX(left, viewport, rowWidth, v.progress)

            drawShadowAndHalo(canvas, v.current, x, baseline, v.mainSize, cSung, alpha)
            val sweep = if (complete) rowWidth else rowWidth * v.progress
            if (sweep >= rowWidth - 0.01f) {
                drawText(canvas, v.current, x, baseline, cSung, alpha)
                return
            }
            drawText(canvas, v.current, x, baseline, cPrev, alpha)
            if (sweep <= 0.01f) return
            val save = canvas.save()
            canvas.clipRect(x, centerY - spacing(v.mainSize), x + sweep, centerY + spacing(v.mainSize))
            drawText(canvas, v.current, x, baseline, cSung, alpha)
            canvas.restoreToCount(save)
        }

        private fun drawStatic(
            canvas: Canvas,
            text: String,
            size: Float,
            left: Float,
            centerY: Float,
            viewport: Float,
            progress: Float,
            color: Int,
            alpha: Float
        ) {
            if (text.isEmpty()) return
            textPaint.textSize = size
            val textWidth = textPaint.measureText(text)
            val x = textX(left, viewport, textWidth, progress)
            val baseline = baseline(centerY)
            drawShadowAndHalo(canvas, text, x, baseline, size, color, alpha)
            drawText(canvas, text, x, baseline, color, alpha)
        }

        private fun drawShadowAndHalo(
            canvas: Canvas,
            text: String,
            x: Float,
            baseline: Float,
            size: Float,
            color: Int,
            alpha: Float
        ) {
            drawText(
                canvas, text,
                x + 0.75f * density, baseline + 1.25f * density,
                cShadow, alpha * SHADOW_OPACITY
            )
            if (textOutline) {
                haloPaint.textSize = size
                haloPaint.typeface = textPaint.typeface
                haloPaint.strokeWidth = maxOf(1.4f * density, size * 0.10f)
                haloPaint.color = if (luma(color) > 0.5f) Color.BLACK else Color.WHITE
                haloPaint.alpha = (clamp01(alpha) * 0.85f * 255f).toInt()
                canvas.drawText(text, x, baseline, haloPaint)
            }
        }

        private fun drawText(
            canvas: Canvas,
            text: String,
            x: Float,
            baseline: Float,
            color: Int,
            alpha: Float
        ) {
            textPaint.color = color
            textPaint.alpha = (clamp01(alpha) * (color ushr 24).coerceAtLeast(0)).toInt()
            canvas.drawText(text, x, baseline, textPaint)
        }

        private fun baseline(centerY: Float): Float {
            val fm = textPaint.fontMetrics
            return centerY - (fm.ascent + fm.descent) * 0.5f
        }

        private fun textX(left: Float, viewport: Float, textWidth: Float, progress: Float): Float {
            if (textWidth <= viewport) return left + (viewport - textWidth) * 0.5f
            return left - (textWidth - viewport) * clamp01(progress)
        }

        private fun luma(argb: Int): Float =
            0.2126f * Color.red(argb) / 255f +
                0.7152f * Color.green(argb) / 255f +
                0.0722f * Color.blue(argb) / 255f

        private fun clamp01(v: Float): Float = v.coerceIn(0f, 1f)
    }

    private class FrameSnapshot {
        var playing = false
        var fallback = ""
        var positionMs = 0L
        var groupIndex = -1
        var previousText = ""
        var currentText = ""
        var translationText = ""
        var nextText = ""
        var progress = 0f
    }

    private class Visual(
        snapshot: FrameSnapshot,
        val mainSize: Float
    ) {
        val groupIndex = snapshot.groupIndex
        val previous = snapshot.previousText
        val current = snapshot.currentText.ifEmpty { snapshot.fallback }
        val translation = snapshot.translationText
        val next = snapshot.nextText
        var progress = 0f

        fun matches(f: FrameSnapshot): Boolean {
            val cur = f.currentText.ifEmpty { f.fallback }
            return groupIndex == f.groupIndex &&
                previous == f.previousText &&
                current == cur &&
                translation == f.translationText &&
                next == f.nextText
        }

        fun update(f: FrameSnapshot) {
            progress = f.progress.coerceIn(0f, 1f)
        }
    }
}
