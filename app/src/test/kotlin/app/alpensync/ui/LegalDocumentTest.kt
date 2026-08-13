package app.alpensync.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LegalDocumentTest {

    @Test
    fun parse_splits_titles_headings_and_paragraphs() {
        val blocks = parseLegalMarkdown(
            """
            # Title here

            Intro sentence.

            ## Section
            Body one.
            Still body one.
            """.trimIndent(),
        )
        assertTrue(blocks[0] == LegalBlock.Title("Title here"))
        assertTrue(blocks[1] == LegalBlock.Paragraph("Intro sentence."))
        assertTrue(blocks[2] == LegalBlock.Heading("Section"))
        assertTrue(blocks[3] == LegalBlock.Paragraph("Body one. Still body one."))
    }

    @Test
    fun privacy_notice_states_no_telemetry_and_gpl() {
        val text = readRepoDoc("PRIVACY.md")
        assertTrue(text.contains("GPL-3.0-only"))
        assertTrue(text.contains("telemetry"))
        assertTrue(text.contains("proton.me"))
        assertTrue(parseLegalMarkdown(text).any { it is LegalBlock.Title })
    }

    @Test
    fun terms_defer_to_the_gpl_and_disclaim_warranty() {
        val text = readRepoDoc("TERMS.md")
        assertTrue(text.contains("GPL-3.0-only"))
        assertTrue(text.contains("AS IS"))
        assertTrue(text.contains("unofficial"))
        assertTrue(parseLegalMarkdown(text).any { it is LegalBlock.Title })
    }

    private fun readRepoDoc(name: String): String {
        val fromAppModule = File("..", name)
        val fromRoot = File(name)
        return when {
            fromAppModule.isFile -> fromAppModule.readText()
            fromRoot.isFile -> fromRoot.readText()
            else -> error("missing $name")
        }
    }
}
