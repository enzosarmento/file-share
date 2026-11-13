package br.edu.fileshare.core.concurrency

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock as jvmWithLock

actual class PlatformLock actual constructor() {
    private val delegate = ReentrantLock()

    actual fun <T> withLock(block: () -> T): T = delegate.jvmWithLock(block)
}

