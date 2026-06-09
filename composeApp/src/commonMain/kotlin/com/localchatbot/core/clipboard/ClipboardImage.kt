package com.localchatbot.core.clipboard

/** Returns PNG bytes of the image currently in the system clipboard, or null if none. */
expect fun readClipboardImageBytes(): ByteArray?
