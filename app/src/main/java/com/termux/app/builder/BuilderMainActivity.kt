package com.termux.app.builder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.termux.R

/**
 * Entry point UI builder (native, bukan terminal).
 *
 * Menampilkan BuildDashboardFragment dalam container + MaterialToolbar.
 * Diakses dari launcher (secondary activity) atau dari drawer Termux.
 */
class BuilderMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_builder)

        val toolbar = findViewById<MaterialToolbar>(R.id.builder_toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.builder_fragment_container, BuildDashboardFragment())
                .commit()
        }
    }
}
