package com.swmansion.enriched.markdown.input.formatting

import com.swmansion.enriched.markdown.input.model.BlockRange
import com.swmansion.enriched.markdown.input.model.BlockType
import java.util.Collections

/**
 * Stores the block-level (paragraph-scoped) ranges for the editor, mirroring
 * [FormattingStore]. Unlike inline ranges, block ranges never overlap: at most
 * one block covers any given paragraph, and ranges are kept normalized to
 * whole-line boundaries.
 */
class BlockStore {
  private val ranges = mutableListOf<BlockRange>()

  val allRanges: List<BlockRange> get() = Collections.unmodifiableList(ranges)

  /**
   * Incoming ranges are trusted to be non-overlapping and line-scoped — the
   * parser owns that invariant (md4c block structure never overlaps at the same
   * nesting level, and nested containers are not yet mapped). Revisit
   * enforcement here if a container block type (list, blockquote) is added.
   */
  fun setRanges(newRanges: List<BlockRange>) {
    ranges.clear()
    ranges.addAll(newRanges.sortedBy { it.start })
  }

  fun clearAll() {
    ranges.clear()
  }

  fun blockRangeContaining(position: Int): BlockRange? = ranges.firstOrNull { position >= it.start && position < it.end }

  /**
   * Sets/replaces the block on every paragraph the given range touches, expanding
   * to whole-line boundaries within [text]. Removes any block previously covering
   * those paragraphs.
   */
  fun setBlock(
    type: BlockType,
    level: Int,
    paragraphStart: Int,
    paragraphEnd: Int,
    text: CharSequence,
  ) {
    val (start, end) = paragraphBounds(paragraphStart, paragraphEnd, text)
    removeBlocksOverlapping(start, end)
    // An anchored block (heading or list item) on an empty line is kept as a
    // zero-length anchor (see adjustForEdit); other blocks need real content, so an
    // empty line yields no block.
    if (end < start || (end == start && type !in BlockType.ANCHORED)) return

    val block = BlockRange(type, start, end, level)
    ranges.add(sortedInsertionIndex(start), block)
  }

  /**
   * Clears any block on the paragraphs the given range touches (reverting them to
   * the implicit paragraph default).
   */
  fun removeBlock(
    paragraphStart: Int,
    paragraphEnd: Int,
    text: CharSequence,
  ) {
    val (start, end) = paragraphBounds(paragraphStart, paragraphEnd, text)
    removeBlocksOverlapping(start, end)
  }

  /**
   * Shifts/clips block ranges to follow a text edit. Delegates the shift/clip
   * classification to [RangeEditAdjustment] but layers anchored-block (heading /
   * list item) persistence on top: the shared adjustment removes zero-length
   * ranges, while an emptied anchored line persists as a zero-length anchor (so
   * the line stays a heading / bullet).
   *
   * - Existing anchors are handled around the delegation: one exactly at the
   *   edit location stays put (the edit lands on its line and
   *   [normalizeToLineBounds] grows it over the typed text), ones past the edit
   *   shift with it, and one whose position was deleted goes with its line.
   * - An anchored block whose text is deleted exactly to its end (the deletion
   *   did not consume the line's newline, so the line itself survives) collapses
   *   to an anchor at the edit location instead of disappearing. A deletion
   *   running past the block's end removed the line, so the block is dropped.
   *
   * The view's prune/normalize pass reconciles the surviving anchors against
   * the final text.
   */
  fun adjustForEdit(
    editLocation: Int,
    deletedLength: Int,
    insertedLength: Int,
  ) {
    if (deletedLength == 0 && insertedLength == 0) return

    val deleteEnd = editLocation + deletedLength
    val delta = insertedLength - deletedLength

    val anchors = ranges.filter { it.length == 0 && it.type in BlockType.ANCHORED }
    ranges.removeAll { it.length == 0 }

    // At most one range can end exactly at deleteEnd, so this restores at most
    // one collapsed block.
    val collapsed =
      ranges.firstOrNull {
        it.type in BlockType.ANCHORED && it.start >= editLocation && it.end == deleteEnd
      }

    RangeEditAdjustment.adjustForEdit(ranges, editLocation, deletedLength, insertedLength)

    for (anchor in anchors) {
      when {
        anchor.start <= editLocation -> { /* keeps its position */ }

        anchor.start >= deleteEnd -> {
          anchor.start += delta
          anchor.end = anchor.start
        }

        else -> {
          continue // the anchor's line was deleted
        }
      }
      ranges.add(sortedInsertionIndex(anchor.start), anchor)
    }

    if (collapsed != null) {
      ranges.add(
        sortedInsertionIndex(editLocation),
        BlockRange(collapsed.type, editLocation, editLocation, collapsed.level),
      )
    }
  }

  /**
   * Re-normalizes every stored range back to the whole-line bounds of the line
   * containing its start. Call after [adjustForEdit] once [text] is final:
   * [adjustForEdit] deliberately leaves characters inserted at a range's start
   * or end outside the range (matching [FormattingStore]'s convention), and a
   * newline typed inside a range leaves it spanning two lines. Re-snapping to
   * line bounds re-absorbs edge-typed characters, clips a split range to its
   * first line (the text after the caret becomes a plain paragraph), and drops
   * blocks that a line-join landed on an earlier block's line (first wins).
   * On an empty line an anchored block (heading / list item) persists as a
   * zero-length anchor; any other collapsed range is dropped. Idempotent:
   * ranges already line-scoped are untouched.
   */
  fun normalizeToLineBounds(text: CharSequence) {
    if (ranges.isEmpty()) return

    var previousEnd = -1
    val iterator = ranges.listIterator()
    while (iterator.hasNext()) {
      val range = iterator.next()
      val (lineStart, lineEnd) = paragraphBounds(range.start, range.start, text)
      val isEmptyLine = lineEnd == lineStart
      if ((isEmptyLine && range.type !in BlockType.ANCHORED) || lineStart <= previousEnd) {
        iterator.remove()
        continue
      }
      range.start = lineStart
      range.end = lineEnd
      previousEnd = lineEnd
    }
  }

  /**
   * Drops any stored block overlapping `[start, end)` so a replacement can be
   * inserted cleanly. Blocks are line-scoped and never partially overlap, so a
   * touched block is removed wholesale. A zero-length anchor (an emptied heading
   * or list line, where `start == end`) is also dropped when it sits anywhere in
   * the paragraph bounds, so toggling a block off clears an empty anchored line.
   */
  private fun removeBlocksOverlapping(
    start: Int,
    end: Int,
  ) {
    ranges.removeAll { (it.end > start && it.start < end) || (it.length == 0 && it.start in start..end) }
  }

  /** Expands a selection to cover whole lines (line-scoped block boundaries). */
  private fun paragraphBounds(
    rangeStart: Int,
    rangeEnd: Int,
    text: CharSequence,
  ): Pair<Int, Int> {
    if (text.isEmpty()) return 0 to 0

    val clampedStart = rangeStart.coerceIn(0, text.length)
    val clampedEnd = rangeEnd.coerceIn(clampedStart, text.length)

    var lineStart = clampedStart
    while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--

    var lineEnd = clampedEnd
    while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++

    return lineStart to lineEnd
  }

  private fun sortedInsertionIndex(location: Int): Int {
    var index = 0
    for (existing in ranges) {
      if (existing.start > location) break
      index++
    }
    return index
  }
}
