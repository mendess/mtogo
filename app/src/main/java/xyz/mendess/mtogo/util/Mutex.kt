package xyz.mendess.mtogo.util

import kotlinx.coroutines.sync.withLock


class Mutex<T>(t: T) {
    data class MutexRef<T>(var t: T)

    private val mutex = kotlinx.coroutines.sync.Mutex()
    private val ref = MutexRef(t)

    suspend fun <R> read(f: (T) -> R): R = mutex.withLock {
        val r = f(ref.t)
        if (r === ref.t || r === ref) throw ObjectEscapingLock
        r
    }

    suspend fun <R> write(f: (MutexRef<T>) -> R): R = mutex.withLock {
        val r = f(ref)
        if (r === ref.t || r === ref) throw ObjectEscapingLock
        r
    }
}

data object ObjectEscapingLock: Exception() {
    private fun readResolve(): Any = ObjectEscapingLock
}