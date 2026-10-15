package com.filippo.VoiceTrackingApp.ui.session;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import androidx.annotation.RequiresPermission;
import androidx.core.content.ContextCompat;

import java.io.IOException;

public final class PhoneMicRecorder {

    private static final int PREFERRED_SAMPLE_RATE_HZ = 44100;
    private static final int FALLBACK_SAMPLE_RATE_HZ = 48000;

    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int ENCODING = AudioFormat.ENCODING_PCM_16BIT;

    // Fondoscala di un campione PCM 16 bit con segno: [-32768, 32767]. Un campione entro
    // CLIPPING_MARGIN da uno dei due estremi conta come "al limite".
    private static final int PCM16_MAX = 32767;
    private static final int PCM16_MIN = -32768;
    private static final int CLIPPING_MARGIN = 0;

    private PhoneMicRecorder() {
    }

    public static class Result {
        public final int sampleRateHz;
        public final double[] samples;
        public final boolean clipped;

        Result(int sampleRateHz, double[] samples, boolean clipped) {
            this.sampleRateHz = sampleRateHz;
            this.samples = samples;
            this.clipped = clipped;
        }
    }

    // Permette di interrompere una record() in corso da un altro thread: ferma esplicitamente
    // l'AudioRecord per sbloccare la read() bloccante in corso su di esso.
    public static class Cancellable {
        private volatile AudioRecord audioRecord;

        private synchronized void setAudioRecord(AudioRecord audioRecord) {
            this.audioRecord = audioRecord;
        }

        public synchronized void stop() {
            if (audioRecord != null) {
                try {
                    audioRecord.stop();
                } catch (IllegalStateException e) {
                    // gia' fermo o mai avviato: non importa, l'obiettivo era solo sbloccare
                    // una eventuale read() in corso su un altro thread.
                }
            }
        }
    }

    // Da controllare PRIMA di chiamare record(): costruire un AudioRecord senza il permesso
    // RECORD_AUDIO non lancia un'eccezione Java, l'oggetto resta semplicemente in uno stato non
    // inizializzato (vedi il controllo su getState() sotto) - un errore silenzioso facile da
    // scambiare per un problema hardware se non si esclude prima il permesso mancante.
    public static boolean hasPermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    // Registra per durationMs dal microfono interno. Bloccante: va sempre chiamata da un thread
    // in background (AsyncTask), mai dal main thread. Il chiamante deve aver gia' verificato
    // hasPermission() prima di invocarla (@RequiresPermission lo segnala anche staticamente).
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    public static Result record(long durationMs, Cancellable cancellable) throws IOException {
        int sampleRateHz = pickSupportedSampleRate();

        int minBufferSizeBytes = AudioRecord.getMinBufferSize(sampleRateHz, CHANNEL_CONFIG, ENCODING);
        // Buffer interno di AudioRecord piu' largo del minimo: se il nostro ciclo di lettura
        // restasse indietro anche solo per un istante (es. il thread e' momentaneamente
        // altrove), un bufferl minimo perderebbe campioni per overrun
        // invece di limitarsi ad accumularli in attesa che la read() li finicsa.
        int bufferSizeBytes = minBufferSizeBytes * 4;

        AudioRecord audioRecord = new AudioRecord(MediaRecorder.AudioSource.MIC,
                sampleRateHz, CHANNEL_CONFIG, ENCODING, bufferSizeBytes);

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            audioRecord.release();
            throw new IOException("Impossibile inizializzare il microfono (AudioRecord non pronto)");
        }

        if (cancellable != null) {
            cancellable.setAudioRecord(audioRecord);
        }

        int totalSamplesNeeded = (int) Math.round(sampleRateHz * durationMs / 1000.0);
        short[] rawSamples = new short[totalSamplesNeeded];

        try {
            audioRecord.startRecording();
            if (audioRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                throw new IOException("Impossibile avviare la registrazione (microfono occupato da un'altra app?)");
            }

            // Lettura bloccante: read() restituisce campioni via via che il driver audio li
            // cattura, quindi questo ciclo impiega naturalmente circa durationMs a completarsi
            // (non serve un timer separato). Un stop() da un altro thread ferma l'AudioRecord,
            // facendo tornare read() con un valore <= 0: trattato come interruzione, non come
            // overrun (che restituirebbe comunque un conteggio positivo, solo con dei campioni
            // persi silenziosamente lato driver, non rilevabile da qui).
            int totalRead = 0;
            while (totalRead < totalSamplesNeeded) {
                int read = audioRecord.read(rawSamples, totalRead, totalSamplesNeeded - totalRead);
                if (read <= 0) {
                    throw new IOException("Registrazione interrotta (codice " + read + ")");
                }
                totalRead += read;
            }
        } finally {
            audioRecord.stop();
            audioRecord.release();
        }

        boolean clipped = false;
        double[] samples = new double[totalSamplesNeeded];
        for (int i = 0; i < totalSamplesNeeded; i++) {
            samples[i] = rawSamples[i] / 32768.0;
            if (rawSamples[i] >= PCM16_MAX - CLIPPING_MARGIN || rawSamples[i] <= PCM16_MIN + CLIPPING_MARGIN) {
                clipped = true;
            }
        }

        return new Result(sampleRateHz, samples, clipped);
    }

    private static int pickSupportedSampleRate() throws IOException {
        if (isSampleRateSupported(PREFERRED_SAMPLE_RATE_HZ)) {
            return PREFERRED_SAMPLE_RATE_HZ;
        }
        if (isSampleRateSupported(FALLBACK_SAMPLE_RATE_HZ)) {
            return FALLBACK_SAMPLE_RATE_HZ;
        }
        throw new IOException("Il microfono di questo device non supporta ne' 44100Hz ne' 48000Hz");
    }

    private static boolean isSampleRateSupported(int sampleRateHz) {
        int minBufferSizeBytes = AudioRecord.getMinBufferSize(sampleRateHz, CHANNEL_CONFIG, ENCODING);
        return minBufferSizeBytes != AudioRecord.ERROR && minBufferSizeBytes != AudioRecord.ERROR_BAD_VALUE;
    }
}
