package com.opensab.domain.speech

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The question answered here is **the alphabet, not the language**, and the difference is the whole
 * contract: a message in Chinese read with a Spanish voice is noise, while Spanish and French read
 * with the same Latin voice are merely accented. The first is what this fixes; the second is what
 * it declares it cannot see.
 */
class VoiceLanguageTest {

    @Test
    fun `reads latin text in the rider language`() {
        assertNull(voiceLocaleFor("Llego en 10 minutos"))
    }

    @Test
    fun `picks the chinese voice for han`() {
        assertEquals(Locale.forLanguageTag("zh"), voiceLocaleFor("你好，你在哪里"))
    }

    @Test
    fun `picks the japanese voice for kana`() {
        assertEquals(Locale.forLanguageTag("ja"), voiceLocaleFor("こんにちは、元気ですか"))
    }

    /**
     * Japanese writes with kanji **and** kana, so asking about Han first would read every Japanese
     * message with a Chinese voice. Kana is the discriminator and it has to be asked first.
     */
    @Test
    fun `prefers japanese over chinese when kana rides along with kanji`() {
        assertEquals(Locale.forLanguageTag("ja"), voiceLocaleFor("日本語のメッセージです"))
    }

    @Test
    fun `picks the korean voice for hangul`() {
        assertEquals(Locale.forLanguageTag("ko"), voiceLocaleFor("안녕하세요"))
    }

    @Test
    fun `picks the russian voice for cyrillic`() {
        assertEquals(Locale.forLanguageTag("ru"), voiceLocaleFor("Привет, где estás"))
    }

    @Test
    fun `picks the greek voice`() {
        assertEquals(Locale.forLanguageTag("el"), voiceLocaleFor("Γεια σου, πού είσαι"))
    }

    @Test
    fun `picks the arabic voice`() {
        assertEquals(Locale.forLanguageTag("ar"), voiceLocaleFor("مرحبا أين أنت"))
    }

    @Test
    fun `picks the hebrew voice`() {
        assertEquals(Locale.forLanguageTag("he"), voiceLocaleFor("שלום איפה אתה"))
    }

    @Test
    fun `picks the thai voice`() {
        assertEquals(Locale.forLanguageTag("th"), voiceLocaleFor("สวัสดี คุณอยู่ที่ไหน"))
    }

    @Test
    fun `picks the hindi voice for devanagari`() {
        assertEquals(Locale.forLanguageTag("hi"), voiceLocaleFor("नमस्ते आप कहाँ हैं"))
    }

    /** Digits, punctuation and emoji belong to no alphabet and must not vote. */
    @Test
    fun `ignores digits punctuation and emoji`() {
        assertNull(voiceLocaleFor("12:30 😀 !!! ---"))
    }

    /**
     * The dominant script wins, not the first one seen. A brand name inside a Russian message is a
     * handful of Latin letters and must not turn the voice.
     */
    @Test
    fun `ignores a latin brand name inside a cyrillic message`() {
        assertEquals(
            Locale.forLanguageTag("ru"),
            voiceLocaleFor("Скачал WhatsApp и не работает, помоги пожалуйста")
        )
    }

    /** And the other way round: a stray non-Latin word does not carry a Latin message. */
    @Test
    fun `keeps the rider voice when the foreign script is a minority`() {
        assertNull(voiceLocaleFor("Te mando el kanji 空 para que lo veas"))
    }

    @Test
    fun `reads an empty body in the rider language`() {
        assertNull(voiceLocaleFor(""))
    }

    @Test
    fun `reads a blank body in the rider language`() {
        assertNull(voiceLocaleFor("   \n\t "))
    }
}
