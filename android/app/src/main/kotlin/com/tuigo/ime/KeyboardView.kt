package com.tuigo.ime

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.Typeface
import android.graphics.LinearGradient
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.WindowInsets
import com.tuigo.KeyTheme
import com.tuigo.KeyboardThemes
import com.tuigo.Prefs
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Canvas-drawn software keyboard (framework KeyboardView is deprecated).
 * Gboard-style: key popup preview, shift one-shot / caps-lock on double tap,
 * backspace auto-repeat, letters/symbols pages.
 * Colors follow the Darkroom tokens.
 */
class KeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onChar(ch: Char)
        fun onDelete()
        fun onEnter()
        fun onShiftChanged(shifted: Boolean, capsLock: Boolean)
        fun onVietToggle() {}
        fun onSwitchIme() {}
        fun onSpaceMenu() {}
        /** Extend/reduce the hold-Backspace selection by `count` chars;
         *  dir<0 selects leftward, dir>0 rightward. */
        fun onSelectMove(dir: Int, count: Int = 1) {}
        /** Commit the current selection — delete whatever is highlighted. */
        fun onSelectDelete() {}
        fun onHideKeyboard() {}
        fun onCursorMove(delta: Int) {}
    }

    var listener: Listener? = null

    /** Keyboard skin; themes follow the Laban Key catalog (KeyboardThemes.kt). */
    var theme: KeyTheme = KeyboardThemes.current(context)
        set(v) {
            field = v
            applyTheme()
            invalidate()
        }

    /** VN mode state shown on the space bar. */
    var vietnameseOn: Boolean = true
        set(v) {
            if (field == v) return
            field = v
            rows = currentRows()
            relayout()
            invalidate()
        }

    /** True when the active input method is VNI — shows VNI tone/mark hints on the number row. */
    var vniMode: Boolean = false
        set(v) {
            if (field == v) return
            field = v
            rows = currentRows()
            relayout()
            invalidate()
        }

    var symbolsPage = false
        private set
    private var symbolLevel = 0

    /** Back to the letter page — called when the keyboard opens so a new
     *  field never starts on the symbols page. */
    fun resetPage() {
        if (!symbolsPage) return
        symbolsPage = false
        symbolLevel = 0
        rows = currentRows()
        relayout()
        measuredH = 0
        requestLayout()
    }

    /** Edge swipe down packs all keys to one side for one-handed typing.
     *  0 = disabled (feature off), 1 = waiting for gesture. */
    var oneHandedEnabled = false
        set(v) {
            field = v
            if (!v) oneHandedSide = 0
            syncToolbarSide()
            relayout()
            invalidate()
        }

    /** 0 = full width, -1 = packed left, +1 = packed right. */
    var oneHandedSide = 0
        private set

    /** Which half of the rows actually holds keys. The edge-swipe gesture must
     *  measure against the drawn span, not the view width: after an app switch
     *  the rows can be laid out for a narrower width, and a stale window width
     *  turned every touch into an edge swipe. With nothing drawn, the packed
     *  side is the one the next tap would land on. */
    private fun activeSide(x: Float): Int {
        if (keys.isEmpty()) return -1
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        for (k in keys) {
            if (k.spec.code == K.SPACER) continue
            left = minOf(left, k.rect.left)
            right = maxOf(right, k.rect.right)
        }
        if (left == Float.MAX_VALUE || right <= left) return 0
        return if (x < (left + right) / 2f) -1 else 1
    }

    /** Toolbar above the keyboard. The letter page gets one settings button
     *  (Gboard's tab / TuiGo's own settings); Android can't host an emoji
     *  picker, so no emoji key is offered — the iOS keyboard draws it on the
     *  symbol pages instead. */
    enum class ToolbarSide { LEFT, RIGHT }

    /** Where the single toolbar button sits; follows one-handed packing. */
    var toolbarSide = ToolbarSide.LEFT

    /** Gboard keeps its tab on the side the keys are packed to, so a one
     *  thumb can reach settings too. Mirrored, not moved, so the button stays
     *  on the outer edge. */
    private fun syncToolbarSide() {
        toolbarSide = if (oneHandedSide > 0) ToolbarSide.RIGHT else ToolbarSide.LEFT
    }

    var onSettings: (() -> Unit)? = null
    var settingsIcon: Bitmap? = null

    private data class ToolButton(
        val action: () -> Unit,
        val icon: Bitmap?,
        val glyph: String,
    )

    private val toolbarH = 44 * dp
    private val toolIconSize = 24 * dp
    private val toolEdgePad = 12 * dp
    /** Hit rects, rebuilt with the rows (empty while the view has no size). */
    private var toolButtons = emptyList<Pair<ToolButton, RectF>>()
    private var toolPressed: ToolButton? = null
    private var toolPressedAt = 0L
    private var toolIcon: Bitmap? = null
    private var toolIconSource: Bitmap? = null
    private val iconRect = RectF()

    /** Toolbar shows on the letter page only — the symbol pages reuse the
     *  four-row grid. */
    private fun toolbarVisible(): Boolean = !symbolsPage

    private fun buildToolButtons(): List<Pair<ToolButton, RectF>> {
        if (!toolbarVisible()) return emptyList()
        val touch = (toolIconSize + 16 * dp) / 2
        val cy = toolbarH / 2
        val cx = if (toolbarSide == ToolbarSide.LEFT) toolEdgePad + toolIconSize / 2
        else width - toolEdgePad - toolIconSize / 2
        return toolbarTools().map { tool -> tool to RectF(cx - touch, cy - touch, cx + touch, cy + touch) }
    }

    private fun toolbarTools(): List<ToolButton> =
        listOf(ToolButton({ onSettings?.invoke() }, settingsIcon, "⚙"))

    /** Split the letter rows into left/right halves in landscape. */
    var landscapeSplit = true
        set(v) {
            if (field == v) return
            field = v
            relayout()
            invalidate()
        }

    /** Hold-Backspace mode: 0 = repeat-delete, 1 = hold then drag to highlight,
     *  2 = hold auto-highlights leftward; releasing deletes the selection. */
    var delHoldMode = 0

    /** Swipe distance per selected char in drag-select mode — wired from
     *  Prefs.holdDelSensDp (smaller = more sensitive). */
    var delSelectSensDp = 10f
        set(v) {
            field = v.coerceIn(0.1f, 15f)
            delStepPx = field * dp
        }

    /** Vibrate on key press. */
    var hapticEnabled = true
        set(v) {
            field = v
            isHapticFeedbackEnabled = v
        }

    /** Show the key popup preview on press. */
    var popupEnabled = true


    /** Number row above letters — needed by VNI-style input methods
     *  (digits double as tone marks). */
    var showNumberRow = false
        set(v) {
            if (field == v) return
            field = v
            rows = currentRows()
            relayout()
            requestLayout()
        }

    /** Gboard-style 4x4 keypad for number/phone/date-time fields (PIN, OTP…). */
    enum class NumericLayout { NONE, NUMBER, PHONE, DATETIME }

    var numericLayout = NumericLayout.NONE
        set(v) {
            if (field == v) return
            field = v
            symbolsPage = false
            symbolLevel = 0
            rows = currentRows()
            relayout()
            requestLayout()
        }

    var shifted = false
        private set
    var capsLock = false
        private set
    private var manualShift = false

    fun resetManualShift() {
        manualShift = false
    }

    fun setAutoShifted(enabled: Boolean) {
        if (capsLock || manualShift || shifted == enabled) return
        shifted = enabled
        listener?.onShiftChanged(shifted, false)
        invalidate()
    }

    // ---- layout model ------------------------------------------------------

    private object K {
        const val DIGIT_DIAL = -12
        const val NUM_SPACE = -11
        const val MORE = -10
        const val IME = -9
        const val HIDE = -8
        const val SPACER = -7
        const val EMOJI = -6
        const val SPACE = -5
        const val ENTER = -4
        const val MODE = -3
        const val DEL = -2
        const val SHIFT = -1
    }

    private data class KeySpec(
        val code: Int,
        val label: String,
        val weight: Float = 1f,
        val hint: String = "",
        val longPressChar: Char? = hint.firstOrNull(),
        /** Draw [label] with the tiny function-key font instead of the letter
         *  font — `=\<` on the symbol page reads as a function key. */
        val smallLabel: Boolean = false,
    )

    private class Key(val spec: KeySpec, val rect: RectF = RectF())

    /** Top-row digit hints when the dedicated number row is hidden. */
    private val topRowNumberHints = mapOf(
        'q' to '1', 'w' to '2', 'e' to '3', 'r' to '4', 't' to '5',
        'y' to '6', 'u' to '7', 'i' to '8', 'o' to '9', 'p' to '0',
    )

    /** Standard Gboard / Laban Key secondary symbols on rows 2 and 3. */
    private val letterHints = mapOf(
        'a' to '@', 's' to '#', 'd' to '$', 'f' to '%', 'g' to '&',
        'h' to '-', 'j' to '+', 'k' to '(', 'l' to ')',
        'z' to '*', 'x' to '"', 'c' to '\'', 'v' to ':',
        'b' to ';', 'n' to '!', 'm' to '?',
    )

    /** Standard Laban Key VNI diacritic/tone hints on the number row (1..9). */
    private val vniNumberHints = mapOf(
        '1' to "´", '2' to "`", '3' to "?", '4' to "~", '5' to ".",
        '6' to "^", '7' to "ʼ", '8' to "˘", '9' to "Đ",
    )

    private fun row(s: String, insetWeight: Float = 0f, withLetterHints: Boolean = false): Array<KeySpec> {
        val keys = s.map { ch ->
            val hintChar = if (!withLetterHints) null
            else letterHints[ch] ?: if (!showNumberRow) topRowNumberHints[ch] else null
            KeySpec(
                code = ch.code,
                label = ch.toString(),
                hint = hintChar?.toString() ?: "",
                longPressChar = hintChar,
            )
        }
        return if (insetWeight > 0f) {
            (listOf(KeySpec(K.SPACER, "", insetWeight)) + keys +
                KeySpec(K.SPACER, "", insetWeight)).toTypedArray()
        } else keys.toTypedArray()
    }

    /** Bottom row of the letter page: the dial opens the symbol page and iOS
     *  leaves the slot between it and `?123` empty. */
    private val letterBottom: Array<KeySpec> = arrayOf(
        KeySpec(K.DIGIT_DIAL, "", 0.7f),
        KeySpec(K.MODE, "?123", 1.7f),
        KeySpec(K.SPACE, "Tiếng Việt", 7.2f),
        KeySpec(K.ENTER, "⏎", 1.5f),
    )

    /** Bottom row shared by the symbol pages. iOS puts the emoji key between
     *  Space and Return; a third-party IME cannot open the system emoji picker,
     *  so that slot opens the input-method picker instead and `12/34` switches
     *  to the numeric keypad. */
    private val symbolBottom: Array<KeySpec> = arrayOf(
        KeySpec(K.MODE, "ABC", 1.7f),
        KeySpec(','.code, ",", 1.1f),
        KeySpec(K.NUM_SPACE, "12\n34", 1.15f),
        KeySpec(K.SPACE, "Tiếng Việt", 5.0f),
        KeySpec(K.EMOJI, "", 1.1f),
        KeySpec(K.ENTER, "⏎", 1.5f),
    )

    /** Symbol page, first row: leading function key on the left, Backspace on
     *  the right, eight symbol keys between them. */
    private fun symbolTopRow(first: KeySpec, keys: String): Array<KeySpec> =
        arrayOf(first, *row(keys), KeySpec(K.DEL, "⌫", 1.5f))

    private fun buildLettersRows(): Array<Array<KeySpec>> = arrayOf(
        row("qwertyuiop", withLetterHints = true),
        row("asdfghjkl", insetWeight = 0.5f, withLetterHints = true),
        arrayOf(
            KeySpec(K.SHIFT, "⇧", 1.5f),
            *row("zxcvbnm", withLetterHints = true),
            KeySpec(K.DEL, "⌫", 1.5f),
        ),
        letterBottom,
    )

    private val symbolsRows: Array<Array<KeySpec>> by lazy {
        arrayOf(
            row("1234567890"),
            row("@#₫_&-+()/"),
            symbolTopRow(KeySpec(K.MORE, "=\\<", 1.5f, smallLabel = true), "*\"':;!?"),
            symbolBottom,
        )
    }

    /** Second symbols page (`=\<`). Rows 1–2 carry the iOS page that follows
     *  the first `=\<` tap; rows 3–4 reuse the first page's keys. Characters
     *  commit literally — the engine passes non-letters through. */
    private val extraSymbolRows: Array<Array<KeySpec>> by lazy {
        arrayOf(
            row("~`|•√π÷×§Δ"),
            row("£€$¢^°={}\\"),
            symbolTopRow(KeySpec(K.MODE, "?123", 1.5f), "%©®™✓[]"),
            symbolBottom,
        )
    }

    private fun buildNumberRow(): Array<KeySpec> =
        "1234567890".map { ch ->
            val vniHint = if (vniMode && vietnameseOn) vniNumberHints[ch] ?: "" else ""
            KeySpec(code = ch.code, label = ch.toString(), hint = vniHint, longPressChar = null)
        }.toTypedArray()

    private fun numPadRows(layout: NumericLayout): Array<Array<KeySpec>> {
        val phone = layout == NumericLayout.PHONE
        val bottom = when (layout) {
            NumericLayout.PHONE -> "*0#"
            NumericLayout.DATETIME -> "/0:"
            else -> ",0."
        }
        return arrayOf(
            row("1234567890"),
            arrayOf(*row("123"), if (phone) KeySpec('+'.code, "+") else KeySpec('-'.code, "-")),
            arrayOf(*row("456"), if (phone) KeySpec('-'.code, "-") else KeySpec(K.NUM_SPACE, "␣")),
            arrayOf(*row("789"), KeySpec(K.DEL, "⌫")),
            arrayOf(*row(bottom), KeySpec(K.ENTER, "✓")),
        )
    }

    private fun currentRows(): Array<Array<KeySpec>> =
        when {
            numericLayout != NumericLayout.NONE -> numPadRows(numericLayout)
            symbolsPage -> arrayOf(*(if (symbolLevel == 1) extraSymbolRows else symbolsRows), symbolBottom)
            showNumberRow -> arrayOf(buildNumberRow(), *buildLettersRows())
            else -> buildLettersRows()
        }

    private val keys = mutableListOf<Key>()
    private var rows: Array<Array<KeySpec>> = buildLettersRows()

    /** Split each letter row into left/right halves so landscape thumbs reach
     *  every key — same idea as the iPad/Laban Key landscape layout. The
     *  bottom row (mostly functional keys) stays one continuous row. */
    private fun computeVisualRows(): List<List<Array<KeySpec>>> {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (!landscape || !landscapeSplit || numericLayout != NumericLayout.NONE) {
            return rows.map { listOf(it) }
        }
        val out = ArrayList<List<Array<KeySpec>>>(rows.size)
        for (rowSpec in rows) {
            val spaceIdx = rowSpec.indexOfFirst { it.code == K.SPACE }
            if (spaceIdx >= 0) {
                // Bottom row: give each thumb its own Space half, like the
                // Laban Key landscape layout.
                val space = rowSpec[spaceIdx]
                val halfSpace = space.copy(weight = space.weight * 0.48f)
                out += listOf(
                    arrayOf(*rowSpec.sliceArray(0 until spaceIdx), halfSpace),
                    arrayOf(halfSpace, *rowSpec.sliceArray(spaceIdx + 1 until rowSpec.size)),
                )
                continue
            }
            var letters = 0
            for (spec in rowSpec) if (spec.code >= 0) letters++
            if (letters == 0) {
                out += listOf(rowSpec)
                continue
            }
            var seen = 0
            var splitAt = -1
            for (i in rowSpec.indices) {
                if (rowSpec[i].code >= 0) {
                    seen++
                    if (seen == (letters + 1) / 2) { splitAt = i + 1; break }
                }
            }
            if (splitAt <= 0 || splitAt >= rowSpec.size) {
                out += listOf(rowSpec)
                continue
            }
            out += listOf(
                rowSpec.sliceArray(0 until splitAt),
                rowSpec.sliceArray(splitAt until rowSpec.size),
            )
        }
        return out
    }

    // ---- metrics ------------------------------------------------------------

        /** True once [relayout] has drawn at least one key — until then touches
         *  have nothing to hit and are ignored rather than landing on a leftover
         *  rect from the previous layout. */
        private val laidOut: Boolean get() = keys.isNotEmpty()

    private val dp = resources.displayMetrics.density
    private var keyHeight = 56 * dp
    private val rowGap = 6 * dp

    /** Height the view is currently measured to (see [onMeasure]); the rows
     *  are squeezed to fill it instead of keeping their natural height. */
    private var measuredH = 0
    /** Rows were rebuilt before the view had a size; the next measure pass
     *  lays them out again. */
    private var rowsNeedLayout = false
    private var keyGap = 4 * dp
    /** Horizontal gap between the left/right halves in landscape split mode. */
    private var splitGap = 40 * dp
    private val padH = 3 * dp
    private val padTop = 8 * dp
    private val padBottom = 8 * dp
    private var corner = 10 * dp

    // ---- paint --------------------------------------------------------------

    /** Reserve any inset dispatched by the framework; never put controls in it.
     * Android owns the navigation bar and its IME hide/switch buttons. */
    private var navInset = 0

    private val bgPaint = Paint().apply { color = theme.bg }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = theme.border
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.text
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val popupTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = theme.popupText
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = 0x40FFFFFF.toInt()
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL; color = 0x1A000000.toInt()
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowRect = RectF()
    private val popupRect = RectF()
    private val dialRect = RectF()

    private var pressed: Key? = null
    /** One-handed edge swipe: -1 started on left edge, +1 right, 0 none. */
    private var edgeSwipeSide = 0
    private var edgeSwipeY = 0f
    private var edgeSwipeX = 0f
    private val edgeZonePx = 28 * dp
    private val edgeSwipeTrigger = 36 * dp
    /** Last touched key kept briefly after release so the popup lingers. */
    private var popupKey: Key? = null
    private var popupHoldUntil = 0L
    private val popupHoldMs = 120L

    /** Root container of the whole input view (suggestion strip + keyboard).
     *  When set, a top-row popup that would overflow the view's top edge is
     *  drawn into this parent's overlay so it floats over the strip. */
    var overlayHost: View? = null
    private var overlayDrawable: PopupDrawable? = null

    /** Popup drawn in the input-view container's overlay — free to paint over
     *  the suggestion strip above the keyboard. */
    private inner class PopupDrawable : android.graphics.drawable.Drawable() {
        var label = ""
        override fun draw(canvas: Canvas) {
            val r = RectF(bounds)
            keyPaint.color = theme.popup
            canvas.drawRoundRect(r, corner, corner, keyPaint)
            if (theme.popupBorder ushr 24 != 0) {
                borderPaint.color = theme.popupBorder
                canvas.drawRoundRect(r, corner, corner, borderPaint)
            }
            popupTextPaint.textSize = 20 * dp
            val ty = r.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2
            canvas.drawText(label, r.centerX(), ty, popupTextPaint)
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
    }

    /** True while the suggestion strip occupies the row above the keys —
     *  set by the service whenever it shows/hides the bar. */
    var suggestionStripVisible: () -> Boolean = { true }

    private fun showOverlayPopup(key: Key, py: Float, pw: Float, ph: Float) {
        val host = overlayHost ?: return
        val d = overlayDrawable ?: PopupDrawable().also { overlayDrawable = it }
        d.label = currentLabel(key.spec)
        val r = key.rect
        val px = (r.centerX() - pw / 2).coerceIn(padH, width - padH - pw)
        // Keyboard coords → host coords.
        val l = (left + px).roundToInt()
        val t = (top + py).roundToInt()
        d.setBounds(l, t, l + pw.roundToInt(), t + ph.roundToInt())
        host.overlay.remove(d)
        host.overlay.add(d)
    }

    private fun hideOverlayPopup() {
        val d = overlayDrawable ?: return
        overlayHost?.overlay?.remove(d)
        overlayDrawable = null
    }
    private var shiftTapTime = 0L
    private var downPointerId = -1
    private var downX = 0f
    private var downY = 0f
    private var spaceLongFired = false
    private var spaceSliding = false
    private var spaceDownX = 0f
    private var spaceDownY = 0f
    private var spaceLastStepX = 0f
    private val spaceSwipeSlop = 10 * dp
    private val spaceStepPx = 10 * dp
    private val keyHysteresis = 8 * dp
    private val tapLockSlop = 10 * dp

    /** True after a long-press already produced output — further moves in
     *  the same gesture must not re-arm the touched key (finger jitter used
     *  to type "4r" instead of just the hint char). */
    private var pressConsumed = false

    private val handler = Handler(Looper.getMainLooper())
    private val repeatDelete = object : Runnable {
        override fun run() {
            listener?.onDelete()
            handler.postDelayed(this, 50)
        }
    }

    /** After the long-press delay in hold-select mode the finger becomes a
     *  selection handle: dragging left/right extends the highlight, releasing
     *  deletes the selected text. */
    private var delSelecting = false
    private var delSelectDir = 0
    private var delSelectDownX = 0f
    private var delSelectLastX = 0f
    private val delSwipeSlop = 10 * dp
    private var delStepPx = 10 * dp

    private val delLongPress = Runnable {
        if (delHoldMode == 0 || pressed?.spec?.code != K.DEL) return@Runnable
        delSelecting = true
        delSelectDir = 0
        handler.removeCallbacks(repeatDelete)
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        if (delHoldMode == 2) handler.post(delAutoSelect)
        invalidate()
    }

    /** Mode 2: while held, steadily extend the highlight leftward. */
    private val delAutoSelect = object : Runnable {
        override fun run() {
            if (!delSelecting || pressed?.spec?.code != K.DEL) return
            listener?.onSelectMove(-1)
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            handler.postDelayed(this, 60)
        }
    }

    /** Long-press a hinted key types the hint char directly (Unikey-style). */
    private val hintLongPress = Runnable {
        val k = pressed ?: return@Runnable
        val alt = k.spec.longPressChar ?: return@Runnable
        pressed = null
        pressConsumed = true
        manualShift = false
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onChar(alt)
        invalidate()
    }

    /** Long-press Space = language/input-method menu (Gboard-style). */
    private val spaceLongPress = Runnable {
        if (spaceSliding) return@Runnable
        spaceLongFired = true
        pressed = null
        pressConsumed = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        listener?.onSpaceMenu()
        invalidate()
    }

    init {
        isHapticFeedbackEnabled = true
        setOnApplyWindowInsetsListener { _, insets ->
            // Only the mandatory gesture inset (gesture pill) pads the keys —
            // the IME window already sits above the nav bar, so adding the
            // full navigationBars() inset pushed the layout taller than the
            // window and clipped the bottom row on some apps.
            val b = when {
                Build.VERSION.SDK_INT >= 30 ->
                    insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
                Build.VERSION.SDK_INT == 29 ->
                    @Suppress("DEPRECATION") insets.mandatorySystemGestureInsets.bottom
                else -> 0
            }
            if (b != navInset) {
                navInset = b
                requestLayout()
            }
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestApplyInsets()
    }

    /** Key metrics in dp — from the settings screen. `landHeightDp` lets the
     *  user pick a separate key height for landscape; when it is unset the
     *  landscape default of 36dp applies. `landSplitGapDp` is the space
     *  between the two halves in landscape split mode. */
    fun setMetrics(heightDp: Int, gapDp: Int, landHeightDp: Int = -1, landSplitGapDp: Int = 40) {
        val h = (if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE)
            (if (landHeightDp > 0) landHeightDp else 36).coerceIn(32, 56)
            else heightDp.coerceIn(44, 68)) * dp
        val g = gapDp.coerceIn(0, 8) * dp
        val s = landSplitGapDp.coerceIn(8, 200) * dp
        if (h == keyHeight && g == keyGap && s == splitGap) return
        keyHeight = h
        keyGap = g
        splitGap = s
        relayout()
        requestLayout()
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        val t = KeyboardThemes.current(context)
        if (t != theme) {
            theme = t
        } else {
            applyTheme()
        }
        setMetrics(Prefs.keyHeightDp(context), Prefs.keyGapDp(context), Prefs.landKeyHeightDp(context), Prefs.landSplitGapDp(context))
    }

    private fun applyTheme() {
        corner = theme.cornerDp * dp
        bgPaint.color = theme.bg
        bgPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f), theme.bg, theme.bgEnd, Shader.TileMode.CLAMP)
        borderPaint.color = theme.border
        textPaint.color = theme.text
        popupTextPaint.color = theme.popupText
        invalidate()
    }

    // ---- sizing -------------------------------------------------------------

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        if (rowsNeedLayout) {
            rowsNeedLayout = false
            requestLayout()
        }
        val n = rows.size
        val toolbar = if (toolbarVisible()) toolbarH else 0f
        val h = (toolbar + padTop + maxOf(padBottom, navInset.toFloat()) +
            n * keyHeight + (n - 1) * rowGap).roundToInt()
        measuredH = h
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        relayout()
    }


    private fun relayout() {
        keys.clear()
        val visualRows = computeVisualRows()
        if (width <= 0) {
            rowsNeedLayout = true
            return
        }
        rowsNeedLayout = false
        toolButtons = buildToolButtons()
        // One-handed mode packs keys into 78% of the width on the chosen side.
        val contentW = (width - 2 * padH) * (if (oneHandedSide != 0) 0.78f else 1f)
        val contentX = padH + when {
            oneHandedSide > 0 -> (width - 2 * padH) - contentW
            else -> 0f
        }
        // Rows are laid out to the measured height, which can differ from the
        // natural row height: a host app re-measuring the IME window, a
        // configuration switch (app hop, split screen, fullscreen extract
        // editor) or a late inset re-measure all leave keys taller than the
        // view — the bottom rows then sit off-screen. Squeeze the padding,
        // then the gaps, then the keys so every row stays inside the view and
        // hit-testing matches what is drawn.
        val n = visualRows.size
        val toolbarUsed = if (toolbarVisible()) toolbarH else 0f
        var padTopUsed = padTop
        var padBottomUsed = maxOf(padBottom, navInset.toFloat())
        var gapUsed = rowGap
        var kh = keyHeight
        if (n > 0) {
            val avail = when {
                measuredH > 0 && height > 0 -> minOf(height, measuredH).toFloat()
                height > 0 -> height.toFloat()
                else -> Float.MAX_VALUE
            }
            val natural = toolbarUsed + padTopUsed + padBottomUsed + n * kh + (n - 1) * gapUsed
            if (natural > avail + 0.5f) {
                val body = (avail - toolbarUsed).coerceAtLeast(avail * 0.5f)
                var header = (padTopUsed + padBottomUsed).coerceAtMost(avail * 0.2f)
                if (header > body) header = body
                val gaps = (n - 1) * gapUsed
                val scale = ((body - header) / (n * kh + gaps)).coerceAtLeast(0.1f)
                kh *= scale
                gapUsed *= scale
                val padding = padTopUsed + padBottomUsed
                padTopUsed = if (padding > 0f) padTop * (header / padding) else 0f
                padBottomUsed = header - padTopUsed
            }
        }
        var y = toolbarUsed + padTopUsed
        for (halves in visualRows) {
            val halfW = if (halves.size > 1) (contentW - splitGap) / 2f else contentW
            for (half in halves.indices) {
                val rowSpec = halves[half]
                var x = contentX + half * (halfW + splitGap)
                val totalWeight = rowSpec.sumOf { it.weight.toDouble() }.toFloat()
                val unitW = ((halfW - keyGap * (rowSpec.size - 1)) / totalWeight).coerceAtLeast(0f)
                for (spec in rowSpec) {
                    val kw = unitW * spec.weight
                    keys += Key(spec, RectF(x, y, x + kw, y + kh))
                    x += kw + keyGap
                }
            }
            y += kh + gapUsed
        }
        bgPaint.shader = LinearGradient(0f, 0f, 0f, height.toFloat().coerceAtLeast(1f), theme.bg, theme.bgEnd, Shader.TileMode.CLAMP)
        invalidate()
    }

    // ---- drawing ------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        drawToolbar(canvas)
        for (key in keys) drawKey(canvas, key)
        drawPreview(canvas)
    }

    private var toolIcon: Bitmap? = null
    private var toolIconSource: Bitmap? = null
    private val iconRect = RectF()

    /** Toolbar glyphs are drawn, not text keys: no key cap, no press fill. */
    private fun drawToolbar(canvas: Canvas) {
        if (toolButtons.isEmpty()) return
        if (settingsIcon !== toolIconSource) {
            toolIconSource = settingsIcon
            val px = toolIconSize.roundToInt().coerceAtLeast(1)
            toolIcon = settingsIcon?.let { Bitmap.createScaledBitmap(it, px, px, true) }
        }
        for ((tool, r) in toolButtons) {
            val cx = r.centerX()
            val cy = r.centerY()
            val icon = toolIcon
            if (icon != null) {
                val half = icon.width / 2f
                iconRect.set(cx - half, cy - half, cx + half, cy + half)
                canvas.drawBitmap(icon, null, iconRect, keyPaint)
            } else {
                textPaint.color = theme.funcText
                textPaint.textSize = 20 * dp
                textPaint.alpha = 0xDD
                drawCentered(canvas, tool.glyph, r)
                textPaint.alpha = 0xFF
            }
        }
    }

    private fun drawKey(canvas: Canvas, key: Key) {
        val spec = key.spec
        if (spec.code == K.SPACER) return
        val r = key.rect
        val isPressed = pressed === key
        val t = theme

        fun cap(fill: Int, stroke: Int = t.border, keyCorner: Float = corner) {
            shadowRect.set(r.left, r.top + 1.5f * dp, r.right, r.bottom + 2.0f * dp)
            shadowPaint.color = if (t.bg ushr 24 != 0 && (t.bg and 0xFF) > 128) 0x1F000000.toInt() else 0x3D000000.toInt()
            canvas.drawRoundRect(shadowRect, keyCorner, keyCorner, shadowPaint)

            keyPaint.color = fill
            canvas.drawRoundRect(r, keyCorner, keyCorner, keyPaint)
            if (stroke ushr 24 != 0) {
                borderPaint.color = stroke
                canvas.drawRoundRect(r, keyCorner, keyCorner, borderPaint)
            }
            val sheenAlpha = if ((t.key and 0xFF) > 128) 0x55FFFFFF.toInt() else 0x1AFFFFFF.toInt()
            sheenPaint.color = sheenAlpha
            canvas.drawLine(r.left + keyCorner * 0.8f, r.top + 1f, r.right - keyCorner * 0.8f, r.top + 1f, sheenPaint)
            if (isPressed) {
                val bloomColor = (0x35 shl 24) or (t.accent and 0x00FFFFFF)
                borderPaint.color = bloomColor
                borderPaint.strokeWidth = 2f * dp
                canvas.drawRoundRect(r, keyCorner, keyCorner, borderPaint)
                borderPaint.strokeWidth = 1f
            }
        }
        fun pressedGlyph(normal: Int) = if (t.pressAccent) t.onAccent else normal

        when (spec.code) {
            K.SHIFT -> {
                val active = capsLock || shifted
                cap(when {
                    isPressed -> if (t.pressAccent) t.accent else t.funcPressed
                    active -> if (t.blueGlyphs) t.space else t.accent
                    else -> t.func
                })
                iconPaint.color = when {
                    isPressed -> pressedGlyph(t.funcText)
                    active -> if (t.blueGlyphs) t.accent else t.onAccent
                    else -> t.funcText
                }
                drawShiftIcon(canvas, r, active, capsLock)
            }
            K.ENTER -> {
                val enterCorner = if (t.enterAccentFill) r.height() / 2f else corner
                cap(when {
                    isPressed -> if (t.pressAccent) t.accent else t.funcPressed
                    t.enterAccentFill -> t.accent
                    else -> t.func
                }, t.border, enterCorner)
                iconPaint.color = when {
                    isPressed -> pressedGlyph(t.funcText)
                    t.enterAccentFill -> t.onAccent
                    t.blueGlyphs -> t.accent
                    else -> t.funcText
                }
                if (numericLayout != NumericLayout.NONE) drawCheckIcon(canvas, r)
                else drawEnterIcon(canvas, r)
            }
            K.SPACE -> {
                val spaceCorner = r.height() / 2f
                cap(if (isPressed && !t.pressAccent) t.funcPressed else t.space, t.spaceBorder, spaceCorner)
                textPaint.color = if (isPressed) pressedGlyph(t.spaceText) else t.spaceText
                textPaint.textSize = 13 * dp
                val baseLabel = if (vietnameseOn) "Tiếng Việt" else "English"
                val spaceLabel = if (spaceSliding) "◀   $baseLabel   ▶" else baseLabel
                if (symbolsPage) {
                    // Symbol pages carry a narrow Space — keep the label small
                    // enough to stay inside the cap.
                    textPaint.textSize = 11 * dp
                    drawCentered(canvas, if (spaceSliding) "◀ $baseLabel ▶" else baseLabel, r)
                } else {
                    drawCentered(canvas, spaceLabel, r)
                }
            }
            K.DEL -> {
                cap(if (isPressed) (if (t.pressAccent) t.accent else t.funcPressed) else t.func)
                iconPaint.color = if (isPressed) pressedGlyph(t.funcText) else t.funcText
                drawDeleteIcon(canvas, r)
            }
            K.MODE, K.MORE -> {
                cap(if (isPressed) (if (t.pressAccent) t.accent else t.funcPressed) else t.func)
                textPaint.color = if (isPressed) pressedGlyph(t.funcText) else t.funcText
                textPaint.textSize = 14 * dp
                drawCentered(canvas, spec.label, r)
            }
            K.NUM_SPACE -> {
                cap(if (isPressed) (if (t.pressAccent) t.accent else t.funcPressed) else t.func)
                textPaint.color = if (isPressed) pressedGlyph(t.funcText) else t.funcText
                textPaint.textSize = 12 * dp
                // Two-line "12/34", stacked like iOS's numeric key.
                val lines = spec.label.split('\n')
                val step = textPaint.textSize + 1 * dp
                val first = r.centerY() - step / 2 - (textPaint.descent() + textPaint.ascent()) / 2
                lines.forEachIndexed { i, line ->
                    canvas.drawText(line, r.centerX(), first + i * step, textPaint)
                }
            }
            K.DIGIT_DIAL -> {
                // Circular key with an iOS-style dial of tiny squares.
                val d = minOf(r.height(), 56 * dp)
                dialRect.set(r.centerX() - d / 2, r.centerY() - d / 2, r.centerX() + d / 2, r.centerY() + d / 2)
                keyPaint.color = if (isPressed && !t.pressAccent) t.funcPressed else t.func
                canvas.drawRoundRect(dialRect, d / 2, d / 2, keyPaint)
                dotPaint.color = t.funcText
                val cell = 1.7f * dp
                val step = 4.4f * dp
                for (dy in -1..1) for (dx in -1..1) {
                    val x = r.centerX() + dx * step
                    val y = r.centerY() + dy * step
                    canvas.drawRoundRect(x - cell / 2, y - cell / 2, x + cell / 2, y + cell / 2, 0.6f * dp, 0.6f * dp, dotPaint)
                }
            }
            K.EMOJI -> {
                cap(if (isPressed) (if (t.pressAccent) t.accent else t.funcPressed) else t.func)
                textPaint.color = if (isPressed) pressedGlyph(t.funcText) else t.funcText
                textPaint.textSize = 19 * dp
                drawCentered(canvas, "🌐", r)
            }
            else -> {
                cap(if (isPressed) (if (t.pressAccent) t.accent else t.keyPressed) else t.key)
                textPaint.color = if (isPressed) pressedGlyph(t.text) else t.text
                textPaint.textSize = if (spec.smallLabel) 14 * dp else 21 * dp
                drawCentered(canvas, currentLabel(spec), r)
                if (spec.hint.isNotEmpty()) {
                    textPaint.textSize = 10.5f * dp
                    textPaint.color = if (isPressed) pressedGlyph(t.text) else t.funcText
                    textPaint.alpha = 0xAA
                    val hx = r.right - 7 * dp - textPaint.measureText(spec.hint) / 2
                    canvas.drawText(spec.hint, hx, r.top + 13 * dp, textPaint)
                    textPaint.alpha = 0xFF
                }
            }
        }
    }

    private fun drawCentered(canvas: Canvas, label: String, r: RectF) {
        if (label.isEmpty()) return
        val y = r.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
        canvas.drawText(label, r.centerX(), y, textPaint)
    }

    private val iconPath = android.graphics.Path()

    /** Gboard-style arrow: outline when off, filled when shifted, filled +
     *  underline when caps-lock. */
    private fun drawShiftIcon(canvas: Canvas, r: RectF, active: Boolean, caps: Boolean) {
        val cx = r.centerX(); val cy = r.centerY(); val s = 6.5f * dp
        iconPath.reset()
        iconPath.moveTo(cx, cy - s)
        iconPath.lineTo(cx + s, cy - 0.05f * s)
        iconPath.lineTo(cx + 0.42f * s, cy - 0.05f * s)
        iconPath.lineTo(cx + 0.42f * s, cy + 0.95f * s)
        iconPath.lineTo(cx - 0.42f * s, cy + 0.95f * s)
        iconPath.lineTo(cx - 0.42f * s, cy - 0.05f * s)
        iconPath.lineTo(cx - s, cy - 0.05f * s)
        iconPath.close()
        iconPaint.style = if (active) Paint.Style.FILL else Paint.Style.STROKE
        iconPaint.strokeWidth = 1.7f * dp
        canvas.drawPath(iconPath, iconPaint)
        if (caps) {
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 1.6f * dp
            canvas.drawLine(cx - 0.42f * s, cy + 1.35f * s, cx + 0.42f * s, cy + 1.35f * s, iconPaint)
        }
        iconPaint.style = Paint.Style.STROKE
    }

    /** Return-arrow icon (letter page). */
    private fun drawEnterIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val s = 6.5f * dp
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 1.8f * dp
        iconPath.reset()
        iconPath.moveTo(cx + 0.65f * s, cy - 1.0f * s)
        iconPath.lineTo(cx + 0.65f * s, cy + 0.3f * s)
        iconPath.lineTo(cx - 0.8f * s, cy + 0.3f * s)
        canvas.drawPath(iconPath, iconPaint)
        iconPath.reset()
        iconPath.moveTo(cx - 0.25f * s, cy - 0.35f * s)
        iconPath.lineTo(cx - 0.8f * s, cy + 0.3f * s)
        iconPath.lineTo(cx - 0.25f * s, cy + 0.95f * s)
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Check icon (numeric keypad "done" key). */
    private fun drawCheckIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val s = 6f * dp
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 2f * dp
        iconPath.reset()
        iconPath.moveTo(cx - 0.9f * s, cy + 0.1f * s)
        iconPath.lineTo(cx - 0.25f * s, cy + 0.75f * s)
        iconPath.lineTo(cx + s, cy - 0.6f * s)
        canvas.drawPath(iconPath, iconPaint)
    }

    /** Backspace icon: pentagon with an x inside. */
    private fun drawDeleteIcon(canvas: Canvas, r: RectF) {
        val cx = r.centerX(); val cy = r.centerY(); val s = 7.5f * dp
        iconPaint.style = Paint.Style.STROKE
        iconPaint.strokeWidth = 1.6f * dp
        iconPath.reset()
        iconPath.moveTo(cx - s, cy)
        iconPath.lineTo(cx - 0.15f * s, cy - 0.72f * s)
        iconPath.lineTo(cx + 0.85f * s, cy - 0.72f * s)
        iconPath.quadTo(cx + s, cy - 0.72f * s, cx + s, cy - 0.5f * s)
        iconPath.lineTo(cx + s, cy + 0.5f * s)
        iconPath.quadTo(cx + s, cy + 0.72f * s, cx + 0.85f * s, cy + 0.72f * s)
        iconPath.lineTo(cx - 0.15f * s, cy + 0.72f * s)
        iconPath.close()
        canvas.drawPath(iconPath, iconPaint)
        val x = 0.22f * s
        canvas.drawLine(cx - x, cy - x, cx + 0.5f * s, cy + x, iconPaint)
        canvas.drawLine(cx + 0.5f * s, cy - x, cx - x, cy + x, iconPaint)
    }

    private fun drawPreview(canvas: Canvas) {
        if (!popupEnabled) { hideOverlayPopup(); return }
        val key = pressed ?: popupKey.takeIf {
            SystemClock.uptimeMillis() < popupHoldUntil
        } ?: run { hideOverlayPopup(); return }
        if (key.spec.code <= 0) { hideOverlayPopup(); return } // only char keys get a popup
        val r = key.rect
        val pw = r.width() * 1.0f
        val ph = keyHeight * 0.9f
        // Tight rise above the pressed key — just clears the fingertip.
        val lift = rowGap * 0.5f
        var py = r.top - ph - lift
        if (py < 0) {
            if (suggestionStripVisible()) {
                // Overflows the top edge — paint it into the parent overlay
                // so it floats over the suggestion strip.
                showOverlayPopup(key, py, pw, ph)
                return
            }
            // Strip hidden: nothing above the keyboard — slide the popup back
            // down over the key instead of wasting the space.
            py = r.top
        }
        hideOverlayPopup()
        val px = (r.centerX() - pw / 2).coerceIn(padH, width - padH - pw)
        popupRect.set(px, py, px + pw, py + ph)
        keyPaint.color = theme.popup
        canvas.drawRoundRect(popupRect, corner, corner, keyPaint)
        if (theme.popupBorder ushr 24 != 0) {
            borderPaint.color = theme.popupBorder
            canvas.drawRoundRect(popupRect, corner, corner, borderPaint)
        }
        popupTextPaint.textSize = 20 * dp
        val y = popupRect.centerY() - (popupTextPaint.descent() + popupTextPaint.ascent()) / 2
        canvas.drawText(currentLabel(key.spec), popupRect.centerX(), y, popupTextPaint)
    }

    // ---- touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downPointerId = ev.getPointerId(0)
                edgeSwipeSide = edgeSideOf(ev.x)
                edgeSwipeX = ev.x
                edgeSwipeY = ev.y
                // Touches that arrive before the rows exist (first show, or a
                // rebuild mid-gesture) have nothing valid to hit — drop them
                // instead of pressing a stale rect.
                if (!laidOut) return true
                // The toolbar is above the keys, so it gets first claim; its
                // hit boxes never overlap a key rect.
                val tool = toolButtons.firstOrNull { it.second.contains(ev.x, ev.y) }?.first
                if (tool != null) {
                    toolPressed = tool
                    toolPressedAt = SystemClock.uptimeMillis()
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                    invalidate()
                } else {
                    onDown(ev.x, ev.y)
                }
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = ev.actionIndex
                // Multi-touch rollover: when a second thumb lands before the first
                // thumb lifts, commit the currently pressed key in order immediately.
                val prev = pressed
                if (!pressConsumed && !spaceSliding && prev != null &&
                    prev.spec.code != K.DEL && prev.spec.code != K.SPACER
                ) {
                    cancelPress()
                    dispatch(prev)
                } else {
                    cancelPress()
                }
                downPointerId = ev.getPointerId(idx)
                edgeSwipeSide = edgeSideOf(ev.getX(idx))
                edgeSwipeX = ev.getX(idx)
                edgeSwipeY = ev.getY(idx)
                // Touches that arrive before the rows exist (first show, or a
                // rebuild mid-gesture) have nothing valid to hit — drop them
                // instead of pressing a stale rect or cancelling a live press.
                if (laidOut) onDown(ev.getX(idx), ev.getY(idx))
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = ev.findPointerIndex(downPointerId)
                if (idx >= 0) onMove(ev.getX(idx), ev.getY(idx))
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val idx = ev.actionIndex
                if (idx >= 0 && ev.getPointerId(idx) == downPointerId) {
                    if (toolPressed != null) onToolUp(ev.getX(idx), ev.getY(idx))
                    else onUp(ev.getX(idx), ev.getY(idx))
                    downPointerId = -1
                    edgeSwipeSide = 0
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                edgeSwipeSide = 0
                cancelPress()
                val tool = toolPressed
                toolPressed = null
                if (tool != null) invalidate()
            }
        }
        return true
    }

    /** Toolbar buttons act like plain buttons: fire on release when the finger
     *  is still on the button, drop it on a drag off. */
    private fun onToolUp(x: Float, y: Float) {
        val tool = toolPressed ?: return
        toolPressed = null
        val inside = toolButtons.firstOrNull { it.first === tool }?.second?.contains(x, y) == true
        if (inside) tool.action()
        invalidate()
    }

    /** Which side an edge-swipe starting at x belongs to: left/right edge
     *  strip always counts; when keys are packed to one side the whole empty
     *  side acts as the gesture zone too. 0 = not an edge start. The side is
     *  measured against the drawn keys, never the view width: after switching
     *  apps the rows can be laid out for a narrower width than the view, and a
     *  stale window width made every touch count as an edge swipe. */
    private fun edgeSideOf(x: Float): Int {
        if (!oneHandedEnabled) return 0
        if (oneHandedSide != 0) return activeSide(x)
        if (x <= edgeZonePx) return -1
        if (x >= width - edgeZonePx) return 1
        return 0
    }

    private fun toggleOneHanded(side: Int) {
        oneHandedSide = if (oneHandedSide == side) 0 else side
        syncToolbarSide()
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        relayout()
        invalidate()
    }

    private fun onDown(x: Float, y: Float) {
        val key = hit(x, y) ?: return
        pressConsumed = false
        pressed = key
        downX = x
        downY = y
        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        playSoundEffect(SoundEffectConstants.CLICK)
        if (key.spec.code == K.DEL) {
            manualShift = false
            if (delHoldMode != 0) {
                // Select-on-hold modes: nothing deletes on down — a quick tap
                // deletes once on release, holding highlights instead.
                delSelecting = false
                delSelectDownX = x
                delSelectLastX = x
                handler.postDelayed(delLongPress, 400)
            } else {
                listener?.onDelete()
                handler.postDelayed(repeatDelete, 400)
            }
        }
        if (key.spec.code == K.SPACE) {
            spaceLongFired = false
            spaceSliding = false
            spaceDownX = x
            spaceDownY = y
            spaceLastStepX = x
            handler.postDelayed(spaceLongPress, 550)
        } else if (key.spec.longPressChar != null) {
            handler.postDelayed(hintLongPress, 450)
        }
        invalidate()
    }

    private fun withinHysteresis(key: Key, x: Float, y: Float): Boolean {
        if (abs(x - downX) <= tapLockSlop && abs(y - downY) <= tapLockSlop) return true
        val r = key.rect
        return x >= r.left - keyHysteresis && x <= r.right + keyHysteresis &&
            y >= r.top - keyHysteresis && y <= r.bottom + keyHysteresis
    }

    /** Cancels a press that has drifted off its key but is still inside the
     *  hysteresis band. Without this, a tap on the toolbar above the top row
     *  could still press the key the finger started on. */
    private fun releaseStalePress(x: Float, y: Float) {
        val p = pressed ?: return
        if (withinHysteresis(p, x, y)) return
        if (hit(x, y) === p) return
        cancelPress()
    }

    private fun onMove(x: Float, y: Float) {
        if (toolPressed != null) {
            if (toolButtons.none { it.first === toolPressed && it.second.contains(x, y) }) {
                toolPressed = null
                invalidate()
            }
            return
        }
        releaseStalePress(x, y)
        // One-handed edge swipe: downward pull from a side edge packs the keys
        // to that side (or exits when swiping the same side again).
        if (edgeSwipeSide != 0) {
            val dy = y - edgeSwipeY
            val dx = x - edgeSwipeX
            if (dy > edgeSwipeTrigger && abs(dy) > abs(dx) * 1.3f) {
                edgeSwipeSide = 0
                cancelPress()
                toggleOneHanded(activeSide(x))
                return
            }
            if (abs(dx) > abs(dy) * 1.5f) edgeSwipeSide = 0 // horizontal swipe — not ours
        }
        if (pressConsumed && !delSelecting) return
        // Quick horizontal swipe on Backspace enters drag-select immediately —
        // no need to wait out the hold delay (same feel as the space-bar
        // cursor swipe). Steps below then count from the touch-down point,
        // so the slop distance already travelled selects its first chars.
        if (!delSelecting && delHoldMode != 0 && pressed?.spec?.code == K.DEL) {
            val dx = x - delSelectDownX
            val dy = y - downY
            if (abs(dx) > delSwipeSlop && abs(dx) >= abs(dy)) {
                delSelecting = true
                delSelectDir = if (dx > 0) 1 else -1
                handler.removeCallbacks(delLongPress)
                handler.removeCallbacks(repeatDelete)
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                invalidate()
            }
        }
        // Hold-Backspace selection drag — like the space-bar cursor swipe but
        // it highlights text instead of moving the caret.
        if (delSelecting && pressed?.spec?.code == K.DEL) {
            // Drag adjusts the highlight too — in auto-select mode it can
            // trim back to the right or extend further left.
            val dx = x - delSelectDownX
            if (delSelectDir == 0 && abs(dx) > delSwipeSlop) {
                delSelectDir = if (dx > 0) 1 else -1
            }
            if (delSelectDir != 0) {
                val steps = ((x - delSelectLastX) / delStepPx).toInt()
                if (steps != 0) {
                    delSelectLastX += steps * delStepPx
                    val dir = if (steps > 0) 1 else -1
                    // One batched call per move event — a fast swipe used to
                    // fire one IPC per char and visibly stutter.
                    listener?.onSelectMove(dir, abs(steps))
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
            }
            return
        }
        if (spaceSliding || pressed?.spec?.code == K.SPACE) {
            val dx = x - spaceDownX
            val dy = y - spaceDownY
            if (!spaceSliding && abs(dx) > spaceSwipeSlop && abs(dx) >= abs(dy)) {
                spaceSliding = true
                manualShift = false
                handler.removeCallbacks(spaceLongPress)
                invalidate()
            }
            if (spaceSliding) {
                val steps = ((x - spaceLastStepX) / spaceStepPx).toInt()
                if (steps != 0) {
                    spaceLastStepX += steps * spaceStepPx
                    val dir = if (steps > 0) 1 else -1
                    repeat(abs(steps)) { listener?.onCursorMove(dir) }
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                return
            }
        }
        // Keep the current key locked while the finger is still inside its hysteresis
        // zone so slight vertical thumb-roll between the letter row and number row
        // never jumps to the adjacent row mid-tap.
        val current = pressed
        if (current != null && withinHysteresis(current, x, y)) return
        val key = hit(x, y)
        if (key !== current) {
            pressed = key
            downX = x
            downY = y
            handler.removeCallbacks(repeatDelete)
            handler.removeCallbacks(delLongPress)
            handler.removeCallbacks(delAutoSelect)
            handler.removeCallbacks(spaceLongPress)
            handler.removeCallbacks(hintLongPress)
            if (key?.spec?.code == K.DEL) {
                // Rolling onto Backspace from another key: rebase the swipe
                // origin so a stale coordinate can't insta-trigger select.
                delSelectDownX = x
                delSelectLastX = x
                if (delHoldMode != 0) handler.postDelayed(delLongPress, 400)
                else handler.postDelayed(repeatDelete, 400)
            }
            else if (key?.spec?.longPressChar != null) handler.postDelayed(hintLongPress, 450)
            invalidate()
        }
    }

    private fun onUp(x: Float, y: Float) {
        val p = pressed ?: return
        val wasConsumed = pressConsumed
        val wasDelSelecting = delSelecting
        val shouldDispatch = !wasSliding && p.spec.code != K.DEL && p.spec.code != K.SPACER &&
            (withinHysteresis(p, x, y) || hit(x, y) === p)
        cancelPress()
        if (wasDelSelecting) {
            // Release after hold+drag — delete the highlighted text.
            listener?.onSelectDelete()
            return
        }
        if (delHoldMode != 0 && !wasConsumed && p.spec.code == K.DEL &&
            (withinHysteresis(p, x, y) || hit(x, y) === p)) {
            // Quick tap in select-on-hold mode: delete once on release.
            listener?.onDelete()
            return
        }
        if (shouldDispatch) {
            dispatch(p)
        }
    }

    private fun cancelPress() {
        val released = pressed
        if (popupEnabled && released != null && released.spec.code > 0) {
            popupKey = released
            popupHoldUntil = SystemClock.uptimeMillis() + popupHoldMs
            postInvalidateDelayed(popupHoldMs + 10)
        } else {
            popupKey = null
        }
        pressed = null
        spaceSliding = false
        delSelecting = false
        handler.removeCallbacks(repeatDelete)
        handler.removeCallbacks(delLongPress)
        handler.removeCallbacks(delAutoSelect)
        handler.removeCallbacks(spaceLongPress)
        handler.removeCallbacks(hintLongPress)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        cancelPress()
        hideOverlayPopup()
        super.onDetachedFromWindow()
    }

    private fun hit(x: Float, y: Float): Key? {
        for (i in 0 until keys.size) {
            val k = keys[i]
            if (k.spec.code != K.SPACER && k.rect.contains(x, y)) return k
        }
        // Snap touches landing in the narrow gap between keys/rows to the closest key.
        val padX = keyGap * 0.6f
        val padY = rowGap * 0.6f
        var best: Key? = null
        var bestDistSq = Float.MAX_VALUE
        for (i in 0 until keys.size) {
            val k = keys[i]
            if (k.spec.code == K.SPACER) continue
            val r = k.rect
            if (x >= r.left - padX && x <= r.right + padX &&
                y >= r.top - padY && y <= r.bottom + padY
            ) {
                val dx = x - r.centerX()
                val dy = y - r.centerY()
                val distSq = dx * dx + dy * dy
                if (distSq < bestDistSq) {
                    bestDistSq = distSq
                    best = k
                }
            }
        }
        return best
    }

    private fun dispatch(key: Key) {
        when (key.spec.code) {
            K.SHIFT -> {
                val now = SystemClock.uptimeMillis()
                if (capsLock) {
                    capsLock = false; shifted = false; manualShift = false
                } else if (shifted && now - shiftTapTime < 350) {
                    capsLock = true; manualShift = false
                } else {
                    shifted = !shifted; manualShift = true
                }
                shiftTapTime = if (shifted || capsLock) now else 0
                listener?.onShiftChanged(shifted, capsLock)
            }
            K.DEL, K.SPACER -> {} // delete fires on down/repeat
            K.DIGIT_DIAL -> setSymbolPage(true)
            K.MODE -> {
                // On the second symbols page the same key is labelled `?123`
                // and steps back to the main symbol page instead of leaving.
                if (symbolsPage && symbolLevel == 1) showSymbolLevel(0) else setSymbolPage(!symbolsPage)
            }
            K.MORE -> showSymbolLevel(1)
            K.EMOJI -> listener?.onSwitchIme()
            K.ENTER -> {
                manualShift = false
                listener?.onEnter()
            }
            K.SPACE, K.NUM_SPACE -> {
                manualShift = false
                listener?.onChar(' ')
            }
            K.HIDE -> listener?.onHideKeyboard()
            K.IME -> listener?.onSwitchIme()
            else -> {
                manualShift = false
                listener?.onChar(currentLabel(key.spec).first())
                if (shifted && !capsLock) {
                    shifted = false
                    listener?.onShiftChanged(false, false)
                }
            }
        }
        invalidate()
    }

    /** Grid size for `rows`, including the toolbar row when it is shown. The
     *  numeric keypad is five rows and has no toolbar, so callers cannot just
     *  count `rows.size`. */
    private fun pageRowCount(): Int = rows.size + (if (toolbarVisible()) 1 else 0)

    private fun setSymbolPage(on: Boolean) {
        if (symbolsPage == on) return
        val previousCount = pageRowCount()
        symbolsPage = on
        symbolLevel = 0
        rows = currentRows()
        relayout()
        if (previousCount != pageRowCount()) {
            measuredH = 0
            requestLayout()
        }
    }

    /** Row 1 is the `?123` page, row 2 the `=\<` page — same grid, so only
     *  the keys need a new layout. */
    private fun showSymbolLevel(level: Int) {
        if (!symbolsPage || symbolLevel == level) return
        symbolLevel = level
        rows = currentRows()
        relayout()
        invalidate()
    }

    private fun currentLabel(spec: KeySpec): String =
        if (shifted && spec.label.length == 1 && spec.label[0].isLetter())
            spec.label.uppercase()
        else spec.label
}