package xyz.mendess.mtogo.util

import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
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