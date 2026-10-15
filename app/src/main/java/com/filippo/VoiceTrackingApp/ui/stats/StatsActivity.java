package com.filippo.VoiceTrackingApp.ui.stats;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;

public class StatsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_stats);

        int userId = getIntent().getIntExtra(HomeActivity.EXTRA_USER_ID, -1);

        // savedInstanceState != null vuol dire che il FragmentManager sta gia' ricreando da solo
        // il Fragment esistente (es. dopo una rotazione dello schermo): aggiungerlo di nuovo qui
        // lo duplicherebbe.
        if (savedInstanceState == null) {
            StatsChartFragment fragment = StatsChartFragment.newInstance(userId);
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, fragment)
                    .commit();
        }
    }
}
