package com.fujisim.cam

import java.util.Locale

/**
 * Turns the text of a Fuji recipe card (read from a screenshot) into a Recipe.
 * Handles the common formats: "Film Simulation: Classic Negative", "Grain Effect: Weak, Large",
 * "White Balance: Auto, +3 Red & -4 Blue", "Red: +3 Blue: -4", "Highlight: -1.5", "Exposure Compensation: +1/3 to +2/3"…
 */
object RecipeParser {

    class Result(val recipe: Recipe, val found: List<String>, val missing: List<String>)

    private fun norm(s: String) = s
        .replace('−', '-').replace('–', '-').replace('—', '-')
        .replace('＋', '+').replace("’", "'")

    fun parse(raw: String): Result? {
        val text = norm(raw)
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lower = text.lowercase(Locale.US)
        val found = mutableListOf<String>()

        // --- helpers ------------------------------------------------------------
        fun valueAfter(vararg keys: String): String? {
            for (k in keys) {
                val rx = Regex("(?im)^\\s*" + k + "\\s*[:=\\-]?\\s*(.+)$")
                val m = rx.find(text) ?: continue
                return m.groupValues[1].trim()
            }
            return null
        }
        fun number(s: String?): Float? {
            if (s == null) return null
            val m = Regex("([+-]?\\s*\\d+(?:[.,]\\d+)?)").find(s) ?: return null
            return m.groupValues[1].replace(" ", "").replace(',', '.').toFloatOrNull()
        }
        fun level(s: String?): Int? {
            val v = s?.lowercase(Locale.US) ?: return null
            return when {
                "strong" in v -> 2
                "weak" in v -> 1
                "off" in v -> 0
                else -> null
            }
        }

        // --- film simulation ----------------------------------------------------
        // The value may wrap onto the next line ("Classic" / "Negative"), so include it.
        val simIdx = lines.indexOfFirst { Regex("(?i)^\\s*film\\s*sim").containsMatchIn(it) }
        val simText = (if (simIdx >= 0) {
            lines[simIdx].substringAfter(':', lines[simIdx]) + " " + lines.getOrElse(simIdx + 1) { "" }
        } else lower).lowercase(Locale.US)
        val film = when {
            "bleach" in simText -> FilmSim.ETERNA_BB
            "eterna" in simText || "cinema" in simText -> FilmSim.ETERNA
            "nostalgic" in simText -> FilmSim.NOSTALGIC_NEG
            "classic neg" in simText || "classic negative" in simText -> FilmSim.CLASSIC_NEG
            "classic chrome" in simText || "chrome" == simText.trim() -> FilmSim.CLASSIC_CHROME
            "reala" in simText -> FilmSim.REALA_ACE
            "pro neg" in simText && ("hi" in simText || "high" in simText) -> FilmSim.PRO_NEG_HI
            "pro neg" in simText -> FilmSim.PRO_NEG_STD
            "velvia" in simText || "vivid" in simText -> FilmSim.VELVIA
            "astia" in simText || "soft" in simText -> FilmSim.ASTIA
            "acros" in simText && ("+ye" in simText.replace(" ", "") || "yellow" in simText) -> FilmSim.ACROS_YE
            "acros" in simText && ("+r" in simText.replace(" ", "") || "red" in simText) -> FilmSim.ACROS_R
            "acros" in simText && ("+g" in simText.replace(" ", "") || "green" in simText) -> FilmSim.ACROS_G
            "acros" in simText -> FilmSim.ACROS
            "sepia" in simText -> FilmSim.SEPIA
            "monochrome" in simText -> FilmSim.MONOCHROME
            "provia" in simText || "standard" in simText -> FilmSim.PROVIA
            else -> null
        }
        if (film != null) found += "Film simulation"

        // --- grain ----------------------------------------------------------------
        val grainText = valueAfter("grain\\s*effect", "grain")
        val grain = level(grainText) ?: 0
        val grainLarge = grainText?.lowercase(Locale.US)?.contains("large") == true
        if (grainText != null) found += "Grain"

        // --- colour chrome --------------------------------------------------------
        val ccBlueText = valueAfter("colou?r\\s*chrome\\s*(?:fx|effect)?\\s*blue", "cc\\s*fx\\s*blue", "ccfxb")
        val ccText = valueAfter("colou?r\\s*chrome\\s*effect", "colou?r\\s*chrome(?!\\s*(?:fx|effect)?\\s*blue)", "cce")
        val chrome = level(ccText) ?: 0
        val chromeBlue = level(ccBlueText) ?: 0
        if (ccText != null) found += "Color Chrome Effect"
        if (ccBlueText != null) found += "Color Chrome FX Blue"

        // --- white balance ----------------------------------------------------------
        val wbText = (valueAfter("white\\s*balance", "wb") ?: "").lowercase(Locale.US)
        var wb = WbMode.AUTO
        var kelvin = 5500
        val kMatch = Regex("(\\d{4,5})\\s*k").find(wbText)
        when {
            kMatch != null -> { wb = WbMode.KELVIN; kelvin = kMatch.groupValues[1].toInt() }
            "daylight" in wbText || "fine" in wbText -> wb = WbMode.DAYLIGHT
            "shade" in wbText -> wb = WbMode.SHADE
            "cloud" in wbText -> wb = WbMode.CLOUDY
            "fluor" in wbText -> wb = WbMode.FLUORESCENT
            "incand" in wbText || "tungsten" in wbText -> wb = WbMode.INCANDESCENT
        }
        if (wbText.isNotBlank()) found += "White balance"
        // R/B shift: "+3 Red & -4 Blue", "R: +3 B: -4", "Red: +3", "R+3 B-4"
        fun shift(color: String, letter: String): Int? {
            val pats = listOf(
                Regex("(?i)$color\\s*[:=]?\\s*([+-]?\\d+)"),
                Regex("(?i)([+-]?\\d+)\\s*$color"),
                Regex("(?i)(?<![a-z])$letter\\s*[:=]?\\s*([+-]?\\d+)"),
            )
            for (p in pats) p.find(text)?.let { return it.groupValues[1].toIntOrNull() }
            return null
        }
        val wbR = (shift("red", "r") ?: 0).coerceIn(-9, 9)
        val wbB = (shift("blue", "b") ?: 0).coerceIn(-9, 9)

        // --- tone ----------------------------------------------------------------
        val drText = valueAfter("dynamic\\s*range", "dr")?.lowercase(Locale.US)
        val dr = when {
            drText == null -> 100
            "400" in drText -> 400
            "200" in drText -> 200
            "auto" in drText -> 200
            else -> 100
        }
        if (drText != null) found += "Dynamic range"
        val hl = number(valueAfter("highlights?\\s*(?:tone)?"))
        val sh = number(valueAfter("shadows?\\s*(?:tone)?"))
        if (hl != null) found += "Highlight"
        if (sh != null) found += "Shadow"
        val col = number(valueAfter("colou?r(?!\\s*chrome)"))
        if (col != null) found += "Color"
        val sharp = number(valueAfter("sharpness"))
        val clarity = number(valueAfter("clarity"))
        if (sharp != null) found += "Sharpness"
        if (clarity != null) found += "Clarity"

        // --- exposure --------------------------------------------------------------
        val evText = valueAfter("exposure\\s*compensation", "exposure\\s*comp", "exposure", "ev")
        val ev = evText?.let { t ->
            val m = Regex("([+-]?)\\s*(\\d+)?\\s*(\\d)/(\\d)").find(t)
            if (m != null) {
                val sign = if (m.groupValues[1] == "-") -1f else 1f
                val whole = m.groupValues[2].toFloatOrNull() ?: 0f
                sign * (whole + m.groupValues[3].toFloat() / m.groupValues[4].toFloat())
            } else number(t)
        } ?: 0f

        if (film == null && found.size < 3) return null

        // --- name: a short line without ':' near the top ---------------------------
        val name = lines.take(6).firstOrNull { l ->
            ':' !in l && l.length in 3..32 && l.any { it.isLetter() } &&
                !l.lowercase(Locale.US).let { it.startsWith("film") || it.contains("recipe by") }
        }?.let { t -> t.lowercase(Locale.US).split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } } }
            ?: "Imported recipe"

        val recipe = Recipe(
            name = name,
            film = film ?: FilmSim.CLASSIC_CHROME,
            dr = dr,
            highlight = ((hl ?: 0f) * 2f).let { Math.round(it) / 2f }.coerceIn(-2f, 4f),
            shadow = ((sh ?: 0f) * 2f).let { Math.round(it) / 2f }.coerceIn(-2f, 4f),
            color = (col ?: 0f).toInt().coerceIn(-4, 4),
            sharpness = (sharp ?: 0f).toInt().coerceIn(-4, 4),
            clarity = (clarity ?: 0f).toInt().coerceIn(-5, 5),
            grain = grain,
            grainLarge = grainLarge,
            chrome = chrome,
            chromeBlue = chromeBlue,
            wb = wb,
            kelvin = kelvin.coerceIn(2500, 10000),
            wbR = wbR,
            wbB = wbB,
            ev = ev.coerceIn(-2f, 2f),
            credit = "From screenshot",
        )
        val all = listOf("Film simulation", "Grain", "Color Chrome Effect", "Color Chrome FX Blue", "White balance",
            "Dynamic range", "Highlight", "Shadow", "Color", "Sharpness", "Clarity")
        return Result(recipe, found, all.filter { it !in found })
    }
}
