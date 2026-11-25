package com.meenai.mina.ui.viewer

/** There are several types of files that the built-in viewer can display; those that are not here are left to other apps. */
enum class ViewerKind { Html, Markdown, Pdf, Image, Text }

object ViewerKinds {
    private val images = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp", "heic", "heif")
    private val texts = setOf(
        "txt", "text", "log", "csv", "tsv", "json", "jsonl", "ndjson", "xml", "yaml", "yml", "toml", "ini", "conf", "cfg",
        "properties", "env", "kt", "kts", "java", "py", "js", "mjs", "ts", "tsx", "jsx", "sh", "bash", "zsh", "c", "h", "cpp",
        "hpp", "cc", "go", "rs", "rb", "php", "swift", "sql", "css", "scss", "gradle", "diff", "patch", "srt", "vtt",
    )
    private val imageMimes = images.map { if (it == "jpg") "image/jpeg" else "image/$it" }.toSet() + "image/x-ms-bmp"
    private val textMimes = setOf(
        "application/json", "application/xml", "application/javascript", "application/x-javascript", "application/x-yaml",
        "application/yaml", "application/x-sh", "application/toml", "application/sql",
    )

    /**
     * Recognize the MIME type given by the content provider, and look at the extension if it is not
     * recognized (empty, `application/octet-stream`, general `text/plain`). Clear HTML / Markdown / PDF
     * / image types are directly determined; `text/plain`'s.md is still displayed in Markdown. Neither
     * returns null.
     */
    fun of(mime: String?, name: String): ViewerKind? {
        val m = mime?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        when {
            m == "text/html" || m == "application/xhtml+xml" -> return ViewerKind.Html
            m == "text/markdown" || m == "text/x-markdown" -> return ViewerKind.Markdown
            m == "application/pdf" -> return ViewerKind.Pdf
            m in imageMimes -> return ViewerKind.Image
        }
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            ext == "html" || ext == "htm" || ext == "xhtml" -> ViewerKind.Html
            ext == "md" || ext == "markdown" -> ViewerKind.Markdown
            ext == "pdf" -> ViewerKind.Pdf
            ext in images -> ViewerKind.Image
            ext in texts -> ViewerKind.Text
            m.startsWith("image/") -> null
            m.startsWith("text/") || m in textMimes || m.endsWith("+json") || m.endsWith("+xml") -> ViewerKind.Text
            else -> null
        }
    }
}
