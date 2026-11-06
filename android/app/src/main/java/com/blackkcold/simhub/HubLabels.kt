package com.blackkcold.simhub

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/** Locale-aware labels for the remaining Compose-only settings surfaces. */
@Composable
internal fun hubLabel(zh: String, en: String): String {
    val language = LocalConfiguration.current.locales.get(0)?.language
    return if (language == "zh") zh else en
}
