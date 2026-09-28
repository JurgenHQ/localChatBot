package com.localchatbot.data.remote

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OllamaApiTest {

    private fun ps(json: String) = Json.parseToJsonElement(json) as JsonObject

    private val loaded = ps(
        """{"models":[
            {"name":"qwen2.5-coder:7b","model":"qwen2.5-coder:7b","context_length":16384},
            {"name":"llama3:latest","model":"llama3:latest","context_length":4096}
        ]}"""
    )

    @Test
    fun leeElContextoDelModeloCargado() {
        assertEquals(16384, OllamaApi.loadedContextLength(loaded, "qwen2.5-coder:7b"))
    }

    @Test
    fun ignoraElSufijoLatest() {
        assertEquals(4096, OllamaApi.loadedContextLength(loaded, "llama3"))
    }

    @Test
    fun unModeloNoCargadoDevuelveNull() {
        assertNull(OllamaApi.loadedContextLength(loaded, "mistral"))
        assertNull(OllamaApi.loadedContextLength(ps("""{"models":[]}"""), "llama3"))
        // Versiones viejas de Ollama no mandan context_length en /api/ps.
        assertNull(OllamaApi.loadedContextLength(ps("""{"models":[{"name":"llama3:latest"}]}"""), "llama3"))
    }

    @Test
    fun leeNumCtxDeLosParametros() {
        val parameters = "stop                           \"<|eot_id|>\"\nnum_ctx                        8192\ntemperature                    0.6"
        assertEquals(8192, OllamaApi.numCtxFromParameters(parameters))
    }

    @Test
    fun sinNumCtxDevuelveNull() {
        assertNull(OllamaApi.numCtxFromParameters("temperature 0.7\nnum_predict 128"))
        assertNull(OllamaApi.numCtxFromParameters(""))
    }
}
