package com.swmansion.enriched.markdown.compose.style

import android.graphics.Typeface
import com.swmansion.enriched.markdown.utils.text.TypefaceUtils
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object ComposeFontRegistry {
  private val idGenerator = AtomicInteger()
  private val typefaceToKey =
    Collections.synchronizedMap(WeakHashMap<Typeface, String>())

  fun register(typeface: Typeface): String =
    typefaceToKey.getOrPut(typeface) {
      val key = "compose-font:${idGenerator.incrementAndGet()}"
      TypefaceUtils.registerComposeFont(key, typeface)
      key
    }
}
