package com.tiredvpn.android.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityServerLocationsBinding
import com.tiredvpn.android.importer.ConfigCodec
import com.tiredvpn.android.importer.ImportPreview
import com.tiredvpn.android.vpn.ServerPoolConfig
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.VpnConfig

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.tiredvpn.android.util.PingManager

class ServerListActivity : BaseActivity() {
    private lateinit var binding: ActivityServerLocationsBinding
    private lateinit var adapter: ServerAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityServerLocationsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        setupRecyclerView()
        setupListeners()

        // Hide elements we aren't using yet
        binding.fastestServerCard.visibility = View.GONE
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun setupRecyclerView() {
        adapter = ServerAdapter(
            emptyList(),
            null,
            lifecycleScope, // Pass lifecycleScope
            onServerClick = { server ->
                ServerRepository.setActiveServerId(this, server.id)
                refreshList()
                finish() // Go back to Main
            },
            onServerLongClick = { server ->
                showServerOptions(server)
            }
        )
        binding.serverList.layoutManager = LinearLayoutManager(this)
        binding.serverList.adapter = adapter
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener { finish() }
        binding.addServerButton.setOnClickListener {
            // Check clipboard for server config
            checkClipboardAndAdd()
        }
    }

    /**
     * The add button offers the clipboard only when the clipboard actually holds
     * something importable - which is decided by parsing it, not by looking for
     * a substring. The previous heuristic ("contains serverAddress") missed
     * every link list and every base64 subscription.
     */
    private fun checkClipboardAndAdd() {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clipData = clipboard.primaryClip

        if (clipData != null && clipData.itemCount > 0) {
            val clipText = (0 until clipData.itemCount)
                .joinToString("\n") { clipData.getItemAt(it).coerceToText(this).toString() }

            val parsed = ConfigCodec.parse(clipText)
            if (parsed.servers.isNotEmpty()) {
                ImportPreview.show(this, parsed, fromExternalSource = false) { refreshList() }
                return
            }
        }

        // Nothing importable in the clipboard, open manual add
        openServerConfig()
    }

    private fun openServerConfig() {
        val intent = Intent(this, ServerConfigActivity::class.java)
        startActivity(intent)
    }

    private fun refreshList() {
        val servers = ServerRepository.getServers(this)
        val activeServer = ServerRepository.getActiveServer(this)
        adapter.updateList(servers, activeServer?.id, poolIdsFor(servers, activeServer))

        // Ping servers in background
        pingServers(servers)
    }

    /** Ids of the servers the core may fail over between, active one included. */
    private fun poolIdsFor(servers: List<VpnConfig>, active: VpnConfig?): Set<String> {
        if (active == null) return emptySet()
        return ServerPoolConfig.selectPool(servers, active).map { it.id }.toSet()
    }
    
    private fun pingServers(servers: List<VpnConfig>) {
        lifecycleScope.launch {
            servers.forEach { server ->
                // Launch individual coroutine for each server to ping in parallel
                launch {
                    val latency = PingManager.ping(server.serverAddress, server.serverPort)
                    // Update server with new latency
                    val updatedServer = server.copy(lastLatencyMs = latency)
                    
                    // Save to repository (optional, but good for caching)
                    ServerRepository.saveServer(this@ServerListActivity, updatedServer)
                    
                    // Update UI if still on this screen
                    // We need to fetch the latest list again because multiple coroutines might update it
                    // This is a bit inefficient but safe for now. 
                    // Better approach would be to have a StateFlow in ViewModel.
                    runOnUiThread {
                         val currentServers = ServerRepository.getServers(this@ServerListActivity)
                         val currentActive = ServerRepository.getActiveServer(this@ServerListActivity)
                         adapter.updateList(
                             currentServers,
                             currentActive?.id,
                             poolIdsFor(currentServers, currentActive)
                         )
                    }
                }
            }
        }
    }

    private fun showServerOptions(server: VpnConfig) {
        val options = arrayOf("Share", "Copy link", "Edit", "Delete")
        MaterialAlertDialogBuilder(this)
            .setTitle(server.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> shareServer(server)
                    1 -> copyServerLink(server)
                    2 -> editServer(server)
                    3 -> deleteServer(server)
                }
            }
            .show()
    }

    private fun shareServer(server: VpnConfig) {
        val link = server.toUrl()
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "TiredVPN: ${server.name}")
            putExtra(Intent.EXTRA_TEXT, link)
        }
        startActivity(Intent.createChooser(shareIntent, getString(R.string.share_server)))
    }

    private fun copyServerLink(server: VpnConfig) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("TiredVPN config", server.toUrl())
        )
        Toast.makeText(this, R.string.link_copied, Toast.LENGTH_SHORT).show()
    }

    private fun editServer(server: VpnConfig) {
        val intent = Intent(this, ServerConfigActivity::class.java)
        intent.putExtra("SERVER_ID", server.id)
        startActivity(intent)
    }

    private fun deleteServer(server: VpnConfig) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Delete Server")
            .setMessage("Are you sure you want to delete ${server.name}?")
            .setPositiveButton("Delete") { _, _ ->
                ServerRepository.deleteServer(this, server.id)
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
