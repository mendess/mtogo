package xyz.mendess.mtogo.util


inline fun <T> Result<T>.orelse(f: (Throwable) -> Nothing): T = fold(::identity) { f(it) }
inline fun <T, R> Result<T>.ret(f: (Result<R>) -> Nothing): T =
    fold(::identity) { f(Result.failure(it)) }

@Suppress("FunctionName")
fun <T> Ok(t: T): Result<T> = Result.success(t)

@Suppress("FunctionName")
fun <T> Err(t: Throwable): Result<T> = Result.failure(t)

fun Throwable.stackTraceToList(): ArrayList<String> {
    return iterateCauses()
        .map {
            it.stackTrace
                .asSequence()
                .map { line ->
                    "${line.className}.${line.methodName}(${line.fileName}:${line.lineNumber})"
                }
        }
        .flatten()
        .toCollection(ArrayList())
}

fun Throwable.fullMessage(): String {
    return iterateCauses().map { it.message ?: it.javaClass.simpleName }.joinToString(" caused by ")
}

private fun Throwable.iterateCauses(): Sequence<Throwable> {
    return generateSequence({ this }) { it.cause }
}