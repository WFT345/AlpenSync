package app.alpensync

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Offline proof for the plan Rules 5/19 containment policy (the live-test-1
 * crash fix): an unexpected failure inside a UI-facing step becomes an error
 * value instead of escaping as a crash; cancellation still propagates.
 */
class ContainUnexpectedTest {

    @Test fun success_passes_through_untouched() = runBlocking {
        val result = containUnexpected(onUnexpected = { -1 }) { 42 }
        assertEquals(42, result)
    }

    @Test fun unexpected_throwable_is_contained_not_rethrown() = runBlocking {
        // A RuntimeException stand-in for the raw retrofit2.HttpException
        // that crashed live test 1 past every typed catch.
        val result = containUnexpected(onUnexpected = { t -> "contained:${t.javaClass.simpleName}" }) {
            throw IllegalStateException("boom")
        }
        assertEquals("contained:IllegalStateException", result)
    }

    @Test fun errors_not_just_exceptions_are_contained() = runBlocking {
        val result = containUnexpected(onUnexpected = { "contained" }) {
            throw StackOverflowError("deep recursion")
        }
        assertEquals("contained", result)
    }

    @Test fun cancellation_still_propagates() {
        assertThrows(CancellationException::class.java) {
            runBlocking {
                containUnexpected(onUnexpected = { "swallowed" }) {
                    throw CancellationException("coroutine cancelled")
                }
            }
        }
    }
}
