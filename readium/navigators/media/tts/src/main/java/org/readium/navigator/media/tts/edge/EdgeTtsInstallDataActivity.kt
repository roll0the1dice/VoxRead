/*
 * Copyright 2026 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.readium.navigator.media.tts.edge

import android.app.Activity
import android.os.Bundle
import org.readium.r2.shared.ExperimentalReadiumApi

/**
 * No-op installer: Edge voices are served from the cloud and need no local data.
 */
@ExperimentalReadiumApi
public class EdgeTtsInstallDataActivity : Activity() {

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
