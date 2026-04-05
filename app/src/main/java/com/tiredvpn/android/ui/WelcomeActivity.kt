package com.tiredvpn.android.ui

import android.content.Intent
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity
import com.tiredvpn.android.R
import com.tiredvpn.android.databinding.ActivityWelcomeBinding

class WelcomeActivity : BaseActivity() {

    private lateinit var binding: ActivityWelcomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityWelcomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        setupFeatures()
        setupListeners()

        onBackPressedDispatcher.addCallback(this) { goToMain() }
    }

    private fun setupFeatures() {
        // Feature 1: Security
        setupFeature(
            binding.feature1.root,
            R.drawable.ic_shield,
            getString(R.string.feature_security_title),
            getString(R.string.feature_security_desc)
        )

        // Feature 2: Unlock
        setupFeature(
            binding.feature2.root,
            R.drawable.ic_public,
            getString(R.string.feature_unlock_title),
            getString(R.string.feature_unlock_desc)
        )

        // Feature 3: Privacy
        setupFeature(
            binding.feature3.root,
            R.drawable.ic_no_encryption,
            getString(R.string.feature_privacy_title),
            getString(R.string.feature_privacy_desc)
        )
    }

    private fun setupFeature(root: LinearLayout, iconRes: Int, title: String, description: String) {
        root.findViewById<ImageView>(R.id.featureIcon).setImageResource(iconRes)
        root.findViewById<TextView>(R.id.featureTitle).text = title
        root.findViewById<TextView>(R.id.featureDescription).text = description
    }

    private fun setupListeners() {
        binding.skipButton.setOnClickListener {
            goToMain()
        }

        binding.getStartedButton.setOnClickListener {
            // Go to server configuration first
            startActivity(Intent(this, ServerConfigActivity::class.java))
            finish()
        }
    }

    private fun goToMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }

}
