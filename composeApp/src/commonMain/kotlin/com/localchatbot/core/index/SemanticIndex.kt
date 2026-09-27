package com.localchatbot.core.index

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Modelo y lógica **pura** del índice de embeddings del workspace (tool
 * `search_code_semantic`). Sin I/O ni red: el archivo lo maneja
 * [SemanticIndexStore] y los vectores los produce
 * [com.localchatbot.data.remote.EmbeddingsApi].
 *
 * ## Por qué un archivo y no SQLite
 *
 * No hay ruta de migración de esquema que funcione en este proyecto (cero `.sqm`, y el
 * driver de desktop nunca llama a `Schema.migrate()`), así que una tabla nueva llegaría
 * **solo a bases nuevas**, en silencio. El índice va a `~/.localchatbot/semantic-index/`
 * con el mismo patrón que `hooks.json` / `memory.md` / `tools.md`: un archivo por
 * workspace, regenerable, y si el formato cambia se descarta y se reindexa.
 *
 * ## Por qué los vectores van cuantizados a int8
 *
 * Un embedding típico son 768 floats. En JSON decimal eso es ~8 KB por chunk: un
 * workspace mediano (2.000 chunks) daría un archivo de ~15 MB que hay que reescribir
 * entero en cada reindexado. En NTFS con Defender escaneando eso se nota. Cuantizado a
 * int8 + base64 queda en ~1 KB por chunk (~2 MB de archivo). La pérdida de precisión es
 * irrelevante para *ordenar* candidatos, que es todo lo que hace la búsqueda.
 */

/** Versión del formato en disco. Si sube, el índice viejo se descarta y se reindexa. */
const val SEMANTIC_INDEX_VERSION: Int = 1

/** Trozo indexado: de dónde salió, un preview legible, y su vector cuantizado. */
@Serializable
data class IndexedChunk(
    /** Path relativo al workspace, con `/` como separador. */
    val path: String,
    /** Línea inicial, 1-based e inclusiva (lista para pasar a `read_file`). */
    val startLine: Int,
    /** Línea final, 1-based e inclusiva. */
    val endLine: Int,
    /** Primeras líneas del trozo, para que el resultado se entienda sin abrir el archivo. */
    val preview: String,
    /** Factor de dequantización (el mayor valor absoluto del vector original). */
    val scale: Float,
    /** Vector cuantizado a int8 y codificado en base64. Ver [dequantize]. */
    val vec: String
)

/**
 * Huella de un archivo ya indexado. Sirve para el reindexado **incremental**: si tamaño
 * y fecha coinciden, sus chunks se reutilizan sin volver a llamar al modelo de embeddings
 * (que es lo caro y lo que compite por memoria con el modelo de chat).
 */
@Serializable
data class FileStamp(
    val size: Long,
    val modifiedEpochMs: Long
)

@Serializable
data class SemanticIndexFile(
    val version: Int = SEMANTIC_INDEX_VERSION,
    /** Workspace absoluto que indexa este archivo (chequeo de sanidad ante colisión de hash). */
    val workspace: String,
    /** Modelo de embeddings usado. Si cambia, el índice entero deja de ser comparable. */
    val model: String,
    /** Dimensión de los vectores. Un cambio también invalida el índice. */
    val dims: Int,
    val indexedAtEpochMs: Long,
    /** relPath → huella, para el reindexado incremental. */
    val files: Map<String, FileStamp> = emptyMap(),
    val chunks: List<IndexedChunk> = emptyList()
)

/** Un trozo de texto antes de embeberlo. */
data class TextChunk(
    val startLine: Int,
    val endLine: Int,
    val text: String
)

/**
 * Parte [text] en trozos solapados de [maxLines] líneas.
 *
 * El solape ([overlapLines]) existe porque el corte es ciego: una función que empieza en
 * la última línea de un trozo quedaría descabezada en el siguiente y su embedding no
 * representaría nada. Con solape, cualquier tramo de hasta [overlapLines] líneas aparece
 * completo en al menos un trozo.
 *
 * Los trozos totalmente en blanco se descartan: embeber espacios en blanco gasta una
 * llamada al modelo y mete ruido en el ranking.
 */
fun chunkText(
    text: String,
    maxLines: Int = DEFAULT_CHUNK_LINES,
    overlapLines: Int = DEFAULT_CHUNK_OVERLAP
): List<TextChunk> {
    if (text.isBlank()) return emptyList()
    val safeMax = maxLines.coerceAtLeast(1)
    // El paso debe ser >= 1 o el bucle no avanza (overlap >= maxLines colgaría el indexado).
    val step = (safeMax - overlapLines).coerceAtLeast(1)
    val lines = text.lines()

    val chunks = mutableListOf<TextChunk>()
    var start = 0
    while (start < lines.size) {
        val end = minOf(start + safeMax, lines.size)
        val body = lines.subList(start, end).joinToString("\n")
        if (body.isNotBlank()) {
            chunks += TextChunk(startLine = start + 1, endLine = end, text = body)
        }
        // El último trozo ya llegó al final: cortar acá evita repetir la cola cuando
        // el archivo no es múltiplo exacto del paso.
        if (end >= lines.size) break
        start += step
    }
    return chunks
}

/** Vector cuantizado listo para persistir. */
data class QuantizedVector(val scale: Float, val base64: String)

/**
 * Cuantiza [vector] a int8. La escala es el mayor valor absoluto, así que el rango
 * completo [-127, 127] se aprovecha siempre, sea cual sea la magnitud del embedding.
 * Un vector todo-ceros devuelve escala 0 (y [dequantize] lo reconstruye como ceros).
 */
fun quantize(vector: FloatArray): QuantizedVector {
    if (vector.isEmpty()) return QuantizedVector(0f, "")
    var max = 0f
    for (v in vector) {
        val a = abs(v)
        if (a > max) max = a
    }
    if (max == 0f) return QuantizedVector(0f, base64Encode(ByteArray(vector.size)))
    val bytes = ByteArray(vector.size)
    for (i in vector.indices) {
        bytes[i] = (vector[i] / max * 127f).roundToInt().coerceIn(-127, 127).toByte()
    }
    return QuantizedVector(max, base64Encode(bytes))
}

/** Inversa de [quantize]. Devuelve null si el base64 está corrupto. */
fun dequantize(base64: String, scale: Float): FloatArray? {
    val bytes = base64Decode(base64) ?: return null
    val out = FloatArray(bytes.size)
    for (i in bytes.indices) {
        out[i] = bytes[i].toInt() * scale / 127f
    }
    return out
}

/**
 * Similitud coseno. Devuelve 0 si alguno de los vectores es nulo o si las dimensiones
 * no coinciden (índice de otro modelo colado por un cambio de configuración).
 */
fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
    if (a.isEmpty() || a.size != b.size) return 0f
    var dot = 0.0
    var na = 0.0
    var nb = 0.0
    for (i in a.indices) {
        val x = a[i].toDouble()
        val y = b[i].toDouble()
        dot += x * y
        na += x * x
        nb += y * y
    }
    if (na == 0.0 || nb == 0.0) return 0f
    return (dot / (sqrt(na) * sqrt(nb))).toFloat()
}

/**
 * Nombre de archivo determinista para el índice de [workspace]. Hash FNV-1a en hex —
 * no criptográfico, solo hace falta que sea estable y que no colisione entre las pocas
 * carpetas que un usuario indexa. El [SemanticIndexFile.workspace] guardado dentro
 * verifica la identidad real, así que una colisión se detecta al leer.
 */
fun workspaceIndexKey(workspace: String): String {
    var hash = 0x811C9DC5u
    for (ch in workspace) {
        hash = hash xor ch.code.toUInt()
        hash *= 0x01000193u
    }
    return hash.toString(16).padStart(8, '0')
}

// --- base64 (implementación propia) ---------------------------------------------------
// kotlin.io.encoding.Base64 sigue siendo experimental en Kotlin 2.1 y arrastraría un
// opt-in en todos los targets; son 20 líneas y así el archivo queda 100% puro y testeable
// suelto con kotlinc.

private const val B64_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

fun base64Encode(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val sb = StringBuilder((bytes.size + 2) / 3 * 4)
    var i = 0
    while (i < bytes.size) {
        val b0 = bytes[i].toInt() and 0xFF
        val b1 = if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xFF else 0
        val b2 = if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xFF else 0
        val triple = (b0 shl 16) or (b1 shl 8) or b2
        sb.append(B64_ALPHABET[(triple shr 18) and 0x3F])
        sb.append(B64_ALPHABET[(triple shr 12) and 0x3F])
        sb.append(if (i + 1 < bytes.size) B64_ALPHABET[(triple shr 6) and 0x3F] else '=')
        sb.append(if (i + 2 < bytes.size) B64_ALPHABET[triple and 0x3F] else '=')
        i += 3
    }
    return sb.toString()
}

fun base64Decode(text: String): ByteArray? {
    if (text.isEmpty()) return ByteArray(0)
    val clean = text.trimEnd('=')
    if (clean.any { B64_ALPHABET.indexOf(it) < 0 }) return null
    val out = ByteArray(clean.length * 3 / 4)
    var acc = 0
    var bits = 0
    var pos = 0
    for (ch in clean) {
        acc = (acc shl 6) or B64_ALPHABET.indexOf(ch)
        bits += 6
        if (bits >= 8) {
            bits -= 8
            out[pos++] = ((acc shr bits) and 0xFF).toByte()
        }
    }
    return out
}

/** ~40 líneas por trozo: suficiente para una función entera sin diluir el embedding. */
const val DEFAULT_CHUNK_LINES: Int = 40

/** Solape: una función corta partida por el corte aparece entera en el trozo siguiente. */
const val DEFAULT_CHUNK_OVERLAP: Int = 10

/** Máximo de líneas del preview que se devuelve al modelo por cada resultado. */
const val PREVIEW_LINES: Int = 8
