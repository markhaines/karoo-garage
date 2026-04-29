package com.hainesy.karoogarage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.serialization.json.Json

class ImportConfigActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = intent?.data ?: run {
            finishWithError("no file URI")
            return
        }
        importFrom(uri)
    }

    private fun importFrom(uri: Uri) {
        val json = runCatching {
            contentResolver.openInputStream(uri).use { stream ->
                requireNotNull(stream).bufferedReader().readText()
            }
        }.getOrElse {
            Log.w(TAG, "Failed to read $uri", it)
            finishWithError(it.message ?: "could not read file")
            return
        }

        val config = runCatching { Json.decodeFromString<Config>(json) }
            .map { it.normalised() }
            .getOrElse {
                Log.w(TAG, "Failed to parse config", it)
                finishWithError("invalid config file")
                return
            }

        if (!config.isValid()) {
            finishWithError("config is missing required fields")
            return
        }

        ConfigStore(this).save(config)
        toast(getString(R.string.import_success))

        // Hand off to settings so the user sees the imported values
        startActivity(
            Intent(this, SettingsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        setResult(Activity.RESULT_OK)
        finish()
    }

    private fun finishWithError(message: String) {
        toast(getString(R.string.import_failed, message))
        setResult(Activity.RESULT_CANCELED)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ImportConfigActivity"
    }
}
