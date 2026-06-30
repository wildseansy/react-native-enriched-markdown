package com.swmansion.enriched.markdown.input.styles

import com.swmansion.enriched.markdown.input.model.BlockRange
import com.swmansion.enriched.markdown.input.model.BlockType
import com.swmansion.enriched.markdown.input.model.InputFormatterStyle
import com.swmansion.enriched.markdown.input.spans.InputBulletSpan
import com.swmansion.enriched.markdown.input.spans.InputListItemSpacingSpan
import com.swmansion.enriched.markdown.parser.MarkdownASTNode

/**
 * Block handler for unordered (bullet) list items. A single instance serves every
 * nesting depth: it reads the 0-based depth from each [BlockRange.level] (the generic
 * level payload), so depth — not [blockType] — drives the indent, marker glyph and
 * serialized indentation. Mirrors [HeadingBlockHandler], which reuses one instance
 * across all six heading levels.
 *
 * Unlike a heading, a list item [continuesOnNewline]: pressing Enter starts a new item
 * at the same depth (the orchestrator exits on an empty item).
 */
class UnorderedListBlockHandler : BlockHandler {
  override val blockType: BlockType = BlockType.UNORDERED_LIST_ITEM

  override val continuesOnNewline: Boolean = true

  override fun createSpans(
    blockRange: BlockRange,
    style: InputFormatterStyle,
  ): List<Any> {
    val spans = mutableListOf<Any>(InputBulletSpan(blockRange.level, style.displayDensity))
    // Leading spacing is a separate LineHeightSpan so it can be applied to only the
    // item's first character by the formatter; 0 means no extra spacing.
    if (style.listItemSpacingPx > 0) {
      spans.add(InputListItemSpacingSpan(style.listItemSpacingPx))
    }
    return spans
  }

  override fun spanClasses(): List<Class<*>> = listOf(InputBulletSpan::class.java, InputListItemSpacingSpan::class.java)

  /** `"- "` for a top-level item, indented two spaces per nesting depth (mirrors iOS). */
  override fun markdownLinePrefix(blockRange: BlockRange): String = "  ".repeat(blockRange.level.coerceAtLeast(0)) + "- "

  override fun matchesNodeType(
    nodeType: MarkdownASTNode.NodeType,
    node: MarkdownASTNode,
    outLevel: IntArray,
  ): Boolean {
    // Depth is derived from list-node nesting during the parse walk (see InputParser),
    // not from a node attribute, so a bare ListItem match records depth 0 here.
    if (nodeType != MarkdownASTNode.NodeType.ListItem) return false
    outLevel[0] = 0
    return true
  }
}
