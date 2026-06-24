package com.swmansion.enriched.markdown.compose.style

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import org.junit.Assert.assertEquals
import org.junit.Test

class StyleUnitsTest {
  private val density = Density(density = 2f, fontScale = 1.5f)
  private val units = StyleUnits(density)

  @Test
  fun convertsSpUsingDensityAndFontScale() {
    val expected = with(density) { 16.sp.toPx() }
    assertEquals(expected, units.sp(16.sp), 0.01f)
  }

  @Test
  fun convertsDpUsingDensity() {
    assertEquals(16f, units.dp(8.dp), 0.01f)
  }

  @Test(expected = IllegalArgumentException::class)
  fun rejectsNonSpTextUnitForSpConversion() {
    units.sp(androidx.compose.ui.unit.TextUnit.Unspecified)
  }

  @Test
  fun mapsMediumFontWeightTo500() {
    assertEquals("500", FontWeight.Medium.toStyleWeight())
  }

  @Test
  fun mapsItalicFontStyleToEmphasisString() {
    assertEquals("italic", FontStyle.Italic.toEmphasisStyleString())
  }
}
