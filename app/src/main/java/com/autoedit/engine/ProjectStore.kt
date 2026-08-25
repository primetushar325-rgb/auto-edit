package com.autoedit.engine

/**
 * Minimal JSON (pure Kotlin, zero dependencies) for project persistence.
 * Compact hand-rolled format keeps the footprint at zero deps and the
 * persistence layer fully unit-testable on the JVM.
 */
object ProjectJson {

    fun encode(p: ProjectModel): String {
        val sb = StringBuilder(1024)
        sb.append("{\"id\":").append(str(p.id)).append(",")
        sb.append("\"name\":").append(str(p.name)).append(",")
        sb.append("\"createdAt\":").append(p.createdAt).append(",")
        sb.append("\"updatedAt\":").append(p.updatedAt).append(",")
        sb.append("\"clips\":[")
        p.clips.forEachIndexed { i, c ->
            if (i > 0) sb.append(",")
            sb.append("{\"uri\":").append(str(c.uri))
            if (c.type != ClipType.IMAGE) {
                sb.append(",\"type\":").append(str(c.type.name))
                sb.append(",\"vin\":").append(c.videoInMs)
                sb.append(",\"vout\":").append(c.videoOutMs)
            }
            if (c.startZoom != null || c.endZoom != null) {
                sb.append(",\"sz\":").append(num((c.startZoom ?: 1f).toDouble()))
                sb.append(",\"ez\":").append(num((c.endZoom ?: ClipRef.DEFAULT_END_ZOOM).toDouble()))
            }
            val m = c.motion
            if (m != null) {
                sb.append(",\"motion\":{\"type\":").append(str(m.type.name))
                    .append(",\"s\":[").append(num(m.start.scale.toDouble())).append(",")
                    .append(num(m.start.x.toDouble())).append(",")
                    .append(num(m.start.y.toDouble())).append("],")
                    .append("\"e\":[").append(num(m.end.scale.toDouble())).append(",")
                    .append(num(m.end.x.toDouble())).append(",")
                    .append(num(m.end.y.toDouble())).append("]}")
            }
            sb.append("}")
        }
        sb.append("],")
        sb.append("\"formulaId\":").append(str(p.formulaId)).append(",")
        sb.append("\"motionSeed\":").append(p.motionSeed).append(",")
        sb.append("\"clipDuration\":").append(num(p.clipDurationSec)).append(",")
        sb.append("\"transition\":").append(str(p.transition.name)).append(",")
        sb.append("\"transitionDuration\":").append(num(p.transitionDurationSec)).append(",")
        sb.append("\"aspect\":").append(str(p.aspect.name)).append(",")
        sb.append("\"voice\":").append(encodeAudio(p.voice)).append(",")
        sb.append("\"music\":").append(encodeAudio(p.music)).append(",")
        sb.append("\"duckMusic\":").append(p.duckMusic).append(",")
        sb.append("\"fitToVoice\":").append(p.fitToVoice).append(",")
        val a = p.adjustments
        sb.append("\"adjust\":{\"brightness\":").append(num(a.brightness.toDouble()))
            .append(",\"contrast\":").append(num(a.contrast.toDouble()))
            .append(",\"saturation\":").append(num(a.saturation.toDouble()))
            .append(",\"vignette\":").append(num(a.vignette.toDouble()))
            .append(",\"blur\":").append(a.blur).append("},")
        val e = p.export
        sb.append("\"export\":{\"quality\":").append(str(e.quality.name))
            .append(",\"fps\":").append(e.fps)
            .append(",\"aspect\":").append(str(e.aspect.name)).append("}")
        if (p.junctionTransitions.isNotEmpty()) {
            sb.append(",\"junctions\":{")
            sb.append(
                p.junctionTransitions.entries.joinToString(",") { (k, v) ->
                    "\"" + k + "\":\"" + v.name + "\""
                }
            )
            sb.append("}")
        }
        sb.append("}")
        return sb.toString()
    }

    private fun encodeAudio(a: AudioConfig?): String {
        if (a == null) return "null"
        return "{" +
            "\"uri\":" + str(a.uri) +
            ",\"name\":" + str(a.displayName) +
            ",\"dur\":" + num(a.durationSec) +
            ",\"vol\":" + num(a.volume.toDouble()) +
            ",\"off\":" + num(a.offsetSec) +
            ",\"fi\":" + num(a.fadeInSec) +
            ",\"fo\":" + num(a.fadeOutSec) +
            ",\"loop\":" + a.loop +
            "}"
    }

    fun str(s: String?): String {
        if (s == null) return "null"
        val out = StringBuilder("\"")
        for (c in s) {
            when (c) {
                '"' -> out.append("\\\"")
                '\\' -> out.append("\\\\")
                '\n' -> out.append("\\n")
                '\r' -> out.append("\\r")
                '\t' -> out.append("\\t")
                else -> if (c.code < 0x20) out.append("\\u%04x".format(c.code)) else out.append(c)
            }
        }
        out.append('"')
        return out.toString()
    }

    fun num(d: Double): String =
        if (d == d.toLong().toDouble() && d in -1e15..1e15) d.toLong().toString() else d.toString()

    fun decode(s: String): ProjectModel {
        val root = JsonReader(s).parseValue() as? Map<String, Any?>
            ?: throw IllegalArgumentException("Invalid project file")

        val clips = (root["clips"] as? List<Any>)?.map { c ->
            val cm = c as Map<String, Any?>
            val mObj = c["motion"] as? Map<String, Any?>
            val motion: ClipMotion? = mObj?.let {
                val sArr = it["s"] as List<Any>
                val eArr = it["e"] as List<Any>
                ClipMotion(
                    type = MotionType.valueOf(it["type"] as String),
                    start = Keyframe(
                        (sArr[0] as Number).toFloat(),
                        (sArr[1] as Number).toFloat(),
                        (sArr[2] as Number).toFloat()
                    ),
                    end = Keyframe(
                        (eArr[0] as Number).toFloat(),
                        (eArr[1] as Number).toFloat(),
                        (eArr[2] as Number).toFloat()
                    )
                )
            }
            val cType = (cm["type"] as? String)?.let { runCatching { ClipType.valueOf(it) }.getOrNull() } ?: ClipType.IMAGE
            val hasZoom = cm.containsKey("sz") || cm.containsKey("ez")
            ClipRef(
                uri = cm["uri"] as String,
                type = cType,
                motion = motion,
                videoInMs = (cm["vin"] as? Number)?.toLong() ?: 0L,
                videoOutMs = (cm["vout"] as? Number)?.toLong() ?: 0L,
                startZoom = if (hasZoom) ((cm["sz"] as? Number)?.toFloat() ?: 1f) else null,
                endZoom = if (hasZoom) ((cm["ez"] as? Number)?.toFloat() ?: ClipRef.DEFAULT_END_ZOOM) else null
            )
        } ?: emptyList()

        val adj = (root["adjust"] as? Map<String, Any>) ?: emptyMap()
        val exp = (root["export"] as? Map<String, Any>) ?: emptyMap()
        val junctions = (root["junctions"] as? Map<String, Any>)?.mapNotNull { (k, v) ->
            val idx = k.toIntOrNull() ?: return@mapNotNull null
            runCatching { TransitionType.valueOf(v.toString()) }.getOrNull()?.let { idx to it }
        }?.toMap() ?: emptyMap()

        return ProjectModel(
            id = root["id"] as String,
            name = (root["name"] as? String) ?: "Project",
            createdAt = (root["createdAt"] as? Number)?.toLong() ?: 0L,
            updatedAt = (root["updatedAt"] as? Number)?.toLong() ?: 0L,
            clips = clips,
            formulaId = root["formulaId"] as? String,
            motionSeed = (root["motionSeed"] as? Number)?.toLong() ?: 1L,
            clipDurationSec = (root["clipDuration"] as? Number)?.toDouble() ?: 3.0,
            transition = TransitionType.valueOf((root["transition"] as? String) ?: "CROSS_DISSOLVE"),
            transitionDurationSec = (root["transitionDuration"] as? Number)?.toDouble() ?: 0.45,
            aspect = AspectRatio.valueOf((root["aspect"] as? String) ?: "LANDSCAPE_16_9"),
            voice = decodeAudio(root["voice"]),
            music = decodeAudio(root["music"]),
            duckMusic = (root["duckMusic"] as? Boolean) ?: true,
            fitToVoice = (root["fitToVoice"] as? Boolean) ?: false,
            adjustments = Adjustments(
                brightness = (adj["brightness"] as? Number)?.toFloat() ?: 0f,
                contrast = (adj["contrast"] as? Number)?.toFloat() ?: 0f,
                saturation = (adj["saturation"] as? Number)?.toFloat() ?: 0f,
                vignette = (adj["vignette"] as? Number)?.toFloat() ?: 0f,
                blur = (adj["blur"] as? Number)?.toInt() ?: 0
            ),
            export = ExportConfig(
                quality = Quality.valueOf((exp["quality"] as? String) ?: "Q1080"),
                fps = (exp["fps"] as? Number)?.toInt() ?: 30,
                aspect = AspectRatio.valueOf((exp["aspect"] as? String) ?: "LANDSCAPE_16_9")
            ),
            junctionTransitions = junctions
        )
    }

    private fun decodeAudio(v: Any?): AudioConfig? {
        val m = v as? Map<String, Any?> ?: return null
        return AudioConfig(
            uri = m["uri"] as String,
            displayName = (m["name"] as? String) ?: "audio",
            durationSec = (m["dur"] as? Number)?.toDouble() ?: 0.0,
            volume = (m["vol"] as? Number)?.toFloat() ?: 1f,
            offsetSec = (m["off"] as? Number)?.toDouble() ?: 0.0,
            fadeInSec = (m["fi"] as? Number)?.toDouble() ?: 0.0,
            fadeOutSec = (m["fo"] as? Number)?.toDouble() ?: 0.0,
            loop = (m["loop"] as? Boolean) ?: false
        )
    }
}

/** Tiny recursive-descent JSON parser: objects, arrays, strings, numbers, booleans, null. */
class JsonReader(private val s: String) {
    private var pos = 0

    fun parseValue(): Any? {
        skipWs()
        return when (peek()) {
            '{' -> parseObject()
            '[' -> parseArray()
            '"' -> parseString()
            't', 'f' -> parseBool()
            'n' -> { pos += 4; null }
            else -> parseNumber()
        }
    }

    private fun parseObject(): Map<String, Any?> {
        expect('{')
        val map = LinkedHashMap<String, Any?>()
        skipWs()
        if (peek() == '}') { pos++; return map }
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            expect(':')
            map[key] = parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                '}' -> { pos++; return map }
                else -> throw IllegalArgumentException("Bad JSON at $pos")
            }
        }
    }

    private fun parseArray(): List<Any?> {
        expect('[')
        val list = ArrayList<Any?>()
        skipWs()
        if (peek() == ']') { pos++; return list }
        while (true) {
            list += parseValue()
            skipWs()
            when (peek()) {
                ',' -> pos++
                ']' -> { pos++; return list }
                else -> throw IllegalArgumentException("Bad JSON at $pos")
            }
        }
    }

    private fun parseString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            if (pos >= s.length) throw IllegalArgumentException("Unterminated string")
            val c = s[pos++]
            if (c == '"') return out.toString()
            if (c != '\\') { out.append(c); continue }
            if (pos >= s.length) throw IllegalArgumentException("Bad escape")
            val e = s[pos++]
            when (e) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'b' -> out.append(Char(8))
                'f' -> out.append(Char(12))
                'u' -> {
                    if (pos + 4 > s.length) throw IllegalArgumentException("Bad unicode escape")
                    out.append(s.substring(pos, pos + 4).toInt(16).toChar())
                    pos += 4
                }
                else -> out.append(e)
            }
        }
    }

    private fun parseBool(): Boolean {
        if (s.startsWith("true", pos)) { pos += 4; return true }
        if (s.startsWith("false", pos)) { pos += 5; return false }
        throw IllegalArgumentException("Bad boolean at $pos")
    }

    private fun parseNumber(): Any {
        val start = pos
        if (peek() == '-') pos++
        while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
        val token = s.substring(start, pos)
        return if (token.contains('.') || token.contains('e') || token.contains('E')) {
            token.toDouble()
        } else {
            token.toLong()
        }
    }

    private fun skipWs() {
        while (pos < s.length && s[pos].isWhitespace()) pos++
    }

    private fun peek(): Char = if (pos < s.length) s[pos] else Char(0)

    private fun expect(c: Char) {
        if (pos >= s.length || s[pos] != c) throw IllegalArgumentException("Expected $c at $pos")
        pos++
    }
}
