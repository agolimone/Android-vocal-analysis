package com.filippo.VoiceTrackingApp.ui.info;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;

// schermata puramente informativa, spiega come interpretare le feature.
// tutti i testi sono in strings.xml.
public class InfoActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_info);
    }
}
