package com.localchatbot.core.background

/**
 * En desktop el proceso JVM no se suspende al perder el foco, así que no hay
 * nada que mantener vivo: no-op.
 */
private object NoopBackgroundExecutor : BackgroundExecutor {
    override fun start(reason: String) {}
    override fun stop() {}
}

actual fun createBackgroundExecutor(): BackgroundExecutor = NoopBackgroundExecutor
