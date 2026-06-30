package com.swmansion.enriched.markdown.input.model

/**
 * Block-level (paragraph-scoped) element types. A block covers whole lines,
 * unlike inline styles ([StyleType]) which cover character ranges.
 *
 * PARAGRAPH is the default: every line is a paragraph until a block handler
 * claims it. Concrete block types (headings, list items, etc.) are appended
 * here as their handlers are added — each new case must have a matching
 * [com.swmansion.enriched.markdown.input.styles.BlockHandler] registered in
 * [com.swmansion.enriched.markdown.input.formatting.InputFormatter].
 */
enum class BlockType {
  PARAGRAPH,
  HEADING_1,
  HEADING_2,
  HEADING_3,
  HEADING_4,
  HEADING_5,
  HEADING_6,
  UNORDERED_LIST_ITEM,
  ;

  companion object {
    /** The six heading block types, indexable by level via [forHeadingLevel]. */
    val HEADINGS: List<BlockType> =
      listOf(HEADING_1, HEADING_2, HEADING_3, HEADING_4, HEADING_5, HEADING_6)

    /**
     * Block types whose empty line persists as a zero-length anchor (rather than
     * reverting to a plain paragraph) until the user toggles them off. Headings keep
     * their level on an emptied line; a bullet keeps its marker on an emptied item.
     * Used by [com.swmansion.enriched.markdown.input.formatting.BlockStore] so the
     * anchor logic is shared instead of duplicated per block kind.
     */
    val ANCHORED: Set<BlockType> = HEADINGS.toSet() + UNORDERED_LIST_ITEM

    /**
     * Maps an H-level (1-6) to its [BlockType], or null when out of range. Used by
     * the parser to turn an AST heading node's `level` attribute into a block type.
     */
    fun forHeadingLevel(level: Int): BlockType? = HEADINGS.getOrNull(level - 1)
  }
}

/** Maximum bullet-list nesting depth (0-based), so indent can't run away unbounded. */
const val MAX_LIST_DEPTH = 5
