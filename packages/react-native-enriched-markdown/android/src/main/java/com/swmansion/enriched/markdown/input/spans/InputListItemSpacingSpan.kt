package com.swmansion.enriched.markdown.input.spans

import android.graphics.Paint
import android.text.style.LineHeightSpan
import com.swmansion.enriched.markdown.input.formatting.MarkdownSpan

/**
 * Adds [spacingPx] of vertical space above a bullet list item so items read as
 * separate rows (the Android counterpart to iOS `paragraphSpacingBefore`). Tagged
 * [MarkdownSpan] so the block formatter cleans it up alongside the bullet span.
 *
 * Apply it to only the first character of the item's line so it affects just the
 * item's first visual line, not wrapped continuations: [chooseHeight] runs per
 * visual line, and only the first one intersects that single-char range.
 */
class InputListItemSpacingSpan(
  val spacingPx: Int,
) : LineHeightSpan,
  MarkdownSpan {
  override fun chooseHeight(
    text: CharSequence?,
    start: Int,
    end: Int,
    spanstartv: Int,
    lineHeight: Int,
    fm: Paint.FontMetricsInt?,
  ) {
    if (fm == null || spacingPx <= 0) return
    fm.ascent -= spacingPx
    fm.top -= spacingPx
  }
}
