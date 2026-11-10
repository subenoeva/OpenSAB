package com.opensab.cluster.text

import com.opensab.domain.weather.WmoCaption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit test for [ClusterPresentation], verifying that it bundles [ClusterTextResolver]
 * and [WeatherCaptionResolver] into a single presentation port without loss of resolution.
 */
class ClusterPresentationTest {

    @Test
    fun resolvesTextAndCaptionsThroughBundledResolvers() {
        val textResolver = ClusterTextResolver { token -> "resolved:${token.name}" }
        val captionResolver = WeatherCaptionResolver { caption -> "caption:${caption.name}" }

        val presentation = ClusterPresentation(
            text = textResolver,
            captions = captionResolver
        )

        assertEquals("resolved:HEADER_WEATHER", presentation.text.resolve(ClusterToken.HEADER_WEATHER))
        assertEquals("caption:CLEAR", presentation.captions.resolve(WmoCaption.CLEAR))
    }

    @Test
    fun englishPresentationTestDoubleResolvesExpectedStrings() {
        assertEquals("Loading", englishPresentation.text.resolve(ClusterToken.LOADING))
        assertEquals("Clear", englishPresentation.captions.resolve(WmoCaption.CLEAR))
    }

    @Test
    fun dataClassEqualityAndComponents() {
        val (text, captions) = englishPresentation
        assertEquals(englishClusterText, text)
        assertEquals(englishWeatherCaptions, captions)

        val copy = englishPresentation.copy()
        assertEquals(englishPresentation, copy)
        assertEquals(englishPresentation.hashCode(), copy.hashCode())

        val other = ClusterPresentation(
            text = { "other" },
            captions = englishWeatherCaptions
        )
        assertNotEquals(englishPresentation, other)
    }
}
