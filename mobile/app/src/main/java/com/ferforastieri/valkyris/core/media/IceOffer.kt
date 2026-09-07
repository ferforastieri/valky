package com.ferforastieri.valkyris.core.media

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

/** Gathering expiry is not a connection failure when local candidates exist. */
internal suspend fun awaitUsableIceOffer(
    gathered: Deferred<Unit>,
    timeoutMillis: Long = 8_000,
    localDescription: () -> String?,
): String {
    withTimeoutOrNull(timeoutMillis) { gathered.await() }
    val sdp = localDescription()
    if (sdp.isNullOrBlank() || sdp.lineSequence().none { it.trim().startsWith("a=candidate:") }) {
        throw IOException("Não foi possível obter um endereço de rede para conectar a câmera. Tente novamente.")
    }
    return sdp
}
