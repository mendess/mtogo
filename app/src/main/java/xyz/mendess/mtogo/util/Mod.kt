@file:Suppress("NAME_SHADOWING")

package xyz.mendess.mtogo.util

import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Semaphore
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

fun Boolean.toInt(): Int = if (this) 1 else 0

fun <T> identity(t: T): T = t

suspend fun <T> ListenableFuture<T>.await() = suspendCoroutine<T> { continuation ->
    addListener({
        @Suppress("BlockingMethodInNonBlockingContext")
        continuation.resume(get())
    }, MoreExecutors.directExecutor())
}

fun logThread(msg: String? = null) {
    val thread = Thread.currentThread()
    val stackTrace = thread.stackTrace
    val callerClass = stackTrace[3].className.split(".").last()
    val callerMethod = stackTrace[3].methodName

    Log.d(
        "dbg:$callerClass",
        "$callerClass::$callerMethod is running in thread ${thread.name}${msg?.let { " :: $it" }}"
    )
}

fun <T> T.dbg(msg: String? = null): T {
    val thread = Thread.currentThread()
    val stackTrace = thread.stackTrace
    val caller =
        stackTrace.asSequence().drop(3).find { !it.className.contains("ModKt") } ?: stackTrace[3]
    val callerClass = caller.className.split(".").last()
    val callerMethod = caller.methodName

    Log.d("dbg:$callerClass", "[$callerClass::$callerMethod] $this${msg?.let { " :: $it" } ?: ""}")
    return this
}

fun <T, R> Flow<T>.mapConcurrent(scope: CoroutineScope, size: UInt, f: suspend (T) -> R): Flow<R> {
    val size = size.toInt()
    val futures = ArrayDeque<Deferred<R>>(size)
    return flow {
        collect {
            if (futures.size == size) {
                val future = futures.removeFirst()
                emit(future.await())
            }
            futures.addLast(scope.async { f(it) })
        }
        while (futures.isNotEmpty()) {
            emit(futures.removeFirst().await())
        }
    }
}

inline fun <T> Result<T>.orelse(f: (Throwable) -> Nothing): T = fold(::identity) { f(it) }

inline fun <reified T : Parcelable> Bundle.parcelable(key: String): T? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelable(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelable(key) as T?
    }

inline fun <reified T : Parcelable> Bundle.parcelableList(key: String): ArrayList<T>? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getParcelableArrayList(key, T::class.java)
    } else {
        @Suppress("DEPRECATION")
        getParcelableArrayList(key)
    }

suspend inline fun <R> Semaphore.withPermits(n: UInt, f: () -> R): R {
    var acquired = 0U
    try {
        while (acquired < n) {
            acquire()
            acquired++
        }
        return f()
    } finally {
        while (acquired > 0U) {
            release()
            --acquired
        }
    }
}

inline fun <R> Boolean.then(f: () -> R): R? = if (this) f() else null