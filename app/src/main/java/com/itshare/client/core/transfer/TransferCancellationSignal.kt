package com.itshare.client.core.transfer

import java.util.concurrent.atomic.AtomicBoolean

object TransferCancellationSignal {
    private val cancelled = AtomicBoolean(false)

    fun reset() = cancelled.set(false)
    fun cancel() = cancelled.set(true)
    fun isCancelled(): Boolean = cancelled.get()
}
