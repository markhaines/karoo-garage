package com.hainesy.karoogarage

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch

class SettingsActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var karooSystem: KarooSystemService
    private lateinit var haClient: HomeAssistantClient

    private lateinit var editBaseUrl: TextInputEditText
    private lateinit var editToken: TextInputEditText
    private lateinit var editEntityId: TextInputEditText
    private lateinit var editDomain: TextInputEditText
    private lateinit var editService: TextInputEditText
    private lateinit var status: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configStore = ConfigStore(this)
        karooSystem = KarooSystemService(this)
        karooSystem.connect { /* ignored — Test handles failure paths */ }
        haClient = HomeAssistantClient(karooSystem)

        editBaseUrl = findViewById(R.id.edit_base_url)
        editToken = findViewById(R.id.edit_token)
        editEntityId = findViewById(R.id.edit_entity_id)
        editDomain = findViewById(R.id.edit_domain)
        editService = findViewById(R.id.edit_service)
        status = findViewById(R.id.text_status)

        val saved = configStore.load()
        editBaseUrl.setText(saved?.baseUrl.orEmpty())
        editToken.setText(saved?.token.orEmpty())
        editEntityId.setText(saved?.entityId.orEmpty())
        editDomain.setText(saved?.domain ?: Config.DEFAULT_DOMAIN)
        editService.setText(saved?.service ?: Config.DEFAULT_SERVICE)

        findViewById<MaterialButton>(R.id.button_save).setOnClickListener { onSave() }
        findViewById<MaterialButton>(R.id.button_test).setOnClickListener { onTest() }
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    private fun readConfig(): Config = Config(
        baseUrl = editBaseUrl.text?.toString().orEmpty(),
        token = editToken.text?.toString().orEmpty(),
        entityId = editEntityId.text?.toString().orEmpty(),
        domain = editDomain.text?.toString().orEmpty().ifBlank { Config.DEFAULT_DOMAIN },
        service = editService.text?.toString().orEmpty().ifBlank { Config.DEFAULT_SERVICE },
    ).normalised()

    private fun onSave() {
        val config = readConfig()
        if (!config.isValid()) {
            status.text = getString(R.string.status_invalid)
            return
        }
        configStore.save(config)
        status.text = getString(R.string.status_saved)
    }

    private fun onTest() {
        val config = readConfig()
        if (!config.isValid()) {
            status.text = getString(R.string.status_invalid)
            return
        }
        configStore.save(config)
        status.text = getString(R.string.status_testing)

        lifecycleScope.launch {
            haClient.trigger(config)
                .onSuccess { status.text = getString(R.string.status_test_ok) }
                .onFailure { error ->
                    status.text = getString(
                        R.string.status_test_failed,
                        error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }
}
