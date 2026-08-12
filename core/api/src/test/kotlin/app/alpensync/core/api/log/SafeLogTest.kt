// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 WFT345 and AlpenSync contributors

package app.alpensync.core.api.log

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeLogTest {

    @Test fun default_logcat_sink_never_throws_on_the_caller() {
        // android.util.Log is not mocked in local unit tests; the emit
        // wrapper must swallow that (on-device it forwards to logcat).
        SafeLog.log(SafeLog.Event.SERVER_CODE, 9001)
        SafeLog.log(SafeLog.Event.HUMAN_VERIFICATION_REQUIRED)
    }

    @Test fun replaced_sink_receives_event_and_detail() {
        val seen = mutableListOf<Pair<SafeLog.Event, Int?>>()
        val previous = SafeLog.sink
        SafeLog.sink = { event, detail -> seen.add(event to detail) }
        try {
            SafeLog.log(SafeLog.Event.SERVER_CODE, 2511)
            SafeLog.log(SafeLog.Event.TOKEN_REFRESH_FAILED)
        } finally {
            SafeLog.sink = previous
        }
        assertEquals(
            listOf(SafeLog.Event.SERVER_CODE to 2511, SafeLog.Event.TOKEN_REFRESH_FAILED to null),
            seen,
        )
    }
}
