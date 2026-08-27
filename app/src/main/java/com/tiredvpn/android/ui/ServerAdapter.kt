package com.tiredvpn.android.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ItemServerLocationBinding
import com.tiredvpn.android.util.CountryDetector
import com.tiredvpn.android.vpn.VpnConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ServerAdapter(
    private var servers: List<VpnConfig>,
    private var activeServerId: String?,
    private val scope: CoroutineScope,
    private val onServerClick: (VpnConfig) -> Unit,
    private val onServerLongClick: (VpnConfig) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    /**
     * Ids of the servers the core may switch between, active one included.
     * The core takes one secret for the whole pool, so this is the active
     * server plus everyone sharing its secret - see ServerPoolConfig.
     */
    private var poolIds: Set<String> = emptySet()

    fun updateList(newServers: List<VpnConfig>, newActiveId: String?, newPoolIds: Set<String> = emptySet()) {
        servers = newServers
        activeServerId = newActiveId
        poolIds = newPoolIds
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ServerViewHolder {
        val binding = ItemServerLocationBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ServerViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ServerViewHolder, position: Int) {
        holder.bind(servers[position])
    }

    override fun getItemCount() = servers.size

    inner class ServerViewHolder(private val binding: ItemServerLocationBinding) : 
        RecyclerView.ViewHolder(binding.root) {
        
        fun bind(server: VpnConfig) {
            binding.serverName.text = server.name.ifEmpty { server.serverAddress }

            bindPoolLabel(server)

            // Detect country and set flag
            scope.launch {
                val countryInfo = CountryDetector.detectCountry(server.serverAddress)
                // Ensure UI update on main thread
                withContext(Dispatchers.Main) {
                    binding.flagImage.text = countryInfo.flag
                }
            }
            
            // Set latency text and color
            val latency = server.lastLatencyMs
            if (latency > 0) {
                binding.latencyText.text = "${latency}ms"
                val signalColor = when {
                    latency < 100 -> R.color.signal_excellent
                    latency < 200 -> R.color.signal_good
                    latency < 300 -> R.color.signal_fair
                    else -> R.color.signal_poor
                }
                binding.signalIndicator.setColorFilter(
                    ContextCompat.getColor(binding.root.context, signalColor)
                )
            } else if (latency == -1L) {
                binding.latencyText.text = "..."
                binding.signalIndicator.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.text_hint)
                )
            } else {
                 binding.latencyText.text = "Error"
                 binding.signalIndicator.setColorFilter(
                    ContextCompat.getColor(binding.root.context, R.color.signal_poor)
                )
            }
            
            // Highlight active server
            if (server.id == activeServerId) {
                // 4dp to px conversion approximately
                val density = binding.root.context.resources.displayMetrics.density
                val strokeWidth = (2 * density).toInt()
                binding.root.strokeWidth = strokeWidth
                binding.root.strokeColor = ContextCompat.getColor(binding.root.context, R.color.connected)
            } else {
                binding.root.strokeWidth = 0
            }

            binding.root.setOnClickListener { onServerClick(server) }
            binding.root.setOnLongClickListener {
                onServerLongClick(server)
                true
            }
        }

        /**
         * Say which servers the connection can move to on its own. Silence here
         * would read as "failover works everywhere", which it does not: a
         * server with a secret of its own has nowhere to go.
         */
        private fun bindPoolLabel(server: VpnConfig) {
            val label = binding.poolLabel
            val context = binding.root.context
            when {
                server.id == activeServerId && poolIds.size > 1 ->
                    label.text = context.getString(R.string.pool_active, poolIds.size - 1)
                server.id == activeServerId ->
                    label.text = context.getString(R.string.pool_alone)
                poolIds.contains(server.id) ->
                    label.text = context.getString(R.string.pool_member)
                else -> {
                    label.visibility = View.GONE
                    return
                }
            }
            label.visibility = View.VISIBLE
        }
    }
}
