package com.hainesy.karoogarage

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.EditText
import android.widget.ListView
import android.widget.SimpleAdapter
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.hammerhead.karooext.KarooSystemService
import kotlinx.coroutines.launch

/**
 * Lists the controllable entities of the logged-in Home Assistant instance
 * so the user taps their garage door instead of typing an entity ID.
 */
class EntityPickerActivity : AppCompatActivity() {

    private lateinit var karooSystem: KarooSystemService
    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var filterEdit: EditText

    private var allEntities: List<PickableEntity> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_entity_picker)
        title = getString(R.string.picker_title)

        karooSystem = KarooSystemService(this)
        karooSystem.connect { }

        listView = findViewById(R.id.list_entities)
        statusText = findViewById(R.id.text_picker_status)
        filterEdit = findViewById(R.id.edit_filter)
        filterEdit.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = showEntities(s?.toString().orEmpty())
        })

        loadEntities()
    }

    override fun onDestroy() {
        karooSystem.disconnect()
        super.onDestroy()
    }

    private fun loadEntities() {
        statusText.text = getString(R.string.picker_loading)
        val configStore = ConfigStore(this)
        val http = KarooHttp(karooSystem)
        val haClient = HomeAssistantClient(http, AuthClient(http), configStore)
        val repository = EntityRepository(http)

        lifecycleScope.launch {
            val state = configStore.load()
            if (state == null || state.authMode != AuthMode.OAUTH) {
                statusText.text = getString(R.string.picker_error, "not logged in")
                return@launch
            }
            haClient.freshAccessToken(state)
                .mapCatching { token ->
                    repository.fetchEntities(state.baseUrl, token).getOrThrow()
                }
                .onSuccess { entities ->
                    allEntities = entities
                    statusText.text = if (entities.isEmpty()) {
                        getString(R.string.picker_empty)
                    } else {
                        ""
                    }
                    showEntities(filterEdit.text?.toString().orEmpty())
                }
                .onFailure { error ->
                    statusText.text = getString(
                        R.string.picker_error,
                        error.message ?: error::class.java.simpleName,
                    )
                }
        }
    }

    private fun showEntities(filter: String) {
        val needle = filter.trim().lowercase()
        val visible = if (needle.isEmpty()) {
            allEntities
        } else {
            allEntities.filter {
                it.friendlyName.lowercase().contains(needle) ||
                    it.entityId.lowercase().contains(needle)
            }
        }
        val rows = visible.map {
            mapOf("name" to it.friendlyName, "id" to it.entityId)
        }
        listView.adapter = SimpleAdapter(
            this,
            rows,
            android.R.layout.simple_list_item_2,
            arrayOf("name", "id"),
            intArrayOf(android.R.id.text1, android.R.id.text2),
        )
        listView.setOnItemClickListener { _, _, position, _ ->
            val picked = visible[position]
            setResult(
                Activity.RESULT_OK,
                Intent()
                    .putExtra(EXTRA_ENTITY_ID, picked.entityId)
                    .putExtra(EXTRA_ENTITY_NAME, picked.friendlyName),
            )
            finish()
        }
    }

    companion object {
        const val EXTRA_ENTITY_ID = "entity_id"
        const val EXTRA_ENTITY_NAME = "entity_name"
    }
}
