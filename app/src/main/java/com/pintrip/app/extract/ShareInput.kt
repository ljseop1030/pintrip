package com.pintrip.app.extract

import android.net.Uri

sealed interface ShareInput {
    data class Screenshots(
        val uris: List<Uri>,
        val originalCount: Int,
    ) : ShareInput

    data class LinkText(
        val text: String,
        val url: String?,
    ) : ShareInput

    data object Empty : ShareInput
}
