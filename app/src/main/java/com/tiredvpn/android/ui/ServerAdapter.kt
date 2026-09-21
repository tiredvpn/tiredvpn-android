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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ServerAdapter(
    private var servers: List<VpnConfig>,
    private var activeServerId: String?,
    private val scope: CoroutineScope,
    private val onServerClick: (VpnConfig) -> Unit,
    private val onServerLongClick: (VpnConfig) -> Unit
) : RecyclerView.Adapter<ServerAdapter.ServerViewHolder>() {

    companion object {
        /** Shown while the country of a freshly seen address is unknown. */
        const val UNKNOWN_FLAG = "🌐"
    }

    /**
     * Ids of the servers the core may switch between, active one included.
     * Since core 1.8.0 the key travels with the dial, so this is every
     * configured server rather than the ones sharing a secret - see
     * ServerPoolConfig.selectPool.
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

    override fun onViewRecycled(holder: ServerViewHolder) {
        holder.flagJob?.cancel()
        holder.flagJob = null
        holder.boundAddress = null
    }

    inner class ServerViewHolder(private val binding: ItemServerLocationBinding) :
        RecyclerView.ViewHolder(binding.root) {

        /** Address this holder currently shows; a finished lookup checks it. */
        internal var boundAddress: String? = null
        internal var flagJob: Job? = null

        fun bind(server: VpnConfig) {
            binding.serverName.text = server.name.ifEmpty { server.serverAddress }
            boundAddress = server.serverAddress

            bindPoolLabel(server)

            // Country flag. A cached answer paints straight away - which is the
            // normal case, since notifyDataSetChanged() rebinds every visible row
            // after each finished ping. Only a cold address starts a coroutine,
            // and that coroutine checks the row hasn't been recycled under it
            // (same shape as the icon loader in SplitTunnelingActivity).
            flagJob?.cancel()
            flagJob = null
            val cached = CountryDetector.cached(server.serverAddress)
            if (cached != null) {
                binding.flagImage.text = cached.flag
            } else {
                binding.flagImage.text = UNKNOWN_FLAG
                flagJob = scope.launch {
                    val countryInfo = CountryDetector.detectCountry(server.serverAddress)
                    if (boundAddress == server.serverAddress) {
                        binding.flagImage.text = countryInfo.flag
                    }
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
         * Say how far the connection can move on its own, on the active row and
         * nowhere else.
         *
         * The per-row "in the failover pool" badge is gone with the rule that
         * made it worth saying. It marked the subset sharing the active
         * server's secret, back when that was the subset the core would switch
         * between; now every configured server is, so the badge would sit on
         * every row and tell the user nothing. The one number that still
         * carries information is how many there are, which belongs on the
         * active row.
         */
        private fun bindPoolLabel(server: VpnConfig) {
            val label = binding.poolLabel
            val context = binding.root.context
            when {
                server.id != activeServerId -> {
                    label.visibility = View.GONE
                    return
                }
                poolIds.size > 1 ->
                    label.text = context.getString(R.string.pool_active, poolIds.size - 1)
                else ->
                    label.text = context.getString(R.string.pool_alone)
            }
            label.visibility = View.VISIBLE
        }
    }
}
