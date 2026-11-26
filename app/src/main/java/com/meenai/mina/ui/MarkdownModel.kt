package com.meenai.mina.ui

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlBlock
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser
import com.meenai.mina.ui.theme.LightColors
import com.meenai.mina.ui.theme.MinaColors

/** The display model parsed by Markdown in the answer: block-level structure + styled inline text. Pure data, does not rely on interfaces. */
@Immutable
sealed interface MdBlock {
    @Immutable data class Para(val text: AnnotatedString) : MdBlock
    @Immutable data class Heading(val level: Int, val text: AnnotatedString) : MdBlock
    @Immutable data class Code(val info: String, val code: String) : MdBlock
    @Immutable data class Quote(val blocks: List<MdBlock>) : MdBlock
    /** [start] is null is an unordered list; each list item is a set of blocks. */
    @Immutable data class ListBlock(val start: Int?, val tight: Boolean, val items: List<List<MdBlock>>) : MdBlock
    /** The number of cells in each row is completed to the number of columns in the header. */
    @Immutable data class Table(val header: List<AnnotatedString>, val rows: List<List<AnnotatedString>>) : MdBlock
    @Immutable data class Image(val destination: String, val alt: String) : MdBlock
    @Immutable data object Rule : MdBlock
}

object MarkdownModel {
    /** No matter how deeply nested the references or lists are, they will be displayed as plain text to avoid recursion too deep. */
    private const val MAX_DEPTH = 12
    private const val PARSE_CACHE_LIMIT = 160
    private const val STREAM_CACHE_LIMIT = 32
    private const val CACHE_MAX_MARKDOWN_LEN = 48_000
    private val LINK_SCHEMES = setOf("http", "https", "mailto", "tel")

    /** The style of inline code and links; the color follows the dark and light colors, and is given by the current color matching where it is displayed. */
    @Immutable
    data class Styles(val code: SpanStyle, val link: TextLinkStyles) {
        companion object {
            fun of(c: MinaColors) = Styles(
                code = SpanStyle(fontFamily = FontFamily.Monospace, background = c.fill),
                link = TextLinkStyles(SpanStyle(color = c.accent)),
            )

            val Light = of(LightColors)
        }
    }

    private data class ParseKey(val markdown: String, val styles: Styles)
    private val cacheLock = Any()
    private val parseCache = object : LinkedHashMap<ParseKey, List<MdBlock>>(PARSE_CACHE_LIMIT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ParseKey, List<MdBlock>>?): Boolean =
            size > PARSE_CACHE_LIMIT
    }
    private val streamCache = object : LinkedHashMap<ParseKey, List<MdBlock>>(STREAM_CACHE_LIMIT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ParseKey, List<MdBlock>>?): Boolean =
            size > STREAM_CACHE_LIMIT
    }
    private val plainCache = object : LinkedHashMap<String, String>(PARSE_CACHE_LIMIT + 1, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > PARSE_CACHE_LIMIT
    }

    private val parser: Parser = Parser.builder()
        .extensions(listOf(TablesExtension.create(), StrikethroughExtension.create()))
        .build()

    /** When parsing fails (including any accidents caused by half-written streaming text), the entire paragraph is displayed as plain text. */
    fun parse(markdown: String, styles: Styles = Styles.Light): List<MdBlock> {
        if (markdown.isEmpty()) return emptyList()
        val key = if (markdown.length <= CACHE_MAX_MARKDOWN_LEN) ParseKey(markdown, styles) else null
        if (key != null) {
            synchronized(cacheLock) {
                parseCache[key]?.let { return it }
            }
        }
        val computed = try {
            blocks(parser.parse(markdown), 0, styles)
        } catch (e: Exception) {
            plain(markdown)
        } catch (e: StackOverflowError) {
            plain(markdown)
        }
        if (key != null) {
            synchronized(cacheLock) {
                parseCache[key] = computed
            }
        }
        return computed
    }

    internal fun cachedStreaming(markdown: String, styles: Styles = Styles.Light): List<MdBlock>? {
        if (markdown.isEmpty()) return emptyList()
        if (markdown.length > CACHE_MAX_MARKDOWN_LEN) return null
        val key = ParseKey(markdown, styles)
        return synchronized(cacheLock) { streamCache[key] }
    }

    /** Streaming snippets can be stopped in the middle of the grammar; only the tail is completed when displayed, and the actual answer is not modified. */
    fun parseStreaming(markdown: String, styles: Styles = Styles.Light): List<MdBlock> {
        if (markdown.isEmpty()) return emptyList()
        val key = if (markdown.length <= CACHE_MAX_MARKDOWN_LEN) ParseKey(markdown, styles) else null
        if (key != null) {
            synchronized(cacheLock) {
                streamCache[key]?.let { return it }
            }
        }
        val computed = parse(StreamingMarkdown.preview(markdown), styles)
        if (key != null) {
            synchronized(cacheLock) {
                streamCache[key] = computed
            }
        }
        return computed
    }

    /**
     * Answer the plain text after removing the Markdown symbols (for copying and quoting): paragraphs,
     * titles, and codes are as original text, with a blank line between each block; each item in the
     * list is on one line, preceded by "•" or "1."; each line of quotation is preceded by ">"; each line
     * of the table is one line, and tabs are used between cells; no separators are required.
     */
    fun toPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val cacheable = markdown.length <= CACHE_MAX_MARKDOWN_LEN
        if (cacheable) {
            synchronized(cacheLock) {
                plainCache[markdown]?.let { return it }
            }
        }
        val computed = blocksText(parse(markdown)).trim()
        if (cacheable) {
            synchronized(cacheLock) {
                plainCache[markdown] = computed
            }
        }
        return computed
    }

    private fun blocksText(blocks: List<MdBlock>, sep: String = "\n\n"): String = blocks.mapNotNull { b ->
        when (b) {
            is MdBlock.Para -> b.text.text
            is MdBlock.Heading -> b.text.text
            is MdBlock.Code -> b.code
            is MdBlock.Quote -> blocksText(b.blocks).lines().joinToString("\n") { "> $it".trimEnd() }
            is MdBlock.ListBlock -> b.items.mapIndexed { i, item ->
                val mark = if (b.start == null) "• " else "${b.start + i}. "
                mark + blocksText(item, "\n").lines().joinToString("\n" + " ".repeat(mark.length))
            }.joinToString("\n")
            is MdBlock.Table -> (listOf(b.header) + b.rows).joinToString("\n") { r -> r.joinToString("\t") { it.text } }
            is MdBlock.Image -> b.alt.takeIf { it.isNotBlank() } ?: b.destination
            MdBlock.Rule -> null
        }?.takeIf { it.isNotBlank() }
    }.joinToString(sep)

    private fun plain(s: String): List<MdBlock> = if (s.isBlank()) emptyList() else listOf(MdBlock.Para(AnnotatedString(s.trim())))

    private fun children(n: Node): Sequence<Node> = generateSequence(n.firstChild) { it.next }

    private fun blocks(parent: Node, depth: Int, s: Styles): List<MdBlock> = children(parent).flatMap { blockList(it, depth, s) }.toList()

    private fun blockList(n: Node, depth: Int, s: Styles): List<MdBlock> {
        if (depth > MAX_DEPTH) return listOf(MdBlock.Para(AnnotatedString(plainText(n))))
        return when (n) {
            is Paragraph -> paragraphBlocks(n, s)
            is org.commonmark.node.Heading -> listOf(MdBlock.Heading(n.level, inlines(n, s)))
            is FencedCodeBlock -> listOf(MdBlock.Code(n.info.orEmpty().trim(), n.literal.orEmpty().trimEnd('\n')))
            is IndentedCodeBlock -> listOf(MdBlock.Code("", n.literal.orEmpty().trimEnd('\n')))
            is HtmlBlock -> listOf(MdBlock.Para(AnnotatedString(n.literal.orEmpty().trimEnd('\n'))))
            is BlockQuote -> listOf(MdBlock.Quote(blocks(n, depth + 1, s)))
            is ListBlock -> listOf(MdBlock.ListBlock(
                start = (n as? OrderedList)?.let { it.markerStartNumber ?: 1 },
                tight = n.isTight,
                items = children(n).filterIsInstance<ListItem>().map { blocks(it, depth + 1, s) }.toList(),
            ))
            is TableBlock -> listOf(table(n, s))
            is ThematicBreak -> listOf(MdBlock.Rule)
            is LinkReferenceDefinition -> emptyList()
            else -> plainText(n).takeIf { it.isNotBlank() }?.let { listOf(MdBlock.Para(AnnotatedString(it))) } ?: emptyList()
        }
    }

    private fun paragraphBlocks(p: Paragraph, s: Styles): List<MdBlock> {
        val hasImage = children(p).any { it is Image }
        if (!hasImage) {
            val t = inlines(p, s)
            return if (t.isNotEmpty()) listOf(MdBlock.Para(t)) else emptyList()
        }
        val result = mutableListOf<MdBlock>()
        val currentInlines = mutableListOf<Node>()

        fun flushInlines() {
            if (currentInlines.isEmpty()) return
            val t = buildAnnotatedString {
                for (node in currentInlines) appendInlineNode(node, s)
            }
            if (t.text.isNotBlank()) {
                result.add(MdBlock.Para(t))
            }
            currentInlines.clear()
        }

        for (node in children(p)) {
            if (node is Image) {
                while (currentInlines.isNotEmpty() && (currentInlines.last() is SoftLineBreak || currentInlines.last() is HardLineBreak)) {
                    currentInlines.removeAt(currentInlines.size - 1)
                }
                flushInlines()
                val dest = node.destination.orEmpty()
                val alt = plainText(node)
                result.add(MdBlock.Image(dest, alt))
            } else {
                if (currentInlines.isEmpty() && result.lastOrNull() is MdBlock.Image && (node is SoftLineBreak || node is HardLineBreak)) {
                    continue
                }
                currentInlines.add(node)
            }
        }
        flushInlines()
        return result
    }

    private fun table(t: TableBlock, s: Styles): MdBlock.Table {
        val rows = mutableListOf<List<AnnotatedString>>()
        var header: List<AnnotatedString> = emptyList()
        for (section in children(t)) for (row in children(section).filterIsInstance<TableRow>()) {
            val cells = children(row).filterIsInstance<TableCell>().map { inlines(it, s) }.toList()
            if (section is TableHead && header.isEmpty()) header = cells else rows += cells
        }
        val cols = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0)
        fun pad(r: List<AnnotatedString>) = r + List(cols - r.size) { AnnotatedString("") }
        return MdBlock.Table(pad(header), rows.map(::pad))
    }

    private fun inlines(parent: Node, s: Styles): AnnotatedString = buildAnnotatedString { appendInlines(parent, s) }

    private fun AnnotatedString.Builder.appendInlines(parent: Node, s: Styles) {
        for (n in children(parent)) appendInlineNode(n, s)
    }

    private fun AnnotatedString.Builder.appendInlineNode(n: Node, s: Styles) {
        when (n) {
            is Text -> append(n.literal)
            is SoftLineBreak, is HardLineBreak -> append('\n')
            is Code -> withStyle(s.code) { append(n.literal) }
            is Emphasis -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInlines(n, s) }
            is StrongEmphasis -> withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { appendInlines(n, s) }
            is Strikethrough -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInlines(n, s) }
            is Link -> link(n.destination, s) { appendInlines(n, s) }
            // Do not display pictures in the chat, only leave alternative text (if there is an address
            // that can be opened, make it a link)
            is Image -> link(n.destination, s) { if (n.firstChild != null) appendInlines(n, s) else append(n.destination.orEmpty()) }
            is HtmlInline -> append(n.literal)
            else -> appendInlines(n, s)
        }
    }

    private inline fun AnnotatedString.Builder.link(dest: String?, s: Styles, content: AnnotatedString.Builder.() -> Unit) {
        val url = dest?.trim().orEmpty()
        if (url.substringBefore(':', "").lowercase() in LINK_SCHEMES) withLink(LinkAnnotation.Url(url, s.link)) { content() } else content()
    }

    private fun plainText(n: Node): String = buildString {
        fun walk(x: Node) {
            when (x) {
                is Text -> append(x.literal)
                is Code -> append(x.literal)
                is FencedCodeBlock -> append(x.literal)
                is IndentedCodeBlock -> append(x.literal)
                is HtmlBlock -> append(x.literal)
                is HtmlInline -> append(x.literal)
                is SoftLineBreak, is HardLineBreak -> append('\n')
                else -> {
                    children(x).forEach(::walk)
                    if (x is Paragraph || x is org.commonmark.node.Heading) append('\n')
                }
            }
        }
        walk(n)
    }.trim()
}
