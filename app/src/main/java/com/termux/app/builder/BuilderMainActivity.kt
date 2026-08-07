package com.termux.app.builder

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.termux.R

/**
 * Entry point UI builder (native, bukan terminal).
 *
 * Menampilkan BuildDashboardFragment dalam container sederhana.
 * Diakses dari launcher (secondary activity) atau dari drawer Termux.
 */
class BuilderMainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_builder)

        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction()
                .replace(R.id.builder_fragment_container, BuildDashboardFragment())
                .commit()
        }
    }
}
