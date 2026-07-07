package com.localchatbot.core.remote

actual fun createRemoteAccessServer(deps: RemoteAccessDeps): RemoteAccessServer =
    NoopRemoteAccessServer()

actual fun localIpAddresses(): List<String> = emptyList()
