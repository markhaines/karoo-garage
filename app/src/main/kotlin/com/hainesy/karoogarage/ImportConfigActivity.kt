package com.hainesy.karoogarage

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.util.Log
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * Handles a tapped (or `push-config.sh`-pushed) .kgcfg. The checks live in [ConfigImport];
 * this is the Android glue: read the source, ask the rider, and only then write.
 */
class ImportConfigActivity : Activity() {

    private var confirmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pending = try {
            ConfigImport.Pending(ConfigImport.parse(readSource(intent)))
        } catch (e: ConfigImport.Rejected) {
            finishWithError(e.message ?: "invalid config file")
            return
        } catch (e: Exception) {
            // Class name only: messages from the stream or parser can quote file content.
            Log.w(TAG, "import failed: ${e.javaClass.simpleName}")
            finishWithError("could not read file")
            return
        }
        confirm(pending)
    }

    private fun readSource(intent: Intent?): ByteArray {
        val uri = intent?.data ?: throw ConfigImport.Rejected("no file")
        ConfigImport.checkSource(
            action = intent.action,
            scheme = uri.scheme,
            path = uri.path,
            displayName = if (uri.scheme.equals("content", ignoreCase = true)) displayName(uri) else null,
            ownFilesDir = getExternalFilesDir(null)?.path,
        )
        val stream = contentResolver.openInputStream(uri) ?: throw ConfigImport.Rejected("no file")
        return stream.use { ConfigImport.readBounded(it) }
    }

    private fun displayName(uri: Uri): String? {
        val fromProvider = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
        }.getOrNull()
        return fromProvider ?: uri.lastPathSegment
    }

    private fun confirm(pending: ConfigImport.Pending) {
        val store = ConfigStore(this)
        val summary = pending.summary
        val replacing = pending.replacing(store)
        val message = buildString {
            append(getString(R.string.import_confirm_message, summary.origin, summary.entityId, summary.action))
            if (replacing != null) {
                append("\n\n")
                append(getString(R.string.import_confirm_replaces, replacing))
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_confirm_title)
            .setMessage(message)
            .setPositiveButton(R.string.import_confirm_accept) { _, _ ->
                confirmed = true
                val replaced = pending.confirm(store)
                if (replaced != null) GarageRuntime.revokeBestEffort(this, replaced)
                toast(getString(R.string.import_success))
                // Hand off to settings so the user sees the imported values
                startActivity(
                    Intent(this, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                setResult(RESULT_OK)
            }
            .setNegativeButton(R.string.import_confirm_cancel, null)
            // Back, tap outside and Cancel all land here; nothing was written.
            .setOnDismissListener {
                if (!confirmed) {
                    toast(getString(R.string.import_cancelled))
                    setResult(RESULT_CANCELED)
                }
                finish()
            }
            .show()
    }

    private fun finishWithError(message: String) {
        toast(getString(R.string.import_failed, message))
        setResult(RESULT_CANCELED)
        finish()
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    companion object {
        private const val TAG = "ImportConfigActivity"
    }
}
