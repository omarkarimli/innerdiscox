package com.omarkarimli.innerdiscox.models

import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

class DefaultDiscoLocaleTest {
    @Test
    fun preservesLanguageScriptAndRegion() {
        val locale =
            Locale
                .Builder()
                .setLanguage("zh")
                .setScript("Hant")
                .setRegion("TW")
                .build()

        assertEquals(DiscoLocale(gl = "TW", hl = "zh-Hant-TW"), systemDiscoLocale(locale))
    }

    @Test
    fun doesNotInventRegionWhenSystemLocaleHasNone() {
        assertEquals(DiscoLocale(gl = "", hl = "it"), systemDiscoLocale(Locale.forLanguageTag("it")))
    }
}
