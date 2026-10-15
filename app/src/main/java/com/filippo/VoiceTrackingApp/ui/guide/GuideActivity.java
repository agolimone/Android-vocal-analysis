package com.filippo.VoiceTrackingApp.ui.guide;

import android.os.Bundle;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;

// Schermata puramente informativa/statica: spiega come prepararsi alla registrazione dal
// microfono interno e come funziona il flusso di sessione. Nessuna logica oltre
// all'inizializzazione della UI, tutti i testi sono in strings.xml.
public class GuideActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);
    }
}
