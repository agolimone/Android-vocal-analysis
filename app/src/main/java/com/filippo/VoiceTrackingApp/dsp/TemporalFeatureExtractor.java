package com.filippo.VoiceTrackingApp.dsp;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

// Feature TEMPORALI/prosodiche su un segnale di parlato connesso: energia, VAD, pause.
// F0/jitter/shimmer/HNR (qualita' della voce, su vocale sostenuta) sono un'altra classe, step successivo.
//
// I campioni si assumono double normalizzati in [-1, 1] (es. PCM 16 bit diviso 32768): scelto invece
// di short[] perche' tutta la matematica (RMS, deviazione standard) e' su numeri reali e con double
// non bisogna preoccuparsi di overflow/troncamenti a meta' calcolo; la conversione da short[] a double[]
// (normalizzazione) va fatta una sola volta quando il campione arriva dal microfono.
public final class TemporalFeatureExtractor {

    private TemporalFeatureExtractor() {
        // Solo metodi statici: nessuna istanza ha senso.
    }

    // ---------------------------------------------------------------------
    // 1. Energia RMS del segnale intero
    // ---------------------------------------------------------------------

    // RMS (Root Mean Square): radice della media dei quadrati dei campioni.
    // E' la misura standard di "energia media" di un segnale audio: elevare al quadrato
    // rende tutto positivo (altrimenti campioni negativi e positivi si annullerebbero in media)
    // e la radice finale riporta il risultato nella stessa scala di ampiezza dei campioni originali.
    public static double computeRmsEnergy(double[] samples) {
        if (samples == null || samples.length == 0) {
            return 0.0;
        }
        double sumSquares = 0.0;
        for (double sample : samples) {
            sumSquares += sample * sample;
        }
        return Math.sqrt(sumSquares / samples.length);
    }

    // ---------------------------------------------------------------------
    // 2. Deviazione standard dell'energia a finestre
    // ---------------------------------------------------------------------

    // Energia RMS calcolata su ogni finestra di "windowSizeSamples" campioni (l'ultima finestra
    // puo' essere piu' corta se il segnale non e' un multiplo esatto della dimensione finestra).
    public static double[] computeWindowEnergies(double[] samples, int windowSizeSamples) {
        if (samples == null || samples.length == 0 || windowSizeSamples <= 0) {
            return new double[0];
        }
        int numWindows = (int) Math.ceil((double) samples.length / windowSizeSamples);
        double[] energies = new double[numWindows];
        for (int w = 0; w < numWindows; w++) {
            int start = w * windowSizeSamples;
            int end = Math.min(start + windowSizeSamples, samples.length);
            double sumSquares = 0.0;
            for (int i = start; i < end; i++) {
                sumSquares += samples[i] * samples[i];
            }
            energies[w] = Math.sqrt(sumSquares / (end - start));
        }
        return energies;
    }

    // Quanto l'energia "salta" nel tempo: parlato naturale alterna sillabe forti/deboli e pause,
    // quindi una deviazione standard alta e' plausibile per parlato normale, bassa per un tono
    // costante o per un segnale quasi tutto silenzioso.
    public static double computeEnergyStd(double[] samples, int sampleRate, double windowDurationMs) {
        double[] energies = computeWindowEnergies(samples, millisecondsToSamples(sampleRate, windowDurationMs));
        return standardDeviation(energies);
    }

    // ---------------------------------------------------------------------
    // 3. Voice Activity Detection (VAD) a soglia
    // ---------------------------------------------------------------------

    // Risultato del VAD: quali finestre sono "voce" e quali "silenzio", piu' i parametri usati per
    // calcolarle. Li tengo insieme (invece di restituire solo il boolean[]) cosi' chi consuma il
    // risultato non puo' passare per sbaglio una windowDurationMs diversa da quella usata per il VAD.
    public static class VoiceActivityResult {
        public final boolean[] voicedWindows;
        public final double windowDurationMs;
        public final double energyThreshold;

        public VoiceActivityResult(boolean[] voicedWindows, double windowDurationMs, double energyThreshold) {
            this.voicedWindows = voicedWindows;
            this.windowDurationMs = windowDurationMs;
            this.energyThreshold = energyThreshold;
        }
    }

    // Soglia = thresholdRatio * energia MEDIANA tra le finestre (non piu' il massimo). Una soglia
    // relativa (anziche' un valore assoluto in dB) funziona sia con registrazioni fatte piano sia
    // forte, senza doverla ritarare a mano ogni volta (utile perche' tra una sessione e l'altra il
    // guadagno del microfono puo' cambiare). Il motivo del passaggio dal MASSIMO alla MEDIANA e'
    // la robustezza agli outlier: un singolo picco anomalo (colpo di tosse, click, rumore forte)
    // sposta il massimo moltissimo (il massimo e' definito proprio dal valore piu' estremo), ma
    // sposta la mediana pochissimo, perche' la mediana e' "il valore centrale" della distribuzione
    // delle energie e non dipende da quanto siano estremi i valori sopra/sotto di esso.
    // Nota: col massimo thresholdRatio stava tipicamente tra 0 e 1 (frazione del picco); con la
    // mediana il moltiplicatore utile e' diverso (spesso < 1 ma va ritarato), perche' in un parlato
    // connesso normale piu' finestre sono vocalizzate che silenziose, quindi la mediana cade gia'
    // dentro il livello di energia della voce, non del silenzio.
    public static VoiceActivityResult detectVoiceActivity(double[] samples, int sampleRate,
                                                            double windowDurationMs, double thresholdRatio) {
        double[] energies = computeWindowEnergies(samples, millisecondsToSamples(sampleRate, windowDurationMs));
        double medianEnergy = median(energies);
        double threshold = medianEnergy * thresholdRatio;

        boolean[] voiced = new boolean[energies.length];
        for (int i = 0; i < energies.length; i++) {
            voiced[i] = energies[i] >= threshold;
        }
        return new VoiceActivityResult(voiced, windowDurationMs, threshold);
    }

    // ---------------------------------------------------------------------
    // 4. Pause ratio, numero pause, durata media pause
    // ---------------------------------------------------------------------

    // Soglia minima di durata perche' un run di silenzio conti come una PAUSA vera (pauseCount/
    // meanPauseDurationMs): le pause vere del parlato durano tipicamente oltre 100-200ms, mentre
    // le brevi chiusure meccaniche prima di occlusive (p, t, k) durano molto meno e non sono
    // pause percepite come tali. 150ms e' il compromesso preso nel range 100-200ms indicato in
    // letteratura. Non influisce su pauseRatio, che misura il tempo
    // TOTALE in silenzio (incluse le picole chiusure) e non il numero di pause.
    private static final double MIN_PAUSE_DURATION_MS = 150.0;

    public static class SpeechTimingFeatures {
        public double pauseRatio;
        public int pauseCount;
        public double meanPauseDurationMs;

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "pauseRatio=%.3f, pauseCount=%d, meanPauseDurationMs=%.1f",
                    pauseRatio, pauseCount, meanPauseDurationMs);
        }
    }

    public static SpeechTimingFeatures computeSpeechTimingFeatures(VoiceActivityResult vad) {
        SpeechTimingFeatures result = new SpeechTimingFeatures();
        boolean[] voiced = vad.voicedWindows;
        if (voiced == null || voiced.length == 0) {
            return result;
        }

        int totalWindows = voiced.length;

        int silentWindows = 0;
        for (boolean isVoiced : voiced) {
            if (!isVoiced) {
                silentWindows++;
            }
        }
        result.pauseRatio = (double) silentWindows / totalWindows;

        // Le finestre "silenzio" consecutive formano dei blocchi (run): ogni blocco e' una pausa.
        List<Integer> pauseRunLengths = new ArrayList<>();
        int i = 0;
        while (i < totalWindows) {
            boolean isVoiced = voiced[i];
            int runStart = i;
            while (i < totalWindows && voiced[i] == isVoiced) {
                i++;
            }
            int runLength = i - runStart;
            if (!isVoiced) {
                pauseRunLengths.add(runLength);
            }
        }

        List<Integer> realPauseRunLengths = new ArrayList<>();
        for (int len : pauseRunLengths) {
            double runDurationMs = len * vad.windowDurationMs;
            if (runDurationMs >= MIN_PAUSE_DURATION_MS) {
                realPauseRunLengths.add(len);
            }
        }

        result.pauseCount = realPauseRunLengths.size();

        double totalPauseWindows = 0;
        for (int len : realPauseRunLengths) {
            totalPauseWindows += len;
        }
        result.meanPauseDurationMs = realPauseRunLengths.isEmpty()
                ? 0.0
                : (totalPauseWindows / realPauseRunLengths.size()) * vad.windowDurationMs;

        return result;
    }

    // ---------------------------------------------------------------------
    // 5. F0 media e variabilita' sul parlato (marcatore prosodico)
    // ---------------------------------------------------------------------

    // Risultato: F0 media e deviazione standard (= "variabilita' di F0") sui tratti vocalizzati
    // del parlato, piu' le singole stime finestra per finestra (solo per log/diagnostica).
    // totalVoicedWindowCount e' il numero di finestre VOCALIZZATE (prima del filtro sul range
    // di F0 plausibile): voicedWindowF0sHz.length e' invece quelle poi RISULTATE valide, quindi
    // (totalVoicedWindowCount - voicedWindowF0sHz.length) e' quante sono state scartate.
    public static class F0VariabilityResult {
        public final double f0MeanHz;
        public final double f0StdHz;
        public final double[] voicedWindowF0sHz;
        public final int totalVoicedWindowCount;

        public F0VariabilityResult(double f0MeanHz, double f0StdHz, double[] voicedWindowF0sHz,
                                    int totalVoicedWindowCount) {
            this.f0MeanHz = f0MeanHz;
            this.f0StdHz = f0StdHz;
            this.voicedWindowF0sHz = voicedWindowF0sHz;
            this.totalVoicedWindowCount = totalVoicedWindowCount;
        }
    }

    // F0 stimata finestra per finestra sul parlato connesso, con AUTOCORRELAZIONE
    // (SpectralFeatureExtractor.estimateF0): a differenza del cepstrum, usato per la vocale
    // sostenuta, l'autocorrelazione funziona bene anche su finestre brevi come queste (poche
    // decine di campioni), perche' cerca solo il picco di correlazione entro la banda
    // [minF0Hz, maxF0Hz], senza bisogno di una FFT su un blocco lungo per avere risoluzione in
    // frequenza. Le finestre SILENZIOSE (VAD, stesso criterio soglia/mediana gia' usato per le
    // pause) sono escluse: non hanno un F0 significativo. Le stime fuori dal range vocale
    // plausibile - compreso il caso degenere in cui l'autocorrelazione non trova alcun picco
    // valido, segnalato con f0Hz=0 - sono scartate PRIMA della deviazione standard, cosi' una
    // singola finestra malstimata non gonfia artificialmente la variabilita'.
    public static F0VariabilityResult computeF0Variability(double[] samples, int sampleRate,
                                                             double windowDurationMs, double vadThresholdRatio,
                                                             double minF0Hz, double maxF0Hz) {
        int windowSizeSamples = millisecondsToSamples(sampleRate, windowDurationMs);
        VoiceActivityResult vad = detectVoiceActivity(samples, sampleRate, windowDurationMs, vadThresholdRatio);

        int totalVoicedWindowCount = 0;
        List<Double> validF0s = new ArrayList<>();
        for (int w = 0; w < vad.voicedWindows.length; w++) {
            if (!vad.voicedWindows[w]) {
                continue;
            }
            totalVoicedWindowCount++;
            int start = w * windowSizeSamples;
            int end = Math.min(start + windowSizeSamples, samples.length);
            double[] window = Arrays.copyOfRange(samples, start, end);
            SpectralFeatureExtractor.PitchEstimate pitch =
                    SpectralFeatureExtractor.estimateF0(window, sampleRate, minF0Hz, maxF0Hz);
            if (pitch.f0Hz >= minF0Hz && pitch.f0Hz <= maxF0Hz) {
                validF0s.add(pitch.f0Hz);
            }
        }

        double[] f0s = new double[validF0s.size()];
        for (int i = 0; i < f0s.length; i++) {
            f0s[i] = validF0s.get(i);
        }
        return new F0VariabilityResult(mean(f0s), standardDeviation(f0s), f0s, totalVoicedWindowCount);
    }

    // ---------------------------------------------------------------------
    // Helper privati
    // ---------------------------------------------------------------------

    private static int millisecondsToSamples(int sampleRate, double milliseconds) {
        return Math.max(1, (int) Math.round(sampleRate * milliseconds / 1000.0));
    }

    private static double mean(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.length;
    }

    private static double standardDeviation(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double meanValue = mean(values);
        double sumSquaredDiff = 0.0;
        for (double v : values) {
            sumSquaredDiff += (v - meanValue) * (v - meanValue);
        }
        return Math.sqrt(sumSquaredDiff / values.length);
    }

    private static double median(double[] values) {
        if (values == null || values.length == 0) {
            return 0.0;
        }
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        return sorted.length % 2 == 0
                ? (sorted[mid - 1] + sorted[mid]) / 2.0
                : sorted[mid];
    }

}
