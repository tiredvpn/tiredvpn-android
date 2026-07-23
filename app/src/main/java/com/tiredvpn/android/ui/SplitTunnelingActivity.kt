package com.tiredvpn.android.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.LruCache
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivitySplitTunnelingBinding
import com.tiredvpn.android.vpn.ServerRepository
import com.tiredvpn.android.vpn.SplitTunnelSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelingActivity : BaseActivity() {

    private lateinit var binding: ActivitySplitTunnelingBinding
    private lateinit var adapter: AppListAdapter
    private val selectedApps = mutableSetOf<String>()
    private var allApps: List<AppInfo> = emptyList()
    private var currentMode: String = "exclude"
    private var profileId: String? = null
    private var profileName: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySplitTunnelingBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        loadSettings()
        setupListeners()
        loadApps()
    }

    private fun loadSettings() {
        // Split settings are bound to the active profile.
        val active = ServerRepository.getActiveServer(this)
        profileId = active?.id
        profileName = active?.name

        val prefs = getSharedPreferences(SplitTunnelSettings.PREFS_NAME, MODE_PRIVATE)
        currentMode = SplitTunnelSettings.getMode(prefs, profileId)
        selectedApps.addAll(SplitTunnelSettings.getApps(prefs, profileId))

        // Set radio button
        when (currentMode) {
            "exclude" -> binding.modeExclude.isChecked = true
            "include" -> binding.modeInclude.isChecked = true
            "off" -> binding.modeOff.isChecked = true
        }

        updateDescription()
        updateAppListVisibility() // Initialize visibility based on mode
    }

    private fun saveSettings() {
        SplitTunnelSettings.save(this, profileId, currentMode, selectedApps)
    }

    private fun updateDescription() {
        val desc = when (currentMode) {
            "exclude" -> getString(R.string.mode_exclude)
            "include" -> getString(R.string.mode_include)
            "off" -> getString(R.string.mode_off_description)
            else -> getString(R.string.split_tunneling_desc)
        }
        // Prefix with the active profile name so it's clear these settings are per-profile.
        binding.description.text = profileName?.let { "[$it]\n$desc" } ?: desc
    }

    private fun updateAppListVisibility() {
        val isAppListVisible = (currentMode != "off")
        binding.searchLayout.isVisible = isAppListVisible
        binding.appList.isVisible = isAppListVisible
        binding.loadingIndicator.isVisible = false // Hide loading indicator if app list is hidden
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }

        binding.modeRadioGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode = when (checkedId) {
                R.id.modeExclude -> "exclude"
                R.id.modeInclude -> "include"
                R.id.modeOff -> "off"
                else -> "exclude"
            }
            updateDescription()
            updateAppListVisibility() // New call
            saveSettings()
        }

        binding.searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                filterApps(s?.toString() ?: "")
            }
        })
    }

    private fun loadApps() {
        binding.loadingIndicator.isVisible = true
        binding.appList.isVisible = false

        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) {
                getInstalledApps()
            }
            allApps = apps
            setupRecyclerView(apps)

            binding.loadingIndicator.isVisible = false
            binding.appList.isVisible = true
        }
    }

    private fun setupRecyclerView(apps: List<AppInfo>) {
        adapter = AppListAdapter(apps, selectedApps, lifecycleScope) { packageName, isSelected ->
            if (isSelected) {
                selectedApps.add(packageName)
            } else {
                selectedApps.remove(packageName)
            }
            saveSettings()
        }

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter
    }

    private fun filterApps(query: String) {
        if (query.isEmpty()) {
            adapter.updateList(allApps)
        } else {
            val filtered = allApps.filter {
                it.appName.contains(query, ignoreCase = true) ||
                it.packageName.contains(query, ignoreCase = true)
            }
            adapter.updateList(filtered)
        }
    }

    private fun getInstalledApps(): List<AppInfo> {
        val pm = packageManager

        // Resolve launcher packages with a SINGLE query instead of calling
        // getLaunchIntentForPackage() per app (which is one IPC round-trip each and
        // was the main reason this screen took ages to open on devices with many apps).
        val launcherPackages: Set<String> = try {
            val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(launcherIntent, 0)
                .mapNotNull { it.activityInfo?.packageName }
                .toHashSet()
        } catch (e: Exception) {
            emptySet()
        }

        // No GET_META_DATA flag: we don't need component metadata, and it makes the
        // system marshal extra data for every package.
        return pm.getInstalledApplications(0)
            .filter { it.packageName != packageName } // Exclude ourselves
            .filter {
                // Show user apps + system apps that have a launcher icon (like YouTube, Chrome, etc)
                val isUserApp = it.flags and ApplicationInfo.FLAG_SYSTEM == 0
                isUserApp || launcherPackages.contains(it.packageName)
            }
            .map { appInfo ->
                AppInfo(
                    packageName = appInfo.packageName,
                    appName = pm.getApplicationLabel(appInfo).toString(),
                    icon = appInfo
                )
            }
            .sortedBy { it.appName.lowercase() }
    }

    data class AppInfo(
        val packageName: String,
        val appName: String,
        val icon: ApplicationInfo
    )

    class AppListAdapter(
        private var apps: List<AppInfo>,
        private val selectedApps: Set<String>,
        private val scope: kotlinx.coroutines.CoroutineScope,
        private val onAppToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        // Cache resolved icons so scrolling back doesn't reload them. ~40 icons is plenty
        // for a scrolling list and keeps memory bounded.
        private val iconCache = LruCache<String, Drawable>(64)

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val packageName: TextView = view.findViewById(R.id.appPackage)
            val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
            // Track which package a pending async icon load belongs to, so a recycled
            // holder doesn't get the wrong icon.
            var boundPackage: String? = null
            var iconJob: Job? = null
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            holder.boundPackage = app.packageName

            holder.name.text = app.appName
            holder.packageName.text = app.packageName

            // Icon: use cache, otherwise load off the UI thread. Loading
            // getApplicationIcon() synchronously here was making scrolling stutter.
            holder.iconJob?.cancel()
            val cached = iconCache.get(app.packageName)
            if (cached != null) {
                holder.icon.setImageDrawable(cached)
            } else {
                holder.icon.setImageDrawable(null)
                val ctx = holder.itemView.context.applicationContext
                holder.iconJob = scope.launch {
                    val drawable = withContext(Dispatchers.IO) {
                        try {
                            ctx.packageManager.getApplicationIcon(app.icon)
                        } catch (e: Exception) {
                            null
                        }
                    }
                    if (drawable != null) {
                        iconCache.put(app.packageName, drawable)
                        // Only apply if this holder is still showing the same app.
                        if (holder.boundPackage == app.packageName) {
                            holder.icon.setImageDrawable(drawable)
                        }
                    }
                }
            }

            // Remove listener before setting checked state to avoid unwanted callbacks
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = selectedApps.contains(app.packageName)

            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
            }

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                onAppToggle(app.packageName, isChecked)
            }
        }

        override fun onViewRecycled(holder: ViewHolder) {
            holder.iconJob?.cancel()
            holder.iconJob = null
            holder.boundPackage = null
        }

        override fun getItemCount() = apps.size

        fun updateList(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }
    }
}
