package com.filippo.VoiceTrackingApp.ui.session;

import android.Manifest;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.Session;
import com.filippo.VoiceTrackingApp.db.User;
import com.filippo.VoiceTrackingApp.dsp.CepstralFeatureExtractor;
import com.filippo.VoiceTrackingApp.dsp.SpectralFeatureExtractor;
import com.filippo.VoiceTrackingApp.dsp.TemporalFeatureExtractor;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;


// Pipeline completa di una sessione: 1) vocale sostenuta, 2) parlato connesso, 3) salvataggio.
// I campioni delle due registrazioni arrivano dal MICROFONO INTERNO del telefono
// (PhoneMicRecorder, AudioRecord).
public class SessionFlowActivity extends AppCompatActivity {

    private static final String TAG = "SessionFlowActivity";

    private static final double WINDOW_DURATION_MS = 20.0;
    private static final double VAD_THRESHOLD_RATIO = 0.6;
    private static final double F0_WINDOW_DURATION_MS = 50.0;
    // Range di F0 plausibile per la voce umana,guida la ricerca dell'autocorrelazione
    private static final double F0_MIN_HZ = 75.0;
    private static final double F0_MAX_HZ = 400.0;

    // Conto alla rovescia prima di ogni registrazione (vedi startVowelCountdown/
    // startSpeechCountdown): 3 tick da un secondo (3, 2, 1), poi si avvia la vera registrazione.
    private static final int COUNTDOWN_SECONDS = 3;
    private static final long COUNTDOWN_TICK_MS = 1000L;
    // Solo per il parlato: quanto resta visibile "Parla liberamente" (il "via") prima di avviare
    // davvero la registrazione, cosi' l'utente lo legge per davvero invece di vederlo sparire
    // nello stesso istante in cui parte la registrazione vera e propria.
    private static final long COUNTDOWN_GO_BEAT_MS = 700L;

    private static final long VOWEL_RECORDING_DURATION_MS = 3000L;
    private static final long SPEECH_RECORDING_DURATION_MS = 20000L;

    private static final int PERMISSION_REQUEST_RECORD_AUDIO_VOWEL = 100;
    private static final int PERMISSION_REQUEST_RECORD_AUDIO_SPEECH = 101;

    private int userId;
    private DatabaseHelper db;

    private View layoutStepVowel;
    private TextView textStatusVowel;
    private TextView textCountdownVowel;
    private Button buttonRecordVowel;
    private Button buttonRetryVowel;
    private Button buttonPlaybackVowel;
    private Button buttonContinueToSpeech;

    private View layoutStepSpeech;
    private TextView textStatusSpeech;
    private TextView textCountdownSpeech;
    private Button buttonRecordSpeech;
    private Button buttonRetrySpeech;
    private Button buttonPlaybackSpeech;
    private Button buttonSaveSession;

    // Indicatore "registrazione attiva" (pallino rosso + testo): mostrato durante l'intera
    // registrazione dal microfono interno. Tutti i pulsanti disabilitati tranne "Annulla e
    // ripeti" del passo in corso, per evitare click multipli.
    private View layoutRecordingActive;
    private TextView textRecordingActive;

    private boolean vowelRecorded;
    private long vowelDurationMs;
    private FetchVowelTask pendingVowelTask;
    private CountDownTimer vowelCountdownTimer;
    // Ultima registrazione vocale (campioni + sample rate reale), per il riascolto permanente
    // (button_playback_vowel) - vedi playRecording.
    private double[] vowelSamples;
    private int vowelSampleRateHz;

    private boolean speechRecorded;
    private long speechDurationMs;
    private FetchSpeechTask pendingSpeechTask;
    private CountDownTimer speechCountdownTimer;
    // Vedi commento analogo su vowelSamples/vowelSampleRateHz, stesso ruolo per il parlato.
    private double[] speechSamples;
    private int speechSampleRateHz;

    // Riascolto (permanente, non debug): un solo AudioTrack alla volta, per la registrazione
    // (vocale o parlato) appena riascoltata.
    private AudioTrack playbackTrack;

    // Feature spettrali/di qualita' vocale, calcolate su STEP 1 (vocale sostenuta) con
    // l'analisi cepstrale (CepstralFeatureExtractor): F0 e CPP (Cepstral Peak Prominence).
    private Double f0Mean;
    private Double cpp;
    // Jitter/shimmer: analisi SEPARATA ad autocorrelazione (SpectralFeatureExtractor), tornata
    // valida ora che il segnale e' a 44100Hz - vedi commento in FetchVowelTask.doInBackground.
    // NON sostituiscono f0Mean/cpp sopra: sono un affiancamento, calcolati sugli stessi campioni
    // della vocale ma con un metodo diverso (picco-per-picco invece che globale sul cepstrum).
    private Double jitter;
    private Double shimmer;

    // Feature temporali/prosodiche, calcolate su STEP 2 (parlato connesso).
    private Double energyMean;
    private Double energyStd;
    private Double pauseRatio;
    // Deviazione standard della F0 stimata finestra per finestra sui tratti vocalizzati del
    // parlato (marcatore prosodico di appiattimento/monotonia): vedi TemporalFeatureExtractor.
    // computeF0Variability. NON e' collegata a f0Mean sopra (quello e' il valore singolo dalla
    // vocale sostenuta, analisi cepstrale): sono due grandezze con fonte diversa che condividono
    // solo il nome del parametro fisico (F0), non lo stesso segnale di origine.
    private Double f0Std;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_flow);

        userId = getIntent().getIntExtra(HomeActivity.EXTRA_USER_ID, -1);
        if (userId == -1) {
            Toast.makeText(this, R.string.error_missing_user, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        db = DatabaseHelper.getInstance(getApplicationContext());

        layoutStepVowel = findViewById(R.id.layout_step_vowel);
        textStatusVowel = findViewById(R.id.text_status_vowel);
        textCountdownVowel = findViewById(R.id.text_countdown_vowel);
        buttonRecordVowel = findViewById(R.id.button_record_vowel);
        buttonRetryVowel = findViewById(R.id.button_retry_vowel);
        buttonPlaybackVowel = findViewById(R.id.button_playback_vowel);
        buttonContinueToSpeech = findViewById(R.id.button_continue_to_speech);

        layoutStepSpeech = findViewById(R.id.layout_step_speech);
        textStatusSpeech = findViewById(R.id.text_status_speech);
        textCountdownSpeech = findViewById(R.id.text_countdown_speech);
        buttonRecordSpeech = findViewById(R.id.button_record_speech);
        buttonRetrySpeech = findViewById(R.id.button_retry_speech);
        buttonPlaybackSpeech = findViewById(R.id.button_playback_speech);
        buttonSaveSession = findViewById(R.id.button_save_session);

        layoutRecordingActive = findViewById(R.id.layout_recording_active);
        textRecordingActive = findViewById(R.id.text_recording_active);

        buttonRecordVowel.setOnClickListener(v -> recordSustainedVowel());
        buttonRetryVowel.setOnClickListener(v -> resetVowelStep());
        buttonPlaybackVowel.setOnClickListener(v -> playRecording(vowelSamples, vowelSampleRateHz));
        buttonContinueToSpeech.setOnClickListener(v -> showSpeechStep());

        buttonRecordSpeech.setOnClickListener(v -> recordConnectedSpeech());
        buttonRetrySpeech.setOnClickListener(v -> resetSpeechStep());
        buttonPlaybackSpeech.setOnClickListener(v -> playRecording(speechSamples, speechSampleRateHz));
        buttonSaveSession.setOnClickListener(v -> saveSession());
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Il riascolto va fermato QUI, non solo in onDestroy(): tornando indietro o mandando
        // l'app in background l'Activity passa da onPause()/onStop() e onDestroy() puo' non
        // arrivare mai (o arrivare molto dopo), quindi l'audio continuerebbe a suonare in
        // sottofondo senza piu' nessun pulsante per fermarlo.
        stopPlayback();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // cancel(false) da solo non basta: se il task e' bloccato in lettura sul microfono resta
        // comunque in esecuzione. stopRecording() ferma l'AudioRecord e lo sblocca subito.
        if (pendingVowelTask != null) {
            pendingVowelTask.cancel(false);
            pendingVowelTask.stopRecording();
        }
        if (pendingSpeechTask != null) {
            pendingSpeechTask.cancel(false);
            pendingSpeechTask.stopRecording();
        }
        // I CountDownTimer usano un Handler interno non legato al ciclo di vita dell'Activity:
        // se non cancellati qui, onFinish() potrebbe scattare dopo onDestroy() e toccare view
        // gia' distrutte.
        if (vowelCountdownTimer != null) {
            vowelCountdownTimer.cancel();
        }
        if (speechCountdownTimer != null) {
            speechCountdownTimer.cancel();
        }
        // Ridondante rispetto a onPause() (che arriva sempre prima di onDestroy) ma innocuo:
        // stopPlayback() e' idempotente, se non c'e' niente in riproduzione non fa nulla.
        stopPlayback();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO_VOWEL) {
            if (granted) {
                startVowelRecordingFlow();
            } else {
                Toast.makeText(this, R.string.error_microphone_permission_denied, Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == PERMISSION_REQUEST_RECORD_AUDIO_SPEECH) {
            if (granted) {
                startSpeechRecordingFlow();
            } else {
                Toast.makeText(this, R.string.error_microphone_permission_denied, Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------------------------------------------------------------------
    // Indicatore "registrazione attiva": pallino rosso + testo, mostrato per l'intera durata
    // della registrazione dal microfono interno. A differenza della vecchia pipeline via
    // Arduino/WiFi non c'e' piu' una fase di trasferimento dati separata da attendere dopo: col
    // microfono interno, appena la registrazione finisce i campioni sono gia' pronti (nessuna
    // rete di mezzo), quindi una sola fase basta.
    // "Annulla e ripeti" del passo in corso resta ATTIVO durante la registrazione (l'utente puo'
    // annullare subito se sbaglia, es. tosse o si impappina): tutti gli ALTRI pulsanti sono
    // disabilitati, incluso il riascolto (non ha senso riascoltare mentre si sta registrando).
    // ---------------------------------------------------------------------

    // Chiamato all'avvio di una registrazione (dopo il countdown): mostra l'indicatore e
    // disabilita tutti i pulsanti tranne retryButton (il pulsante "Annulla e ripeti" DI QUESTO
    // STEP: buttonRetryVowel o buttonRetrySpeech).
    private void startRecordingActivePhase(String activeMessage, Button retryButton) {
        // Se l'utente aveva fatto partire un riascolto (i pulsanti restano attivi durante il
        // conto alla rovescia) lo fermo qui: l'audio precedente non deve continuare a suonare
        // mentre si registra il nuovo, finirebbe dritto nel microfono.
        stopPlayback();
        setAllButtonsEnabled(false);
        buttonPlaybackVowel.setEnabled(false);
        buttonPlaybackSpeech.setEnabled(false);
        retryButton.setEnabled(true);
        textRecordingActive.setText(activeMessage);
        layoutRecordingActive.setVisibility(View.VISIBLE);
    }

    // Chiamato quando il risultato arriva (successo o errore) o quando l'utente annulla: nasconde
    // l'indicatore, riabilita i pulsanti e ripristina lo stato dei pulsanti di riascolto in base
    // a cosa e' stato effettivamente registrato finora (non tutti e due indiscriminatamente: se
    // ad es. e' appena finita la vocale, il riascolto del parlato deve restare disabilitato
    // finche' anche il parlato non e' stato registrato almeno una volta).
    private void hideRecordingActivePhase() {
        layoutRecordingActive.setVisibility(View.GONE);
        setAllButtonsEnabled(true);
        buttonPlaybackVowel.setEnabled(vowelSamples != null);
        buttonPlaybackSpeech.setEnabled(speechSamples != null);
    }

    private void setAllButtonsEnabled(boolean enabled) {
        buttonRecordVowel.setEnabled(enabled);
        buttonRetryVowel.setEnabled(enabled);
        buttonContinueToSpeech.setEnabled(enabled);
        buttonRecordSpeech.setEnabled(enabled);
        buttonRetrySpeech.setEnabled(enabled);
        buttonSaveSession.setEnabled(enabled);
    }

    // ---------------------------------------------------------------------
    // Riascolto (permanente, non debug): riproduce l'ultima registrazione di UN passo (vocale o
    // parlato, a seconda del pulsante premuto) con AudioTrack al sample rate NATIVO di
    // acquisizione (44100Hz o 48000Hz col microfono interno) - a differenza del vecchio riascolto
    // debug via Arduino (sample rate troppo basso per AudioTrack, serviva espandere i campioni),
    // qui non serve nessun trucco: si dichiara il sample rate vero e si riproduce cosi' com'e'.
    // ---------------------------------------------------------------------

    private void playRecording(double[] samples, int sampleRateHz) {
        if (samples == null) {
            return; // il pulsante e' disabilitato finche' non c'e' una registrazione, non dovrebbe succedere
        }
        stopPlayback();

        short[] pcm = convertToPcm16(samples);
        int bufferSizeBytes = pcm.length * 2; // PCM 16 bit = 2 byte per campione
        playbackTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufferSizeBytes)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        // MODE_STATIC: si scrive il buffer intero una volta sola e poi play() avvia la
        // riproduzione sul thread audio di sistema senza bloccare qui.
        playbackTrack.write(pcm, 0, pcm.length);
        playbackTrack.play();

        // Rilascio automatico a fine audio: vale SOLO per questa riproduzione. Se nel frattempo
        // ne e' partita un'altra (o e' gia' stata fermata), playbackTrack non e' piu' questo
        // track e il callback non deve toccare niente, altrimenti troncherebbe a meta' la
        // riproduzione successiva.
        AudioTrack startedTrack = playbackTrack;
        long durationMs = Math.round(pcm.length / (double) sampleRateHz * 1000) + 200;
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (playbackTrack == startedTrack) {
                stopPlayback();
            }
        }, durationMs);
    }

    // Ferma la riproduzione in corso e rilascia l'AudioTrack. E' sempre sicuro chiamarlo, anche
    // quando non c'e' niente in riproduzione (nessuna registrazione ancora riascoltata, audio
    // gia' finito da solo, oppure stopPlayback() gia' chiamato poco prima): in quei casi
    // playbackTrack e' null e il metodo esce subito senza fare nulla.
    private void stopPlayback() {
        AudioTrack track = playbackTrack;
        if (track == null) {
            return;
        }
        // Azzerato PRIMA di toccare il track: cosi' una seconda chiamata (o il callback di fine
        // audio) trova gia' null e non prova a fermare/rilasciare due volte lo stesso oggetto.
        playbackTrack = null;
        try {
            // stop() lancia IllegalStateException su un track non inizializzato (costruzione
            // fallita) ed e' inutile su uno gia' fermo (audio finito da solo): in entrambi i
            // casi resta solo da rilasciarlo.
            if (track.getState() == AudioTrack.STATE_INITIALIZED
                    && track.getPlayState() != AudioTrack.PLAYSTATE_STOPPED) {
                track.stop();
            }
        } catch (IllegalStateException e) {
            Log.w(TAG, "Stop della riproduzione fallito", e);
        } finally {
            // release() sempre, anche se stop() e' fallito: altrimenti le risorse audio di
            // sistema resterebbero appese fino alla morte del processo.
            track.release();
        }
    }

    // Conversione da double normalizzato [-1,1] (lo stesso formato che riceve il DSP) a PCM 16
    // bit per la riproduzione. Il clamp e' solo una sicurezza: PhoneMicRecorder normalizza
    // dividendo per 32768, quindi non dovrebbe mai superare [-1,1], ma un valore fuori range qui
    // darebbe un cast scorretto invece di un errore chiaro.
    private static short[] convertToPcm16(double[] samples) {
        short[] pcm = new short[samples.length];
        for (int i = 0; i < samples.length; i++) {
            double clamped = Math.max(-1.0, Math.min(1.0, samples[i]));
            pcm[i] = (short) Math.round(clamped * 32767.0);
        }
        return pcm;
    }

    // ---------------------------------------------------------------------
    // STEP 1: vocale sostenuta -> feature di qualita' vocale
    // ---------------------------------------------------------------------

    private void recordSustainedVowel() {
        // Permesso microfono (punto 2): controllato PRIMA di toccare qualunque stato della UI,
        // cosi' se manca (o viene negato) la schermata resta esattamente com'era, pronta per un
        // nuovo tentativo non appena il permesso e' concesso.
        if (!PhoneMicRecorder.hasPermission(this)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD_AUDIO_VOWEL);
            return;
        }
        startVowelRecordingFlow();
    }

    private void startVowelRecordingFlow() {
        // Stato IN CORSO: nascondo "Avvia registrazione" invece di solo disabilitarlo, cosi' la
        // UI ha un solo bottone "attivo" alla volta (Annulla e ripeti) invece di un bottone
        // disabilitato che occupa spazio senza fare nulla accanto a un altro che fa la stessa cosa.
        buttonRecordVowel.setVisibility(View.GONE);
        buttonRetryVowel.setVisibility(View.VISIBLE);
        buttonContinueToSpeech.setVisibility(View.GONE);

        // Punto 1: conto alla rovescia PRIMA di avviare davvero la registrazione. La vocale dura
        // solo 3 secondi, quindi l'utente deve gia' stare dicendo "a" quando parte la
        // registrazione vera: l'istruzione resta visibile insieme al numero per tutto il conto,
        // non solo alla fine (a differenza del parlato, vedi startSpeechCountdown).
        textStatusVowel.setVisibility(View.GONE);
        textCountdownVowel.setVisibility(View.VISIBLE);
        startVowelCountdown(COUNTDOWN_SECONDS);
    }

    private void startVowelCountdown(int fromSeconds) {
        vowelCountdownTimer = new CountDownTimer(fromSeconds * COUNTDOWN_TICK_MS, COUNTDOWN_TICK_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) Math.ceil(millisUntilFinished / (double) COUNTDOWN_TICK_MS);
                textCountdownVowel.setText(getString(R.string.countdown_vowel_tick_format, secondsLeft));
            }

            @Override
            public void onFinish() {
                textCountdownVowel.setVisibility(View.GONE);
                textStatusVowel.setVisibility(View.VISIBLE);
                startVowelRecording();
            }
        };
        // Primo tick subito (altrimenti il numero "3" apparirebbe solo dopo 1s di schermo vuoto,
        // col rischio che l'utente inizi a vocalizzare in ritardo rispetto a quanto si aspetta).
        textCountdownVowel.setText(getString(R.string.countdown_vowel_tick_format, fromSeconds));
        vowelCountdownTimer.start();
    }

    // Avvia la vera registrazione dal microfono interno, chiamato da
    // startVowelCountdown.onFinish. Mostra l'indicatore "registrazione attiva" per tutta la
    // durata (niente fase di attesa separata col cell).
    private void startVowelRecording() {
        textStatusVowel.setText(R.string.status_recording);
        startRecordingActivePhase(getString(R.string.recording_active_vowel), buttonRetryVowel);

        // Le etichette "M"/"F" sono lette qui (main thread, serve un Context per getString) e
        // passate al task: il confronto col campo "sesso" salvato nel DB avviene in background.
        String sessoMaschileLabel = getString(R.string.sesso_m);
        String sessoFemminileLabel = getString(R.string.sesso_f);
        pendingVowelTask = new FetchVowelTask(db, userId, sessoMaschileLabel, sessoFemminileLabel);
        pendingVowelTask.execute();
    }

    // Chiamato da FetchVowelTask.onPostExecute, gia' sul main thread.
    private void onVowelFetchResult(VowelResult result) {
        pendingVowelTask = null;
        // vowelSamples va assegnato PRIMA di hideRecordingActivePhase(): quel metodo decide se
        // riabilitare il pulsante di riascolto leggendo proprio questo campo, quindi se lo
        // aggiornassimo dopo leggerebbe ancora il valore vecchio (null alla prima registrazione)
        // e il pulsante resterebbe disabilitato anche a registrazione riuscita.
        vowelSamples = result.samples;
        vowelSampleRateHz = result.sampleRateHz;
        hideRecordingActivePhase();
        if (result.success) {
            f0Mean = result.f0Hz;
            cpp = result.cpp;
            jitter = result.jitterPercent;
            shimmer = result.shimmerPercent;
            vowelDurationMs = result.durationMs;
            onVowelRecorded();
            // Le feature vengono comunque salvate: avviso l'utente ma non blocco il flusso,
            // e' lui a decidere se rifare la registrazione con "Annulla e ripeti".
            if (result.clipped) {
                Toast.makeText(this, R.string.warning_signal_clipped, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.w(TAG, "Registrazione (vocale) fallita: " + result.errorMessage);
            Toast.makeText(this, R.string.error_microphone_recording, Toast.LENGTH_LONG).show();
            resetVowelStep();
        }
    }

    private void onVowelRecorded() {
        vowelRecorded = true;
        // Stato COMPLETATO: "Avvia registrazione" resta nascosto (impostato gia' in
        // startVowelRecordingFlow), esplicitato di nuovo qui per rendere lo stato autoconsistente.
        buttonRecordVowel.setVisibility(View.GONE);
        textStatusVowel.setText(getString(R.string.status_recorded, vowelDurationMs / 1000.0));
        buttonContinueToSpeech.setVisibility(View.VISIBLE);
    }

    private void resetVowelStep() {
        // "Annulla e ripeti" annulla anche il riascolto: la registrazione sta per essere
        // scartata (vowelSamples viene azzerato qui sotto), non ha senso continuare a sentirla.
        stopPlayback();
        if (vowelCountdownTimer != null) {
            // "Annulla e ripeti" puo' arrivare anche DURANTE il conto alla rovescia (e' visibile
            // da quando si preme "Avvia registrazione"), non solo durante la registrazione vera.
            vowelCountdownTimer.cancel();
            vowelCountdownTimer = null;
        }
        if (pendingVowelTask != null) {
            // cancel(false) segna il task come annullato (salta onPostExecute) ma NON sblocca
            // una lettura del microfono gia' in corso: stopRecording() ferma l'AudioRecord,
            // cosi' il vecchio tentativo si ferma davvero subito invece di restare a girare in
            // background.
            pendingVowelTask.cancel(false);
            pendingVowelTask.stopRecording();
            pendingVowelTask = null;
        }
        hideRecordingActivePhase();
        vowelRecorded = false;
        vowelDurationMs = 0;
        f0Mean = null;
        cpp = null;
        jitter = null;
        shimmer = null;
        vowelSamples = null;
        vowelSampleRateHz = 0;
        // Stato INIZIALE.
        buttonRecordVowel.setVisibility(View.VISIBLE);
        textCountdownVowel.setVisibility(View.GONE);
        textStatusVowel.setVisibility(View.VISIBLE);
        textStatusVowel.setText(R.string.status_waiting);
        buttonRetryVowel.setVisibility(View.GONE);
        buttonPlaybackVowel.setEnabled(false);
        buttonContinueToSpeech.setVisibility(View.GONE);
    }

    private void showSpeechStep() {
        layoutStepVowel.setVisibility(View.GONE);
        buttonRetryVowel.setVisibility(View.GONE);
        layoutStepSpeech.setVisibility(View.VISIBLE);
    }

    // Risultato di FetchVowelTask: successo oppure fallimento+messaggio. F0/CPP vengono
    // dall'analisi cepstrale (F0 "ufficiale", quella salvata come f0Mean); jitter/shimmer da
    // un'analisi SEPARATA ad autocorrelazione (SpectralFeatureExtractor), tornata valida ora che
    // il segnale e' a 44100Hz - vedi commento in FetchVowelTask.doInBackground. "clipped" segnala
    // che il segnale ricevuto era probabilmente saturato (microfono troppo vicino/volume alto):
    // le feature vengono comunque calcolate, ma non sono affidabili. "samples"/"sampleRateHz":
    // servono al riascolto permanente (button_playback_vowel), vedi playRecording.
    private static class VowelResult {
        final boolean success;
        final double f0Hz;
        final double cpp;
        final double jitterPercent;
        final double shimmerPercent;
        final long durationMs;
        final boolean clipped;
        final String errorMessage;
        final double[] samples;
        final int sampleRateHz;

        private VowelResult(boolean success, double f0Hz, double cpp, double jitterPercent, double shimmerPercent,
                             long durationMs, boolean clipped, String errorMessage, double[] samples, int sampleRateHz) {
            this.success = success;
            this.f0Hz = f0Hz;
            this.cpp = cpp;
            this.jitterPercent = jitterPercent;
            this.shimmerPercent = shimmerPercent;
            this.durationMs = durationMs;
            this.clipped = clipped;
            this.errorMessage = errorMessage;
            this.samples = samples;
            this.sampleRateHz = sampleRateHz;
        }

        static VowelResult success(double f0Hz, double cpp, double jitterPercent, double shimmerPercent,
                                    long durationMs, boolean clipped, double[] samples, int sampleRateHz) {
            return new VowelResult(true, f0Hz, cpp, jitterPercent, shimmerPercent, durationMs, clipped,
                    null, samples, sampleRateHz);
        }

        static VowelResult failure(String errorMessage) {
            return new VowelResult(false, 0, 0, 0, 0, 0, false, errorMessage, null, 0);
        }
    }

    private class FetchVowelTask extends AsyncTask<Void, Void, VowelResult> {

        private final DatabaseHelper db;
        private final int userId;
        private final String sessoMaschileLabel;
        private final String sessoFemminileLabel;
        private final PhoneMicRecorder.Cancellable cancellable = new PhoneMicRecorder.Cancellable();

        FetchVowelTask(
                DatabaseHelper db,
                int userId,
                String sessoMaschileLabel,
                String sessoFemminileLabel) {

            this.db = db;
            this.userId = userId;
            this.sessoMaschileLabel = sessoMaschileLabel;
            this.sessoFemminileLabel = sessoFemminileLabel;
        }

        // Chiamato da resetVowelStep()/onDestroy(): ferma subito la registrazione in corso.
        void stopRecording() {
            cancellable.stop();
        }

        @SuppressWarnings("MissingPermission") // il permesso e' gia' verificato prima di avviare questo task (vedi recordSustainedVowel)
        @Override
        protected VowelResult doInBackground(Void... voids) {
            try {
                PhoneMicRecorder.Result fetched = PhoneMicRecorder.record(VOWEL_RECORDING_DURATION_MS, cancellable);

                // Range F0 adattivo in base al sesso dell'utente: riduce gli errori di ottava
                // rispetto al range generico 75-500Hz (vedi commento su CepstralFeatureExtractor).
                User user = db.findUserById(userId);
                String sesso = user != null ? user.getSesso() : null;

                double minF0Hz;
                double maxF0Hz;
                if (sessoMaschileLabel.equals(sesso)) {
                    minF0Hz = CepstralFeatureExtractor.MALE_MIN_F0_HZ;
                    maxF0Hz = CepstralFeatureExtractor.MALE_MAX_F0_HZ;
                } else if (sessoFemminileLabel.equals(sesso)) {
                    minF0Hz = CepstralFeatureExtractor.FEMALE_MIN_F0_HZ;
                    maxF0Hz = CepstralFeatureExtractor.FEMALE_MAX_F0_HZ;
                } else {
                    // "Altro", sesso non specificato, o utente non trovato: fallback sicuro al
                    // range generico 75-500Hz (lo stesso che userebbe di default analyze()).
                    minF0Hz = CepstralFeatureExtractor.DEFAULT_MIN_F0_HZ;
                    maxF0Hz = CepstralFeatureExtractor.DEFAULT_MAX_F0_HZ;
                }

                // Analisi cepstrale: F0 e CPP.
                CepstralFeatureExtractor.CepstralResult cepstralResult =
                        CepstralFeatureExtractor.analyze(fetched.samples, fetched.sampleRateHz, minF0Hz, maxF0Hz);
                double f0Hz = cepstralResult.f0Hz;
                double cpp = cepstralResult.cpp;

                // Jitter/shimmer
                SpectralFeatureExtractor.PitchEstimate pitch =
                        SpectralFeatureExtractor.estimateF0(fetched.samples, fetched.sampleRateHz, minF0Hz, maxF0Hz);
                SpectralFeatureExtractor.CycleFeatures cycles =
                        SpectralFeatureExtractor.analyzeCycles(fetched.samples, pitch.periodSamples);
                double jitterPercent = SpectralFeatureExtractor.computeJitterPercent(cycles.periodsSamples);
                double shimmerPercent = SpectralFeatureExtractor.computeShimmerPercent(cycles.peakAmplitudes);

                long durationMs = Math.round(fetched.samples.length / (double) fetched.sampleRateHz * 1000);
                return VowelResult.success(f0Hz, cpp, jitterPercent, shimmerPercent,
                        durationMs, fetched.clipped, fetched.samples, fetched.sampleRateHz);
            } catch (Exception e) {
                return VowelResult.failure(e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(VowelResult result) {
            if (isFinishing()) {
                return;
            }

            onVowelFetchResult(result);
        }
    }

    // ---------------------------------------------------------------------
    // STEP 2: parlato connesso -> feature temporali/prosodiche
    // ---------------------------------------------------------------------

    private void recordConnectedSpeech() {
        // Permesso microfono: vedi commento analogo in recordSustainedVowel. Praticamente non
        // dovrebbe mai servire richiederlo di nuovo qui (concesso una volta per la vocale, resta
        // valido), ma vabb.
        if (!PhoneMicRecorder.hasPermission(this)) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, PERMISSION_REQUEST_RECORD_AUDIO_SPEECH);
            return;
        }
        startSpeechRecordingFlow();
    }

    private void startSpeechRecordingFlow() {
        // Stato IN CORSO: stesso ragionamento di recordSustainedVowel, bottone nascosto non solo disabilitato.
        buttonRecordSpeech.setVisibility(View.GONE);
        buttonRetrySpeech.setVisibility(View.VISIBLE);
        buttonSaveSession.setVisibility(View.GONE);

        // Punto 1: conto alla rovescia anche qui, meno critico che per la vocale (non serve che
        // l'utente stia gia' parlando in anticipo) quindi e' una sequenza 3, 2, 1, "Parla
        // liberamente" invece di un'istruzione persistente col numero.
        textStatusSpeech.setVisibility(View.GONE);
        textCountdownSpeech.setVisibility(View.VISIBLE);
        startSpeechCountdown(COUNTDOWN_SECONDS);
    }

    private void startSpeechCountdown(int fromSeconds) {
        speechCountdownTimer = new CountDownTimer(fromSeconds * COUNTDOWN_TICK_MS, COUNTDOWN_TICK_MS) {
            @Override
            public void onTick(long millisUntilFinished) {
                int secondsLeft = (int) Math.ceil(millisUntilFinished / (double) COUNTDOWN_TICK_MS);
                textCountdownSpeech.setText(getString(R.string.countdown_speech_tick_format, secondsLeft));
            }

            @Override
            public void onFinish() {
                // "Il via": resta visibile un momento (COUNTDOWN_GO_BEAT_MS) prima di avviare
                // davvero la registrazione, altrimenti l'utente non avrebbe il tempo di leggerlo
                // (sparirebbe subito, sostituito dall'indicatore "registrazione attiva").
                textCountdownSpeech.setText(R.string.countdown_speech_go);
                new Handler(Looper.getMainLooper()).postDelayed(
                        SessionFlowActivity.this::startSpeechRecording, COUNTDOWN_GO_BEAT_MS);
            }
        };
        textCountdownSpeech.setText(getString(R.string.countdown_speech_tick_format, fromSeconds));
        speechCountdownTimer.start();
    }

    // Avvia la vera registrazione, chiamato da startSpeechCountdown.onFinish (dopo il beat del
    // "via"). Mostra l'indicatore "registrazione attiva" per tutta la durata (niente fase di
    // attesa separata: col microfono interno non c'e' trasferimento dati).
    private void startSpeechRecording() {
        textCountdownSpeech.setVisibility(View.GONE);
        textStatusSpeech.setVisibility(View.VISIBLE);
        textStatusSpeech.setText(R.string.status_recording);
        startRecordingActivePhase(getString(R.string.recording_active_speech), buttonRetrySpeech);

        pendingSpeechTask = new FetchSpeechTask();
        pendingSpeechTask.execute();
    }

    // Chiamato da FetchSpeechTask.onPostExecute, gia' sul main thread.
    private void onSpeechFetchResult(SpeechResult result) {
        pendingSpeechTask = null;
        // Vedi commento analogo in onVowelFetchResult(): speechSamples va assegnato PRIMA di
        // hideRecordingActivePhase(), che decide di riabilitare il pulsante di riascolto
        // leggendo proprio questo campo.
        speechSamples = result.samples;
        speechSampleRateHz = result.sampleRateHz;
        hideRecordingActivePhase();
        if (result.success) {
            energyMean = result.energyMean;
            energyStd = result.energyStd;
            pauseRatio = result.pauseRatio;
            f0Std = result.f0Std;
            speechDurationMs = result.durationMs;
            onSpeechRecorded();
            if (result.clipped) {
                Toast.makeText(this, R.string.warning_signal_clipped, Toast.LENGTH_LONG).show();
            }
        } else {
            Log.w(TAG, "Registrazione (parlato) fallita: " + result.errorMessage);
            Toast.makeText(this, R.string.error_microphone_recording, Toast.LENGTH_LONG).show();
            resetSpeechStep();
        }
    }

    private void onSpeechRecorded() {
        speechRecorded = true;
        // Stato COMPLETATO: vedi commento analogo in onVowelRecorded.
        buttonRecordSpeech.setVisibility(View.GONE);
        textStatusSpeech.setText(getString(R.string.status_recorded, speechDurationMs / 1000.0));
        buttonSaveSession.setVisibility(View.VISIBLE);
    }

    private void resetSpeechStep() {
        // Vedi commento analogo in resetVowelStep(): annullando si scarta la registrazione,
        // quindi va fermato anche il suo riascolto.
        stopPlayback();
        if (speechCountdownTimer != null) {
            // Vedi commento analogo in resetVowelStep(): "Annulla e ripeti" puo' arrivare anche
            // durante il conto alla rovescia, non solo durante la registrazione vera.
            speechCountdownTimer.cancel();
            speechCountdownTimer = null;
        }
        if (pendingSpeechTask != null) {
            // Vedi commento analogo in resetVowelStep(): serve fermare davvero l'AudioRecord.
            pendingSpeechTask.cancel(false);
            pendingSpeechTask.stopRecording();
            pendingSpeechTask = null;
        }
        hideRecordingActivePhase();
        speechRecorded = false;
        speechDurationMs = 0;
        energyMean = null;
        energyStd = null;
        pauseRatio = null;
        f0Std = null;
        speechSamples = null;
        speechSampleRateHz = 0;
        // Stato INIZIALE.
        buttonRecordSpeech.setVisibility(View.VISIBLE);
        textCountdownSpeech.setVisibility(View.GONE);
        textStatusSpeech.setVisibility(View.VISIBLE);
        textStatusSpeech.setText(R.string.status_waiting);
        buttonRetrySpeech.setVisibility(View.GONE);
        buttonPlaybackSpeech.setEnabled(false);
        buttonSaveSession.setVisibility(View.GONE);
    }

    // Risultato di FetchSpeechTask: successo (feature + durata reale) oppure fallimento+messaggio.
    // "clipped" segnala segnale probabilmente saturato, vedi commento analogo su VowelResult.
    // "samples"/"sampleRateHz": servono al riascolto permanente (button_playback_speech).
    private static class SpeechResult {
        final boolean success;
        final double energyMean;
        final double energyStd;
        final double pauseRatio;
        final double f0Std;
        final long durationMs;
        final boolean clipped;
        final String errorMessage;
        final double[] samples;
        final int sampleRateHz;

        private SpeechResult(boolean success, double energyMean, double energyStd,
                              double pauseRatio, double f0Std, long durationMs, boolean clipped, String errorMessage,
                              double[] samples, int sampleRateHz) {
            this.success = success;
            this.energyMean = energyMean;
            this.energyStd = energyStd;
            this.pauseRatio = pauseRatio;
            this.f0Std = f0Std;
            this.durationMs = durationMs;
            this.clipped = clipped;
            this.errorMessage = errorMessage;
            this.samples = samples;
            this.sampleRateHz = sampleRateHz;
        }

        static SpeechResult success(double energyMean, double energyStd, double pauseRatio,
                                     double f0Std, long durationMs, boolean clipped, double[] samples, int sampleRateHz) {
            return new SpeechResult(true, energyMean, energyStd, pauseRatio, f0Std, durationMs, clipped,
                    null, samples, sampleRateHz);
        }

        static SpeechResult failure(String errorMessage) {
            return new SpeechResult(false, 0, 0, 0, 0, 0, false, errorMessage, null, 0);
        }
    }

    private class FetchSpeechTask extends AsyncTask<Void, Void, SpeechResult> {

        private final PhoneMicRecorder.Cancellable cancellable = new PhoneMicRecorder.Cancellable();

        // Chiamato da resetSpeechStep()/onDestroy(): ferma subito la registrazione in corso.
        void stopRecording() {
            cancellable.stop();
        }

        @SuppressWarnings("MissingPermission") // il permesso e' gia' verificato prima di avviare questo task (vedi recordConnectedSpeech)
        @Override
        protected SpeechResult doInBackground(Void... voids) {
            try {
                PhoneMicRecorder.Result fetched = PhoneMicRecorder.record(SPEECH_RECORDING_DURATION_MS, cancellable);

                double energyMean = TemporalFeatureExtractor.computeRmsEnergy(fetched.samples);
                double energyStd = TemporalFeatureExtractor.computeEnergyStd(
                        fetched.samples, fetched.sampleRateHz, WINDOW_DURATION_MS);

                TemporalFeatureExtractor.VoiceActivityResult vad = TemporalFeatureExtractor.detectVoiceActivity(
                        fetched.samples, fetched.sampleRateHz, WINDOW_DURATION_MS, VAD_THRESHOLD_RATIO);
                TemporalFeatureExtractor.SpeechTimingFeatures timing =
                        TemporalFeatureExtractor.computeSpeechTimingFeatures(vad);

                TemporalFeatureExtractor.F0VariabilityResult f0Variability = TemporalFeatureExtractor.computeF0Variability(
                        fetched.samples, fetched.sampleRateHz, F0_WINDOW_DURATION_MS, VAD_THRESHOLD_RATIO,
                        F0_MIN_HZ, F0_MAX_HZ);

                long durationMs = Math.round(fetched.samples.length / (double) fetched.sampleRateHz * 1000);

                return SpeechResult.success(energyMean, energyStd, timing.pauseRatio,
                        f0Variability.f0StdHz, durationMs, fetched.clipped, fetched.samples, fetched.sampleRateHz);
            } catch (Exception e) {
                return SpeechResult.failure(e.getMessage());
            }
        }

        @Override
        protected void onPostExecute(SpeechResult result) {

            if (isFinishing()) {
                return;
            }

            onSpeechFetchResult(result);
        }
    }

    // ---------------------------------------------------------------------
    // STEP 3: salvataggio
    // ---------------------------------------------------------------------

    private void saveSession() {
        buttonSaveSession.setEnabled(false);

        Session session = new Session();
        session.setUserId(userId);
        session.setTimestamp(System.currentTimeMillis());
        session.setDurata(vowelDurationMs + speechDurationMs);

        // Feature temporali/prosodiche: dal parlato connesso (STEP 2). f0Std e' la variabilita'
        // di F0 sul parlato (deviazione standard finestra per finestra sui tratti vocalizzati),
        // NON collegata a f0Mean sotto (vedi commento sul campo f0Std piu' in alto).
        session.setEnergyMean(energyMean);
        session.setEnergyStd(energyStd);
        session.setPauseRatio(pauseRatio);
        session.setF0Std(f0Std);

        // Feature spettrali/di qualita' vocale: dalla vocale sostenuta (STEP 1). F0 "ufficiale"
        // e CPP dall'analisi cepstrale; jitter/shimmer dall'analisi ad autocorrelazione separata
        // (SpectralFeatureExtractor), tornata valida ora che il segnale e' a 44100Hz (branch
        // "telefono") - vedi commento in FetchVowelTask.doInBackground. Colonne DB gia'
        // esistenti (non rimosse quando fu introdotto il CPP), qui riusate senza migrazione.
        session.setF0Mean(f0Mean);
        session.setCpp(cpp);
        session.setJitter(jitter);
        session.setShimmer(shimmer);

        new SaveSessionTask(db).execute(session);
    }

    // Chiamato da SaveSessionTask.onPostExecute, quindi gia' sul main thread.
    private void onSessionSaved() {
        Toast.makeText(this, R.string.session_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private class SaveSessionTask extends AsyncTask<Session, Void, Void> {

        private final DatabaseHelper db;

        SaveSessionTask(DatabaseHelper db) {
            this.db = db;
        }

        @Override
        protected Void doInBackground(Session... sessions) {
            db.insertSession(sessions[0]);
            return null;
        }

        @Override
        protected void onPostExecute(Void result) {

            if (isFinishing()) {
                return;
            }

            onSessionSaved();
        }
    }
}
