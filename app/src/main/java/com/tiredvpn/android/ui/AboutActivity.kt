package com.tiredvpn.android.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.tiredvpn.android.databinding.ActivityAboutBinding

class AboutActivity : BaseActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupWindowInsets(binding.root)

        setupListeners()
    }

    private fun setupListeners() {
        binding.backButton.setOnClickListener {
            finish()
        }
    }
}
