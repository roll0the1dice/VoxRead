package org.readium.r2.shared.publication.services.content.iterators

import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * Optional speech language for formula conversion.
 *
 * When unset, each formula uses the document or publication language.
 * The active TTS language preference is published here so a language
 * change does not reuse speech generated for another language.
 */
@ExperimentalReadiumApi
public object MathSpeechLanguage {
    @Volatile
    public var code: String? = null
}
