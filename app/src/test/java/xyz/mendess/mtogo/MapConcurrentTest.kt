package xyz.mendess.mtogo

import junit.framework.TestCase
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.mendess.mtogo.util.mapConcurrent

/**
 * Example local unit test, which will execute on the development machine (host).
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
class MapConcurrentTest {
    @Test
    fun `mapConcurrent works with many buffer sizes`() {
        runBlocking {
            for (size in arrayOf(1U, 2U, 3U, 5U, 8U, 13U)) {
                val list = (0..<8).asFlow()
                    .mapConcurrent(scope = this, size = size) { it + 1 }
                    .toCollection(ArrayList())

                TestCase.assertEquals((1..<9).toList(), list)
            }
        }
    }
}