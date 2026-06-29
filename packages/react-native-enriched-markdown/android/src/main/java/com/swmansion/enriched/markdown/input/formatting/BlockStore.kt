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
    // A heading on an empty line is kept as a zero-length anchor (see adjustForEdit);
    // other blocks need real content, so an empty line yields no block.
    if (end < start || (end == start && type !in BlockType.HEADINGS)) return

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
   * Re-snaps every heading range to its line bounds `[lineStart, lineEnd)` (lineEnd
   * excluding the trailing newline). A heading is a single-paragraph block that spans
   * its whole line, so after a text edit this grows the block over text typed into the
   * line and shrinks it as text is removed — including collapsing to a zero-length
   * anchor on an emptied line (kept so the line stays a heading). A newline ends the
   * line, so a heading never bleeds onto the next paragraph. Other block types are
   * left untouched.
   */
  fun normalizeHeadingRangesToLines(text: CharSequence) {
    for (range in ranges) {
      if (range.type !in BlockType.HEADINGS) continue
      val anchor = range.start.coerceIn(0, text.length)
      var lineStart = anchor
      while (lineStart > 0 && text[lineStart - 1] != '\n') lineStart--
      var lineEnd = lineStart
      while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
      range.start = lineStart
      range.end = lineEnd
    }
    ranges.sortBy { it.start }
  }

  /**
   * Shifts/clips block ranges to follow a text edit, using the same overlap
   * classification shape as [FormattingStore.adjustForEdit].
   */
  fun adjustForEdit(
    editLocation: Int,
    deletedLength: Int,
    insertedLength: Int,
  ) {
    if (deletedLength == 0 && insertedLength == 0) return

    val deleteEnd = editLocation + deletedLength
    val indexesToRemove = mutableListOf<Int>()

    for ((idx, range) in ranges.withIndex()) {
      if (deletedLength > 0) {
        when (classifyOverlap(range.start, range.end, editLocation, deleteEnd)) {
          EditOverlap.BEFORE_EDIT -> { /* no change */ }

          EditOverlap.AFTER_EDIT -> {
            range.start = range.start - deletedLength + insertedLength
            range.end = range.end - deletedLength + insertedLength
          }

          EditOverlap.FULLY_DELETED -> {
            // A heading whose text is fully deleted persists as a zero-length block
            // anchored at the (now empty) line start, so the line stays a heading
            // until the user toggles it off or the line is merged/removed (those
            // text-aware decisions live in the view). Other blocks are dropped.
            if (range.type in BlockType.HEADINGS) {
              range.start = editLocation
              range.end = editLocation
            } else {
              indexesToRemove.add(idx)
            }
          }

          EditOverlap.DELETED_INSIDE -> {
            range.end = range.end - deletedLength + insertedLength
          }

          EditOverlap.CLIPPED_END -> {
            val newEnd = editLocation + insertedLength
            val newLength = if (newEnd > range.start) newEnd - range.start else 0
            range.end = range.start + newLength
            if (newLength == 0 && range.type !in BlockType.HEADINGS) indexesToRemove.add(idx)
          }

          EditOverlap.CLIPPED_START -> {
            val charsClipped = deleteEnd - range.start
            val newStart = editLocation + insertedLength
            val oldLength = range.length
            range.start = newStart
            range.end = newStart + oldLength - charsClipped
            if (range.length == 0 && range.type !in BlockType.HEADINGS) indexesToRemove.add(idx)
          }
        }
      } else {
        when {
          // A zero-length heading anchor (an emptied heading line) stays put on insert;
          // the view re-normalizes heading ranges to their line bounds afterwards, which
          // grows the block over text typed back into the line.
          range.length == 0 && range.type in BlockType.HEADINGS && range.start == editLocation -> {
            // anchor stays at editLocation; end is grown by the view's normalization
          }

          range.start >= editLocation -> {
            range.start += insertedLength
            range.end += insertedLength
          }

          editLocation < range.end -> {
            range.end += insertedLength
          }
        }
      }
    }

    for (idx in indexesToRemove.reversed()) {
      ranges.removeAt(idx)
    }

    // Keep zero-length heading anchors (an emptied heading line that should stay a
    // heading); drop every other collapsed range.
    ranges.removeAll { it.length == 0 && it.type !in BlockType.HEADINGS }
  }

  /**
   * Drops any stored block overlapping `[start, end)` so a replacement can be
   * inserted cleanly. Blocks are line-scoped and never partially overlap, so a
   * touched block is removed wholesale. A zero-length anchor (an emptied heading
   * line, where `start == end`) is also dropped when it sits anywhere in the
   * paragraph bounds, so toggling a heading off clears an empty heading line.
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

  private enum class EditOverlap {
    BEFORE_EDIT,
    AFTER_EDIT,
    FULLY_DELETED,
    DELETED_INSIDE,
    CLIPPED_END,
    CLIPPED_START,
  }

  private fun classifyOverlap(
    rangeStart: Int,
    rangeEnd: Int,
    editLocation: Int,
    deleteEnd: Int,
  ): EditOverlap {
    if (rangeEnd <= editLocation) return EditOverlap.BEFORE_EDIT
    if (rangeStart >= deleteEnd) return EditOverlap.AFTER_EDIT
    if (rangeStart >= editLocation && rangeEnd <= deleteEnd) return EditOverlap.FULLY_DELETED
    if (rangeStart < editLocation && rangeEnd > deleteEnd) return EditOverlap.DELETED_INSIDE
    return if (rangeStart < editLocation) EditOverlap.CLIPPED_END else EditOverlap.CLIPPED_START
  }
}
