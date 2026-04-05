package com.tiredvpn.android.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplitTunnelingActivity : BaseActivity() {

    private lateinit var binding: ActivitySplitTunnelingBinding
    private lateinit var adapter: AppListAdapter
    private val selectedApps = mutableSetOf<String>()
    private var allApps: List<AppInfo> = emptyList()
    private var currentMode: String = "exclude"

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
        val prefs = getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
        currentMode = prefs.getString("split_tunneling_mode", "exclude") ?: "exclude"
        val apps = prefs.getStringSet("split_tunneling_apps", emptySet()) ?: emptySet()
        selectedApps.addAll(apps)

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
        getSharedPreferences("tiredvpn_settings", MODE_PRIVATE)
            .edit()
            .putString("split_tunneling_mode", currentMode)
            .putStringSet("split_tunneling_apps", selectedApps)
            .apply()
    }

    private fun updateDescription() {
        binding.description.text = when (currentMode) {
            "exclude" -> getString(R.string.mode_exclude)
            "include" -> getString(R.string.mode_include)
            "off" -> getString(R.string.mode_off_description)
            else -> getString(R.string.split_tunneling_desc)
        }
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
        adapter = AppListAdapter(apps, selectedApps) { packageName, isSelected ->
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
        return pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != packageName } // Exclude ourselves
            .filter {
                // Show user apps + system apps that have a launcher icon (like YouTube, Chrome, etc)
                val isUserApp = it.flags and ApplicationInfo.FLAG_SYSTEM == 0
                val hasLauncherIntent = pm.getLaunchIntentForPackage(it.packageName) != null
                isUserApp || hasLauncherIntent
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
        private val onAppToggle: (String, Boolean) -> Unit
    ) : RecyclerView.Adapter<AppListAdapter.ViewHolder>() {

        class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val packageName: TextView = view.findViewById(R.id.appPackage)
            val checkbox: CheckBox = view.findViewById(R.id.appCheckbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = apps[position]
            val pm = holder.itemView.context.packageManager

            holder.icon.setImageDrawable(pm.getApplicationIcon(app.icon))
            holder.name.text = app.appName
            holder.packageName.text = app.packageName

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

        override fun getItemCount() = apps.size

        fun updateList(newApps: List<AppInfo>) {
            apps = newApps
            notifyDataSetChanged()
        }
    }
}
