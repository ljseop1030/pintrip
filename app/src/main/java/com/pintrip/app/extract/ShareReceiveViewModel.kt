package com.pintrip.app.extract

import android.content.Intent
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

class ShareReceiveViewModel : ViewModel() {

    private val _shareInput = MutableLiveData<ShareInput>()
    val shareInput: LiveData<ShareInput> = _shareInput

    fun processIntent(intent: Intent?) {
        if (_shareInput.value != null) return
        parseAndSetInput(intent)
    }

    fun processNewIntent(intent: Intent?) {
        parseAndSetInput(intent)
    }

    private fun parseAndSetInput(intent: Intent?) {
        val input = ShareIntentParser.parse(intent)
        _shareInput.value = input
        logShareInput(input)
    }

    private fun logShareInput(input: ShareInput) {
        when (input) {
            is ShareInput.Screenshots -> {
                Log.d(TAG, "Parsed ShareInput.Screenshots (count: ${input.uris.size}):")
                input.uris.forEachIndexed { index, uri ->
                    Log.d(TAG, "  [$index] $uri")
                }
            }

            is ShareInput.LinkText -> {
                Log.d(TAG, "Parsed ShareInput.LinkText: ${input.text}")
            }

            ShareInput.Empty -> {
                Log.d(TAG, "Parsed ShareInput.Empty (No valid content found)")
            }
        }
    }

    companion object {
        private const val TAG = "SHARETEST"
    }
}
