package com.filippo.VoiceTrackingApp.ui.home;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.ui.auth.LoginActivity;
import com.filippo.VoiceTrackingApp.ui.guide.GuideActivity;
import com.filippo.VoiceTrackingApp.ui.history.HistoryActivity;
import com.filippo.VoiceTrackingApp.ui.info.InfoActivity;
import com.filippo.VoiceTrackingApp.ui.session.SessionFlowActivity;
import com.filippo.VoiceTrackingApp.ui.stats.StatsActivity;

public class HomeActivity extends AppCompatActivity {

    public static final String EXTRA_USERNAME = "com.filippo.progetto.EXTRA_USERNAME";
    public static final String EXTRA_USER_ID = "com.filippo.progetto.EXTRA_USER_ID";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_home);

        String username = getIntent().getStringExtra(EXTRA_USERNAME);
        if (username == null) {
            username = "";
        }
        int userId = getIntent().getIntExtra(EXTRA_USER_ID, -1);

        TextView textWelcome = findViewById(R.id.text_welcome);
        textWelcome.setText(getString(R.string.text_welcome, username));

        Button buttonNewSession = findViewById(R.id.button_new_session);
        Button buttonHistory = findViewById(R.id.button_history);
        Button buttonStats = findViewById(R.id.button_stats);
        Button buttonLogout = findViewById(R.id.button_logout);
        Button buttonInfo = findViewById(R.id.button_info);
        Button buttonGuide = findViewById(R.id.button_guide);

        buttonNewSession.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, SessionFlowActivity.class);
            intent.putExtra(EXTRA_USER_ID, userId);
            startActivity(intent);
        });
        buttonHistory.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, HistoryActivity.class);
            intent.putExtra(EXTRA_USER_ID, userId);
            startActivity(intent);
        });
        buttonStats.setOnClickListener(v -> {
            Intent intent = new Intent(HomeActivity.this, StatsActivity.class);
            intent.putExtra(EXTRA_USER_ID, userId);
            startActivity(intent);
        });
        buttonLogout.setOnClickListener(v -> logout());
        // Schermate puramente informative: nessun dato da passare.
        buttonInfo.setOnClickListener(v ->
                startActivity(new Intent(HomeActivity.this, InfoActivity.class)));
        buttonGuide.setOnClickListener(v ->
                startActivity(new Intent(HomeActivity.this, GuideActivity.class)));
    }

    private void logout() {
        Intent intent = new Intent(HomeActivity.this, LoginActivity.class);
        // Svuota completamente il back stack: dopo il logout non si puo' tornare alla Home premendo "indietro".
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
    }
}
