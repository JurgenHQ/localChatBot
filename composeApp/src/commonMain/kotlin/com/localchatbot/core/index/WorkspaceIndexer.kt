package com.localchatbot.core.index

import com.localchatbot.data.remote.EmbeddingsApi
import com.localchatbot.domain.repository.ModelRepository
import com.localchatbot.domain.repository.PreferencesRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

/**
 * Construye y consulta el índice de embeddings del workspace. Es el motor de la tool
 * `search_code_semantic`; la lógica pura (troceado, cuantización, coseno) vive en
 * [SemanticIndex] y el disco en [SemanticIndexStore].
 *
 * ## Cuándo se indexa
 *
 * **Solo bajo demanda**, la primera vez que se busca en un workspace, y de forma
 * **incremental** después (los archivos cuyo tamaño y fecha no cambiaron reutilizan sus
 * vectores). No hay indexado al arrancar ni al cambiar de workspace a propósito: embeber
 * un repo entero son cientos de llamadas al servidor, y el modelo de embeddings **compite
 * por memoria con el de chat** en LM Studio. Hacerlo sin que el usuario lo pida podría
 * expulsar de memoria el modelo con el que está conversando.
 *
 * Por lo mismo, cuando no hay modelo de embeddings disponible la tool **degrada con un
 * mensaje claro** ("no hay modelo de embeddings; usá search_files") en vez de fallar en
 * seco o intentar cargarlo.
 */
class WorkspaceIndexer(
    private val store: SemanticIndexStore,
    private val embeddings: EmbeddingsApi,
    private val prefs: PreferencesRepository,
    private val models: ModelRepository,
    private val json: Json
) {

    /** Un solo indexado a la vez: son cientos de llamadas al modelo local, no se paralelizan. */
    private val mutex = Mutex()

    /** Índice en memoria del último workspace consultado, para no releer el archivo por búsqueda. */
    private var cached: SemanticIndexFile? = null

    sealed interface IndexResult {
        data class Ok(
            val chunks: Int,
            val files: Int,
            /** Archivos re-embebidos en esta pasada (0 = el índice ya estaba al día). */
            val embedded: Int,
            val model: String
        ) : IndexResult

        data class Failed(val message: String) : IndexResult
    }

    data class SearchHit(
        val path: String,
        val startLine: Int,
        val endLine: Int,
        val preview: String,
        val score: Float
    )

    /**
     * Resuelve el modelo de embeddings a usar: el configurado en preferencias, o el primero
     * de la lista del servidor cuyo id contenga "embed" (convención de facto de LM Studio,
     * Ollama y los repos de HuggingFace). Null si no hay ninguno.
     */
    suspend fun resolveModel(): String? {
        val configured = prefs.current().embeddingsModel.trim()
        if (configured.isNotEmpty()) return configured
        val baseUrl = prefs.current().connection.baseUrl()
        val available = models.listModels(baseUrl).getOrNull().orEmpty()
        return available.firstOrNull { it.contains("embed", ignoreCase = true) }
    }

    /**
     * Asegura que el índice de [workspace] está al día y lo devuelve.
     *
     * Reutiliza los chunks de los archivos cuya huella (tamaño + fecha) no cambió, así que
     * la segunda búsqueda del día solo re-embebe lo que tocaste. Con [force] se descarta el
     * índice existente y se reindexa todo.
     */
    suspend fun ensureIndex(workspace: String, force: Boolean = false): IndexResult = mutex.withLock {
        if (!store.isAvailable) return IndexResult.Failed("El índice semántico solo está disponible en desktop.")

        val model = resolveModel()
            ?: return IndexResult.Failed(
                "No hay modelo de embeddings disponible. Cargá uno en el servidor (p. ej. " +
                    "nomic-embed-text en LM Studio) o configuralo en Ajustes → Modelo de embeddings. " +
                    "Mientras tanto usá `search_files`, que busca por texto sin necesitar embeddings."
            )
        val baseUrl = prefs.current().connection.baseUrl()

        val existing = if (force) null else loadIndex(workspace, model)
        val previousChunks = existing?.chunks?.groupBy { it.path }.orEmpty()
        val previousStamps = existing?.files.orEmpty()

        val files = store.collectFiles(workspace)
        if (files.isEmpty()) {
            return IndexResult.Failed("No se encontraron archivos indexables en $workspace")
        }

        val chunks = mutableListOf<IndexedChunk>()
        val stamps = mutableMapOf<String, FileStamp>()
        var embeddedFiles = 0
        var dims = existing?.dims ?: 0

        for (file in files) {
            val stamp = FileStamp(size = file.size, modifiedEpochMs = file.modifiedEpochMs)
            val cached = previousChunks[file.relPath]
            if (cached != null && previousStamps[file.relPath] == stamp) {
                chunks += cached
                stamps[file.relPath] = stamp
                continue
            }

            val text = store.readText(file.absPath) ?: continue
            val pieces = chunkText(text)
            if (pieces.isEmpty()) {
                // Archivo vacío o en blanco: se registra la huella igual, para no releerlo
                // en cada pasada.
                stamps[file.relPath] = stamp
                continue
            }

            // Se embebe en lotes: una llamada por trozo multiplicaría el overhead HTTP, y
            // un solo request con el archivo entero puede pasarse del límite del servidor.
            for (batch in pieces.chunked(EMBED_BATCH)) {
                val vectors = embeddings.embed(baseUrl, model, batch.map { it.text })
                    .getOrElse { err ->
                        return IndexResult.Failed(
                            "Falló el modelo de embeddings ($model): ${err.message ?: "error desconocido"}. " +
                                "Verificá que esté cargado en el servidor. Mientras tanto usá `search_files`."
                        )
                    }
                for ((piece, vector) in batch.zip(vectors)) {
                    if (vector.isEmpty()) continue
                    if (dims == 0) dims = vector.size
                    // Un vector de otra dimensión significa que el servidor cambió de modelo
                    // a mitad del indexado; mezclarlos daría rankings sin sentido.
                    if (vector.size != dims) {
                        return IndexResult.Failed(
                            "El servidor devolvió vectores de dimensiones distintas ($dims vs ${vector.size}). " +
                                "Reindexá con force=true tras fijar un único modelo de embeddings."
                        )
                    }
                    val q = quantize(vector)
                    chunks += IndexedChunk(
                        path = file.relPath,
                        startLine = piece.startLine,
                        endLine = piece.endLine,
                        preview = piece.text.lines().take(PREVIEW_LINES).joinToString("\n"),
                        scale = q.scale,
                        vec = q.base64
                    )
                }
            }
            stamps[file.relPath] = stamp
            embeddedFiles++
        }

        if (chunks.isEmpty()) {
            return IndexResult.Failed("No se pudo indexar ningún contenido en $workspace")
        }

        val index = SemanticIndexFile(
            workspace = workspace,
            model = model,
            dims = dims,
            indexedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
            files = stamps,
            chunks = chunks
        )
        // Un fallo de escritura no invalida la búsqueda de este turno (el índice está en
        // memoria); solo significa que la próxima vez habrá que reindexar.
        store.save(workspace, json.encodeToString(SemanticIndexFile.serializer(), index))
        cached = index

        return IndexResult.Ok(
            chunks = chunks.size,
            files = stamps.size,
            embedded = embeddedFiles,
            model = model
        )
    }

    /**
     * Busca [query] en el índice de [workspace]. Devuelve los [limit] trozos más parecidos,
     * de mayor a menor similitud. Asume que [ensureIndex] ya corrió en este turno.
     */
    suspend fun search(workspace: String, query: String, limit: Int): Result<List<SearchHit>> {
        val index = cached?.takeIf { it.workspace == workspace }
            ?: loadIndex(workspace, null)
            ?: return Result.failure(IllegalStateException("No hay índice para $workspace"))

        // La consulta se embebe con el MISMO modelo que construyó el índice (no con el que
        // esté configurado ahora): vectores de modelos distintos no son comparables.
        val baseUrl = prefs.current().connection.baseUrl()
        val queryVector = embeddings.embed(baseUrl, index.model, listOf(query))
            .map { it.firstOrNull() }
            .getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("El servidor no devolvió embedding para la consulta"))

        if (queryVector.size != index.dims) {
            return Result.failure(
                IllegalStateException(
                    "El modelo devolvió ${queryVector.size} dimensiones y el índice tiene ${index.dims}. " +
                        "Reindexá con force=true."
                )
            )
        }

        val hits = index.chunks.mapNotNull { chunk ->
            val vector = dequantize(chunk.vec, chunk.scale) ?: return@mapNotNull null
            val score = cosineSimilarity(queryVector, vector)
            if (score < MIN_SCORE) null
            else SearchHit(chunk.path, chunk.startLine, chunk.endLine, chunk.preview, score)
        }
            .sortedByDescending { it.score }
            .take(limit)

        return Result.success(hits)
    }

    /**
     * Lee el índice de disco descartándolo si quedó obsoleto: versión de formato distinta,
     * otro workspace (colisión de hash del nombre de archivo) u otro modelo de embeddings
     * — los vectores de dos modelos distintos no son comparables entre sí.
     */
    private fun loadIndex(workspace: String, expectedModel: String?): SemanticIndexFile? {
        val raw = store.load(workspace) ?: return null
        val parsed = runCatching {
            json.decodeFromString(SemanticIndexFile.serializer(), raw)
        }.getOrNull() ?: return null
        if (parsed.version != SEMANTIC_INDEX_VERSION) return null
        if (parsed.workspace != workspace) return null
        if (expectedModel != null && parsed.model != expectedModel) return null
        cached = parsed
        return parsed
    }

    private companion object {
        /** Trozos por request al servidor de embeddings. */
        const val EMBED_BATCH = 16

        /**
         * Piso de similitud. Sin él, una consulta sin nada parecido en el repo devuelve
         * igual los N trozos "menos malos" y el modelo los toma por respuesta.
         */
        const val MIN_SCORE = 0.25f
    }
}
