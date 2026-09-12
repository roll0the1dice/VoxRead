/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.r2.testapp.reader.tts

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Persists the user's preferred TTS highlight color across app launches and books.
 */
class TtsHighlightColorStore(
    private val dataStore: DataStore<Preferences>,
    private val scope: CoroutineScope,
) {
    private val _color = MutableStateFlow(TtsHighlightColor.DEFAULT)
    val color: StateFlow<TtsHighlightColor> = _color.asStateFlow()

    init {
        scope.launch {
            _color.value = TtsHighlightColor.fromId(dataStore.data.first()[KEY])
        }
    }

    fun setColor(color: TtsHighlightColor) {
        if (color == _color.value) return
        _color.value = color
        scope.launch {
            dataStore.edit { it[KEY] = color.id }
        }
    }

    companion object {
        private val KEY = stringPreferencesKey("tts-highlight-color")
    }
}
