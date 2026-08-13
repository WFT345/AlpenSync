// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 AlpenSync contributors

package app.alpensync.contacts.sync

import app.alpensync.contacts.vcard.ContactMerger.FieldConflict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conflict-log cell format (ADR 0006: per-field entries the M4 sync-log
 * viewer renders): closed alphabet, nulls for deleted sides, order preserved.
 */
class ConflictLogCodecTest {

    @Test fun empty_list_encodes_an_empty_array() {
        assertEquals("[]", ConflictLogCodec.encode(emptyList()))
    }

    @Test fun a_single_conflict_encodes_field_and_both_hashes() {
        val json = ConflictLogCodec.encode(listOf(FieldConflict("fn", localHash = "aa", serverHash = "bb")))
        assertEquals("[{\"f\":\"fn\",\"l\":\"aa\",\"s\":\"bb\"}]", json)
    }

    @Test fun a_deleted_side_encodes_null() {
        val json = ConflictLogCodec.encode(listOf(FieldConflict("notes", localHash = null, serverHash = "cc")))
        assertEquals("[{\"f\":\"notes\",\"l\":null,\"s\":\"cc\"}]", json)
    }

    @Test fun multiple_conflicts_preserve_order() {
        val json = ConflictLogCodec.encode(
            listOf(
                FieldConflict("emails", "1", "2"),
                FieldConflict("photo", "3", null),
            ),
        )
        assertEquals("[{\"f\":\"emails\",\"l\":\"1\",\"s\":\"2\"},{\"f\":\"photo\",\"l\":\"3\",\"s\":null}]", json)
    }
}
