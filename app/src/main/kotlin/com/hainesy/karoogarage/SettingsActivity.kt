package com.hainesy.karoogarage

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsActivity : AppCompatActivity() {

    private lateinit var configStore: ConfigStore
    private lateinit var karooSystem: KarooSystemService
    private lateinit var http: KarooHttp
    private lateinit var authClient: AuthClient
    private lateinit var haClient: HomeAssistantClient
    private lateinit var entityRepository: EntityRepository
    private lateinit var discovery: HaDiscovery

    private lateinit var sectionServer: LinearLayout
    private lateinit var sectionLogin: LinearLayout
    private lateinit var sectionReady: LinearLayout
    private lateinit var sectionLegacy: LinearLayout

    private lateinit var editBaseUrl: TextInputEditText
    private lateinit var textDiscovered: TextView
    private lateinit var textLoginIntro: TextView
    private lateinit var editUsername: TextInputEditText
    private lateinit var editPassword: TextInputEditText
    private lateinit var layoutMfa: TextInputLayout
    private lateinit var editMfa: TextInputEditText
    private lateinit var textAccount: TextView
    private lateinit var textChosenEntity: TextView
    private lateinit var spinnerService: Spinner
    private lateinit var status: TextView

    private lateinit var editLegacyBaseUrl: TextInputEditText
    private lateinit var editToken: TextInputEditText
    private lateinit var editEntityId: TextInputEditText
    private lateinit var editDomain: TextInputEditText
    private lateinit var editService: TextInputEditText

    /** Active login flow id while the credentials section is showing. */
    private var flowId: String? = null

    /** Guards the spinner listener while it's being programmatically populated. */
    private var suppressServiceSelection = false

    private val pickEntity = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data ?: return@registerForActivityResult
        val entityId = data.getStringExtra(EntityPickerActivity.EXTRA_ENTITY_ID)
            ?: return@registerForActivityResult
        val entityName = data.getStringExtra(EntityPickerActivity.EXTRA_ENTITY_NAME)
        val domain = entityId.substringBefore('.')
        val service = EntityRepository.servicesFor(domain).first()
        configStore.saveAction(entityId, entityName, domain, service)
        status.text = getString(R.string.status_saved)
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        configStore = ConfigStore(this)
        karooSystem = KarooSystemService(this)
        karooSystem.connect { /* ignored — calls surface their own failures */ }
        http = KarooHttp(karooSystem)
        authClient = AuthClient(http)
        haClient = HomeAssistantClient(http, authClient, configStore)
        entityRepository = EntityRepository(http)
        discovery = HaDiscovery(this)

        bindViews()
        wireButtons()
        render()
    }

    override fun onDestroy() {
        discovery.stop()
        karooSystem.disconnect()
        super.onDestroy()
    }

    private fun bindViews() {
        sectionServer = findViewById(R.id.section_server)
        sectionLogin = findViewById(R.id.section_login)
        sectionReady = findViewById(R.id.section_ready)
        sectionLegacy = findViewById(R.id.section_legacy)

        editBaseUrl = findViewById(R.id.edit_base_url)
        textDiscovered = findViewById(R.id.text_discovered)
        textLoginIntro = findViewById(R.id.text_login_intro)
        editUsername = findViewById(R.id.edit_username)
        editPassword = findViewById(R.id.edit_password)
        layoutMfa = findViewById(R.id.layout_mfa)
        editMfa = findViewById(R.id.edit_mfa)
        textAccount = findViewById(R.id.text_account)
        textChosenEntity = findViewById(R.id.text_chosen_entity)
        spinnerService = findViewById(R.id.spinner_service)
        status = findViewById(R.id.text_status)

        editLegacyBaseUrl = findViewById(R.id.edit_legacy_base_url)
        editToken = findViewById(R.id.edit_token)
        editEntityId = findViewById(R.id.edit_entity_id)
        editDomain = findViewById(R.id.edit_domain)
        editService = findViewById(R.id.edit_service)
    }

    private fun wireButtons() {
        findViewById<MaterialButton>(R.id.button_login).setOnClickListener { onStartLogin() }
        findViewById<MaterialButton>(R.id.button_sign_in).setOnClickListener { onSignIn() }
        findViewById<MaterialButton>(R.id.button_cancel_login).setOnClickListener {
            flowId = null
            showSection(sectionServer)
        }
        findViewById<MaterialButton>(R.id.button_pick_entity).setOnClickListener {
            pickEntity.launch(Intent(this, EntityPickerActivity::class.java))
        }
        findViewById<MaterialButton>(R.id.button_test).setOnClickListener { onTestOauth() }
        findViewById<MaterialButton>(R.id.button_logout).setOnClickListener { onLogout() }
        findViewById<MaterialButton>(R.id.button_show_legacy).setOnClickListener {
            showSection(sectionLegacy)
        }
        findViewById<MaterialButton>(R.id.button_hide_legacy).setOnClickListener {
            showSection(sectionServer)
        }
        findViewById<MaterialButton>(R.id.button_save).setOnClickListener { onLegacySave() }
        findViewById<MaterialButton>(R.id.button_legacy_test).setOnClickListener { onLegacyTest() }

        spinnerService.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, pos: Int, id: Long) {
                if (suppressServiceSelection) return
                val state = configStore.load() ?: return
                val service = parent.getItemAtPosition(pos) as String
                if (service != state.service) {
                    configStore.saveAction(state.entityId, state.entityName, state.domain, service)
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
    }

    /** Shows the section matching the stored state. */
    private fun render() {
        val state = configStore.load()
        when {
            state?.authMode == AuthMode.OAUTH && !state.refreshToken.isNullOrBlank() -> {
                renderReady(state)
                showSection(sectionReady)
            }
            state?.authMode == AuthMode.LEGACY_TOKEN && !state.token.isNullOrBlank() -> {
                renderLegacy(state)
                showSection(sectionLegacy)
            }
            else -> {
                editBaseUrl.setText(state?.baseUrl.orEmpty())
                showSection(sectionServer)
            }
        }
    }

    private fun renderReady(state: GarageState) {
        textAccount.text = getString(
            R.string.text_logged_in_as,
            state.accountName ?: "?",
            state.baseUrl,
        )
        textChosenEntity.text = if (state.entityId.isBlank()) {
            getString(R.string.text_no_entity)
        } else {
            "${state.entityName ?: state.entityId} (${state.entityId})"
        }
        // Always include the stored service (it may be a hand-entered one the
        // canned list doesn't know) so setSelection can land on it — the
        // spinner's initial callback arrives on a later layout pass, and it
        // must find the stored value selected or it would overwrite it.
        val canned = EntityRepository.servicesFor(state.domain)
        val services = if (state.service.isNotBlank() && state.service !in canned) {
            listOf(state.service) + canned
        } else {
            canned
        }
        suppressServiceSelection = true
        spinnerService.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            services,
        )
        spinnerService.setSelection(services.indexOf(state.service).coerceAtLeast(0))
        spinnerService.post { suppressServiceSelection = false }
    }

    private fun renderLegacy(state: GarageState) {
        editLegacyBaseUrl.setText(state.baseUrl)
        editToken.setText(state.token.orEmpty())
        editEntityId.setText(state.entityId)
        editDomain.setText(state.domain)
        editService.setText(state.service)
    }

    private fun showSection(section: LinearLayout) {
        listOf(sectionServer, sectionLogin, sectionReady, sectionLegacy).forEach {
            it.visibility = if (it == section) View.VISIBLE else View.GONE
        }
        if (section == sectionServer) startDiscovery() else discovery.stop()
    }

    private fun startDiscovery() {
        discovery.start { name, url ->
            runOnUiThread {
                if (editBaseUrl.text.isNullOrBlank()) {
                    textDiscovered.text = getString(R.string.text_discovered_instance, name, url)
                    textDiscovered.visibility = View.VISIBLE
                    textDiscovered.setOnClickListener { editBaseUrl.setText(url) }
                }
            }
        }
    }

    // ---- OAuth login ----

    private fun onStartLogin() {
        val baseUrl = editBaseUrl.text?.toString()?.trim()?.trimEnd('/').orEmpty()
        if (!baseUrl.startsWith("http://") && !baseUrl.startsWith("https://")) {
            status.text = getString(R.string.status_invalid_url)
            return
        }
        status.text = getString(R.string.status_logging_in)
        lifecycleScope.launch {
            authClient.startLoginFlow(baseUrl)
                .onSuccess { id ->
                    flowId = id
                    pendingBaseUrl = baseUrl
                    layoutMfa.visibility = View.GONE
                    editMfa.setText("")
                    textLoginIntro.text = getString(R.string.login_intro, baseUrl)
                    status.text = ""
                    showSection(sectionLogin)
                }
                .onFailure { error ->
                    status.text = getString(R.string.status_login_failed, error.message)
                }
        }
    }

    private var pendingBaseUrl: String = ""

    private fun onSignIn() {
        val baseUrl = pendingBaseUrl
        val flow = flowId
        if (flow == null) {
            showSection(sectionServer)
            return
        }
        val mfaVisible = layoutMfa.visibility == View.VISIBLE
        val username = editUsername.text?.toString()?.trim().orEmpty()
        val password = editPassword.text?.toString().orEmpty()
        if (!mfaVisible && (username.isBlank() || password.isBlank())) {
            status.text = getString(R.string.status_invalid_credentials)
            return
        }

        status.text = getString(R.string.status_logging_in)
        lifecycleScope.launch {
            val result = if (mfaVisible) {
                authClient.submitMfaCode(baseUrl, flow, editMfa.text?.toString()?.trim().orEmpty())
            } else {
                authClient.submitCredentials(baseUrl, flow, username, password)
            }
            result
                .onSuccess { step -> handleLoginStep(baseUrl, username, step) }
                .onFailure { error ->
                    // 404 means the flow is gone (consumed or expired) — a
                    // retry on the same id can never succeed, so start over.
                    if ((error as? HttpStatusException)?.statusCode == 404) {
                        flowId = null
                        onStartLogin()
                    } else {
                        status.text = getString(R.string.status_login_failed, error.message)
                    }
                }
        }
    }

    private suspend fun handleLoginStep(baseUrl: String, username: String, step: AuthClient.LoginStep) {
        when (step) {
            is AuthClient.LoginStep.MfaRequired -> {
                flowId = step.flowId
                layoutMfa.visibility = View.VISIBLE
                status.text = getString(R.string.status_mfa_required)
            }
            is AuthClient.LoginStep.Failed -> {
                // Keep the flow when HA allows a retry; otherwise start over.
                if (step.flowId == null) {
                    flowId = null
                    showSection(sectionServer)
                }
                status.text = getString(R.string.status_login_failed, step.message)
            }
            is AuthClient.LoginStep.Success -> {
                // Whatever happens next, this flow is consumed — HA drops it
                // after create_entry, so a retry must start a new one.
                flowId = null
                authClient.exchangeCode(baseUrl, step.code)
                    .onSuccess { tokens ->
                        val refresh = tokens.refreshToken
                        if (refresh == null) {
                            status.text = getString(
                                R.string.status_login_failed,
                                "no refresh token returned",
                            )
                            showSection(sectionServer)
                            return
                        }
                        configStore.saveOauthLogin(
                            baseUrl = baseUrl,
                            refreshToken = refresh,
                            accessToken = tokens.accessToken,
                            expiresAtMillis = System.currentTimeMillis() +
                                tokens.expiresInSeconds * 1000L,
                            accountName = username,
                        )
                        editPassword.setText("")
                        status.text = getString(R.string.status_logged_in)
                        render()
                        maybeSuggestExternalUrl(baseUrl, tokens.accessToken)
                    }
                    .onFailure { error ->
                        status.text = getString(R.string.status_login_failed, error.message)
                        showSection(sectionServer)
                    }
            }
        }
    }

    /**
     * A login through a LAN address (typical after tapping the discovered
     * instance) only works at home. If the instance advertises a public URL,
     * default to switching over — the session isn't host-bound, so it's a
     * one-tap change with no re-login.
     */
    private suspend fun maybeSuggestExternalUrl(baseUrl: String, accessToken: String) {
        val external = entityRepository.fetchExternalUrl(baseUrl, accessToken).getOrNull()
        if (external == null || external == baseUrl || isFinishing) {
            proceedAfterLogin()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_external_url_title)
            .setMessage(getString(R.string.dialog_external_url_message, external, baseUrl))
            .setPositiveButton(R.string.dialog_external_url_accept) { _, _ ->
                configStore.saveBaseUrl(external)
                status.text = getString(R.string.status_switched_url, external)
                render()
            }
            .setNegativeButton(R.string.dialog_external_url_keep, null)
            .setOnDismissListener { proceedAfterLogin() }
            .show()
    }

    /** First login: go straight to the picker so setup finishes in one flow. */
    private fun proceedAfterLogin() {
        if (isFinishing) return
        if (configStore.load()?.entityId.isNullOrBlank()) {
            pickEntity.launch(Intent(this, EntityPickerActivity::class.java))
        }
    }

    private fun onTestOauth() {
        val state = configStore.load()
        if (state == null || state.entityId.isBlank()) {
            status.text = getString(R.string.status_pick_entity_first)
            return
        }
        status.text = getString(R.string.status_testing)
        lifecycleScope.launch {
            haClient.trigger()
                .onSuccess { status.text = getString(R.string.status_test_ok) }
                .onFailure { error ->
                    status.text = getString(
                        R.string.status_test_failed,
                        error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }

    private fun onLogout() {
        val state = configStore.load()
        // Local cleanup first and unconditionally — it must not depend on a
        // network call that a mid-flight destroy could cancel.
        configStore.clearOauthLogin()
        status.text = getString(R.string.status_logged_out)
        render()
        val refresh = state?.refreshToken ?: return
        lifecycleScope.launch {
            // Best-effort server-side revocation, shielded from cancellation.
            withContext(NonCancellable) {
                authClient.revoke(state.baseUrl, refresh)
            }
        }
    }

    // ---- Legacy token mode ----

    private fun readLegacyConfig(): Config = Config(
        baseUrl = editLegacyBaseUrl.text?.toString().orEmpty(),
        token = editToken.text?.toString().orEmpty(),
        entityId = editEntityId.text?.toString().orEmpty(),
        domain = editDomain.text?.toString().orEmpty().ifBlank { Config.DEFAULT_DOMAIN },
        service = editService.text?.toString().orEmpty().ifBlank { Config.DEFAULT_SERVICE },
    ).normalised()

    /**
     * Switching to legacy mode discards any OAuth login — revoke it
     * server-side too (best-effort) so no orphaned session lingers in HA.
     */
    private fun revokeObsoleteOauthSession() {
        val state = configStore.load() ?: return
        val refresh = state.refreshToken ?: return
        if (state.authMode != AuthMode.OAUTH) return
        lifecycleScope.launch {
            withContext(NonCancellable) {
                authClient.revoke(state.baseUrl, refresh)
            }
        }
    }

    private fun onLegacySave() {
        val config = readLegacyConfig()
        if (!config.isValid()) {
            status.text = getString(R.string.status_invalid)
            return
        }
        revokeObsoleteOauthSession()
        configStore.save(config)
        status.text = getString(R.string.status_saved)
    }

    private fun onLegacyTest() {
        val config = readLegacyConfig()
        if (!config.isValid()) {
            status.text = getString(R.string.status_invalid)
            return
        }
        revokeObsoleteOauthSession()
        configStore.save(config)
        status.text = getString(R.string.status_testing)
        lifecycleScope.launch {
            haClient.trigger()
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
