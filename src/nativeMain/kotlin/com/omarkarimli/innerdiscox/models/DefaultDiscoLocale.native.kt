package com.omarkarimli.innerdiscox.models

import platform.Foundation.NSLocale
import platform.Foundation.countryCode
import platform.Foundation.currentLocale
import platform.Foundation.localeIdentifier
import platform.Foundation.preferredLanguages

internal actual fun defaultDiscoLocale(): DiscoLocale {
    val current = NSLocale.currentLocale
    val language =
        (NSLocale.preferredLanguages.firstOrNull() as? String)
            ?.takeIf(String::isNotBlank)
            ?: current.localeIdentifier
    val preferred = NSLocale(language)
    return DiscoLocale(
        gl = (preferred.countryCode ?: current.countryCode).orEmpty().uppercase(),
        hl = language.substringBefore('@').replace('_', '-'),
    )
}
