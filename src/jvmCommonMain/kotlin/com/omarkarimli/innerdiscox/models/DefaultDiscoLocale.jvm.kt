package com.omarkarimli.innerdiscox.models

import java.util.Locale

internal actual fun defaultDiscoLocale(): DiscoLocale = systemDiscoLocale(Locale.getDefault())

internal fun systemDiscoLocale(locale: Locale): DiscoLocale =
    DiscoLocale(
        gl = locale.country.uppercase(Locale.ROOT),
        hl = locale.toLanguageTag(),
    )
