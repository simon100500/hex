package app.hexforandroid

import android.Manifest
import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings as AndroidSettings
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

/**
 * Hex for Android's one window. The page keeps what people change: the
 * status, setup while anything is missing, the dictation key, dictation and recent
 * dictations. Clean-up steps, practice, full history and the rarely changed
 * settings live in sheets or under Advanced, as desktop Hex hides them.
 */
class MainActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var settings: Settings
    private lateinit var root: LinearLayout

    private lateinit var statusDot: View
    private lateinit var statusTitle: TextView
    private lateinit var statusLine: TextView
    private lateinit var statusAction: LinearLayout
    private lateinit var setupSection: View
    private lateinit var setupCard: Ui.Card
    private lateinit var stage: MicKeyView
    private lateinit var cleanupRow: Ui.Row
    private lateinit var recentCard: Ui.Card
    private lateinit var modelRow: Ui.Row
    private lateinit var soundsRow: Ui.Row
    private lateinit var syncRow: Ui.Row
    private lateinit var advanced: View
    private lateinit var advancedChevron: ImageView
    private lateinit var footer: TextView
    private var downloading: String? = null
    private var downloadProgress = 0
    /** The user went to the accessibility settings once; if Hex is still off there, Android may have greyed it out. */
    private var askedAccessibility = false
    private var shown: List<Any?>? = null
    private val listener: (Dictation.State) -> Unit = { state ->
        stage.update(state)
        if (state.shape != shown) {
            shown = state.shape
            refresh()
        }
    }
    private val vocabularyListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        ui = Ui(this)
        settings = Settings(this)
        Dictation.init(this)
        askedAccessibility = savedInstanceState?.getBoolean("askedAccessibility") ?: false
        window.decorView.setBackgroundColor(ui.t.window)
        root = ui.vertical { setPadding(ui.px(16), ui.px(16), ui.px(16), ui.px(36)) }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(ui.t.window)
            addView(root)
            // Android 15+ draws edge to edge: keep clear of the system bars and keyboard.
            setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        })
        build()
        if (savedInstanceState?.getBoolean("advanced") == true) toggleAdvanced()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("askedAccessibility", askedAccessibility)
        outState.putBoolean("advanced", advanced.visibility == View.VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        shown = null
        Dictation.listen(listener)
        Vocabulary.listen(vocabularyListener)
        Vocabulary.sync(this, minGapMs = 30_000)
        refresh()
    }

    override fun onPause() {
        Dictation.unlisten(listener)
        Vocabulary.unlisten(vocabularyListener)
        super.onPause()
    }

    // --- Page -----------------------------------------------------------------

    private fun build() {
        val t = ui.t
        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(4), ui.px(4), ui.px(16))
            addView(ui.icon(R.drawable.hex_mark, 24))
            addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
            addView(ui.text(getString(R.string.app_name), 22f, ui.semibold))
        })

        statusDot = ui.statusDot(t.faint)
        statusTitle = ui.title("")
        statusLine = ui.description("")
        statusAction = ui.horizontal()
        root.addView(ui.card().apply {
            row(ui.horizontal {
                setPadding(ui.px(16), ui.px(14), ui.px(14), ui.px(14))
                addView(ui.vertical {
                    addView(ui.horizontal {
                        addView(statusDot)
                        addView(View(context), LinearLayout.LayoutParams(ui.px(10), 1))
                        addView(statusTitle)
                    })
                    addView(statusLine.apply { setPadding(ui.px(18), ui.px(4), 0, 0) })
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
                addView(statusAction)
            })
        })

        setupCard = ui.card()
        setupSection = ui.vertical {
            addView(ui.section("Set up"))
            addView(setupCard)
        }
        root.addView(setupSection)

        // Dictation key: the small mic the overlay puts in the keyboard's strip.
        root.addView(ui.section("Dictation key"))
        stage = MicKeyView(this).apply {
            contentDescription = "Dictation key preview"
            shape = BubbleShape.of(settings.keyShape)
            sizeDp = MicKeyView.sizeFor(settings.keySize)
        }
        root.addView(ui.card().apply {
            row(FrameLayout(context).apply {
                minimumHeight = ui.px(108)
                addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
                addView(ui.button("Preview") { Dictation.preview() }, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END or Gravity.BOTTOM,
                ).apply { setMargins(0, 0, ui.px(12), ui.px(12)) })
            })
            row(ui.row("Shape", trailing = ui.iconSegmented(
                listOf(BubbleShape.Circle, BubbleShape.Square, BubbleShape.Hexagon)
                    .map { Triple(it.name, it.icon, it.label) }, settings.keyShape,
            ) {
                settings.keyShape = it
                restyle()
            }))
            row(ui.row("Size", trailing = ui.segmented(
                listOf("S" to "S", "M" to "M", "L" to "L"), settings.keySize,
            ) {
                settings.keySize = it
                restyle()
            }))
            row(ui.vertical {
                setPadding(ui.px(16), ui.px(12), ui.px(14), ui.px(12))
                addView(ui.text("Position", 15f, ui.medium, ui.t.text))
                addView(ui.text("1 dp steps. Vertical can put the key over the keyboard; touches there go to dictation.", 13f, ui.regular, ui.t.muted).apply {
                    setPadding(0, ui.px(3), 0, ui.px(10))
                })
                addView(axisControl("Horizontal", settings.keyOffsetX, -300, 40) {
                    settings.keyOffsetX = it
                    HexAccessibilityService.instance?.restyleBubble()
                })
                addView(ui.space(8))
                addView(axisControl("Vertical", settings.keyOffsetY, -40, 360) {
                    settings.keyOffsetY = it
                    HexAccessibilityService.instance?.restyleBubble()
                })
                addView(ui.horizontal {
                    setPadding(0, ui.px(8), 0, 0)
                    addView(ui.button("Reset position") {
                        settings.keyOffsetX = 0
                        settings.keyOffsetY = 0
                        recreate()
                    })
                })
            })
            row(ui.row("Shows", "Only while a text field is focused and the keyboard is open"))
            row(ui.row("Tap", "Start, tap again to stop and insert"))
            row(ui.row("Hold", "Talk while held, release to insert"))
        })

        // Dictation: what Hex writes.
        root.addView(ui.section("Dictation"))
        cleanupRow = ui.row("Replacements", "", R.drawable.hex_icon_sparkles, trailing = chevron()) { cleanupSheet() }
        root.addView(ui.card().apply {
            row(cleanupRow)
            row(ui.row("Practice", "Try the key without leaving Hex", R.drawable.hex_icon_mic, trailing = chevron()) { practiceSheet() })
        })

        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(24), ui.px(4), ui.px(8))
            addView(ui.text("Recent", 15f, ui.medium, t.muted), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("Show all", 13f, ui.medium, t.accent).apply { setOnClickListener { historySheet() } })
        })
        recentCard = ui.card()
        root.addView(recentCard)

        // Advanced, collapsed: the settings people rarely change.
        advancedChevron = ui.icon(R.drawable.hex_chevron, 14, t.muted)
        root.addView(ui.horizontal {
            setPadding(ui.px(4), ui.px(24), ui.px(4), ui.px(8))
            addView(ui.text("Advanced", 15f, ui.medium, t.muted))
            addView(View(context), LinearLayout.LayoutParams(ui.px(6), 1))
            addView(advancedChevron)
            setOnClickListener { toggleAdvanced() }
        })
        modelRow = ui.row("Speech model", "GigaAM v3 Russian", R.drawable.hex_icon_wave, trailing = chevron()) { modelSheet() }
        soundsRow = ui.row("Sounds", "", R.drawable.hex_icon_speaker, trailing = chevron()) { soundsSheet() }
        syncRow = ui.row("Desktop sync", "", R.drawable.hex_icon_refresh, trailing = chevron()) { syncSheet() }
        advanced = ui.card().apply {
            visibility = View.GONE
            row(modelRow)
            row(soundsRow)
            row(ui.row("Insert text", "Paste uses the clipboard", R.drawable.hex_icon_paste, below = ui.segmented(
                listOf("auto" to "Type", "paste" to "Paste"), settings.insertMode,
            ) { settings.insertMode = it }))
            row(ui.row("Lower other audio", icon = R.drawable.hex_icon_volume, trailing = ui.switch(settings.duckAudio) {
                settings.duckAudio = it
            }))
            row(syncRow)
            row(ui.row("CPU threads", icon = R.drawable.hex_icon_zap, below = ui.segmented(
                listOf("0" to "Auto", "2" to "2", "4" to "4", "6" to "6"), settings.threads.toString(),
            ) {
                settings.threads = it.toInt()
                if (DictationService.running) toast("Applies when Hex next turns on")
            }))
        }
        root.addView(advanced)

        footer = ui.text("", 12f, ui.regular, t.faint).apply {
            setPadding(ui.px(4), ui.px(22), ui.px(4), ui.px(8))
            setLineSpacing(0f, 1.4f)
            setOnClickListener { aboutSheet() }
        }
        root.addView(footer)
    }

    private fun chevron() = ui.icon(R.drawable.hex_chevron, 16, ui.t.muted)

    private fun toggleAdvanced() {
        val open = advanced.visibility != View.VISIBLE
        advanced.visibility = if (open) View.VISIBLE else View.GONE
        advancedChevron.animate().rotation(if (open) 90f else 0f).setDuration(150).start()
    }

    private fun restyle() {
        stage.shape = BubbleShape.of(settings.keyShape)
        stage.sizeDp = MicKeyView.sizeFor(settings.keySize)
        stage.invalidate()
        HexAccessibilityService.instance?.restyleBubble()
    }

    /** A labelled 1 dp slider with nudge buttons, for one axis of the key's position. */
    private fun axisControl(label: String, value: Int, min: Int, max: Int, onChange: (Int) -> Unit): View {
        val valueView = ui.text(offsetLabel(value), 13f, ui.medium, ui.t.muted).apply {
            gravity = Gravity.CENTER
            minWidth = ui.px(58)
        }
        lateinit var bar: SeekBar
        fun setValue(next: Int) {
            val clamped = next.coerceIn(min, max)
            bar.progress = clamped - min
            valueView.text = offsetLabel(clamped)
            onChange(clamped)
        }
        bar = SeekBar(this).apply {
            this.max = max - min
            progress = value.coerceIn(min, max) - min
            progressTintList = ColorStateList.valueOf(ui.t.accent)
            thumbTintList = ColorStateList.valueOf(ui.t.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    valueView.text = offsetLabel(min + progress)
                    onChange(min + progress)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
            })
        }
        fun nudge(delta: Int) = setValue(min + bar.progress + delta)
        val minus = ui.text("−", 22f, ui.medium, ui.t.muted).apply {
            gravity = Gravity.CENTER
            setOnClickListener { nudge(-1) }
        }
        val plus = ui.text("+", 22f, ui.medium, ui.t.muted).apply {
            gravity = Gravity.CENTER
            setOnClickListener { nudge(1) }
        }
        return ui.vertical {
            addView(ui.horizontal {
                addView(ui.text(label, 14f, ui.medium, ui.t.text), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(valueView)
            })
            addView(ui.horizontal {
                addView(minus, LinearLayout.LayoutParams(ui.px(30), ui.px(40)))
                addView(bar, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(plus, LinearLayout.LayoutParams(ui.px(30), ui.px(40)))
            })
        }
    }

    private fun offsetLabel(dp: Int) = if (dp > 0) "+$dp dp" else "$dp dp"

    // --- State ----------------------------------------------------------------

    private fun refresh() {
        if (!::footer.isInitialized) return
        val t = ui.t
        val state = Dictation.state
        val missing = setupSteps().filterNot { it.done() }

        val (dot, title, line) = when {
            state.phase == Dictation.Phase.Loading -> Triple(t.accent, "Starting", "Loading the speech model…")
            state.phase == Dictation.Phase.Recording && !Dictation.previewing -> Triple(t.danger, "Listening", "Tap the mic key to stop")
            state.phase == Dictation.Phase.Transcribing && !Dictation.previewing -> Triple(t.accent, "Transcribing", "")
            DictationService.running -> Triple(t.success, "On", "Tap the mic key, or hold it to talk")
            missing.isNotEmpty() -> Triple(t.faint, "Off", "Finish setting up below")
            else -> Triple(t.faint, "Off", "Turn on to dictate")
        }
        statusDot.background = ui.rounded(dot, 4f)
        statusTitle.text = title
        val message = state.message ?: line
        statusLine.text = message
        statusLine.visibility = if (message.isEmpty()) View.GONE else View.VISIBLE
        statusAction.removeAllViews()
        statusAction.addView(
            if (DictationService.running) ui.button("Turn off") { DictationService.stop(this); later { refresh() } }
            else ui.button("Turn on", primary = missing.isEmpty()) { turnOn() },
        )

        setupCard.removeAllViews()
        missing.forEach { step -> setupCard.row(step.row()) }
        setupSection.visibility = if (missing.isEmpty()) View.GONE else View.VISIBLE

        cleanupRow.describe(cleanupSummary())
        modelRow.describe(when {
            downloading != null -> "$downloading $downloadProgress%"
            ModelStore.isPresent(this) -> "GigaAM v3 Russian"
            else -> "Not downloaded"
        })

        soundsRow.describe(if (settings.sounds) Sounds.Set.of(settings.soundSet).label else "Off")
        syncRow.describe(if (Vocabulary.paired(this)) Vocabulary.status(this) else "Off")

        recentCard.removeAllViews()
        val items = History.items(this).take(3)
        if (items.isEmpty()) recentCard.row(ui.row("Nothing yet", "Your dictations show here"))
        items.forEach { recentCard.row(historyRow(it)) }

        footer.text = "${getString(R.string.app_name)} ${versionName()} · About"
    }

    private fun cleanupSummary(): String {
        val count = settings.replacements().size
        return when {
            count == 0 -> "Off"
            count == 1 -> "1 replacement"
            else -> "$count replacements"
        }
    }

    private fun historyRow(item: History.Item) = ui.horizontal {
        setPadding(ui.px(16), ui.px(12), ui.px(14), ui.px(12))
        gravity = Gravity.TOP
        addView(ui.text(item.text, 14f).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        History.ago(item.at).takeIf { it.isNotEmpty() }?.let {
            addView(View(context), LinearLayout.LayoutParams(ui.px(12), 1))
            addView(ui.text(it, 12f, ui.regular, ui.t.faint))
        }
        background = ui.pressable(ui.t.surface, 0f)
        setOnClickListener {
            Inserter.copy(this@MainActivity, item.text)
            toast("Copied")
        }
    }

    private fun versionName() = packageManager.getPackageInfo(packageName, 0).versionName

    private fun turnOn() {
        // Check now, not at the last refresh: setup may have finished meanwhile.
        if (setupSteps().any { !it.done() }) {
            refresh()
            toast("Finish setting up first")
            return
        }
        if (!DictationService.start(this)) toast("Android didn't let Hex start. Try again")
        later { refresh() }
    }

    // --- Setup ----------------------------------------------------------------

    private inner class SetupStep(
        val title: String,
        val description: String,
        val icon: Int,
        val action: String,
        val done: () -> Boolean,
        val run: () -> Unit,
    ) {
        fun row() = ui.row(title, description, icon, below = ui.button(action) { run() })
    }

    private fun setupSteps(): List<SetupStep> {
        val accessibility = if (askedAccessibility && Build.VERSION.SDK_INT >= 33) {
            // Android greys out the accessibility switch of an app installed outside a store.
            SetupStep("Accessibility", "Greyed out? In App info, open ⋮ › Allow restricted settings", R.drawable.hex_icon_hand, "App info",
                { accessibilityOn() },
                {
                    askedAccessibility = false
                    startActivity(Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                })
        } else {
            SetupStep("Accessibility", "Shows the mic key and types for you", R.drawable.hex_icon_hand, "Open",
                { accessibilityOn() },
                {
                    askedAccessibility = true
                    startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
                })
        }
        return listOfNotNull(
            SetupStep("Microphone", "Records only while you dictate", R.drawable.hex_icon_mic, "Allow",
                { checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1) }),
            if (Build.VERSION.SDK_INT >= 33) SetupStep("Notifications", "Shows when Hex is on", R.drawable.hex_icon_bell, "Allow",
                { checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED },
                { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2) }) else null,
            accessibility,
            SetupStep("Battery", "Keeps Hex ready in the background", R.drawable.hex_icon_zap, "Allow",
                { getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName) },
                { startActivity(Intent(AndroidSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))) }),
            SetupStep("Speech model",
                if (downloading != null) "$downloading $downloadProgress%" else "GigaAM v3 Russian · 197 MB",
                R.drawable.hex_icon_wave, if (ModelStore.isPresent(this)) "Verify" else "Download",
                { ModelStore.isVerified(this) },
                { fetchModel() }),
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun accessibilityOn(): Boolean {
        val enabled = AndroidSettings.Secure.getString(contentResolver, AndroidSettings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val name = ComponentName(this, HexAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == name }
    }

    private fun fetchModel() {
        if (downloading != null) return
        val verifyOnly = ModelStore.isPresent(this)
        downloading = if (verifyOnly) "Verifying" else "Downloading"
        downloadProgress = 0
        refresh()
        thread(name = "hex-model") {
            val result = runCatching {
                if (verifyOnly) {
                    ModelStore.verify(this) { f -> progress((f * 100).toInt()) }
                } else {
                    ModelStore.download(this) { have, total -> progress((have * 100 / total).toInt()) }
                }
            }
            runOnUiThread {
                downloading = null
                result.onFailure { toast("Model: ${it.message}") }
                result.onSuccess { ok -> if (!ok) toast("The model's checksum is wrong. Download it again") }
                refresh()
            }
        }
    }

    private fun progress(percent: Int) {
        if (percent == downloadProgress) return
        downloadProgress = percent
        runOnUiThread { refresh() }
    }

    // --- Sheets ---------------------------------------------------------------

    private fun cleanupSheet() {
        val sheet = Sheet(this, ui, "Replacements", listOf("Words you say become the words you chose, on every dictation"))
        val tryInput = ui.field("открой джиру")
        val tryResult = ui.text("", 15f, ui.medium)
        val update = { preview(tryInput, tryResult) }
        val replaceRow = ui.row("Replace words", replacementSummary(), step = 1, trailing = chevron())
        replaceRow.setOnClickListener {
            replacementsSheet {
                replaceRow.describe(replacementSummary())
                update()
            }
        }
        sheet.body.addView(ui.card().apply {
            row(replaceRow)
            row(ui.vertical {
                setPadding(ui.px(16), ui.px(12), ui.px(16), ui.px(14))
                addView(ui.horizontal {
                    addView(ui.text("Try", 14f, ui.medium, ui.t.muted), LinearLayout.LayoutParams(ui.px(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(tryInput, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
                addView(ui.horizontal {
                    setPadding(0, ui.px(10), 0, 0)
                    addView(ui.text("→", 14f, ui.medium, ui.t.faint), LinearLayout.LayoutParams(ui.px(36), ViewGroup.LayoutParams.WRAP_CONTENT))
                    addView(tryResult, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                })
            })
        })
        tryInput.addTextChangedListener(watcher(update))
        update()
        sheet.setOnDismissListener { refresh() }
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    private fun replacementSummary(): String {
        val rules = settings.replacements()
        return if (rules.isEmpty()) "None yet" else rules.take(3).joinToString(" · ") { (a, b) -> "$a → ${b.ifEmpty { "(nothing)" }}" }
    }

    private fun practiceSheet() {
        val sheet = Sheet(this, ui, "Practice", listOf("Tap the field, then use the mic key, or Dictate"))
        val field = ui.field("Dictated text appears here").apply {
            minLines = 3
            gravity = Gravity.TOP
        }
        lateinit var button: TextView
        button = ui.button("Dictate") {
            when {
                Dictation.recording -> Dictation.finish { text ->
                    val at = field.selectionEnd.coerceAtLeast(0)
                    val current = field.text.toString()
                    field.text.insert(at, Inserter.spaced(current.substring(0, at), current.substring(at), text))
                }
                !Dictation.armed -> toast("Turn Hex on first")
                else -> Dictation.start()
            }
            button.text = if (Dictation.recording) "Stop" else "Dictate"
        }
        sheet.body.addView(field)
        sheet.actions(listOf(button), listOf(ui.button("Done") {
            if (Dictation.recording) Dictation.cancel()
            sheet.dismiss()
        }))
        sheet.show()
    }

    private fun historySheet() {
        val sheet = Sheet(this, ui, "History", listOf("Tap one to copy it"))
        val card = ui.card()
        val items = History.items(this)
        if (items.isEmpty()) card.row(ui.row("Nothing yet", "Your dictations show here"))
        items.forEach { card.row(historyRow(it)) }
        sheet.body.addView(card)
        sheet.setOnDismissListener { refresh() }
        sheet.actions(
            if (items.isEmpty()) emptyList() else listOf(ui.button("Clear…") { confirmClear(sheet) }),
            listOf(ui.button("Done") { sheet.dismiss() }),
        )
        sheet.show()
    }

    /** Clearing history is two taps: the button turns into a confirmation. */
    private fun confirmClear(sheet: Sheet) {
        val confirm = Sheet(this, ui, "Clear history?", listOf("Hex keeps your last 50 dictations on this phone"))
        confirm.actions(
            listOf(ui.button("Cancel") { confirm.dismiss() }),
            listOf(ui.button("Clear") {
                History.clear(this)
                confirm.dismiss()
                sheet.dismiss()
            }),
        )
        confirm.show()
    }

    private fun soundsSheet() {
        val sheet = Sheet(this, ui, "Sounds", listOf("Desktop Hex's sounds for start, stop and cancel. Tap a set to hear it"))
        val sets = ui.card()
        val checks = mutableMapOf<Sounds.Set, View>()
        fun mark() = checks.forEach { (set, check) -> check.visibility = if (set.id == settings.soundSet) View.VISIBLE else View.INVISIBLE }
        sheet.body.addView(ui.card().apply {
            row(ui.row("Play sounds", trailing = ui.switch(settings.sounds) {
                settings.sounds = it
                if (it) Sounds.play(this@MainActivity, Sounds.Set.of(settings.soundSet), Sounds.Cue.Start)
            }))
        })
        sheet.body.addView(ui.space(12))
        Sounds.Set.entries.forEach { set ->
            val check = ui.icon(R.drawable.hex_check, 18, ui.t.accent)
            checks[set] = check
            sets.row(ui.row(set.label, set.character, trailing = check) {
                settings.soundSet = set.id
                mark()
                Sounds.play(this, set, Sounds.Cue.Start)
                root.postDelayed({ Sounds.play(this, set, Sounds.Cue.Stop) }, 700)
            })
        }
        mark()
        sheet.body.addView(sets)
        sheet.setOnDismissListener { refresh() }
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    private fun syncSheet() {
        if (!Vocabulary.paired(this)) {
            val sheet = Sheet(this, ui, "Desktop sync", listOf(
                "Optional: keeps this vocabulary in step with desktop Hex on a Linux computer, over Tailscale",
                "To set it up, connect this phone to the computer by USB and run hex-vocab-sync pair there. The project's README has the steps",
            ))
            sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
            sheet.show()
            return
        }
        val sheet = Sheet(this, ui, "Desktop sync", listOf(Vocabulary.status(this)))
        sheet.setOnDismissListener { refresh() }
        sheet.actions(
            listOf(ui.button("Unpair") {
                Vocabulary.unpair(this)
                sheet.dismiss()
            }),
            listOf(ui.button("Sync now") {
                Vocabulary.sync(this)
                sheet.dismiss()
            }),
        )
        sheet.show()
    }

    private fun modelSheet() {
        val sheet = Sheet(this, ui, "Speech model", listOf("GigaAM v3 Russian, desktop Hex's Russian model, running on this phone"))
        val card = ui.card()
        val state = Dictation.state
        val file = when {
            ModelStore.isVerified(this) -> "197 MB · verified"
            ModelStore.isPresent(this) -> "197 MB · not verified"
            else -> "Not downloaded"
        }
        card.row(ui.row("File", file, R.drawable.hex_icon_folder))
        card.row(ui.row("Engine", state.engine ?: "Loads when Hex turns on", R.drawable.hex_icon_terminal))
        state.last?.let {
            card.row(ui.row("Last dictation", "%.1f s of audio in %.2f s".format(it.audioMs / 1000f, it.inferenceMs / 1000f), R.drawable.hex_icon_timer))
        }
        sheet.body.addView(card)
        sheet.actions(
            listOf(ui.button(if (ModelStore.isPresent(this)) "Verify" else "Download") { sheet.dismiss(); fetchModel() }),
            listOf(ui.button("Done") { sheet.dismiss() }),
        )
        sheet.show()
    }

    private fun vocabularySheet() {
        val sheet = Sheet(this, ui, "Vocabulary", listOf("Under Sounds like, add what the model writes instead"))
        val sync = ui.description(Vocabulary.status(this))
        val syncLink = ui.text("Sync now", 13f, ui.medium, ui.t.accent).apply { setPadding(ui.px(10), ui.px(6), 0, ui.px(6)) }
        if (Vocabulary.paired(this)) {
            sheet.explainView(ui.horizontal {
                addView(sync, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(syncLink)
            })
        }
        sheet.body.addView(ui.card().apply {
            row(ui.row("Snap to vocabulary", "Rescore toward these terms (Parakeet only)", trailing = ui.switch(settings.vocabularyBiasing) {
                settings.vocabularyBiasing = it
            }))
            row(ui.row("Strength", trailing = ui.segmented(
                listOf("light" to "Light", "normal" to "Normal", "strong" to "Strong"), settings.vocabularyBoost,
            ) { settings.vocabularyBoost = it }))
        })
        sheet.body.addView(ui.space(12))
        val rows = ui.vertical()
        fun addRow(term: Term?) {
            val termField = ui.field("Term", term?.term.orEmpty())
            val soundsField = ui.field("Sounds like", term?.soundsLike?.joinToString(", ").orEmpty())
            val row = ui.horizontal { setPadding(0, 0, 0, ui.px(8)) }
            row.addView(termField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 0.9f))
            row.addView(View(this), LinearLayout.LayoutParams(ui.px(8), 1))
            row.addView(soundsField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.1f))
            row.addView(removeButton { rows.removeView(row) })
            row.tag = termField to soundsField
            rows.addView(row)
            if (term == null) termField.requestFocus()
        }
        Vocabulary.entries(this).forEach { addRow(it) }
        if (rows.childCount == 0) addRow(null)
        sheet.body.addView(rows)
        fun collect(): List<Term> = (0 until rows.childCount).mapNotNull { i ->
            val (termField, soundsField) = fields(rows.getChildAt(i))
            val term = termField.text.toString().trim()
            if (term.isEmpty()) null
            else Term(term, soundsField.text.toString().split(',').map { it.trim() }.filter { it.isNotEmpty() })
        }
        // Only a sync asked for here rebuilds the rows, so a background sync
        // never discards what is being typed.
        var requested = false
        syncLink.setOnClickListener {
            Vocabulary.save(this, collect(), notify = false)
            requested = true
            Vocabulary.sync(this)
            sync.text = "Syncing…"
        }
        val onSync: () -> Unit = {
            sync.text = Vocabulary.status(this)
            if (requested) {
                requested = false
                rows.removeAllViews()
                Vocabulary.entries(this).forEach { addRow(it) }
                if (rows.childCount == 0) addRow(null)
            }
        }
        Vocabulary.listen(onSync)
        sheet.setOnDismissListener {
            Vocabulary.unlisten(onSync)
            refresh()
        }
        sheet.actions(
            listOf(ui.button("Add term") { addRow(null) }),
            listOf(ui.button("Done") {
                Vocabulary.save(this, collect(), notify = false)
                Vocabulary.sync(this)
                sheet.dismiss()
            }),
        )
        sheet.show()
    }

    private fun replacementsSheet(onDone: () -> Unit) {
        val sheet = Sheet(this, ui, "Replace words", listOf("Matches ignore case. An empty output removes the phrase"))
        val rows = ui.vertical()
        fun addRow(rule: Pair<String, String>?) {
            val heard = ui.field("Heard", rule?.first.orEmpty())
            val output = ui.field("Output", rule?.second.orEmpty())
            val row = ui.horizontal { setPadding(0, 0, 0, ui.px(8)) }
            row.addView(heard, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(ui.text("→", 15f, ui.regular, ui.t.faint).apply { setPadding(ui.px(8), 0, ui.px(8), 0) })
            row.addView(output, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(removeButton { rows.removeView(row) })
            row.tag = heard to output
            rows.addView(row)
            if (rule == null) heard.requestFocus()
        }
        settings.replacements().forEach { addRow(it) }
        if (rows.childCount == 0) addRow(null)
        sheet.body.addView(rows)
        sheet.actions(
            listOf(ui.button("Add rule") { addRow(null) }),
            listOf(ui.button("Done") {
                settings.setReplacements((0 until rows.childCount).map { i ->
                    val (heard, output) = fields(rows.getChildAt(i))
                    heard.text.toString() to output.text.toString()
                })
                sheet.dismiss()
                onDone()
            }),
        )
        sheet.show()
    }

    private fun aboutSheet() {
        val sheet = Sheet(this, ui, "About", listOf(
            "An unofficial Android port of Hex. Speech is transcribed on this phone and never leaves it",
        ))
        val card = ui.card()
        card.row(ui.row("Hex", "Anomaly · MIT License", R.drawable.hex_mark))
        card.row(ui.row("transcribe.cpp", "Speech engine · MIT License", R.drawable.hex_icon_terminal))
        card.row(ui.row("GigaAM v3 Russian", "Sber · MIT", R.drawable.hex_icon_wave))
        card.row(ui.row("Inter, IBM Plex Mono", "SIL Open Font License 1.1", R.drawable.hex_icon_text_tool))
        sheet.body.addView(card)
        sheet.actions(emptyList(), listOf(ui.button("Done") { sheet.dismiss() }))
        sheet.show()
    }

    // --- Helpers --------------------------------------------------------------

    /** The two text fields a sheet row keeps in its tag. */
    @Suppress("UNCHECKED_CAST")
    private fun fields(row: View) = row.tag as Pair<EditText, EditText>

    private fun removeButton(onClick: () -> Unit) = ui.icon(R.drawable.hex_icon_circle_x, 20, ui.t.muted).apply {
        layoutParams = LinearLayout.LayoutParams(ui.px(40), ui.px(40))
        setPadding(ui.px(10), ui.px(10), ui.px(10), ui.px(10))
        contentDescription = "Remove"
        setOnClickListener { onClick() }
    }

    private fun preview(input: EditText, output: TextView) {
        val text = input.text.toString().ifEmpty { input.hint.toString() }
        output.text = runCatching { HexCore.process(text, settings.textSteps()) }.getOrElse { "—" }
    }

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = onChange()
    }

    private fun later(block: () -> Unit) = root.postDelayed(block, 350)

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

}
