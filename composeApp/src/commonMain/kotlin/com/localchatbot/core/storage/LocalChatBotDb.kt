package com.localchatbot.core.storage

import com.localchatbot.core.storage.db.EnumColumnAdapter
import com.localchatbot.core.storage.db.JsonColumnAdapter
import com.localchatbot.data.local.db.LocalChatBotDatabase
import com.localchatbot.data.local.db.Message
import com.localchatbot.data.local.db.Session
import com.localchatbot.domain.model.GenerationParams
import com.localchatbot.domain.model.MessageAttachment
import com.localchatbot.domain.model.PersistedToolCall
import com.localchatbot.domain.model.Role
import com.localchatbot.domain.model.TokenMetrics
import com.localchatbot.domain.model.WebSource
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

fun createLocalChatBotDatabase(json: Json): LocalChatBotDatabase = LocalChatBotDatabase(
    driver = DatabaseDriverFactory.create(),
    messageAdapter = Message.Adapter(
        roleAdapter = EnumColumnAdapter(Role::valueOf),
        attachmentsAdapter = JsonColumnAdapter(json, ListSerializer(MessageAttachment.serializer())),
        tool_callsAdapter = JsonColumnAdapter(json, ListSerializer(PersistedToolCall.serializer())),
        sourcesAdapter = JsonColumnAdapter(json, ListSerializer(WebSource.serializer())),
        metricsAdapter = JsonColumnAdapter(json, TokenMetrics.serializer())
    ),
    sessionAdapter = Session.Adapter(
        generation_paramsAdapter = JsonColumnAdapter(json, GenerationParams.serializer())
    )
)
