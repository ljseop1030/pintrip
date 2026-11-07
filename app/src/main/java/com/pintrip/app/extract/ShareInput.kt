package com.pintrip.app.extract

import android.net.Uri

sealed interface ShareInput {
    data class Screenshots(val uris: List<Uri>) : ShareInput
    data class LinkText(val text: String) : ShareInput
    data object Empty : ShareInput
}
