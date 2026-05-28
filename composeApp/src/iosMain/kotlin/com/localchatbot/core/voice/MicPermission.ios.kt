package com.localchatbot.core.voice

import kotlinx.coroutines.suspendCancellableCoroutine
import platform.AVFAudio.AVAudioSession
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import kotlin.coroutines.resume

actual suspend fun requestVoicePermissions(): Boolean {
    val speechOk = requestSpeechAuthorization()
    if (!speechOk) return false
    return requestMicrophoneAuthorization()
}

private suspend fun requestSpeechAuthorization(): Boolean =
    suspendCancellableCoroutine { cont ->
        SFSpeechRecognizer.requestAuthorization { status: SFSpeechRecognizerAuthorizationStatus ->
            cont.resume(
                status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized
            )
        }
    }

private suspend fun requestMicrophoneAuthorization(): Boolean =
    suspendCancellableCoroutine { cont ->
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            cont.resume(granted)
        }
    }
