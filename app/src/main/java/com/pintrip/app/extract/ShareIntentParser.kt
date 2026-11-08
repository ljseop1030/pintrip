package com.pintrip.app.extract

import android.content.Intent
import android.net.Uri
import android.util.Patterns

object ShareIntentParser {

    private const val MAX_SCREENSHOTS = 5

    fun parse(intent: Intent?): ShareInput {
        if (intent == null) return ShareInput.Empty

        val action = intent.action
        val mimeType = intent.type

        return when (action) {
            Intent.ACTION_SEND -> {
                if (mimeType?.startsWith("image/") == true) {
                    val allUris = parseSingleImage(intent)
                    if (allUris.isNotEmpty()) {
                        ShareInput.Screenshots(
                            uris = allUris.take(MAX_SCREENSHOTS),
                            originalCount = allUris.size,
                        )
                    } else {
                        ShareInput.Empty
                    }
                } else if (((mimeType?.startsWith("text/")) == true) || (mimeType == null)) {
                    val text = parseText(intent)
                    if (!text.isNullOrBlank()) {
                        val extractedUrl = extractUrl(text)
                        ShareInput.LinkText(text = text, url = extractedUrl)
                    } else {
                        // Fallback: check if an image Uri was passed in ClipData or EXTRA_STREAM
                        val allUris = parseSingleImage(intent)
                        if (allUris.isNotEmpty()) {
                            ShareInput.Screenshots(
                                uris = allUris.take(MAX_SCREENSHOTS),
                                originalCount = allUris.size,
                            )
                        } else {
                            ShareInput.Empty
                        }
                    }
                } else {
                    val allUris = parseSingleImage(intent)
                    if (allUris.isNotEmpty()) {
                        ShareInput.Screenshots(
                            uris = allUris.take(MAX_SCREENSHOTS),
                            originalCount = allUris.size,
                        )
                    } else {
                        val text = parseText(intent)
                        if (!text.isNullOrBlank()) {
                            val extractedUrl = extractUrl(text)
                            ShareInput.LinkText(text = text, url = extractedUrl)
                        } else {
                            ShareInput.Empty
                        }
                    }
                }
            }

            Intent.ACTION_SEND_MULTIPLE -> {
                val allUris = parseMultipleImages(intent)
                if (allUris.isNotEmpty()) {
                    ShareInput.Screenshots(
                        uris = allUris.take(MAX_SCREENSHOTS),
                        originalCount = allUris.size,
                    )
                } else {
                    ShareInput.Empty
                }
            }

            else -> ShareInput.Empty
        }
    }

    private fun extractUrl(text: String): String? {
        val matcher = Patterns.WEB_URL.matcher(text)
        return if (matcher.find()) {
            matcher.group()
        } else {
            null
        }
    }

    private fun parseSingleImage(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        // 1. EXTRA_STREAM
        val streamUri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        streamUri?.let { uris.add(it) }

        // 2. ClipData fallback
        if (uris.isEmpty()) {
            val clipData = intent.clipData
            if ((clipData != null) && (clipData.itemCount > 0)) {
                for (i in 0 until clipData.itemCount) {
                    val itemUri = clipData.getItemAt(i).uri
                    itemUri?.let { uris.add(it) }
                }
            }
        }

        return uris
    }

    private fun parseMultipleImages(intent: Intent): List<Uri> {
        val uris = mutableListOf<Uri>()

        // 1. EXTRA_STREAM list
        val streamUris = intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
        if (!streamUris.isNullOrEmpty()) {
            uris.addAll(streamUris.filterNotNull())
        }

        // 2. ClipData fallback
        if (uris.isEmpty()) {
            val clipData = intent.clipData
            if ((clipData != null) && (clipData.itemCount > 0)) {
                for (i in 0 until clipData.itemCount) {
                    val itemUri = clipData.getItemAt(i).uri
                    itemUri?.let { uris.add(it) }
                }
            }
        }

        return uris
    }

    private fun parseText(intent: Intent): String? {
        // 1. EXTRA_TEXT
        val extraText = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
        if (!extraText.isNullOrBlank()) {
            return extraText.trim()
        }

        // 2. ClipData text
        val clipData = intent.clipData
        if ((clipData != null) && (clipData.itemCount > 0)) {
            val clipText = clipData.getItemAt(0).text?.toString()
            if (!clipText.isNullOrBlank()) {
                return clipText.trim()
            }
        }

        return null
    }
}
