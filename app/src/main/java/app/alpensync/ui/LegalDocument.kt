package app.alpensync.ui

enum class LegalKind(val assetPath: String, val title: String) {
    PRIVACY("legal/privacy.md", "Privacy"),
    TERMS("legal/terms.md", "Terms"),
}

sealed interface LegalBlock {
    data class Title(val text: String) : LegalBlock
    data class Heading(val text: String) : LegalBlock
    data class Paragraph(val text: String) : LegalBlock
}

fun parseLegalMarkdown(raw: String): List<LegalBlock> {
    val out = ArrayList<LegalBlock>()
    val para = StringBuilder()
    fun flush() {
        val text = para.toString().trim()
        para.clear()
        if (text.isNotEmpty()) out += LegalBlock.Paragraph(text)
    }
    raw.lineSequence().forEach { line ->
        when {
            line.startsWith("## ") -> {
                flush()
                out += LegalBlock.Heading(line.removePrefix("## ").trim())
            }
            line.startsWith("# ") -> {
                flush()
                out += LegalBlock.Title(line.removePrefix("# ").trim())
            }
            line.isBlank() -> flush()
            else -> {
                if (para.isNotEmpty()) para.append(' ')
                para.append(line.trim())
            }
        }
    }
    flush()
    return out
}
