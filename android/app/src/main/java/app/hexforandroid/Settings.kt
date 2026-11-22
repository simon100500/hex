package app.hexforandroid

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * User settings, with defaults for someone who just installed the app:
 * GigaAM v3 Russian, no English text steps (the model already punctuates and
 * capitalises), sounds off.
 */
class Settings(context: Context) {
    private val app = context.applicationContext
    private val prefs: SharedPreferences = app.getSharedPreferences("hex", Context.MODE_PRIVATE)

    /** The user turned Hex on (the bubble shows) and hasn't turned it off. */
    var enabled by bool("enabled", false)
    var spokenFormatting by bool("spoken_formatting", false)
    /** Off by default: "hashtag" or "at sign" turning into symbols surprises people who didn't ask for it. */
    var spokenSymbols by bool("spoken_symbols", false)
    var fillerWords by bool("filler_words", false)
    /** Desktop Hex's feedback sounds; off by default. */
    var sounds by bool("sounds", false)
    var soundSet by string("sound_set", Sounds.Set.Classic.id)
    /** Labs › Vocabulary biasing: Parakeet rescores toward the vocabulary. On by default here. */
    var vocabularyBiasing by bool("vocabulary_biasing_v2", true)
    /** off, light, normal, strong (Hex's levels). */
    var vocabularyBoost by string("vocabulary_boost", "normal")
    /** One rule per line: `heard phrase => output`. */
    var replacementsText by string("replacements", "")
    /** keyboard: only while a keyboard is open; always: whenever Hex is on. */
    var bubbleVisibility by string("bubble_visibility", "keyboard")
    /** auto: edit the field's text; paste: put it on the clipboard and paste. */
    var insertMode by string("insert_mode", "auto")
    /** CPU threads for transcription; 0 picks [autoThreads]. */
    var threads by int("threads_v2", 0)
    var bubbleShape by string("bubble_shape", BubbleShape.Circle.name)
    var bubbleSize by string("bubble_size", BubbleSize.Medium.name)
    var duckAudio by bool("duck_audio", true)
    /** The edge the bubble docks to and its height as a fraction of the screen, so it survives folding. */
    var bubbleRight by bool("bubble_right", true)
    var bubbleTop by float("bubble_top", 0.42f)

    /** The dictation key: its outline, size in dp, and how far it moves from the trailing edge. */
    var keyShape by string("key_shape", BubbleShape.Circle.name)
    var keySize by string("key_size", "M")
    /** Positive moves the key right, negative left, in dp. */
    var keyOffsetX by int("key_offset_x", 0)
    /** Positive moves the key down, negative up, in dp from the strip's centre. */
    var keyOffsetY by int("key_offset_y", 0)

    init {
        // 0.2 kept English text steps on by default; GigaAM's Russian must not
        // run them after an in-place update.
        if (prefs.getInt("settings_version", 1) < 2) {
            prefs.edit()
                .putBoolean("spoken_formatting", false)
                .putBoolean("filler_words", false)
                .putBoolean("spoken_symbols", false)
                .putInt("settings_version", 2)
                .apply()
        }
    }

    fun threadCount() = if (threads > 0) threads else autoThreads()

    fun runSettings(): JSONObject = JSONObject().apply {
        // GigaAM has no language prompt and no vocabulary rescoring: it hears
        // Russian itself. `language` stays absent, which the core reads as
        // "the model's own choice".
    }

    fun boostNats() = when (vocabularyBoost) {
        "off" -> 0.0
        "light" -> 2.0
        "strong" -> 4.0
        else -> 2.5
    }

    fun textSteps(): JSONObject = JSONObject().apply {
        put("spoken_formatting", spokenFormatting)
        put("spoken_symbols", spokenSymbols)
        put("filler_words", fillerWords)
        put("language", "ru")
        put("replacements", JSONArray(replacements().map { (phrase, output) -> JSONObject().put("matched_phrase", phrase).put("output", output) }))
    }

    fun replacements(): List<Pair<String, String>> = replacementsText.lines().mapNotNull { line ->
        val at = line.indexOf("=>")
        if (at < 0) return@mapNotNull null
        val phrase = line.substring(0, at).trim()
        if (phrase.isEmpty()) null else phrase to line.substring(at + 2).trim()
    }

    fun setReplacements(rules: List<Pair<String, String>>) {
        replacementsText = rules.filter { it.first.isNotBlank() }.joinToString("\n") { "${it.first.trim()} => ${it.second.trim()}" }
    }

    private fun bool(key: String, default: Boolean) = Pref({ prefs.getBoolean(key, default) }) {
        prefs.edit().putBoolean(key, it).apply()
    }

    private fun int(key: String, default: Int) = Pref({ prefs.getInt(key, default) }) {
        prefs.edit().putInt(key, it).apply()
    }

    private fun float(key: String, default: Float) = Pref({ prefs.getFloat(key, default) }) {
        prefs.edit().putFloat(key, it).apply()
    }

    private fun string(key: String, default: String) = Pref({ prefs.getString(key, default) ?: default }) {
        prefs.edit().putString(key, it).apply()
    }

    class Pref<T>(private val read: () -> T, private val write: (T) -> Unit) {
        operator fun getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = read()
        operator fun setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) = write(value)
    }
}

/**
 * The cores outside the slowest cluster, capped at four: on a Snapdragon 8
 * Gen 2 that is four of its five fast cores, which measured fastest (more
 * threads land on the little cores and slow every step down).
 */
fun autoThreads(): Int {
    val speeds = (0 until Runtime.getRuntime().availableProcessors()).mapNotNull { cpu ->
        runCatching { java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
    }
    if (speeds.isEmpty()) return 4
    val slowest = speeds.min()
    val fast = speeds.count { it > slowest }.takeIf { it > 0 } ?: speeds.size
    return fast.coerceIn(2, 4)
}
