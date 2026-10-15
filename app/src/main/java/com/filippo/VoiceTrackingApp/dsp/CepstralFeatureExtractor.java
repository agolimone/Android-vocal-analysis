package com.filippo.VoiceTrackingApp.dsp;

import org.apache.commons.math3.complex.Complex;
import org.apache.commons.math3.transform.DftNormalization;
import org.apache.commons.math3.transform.FastFourierTransformer;
import org.apache.commons.math3.transform.TransformType;

import java.util.Arrays;
import java.util.Locale;

// Analisi cepstrale della VOCALE SOSTENUTA: F0 e CPP (Cepstral Peak Prominence). Sostituisce
// l'approccio precedente ad autocorrelazione/peak-picking/jitter/shimmer (archiviato, non
// cancellato, in cima a SpectralFeatureExtractor - vedi il commento li'). Il cepstrum guarda la
// periodicita' GLOBALE del segnale invece di misurare la posizione di ogni singolo ciclo, quindi
// non soffre dell'errore di quantizzazione del periodo che al nostro sample rate reale (~1000Hz)
// rendeva jitter/shimmer inaffidabili (un errore di 1 campione su un periodo di ~5 campioni e'
// gia' il 20% di errore relativo).
public final class CepstralFeatureExtractor {

    // --- Parametro critico 1: banda di ricerca F0 (Hz) ---
    // E' una MIA scelta (stessa banda di default gia' usata in SpectralFeatureExtractor, valore
    // standard Praat): definisce dove si cerca il picco di periodicita' nel cepstrum.
    public static final double DEFAULT_MIN_F0_HZ = 75.0;
    public static final double DEFAULT_MAX_F0_HZ = 500.0;

    // Range F0 adattivi per sesso (stessi valori standard usati in precedenza con
    // l'autocorrelazione, letteratura/Praat): restringere la banda di ricerca in base al sesso
    // dell'utente riduce gli errori di ottava, valido allo stesso modo con il cepstrum.
    public static final double MALE_MIN_F0_HZ = 75.0;
    public static final double MALE_MAX_F0_HZ = 300.0;
    public static final double FEMALE_MIN_F0_HZ = 100.0;
    public static final double FEMALE_MAX_F0_HZ = 500.0;

    // --- Parametro critico 2: zona di quefrency su cui si fitta la retta di base per il CPP ---
    // Espressa anch'essa come banda F0 equivalente (comoda, stessa unita' della banda sopra), ma
    // tenuta come costante SEPARATA: concettualmente e' una scelta diversa da dove si cerca il
    // picco. Qui si stabilisce dove il cepstrum viene considerato "andamento di fondo" per
    // misurare quanto il picco di F0 sporge sopra quel fondo. Di default coincide con la banda
    // F0 (75-500Hz): esclude le quefrency bassissime (sotto ~2ms, equivalenti a F0>500Hz)
    // dominate dall'inviluppo spettrale/formanti, e non si spinge oltre la piu' bassa F0
    // plausibile (75Hz) perche' oltre quel punto il cepstrum di una vocale non porta piu'
    // informazione utile sulla periodicita'.
    public static final double CPP_REGRESSION_MIN_F0_HZ = 75.0;
    public static final double CPP_REGRESSION_MAX_F0_HZ = 500.0;

    // --- Parametro critico 3: durata del frame per il cepstrum MEDIATO ---
    // MIA scelta, valore tipico in letteratura per l'analisi cepstrale a breve termine
    // (30-40ms). A 44100Hz sono ~1764 campioni, paddati a 2048 per la FFT.
    // Perche' serve: un cepstrum unico su TUTTA la vocale (3s, ~132000 campioni) "spalma" il
    // picco di periodicita' su una finestra troppo lunga rispetto alla variazione naturale del
    // segnale (respiro, micro-instabilita' della fonazione), dando un CPP molto basso (~0.04)
    // anche su una voce sana. Calcolando il cepstrum su tanti frame BREVI e mediandoli
    // punto-per-punto, il picco di ogni singolo frame (piu' netto, perche' il frame e' quasi
    // stazionario) si rinforza nella media, mentre le componenti non periodiche/rumorose tendono
    // ad annullarsi - lo stesso principio del "periodogramma mediato" per stimare uno spettro.
    // Tarabile: frame piu' lunghi = stima di F0 per frame piu' stabile ma picco cepstrale ancora
    // un po' spalmato; frame piu' corti = picco piu' netto per frame ma con meno cicli dentro
    // (piu' rumorosi, specialmente alle F0 basse). 40ms e' il compromesso standard.
    private static final double FRAME_DURATION_MS = 40.0;

    private static final double LOG_EPSILON = 1e-12; // evita log(0) sui bin di ampiezza nulla

    private CepstralFeatureExtractor() {
        // Solo metodi statici: nessuna istanza ha senso.
    }

    public static class CepstralResult {
        public final double f0Hz;
        public final double cpp;

        public CepstralResult(double f0Hz, double cpp) {
            this.f0Hz = f0Hz;
            this.cpp = cpp;
        }

        @Override
        public String toString() {
            return String.format(Locale.US, "F0=%.2f Hz, CPP=%.2f", f0Hz, cpp);
        }
    }

    public static CepstralResult analyze(double[] samples, int sampleRate) {
        return analyze(samples, sampleRate, DEFAULT_MIN_F0_HZ, DEFAULT_MAX_F0_HZ);
    }

    public static CepstralResult analyze(double[] samples, int sampleRate, double minF0Hz, double maxF0Hz) {
        double[] averageCepstrum = computeAverageFramedCepstrum(samples, sampleRate);
        if (averageCepstrum == null) {
            return new CepstralResult(0.0, 0.0); // segnale piu' corto di un frame solo
        }

        // Quefrency (in campioni) equivalente alla banda F0: stessa relazione lag<->Hz usata per
        // l'autocorrelazione (F0 alta -> quefrency corta, F0 bassa -> quefrency lunga). Calcolate
        // rispetto alla lunghezza del FRAME paddato (es. 2048), non piu' di tutto il segnale: e'
        // la lunghezza di ARRAY di averageCepstrum, che ora e' quella di un singolo frame. Il
        // cepstrum e' simmetrico come lo spettro di un segnale reale: si guarda solo la prima
        // meta' (indici 0..N/2-1), quindi la quefrency massima e' limitata a quella.
        int maxUsableQuefrency = averageCepstrum.length / 2 - 1;
        int minQuefrency = Math.max(1, (int) Math.round(sampleRate / maxF0Hz));
        int maxQuefrency = Math.min(maxUsableQuefrency, (int) Math.round(sampleRate / minF0Hz));
        if (minQuefrency > maxQuefrency) {
            return new CepstralResult(0.0, 0.0); // banda F0 vuota
        }

        int peakQuefrency = indexOfMax(averageCepstrum, minQuefrency, maxQuefrency);
        double f0Hz = sampleRate / (double) peakQuefrency;

        int regressionFrom = Math.max(1, (int) Math.round(sampleRate / CPP_REGRESSION_MAX_F0_HZ));
        int regressionTo = Math.min(maxUsableQuefrency, (int) Math.round(sampleRate / CPP_REGRESSION_MIN_F0_HZ));
        LinearRegression regression = computeLinearRegression(averageCepstrum, regressionFrom, regressionTo);
        double baseline = regression.slope * peakQuefrency + regression.intercept;
        double cpp = averageCepstrum[peakQuefrency] - baseline;

        return new CepstralResult(f0Hz, cpp);
    }

    // ---------------------------------------------------------------------
    // 0. Cepstrum MEDIATO su frame brevi (vedi FRAME_DURATION_MS sopra per il perche'):
    // divide il segnale in frame CONSECUTIVI, SENZA sovrapposizione (piu' semplice; la
    // sovrapposizione al 50% e' un possibile miglioramento futuro, non necessaria per il
    // guadagno principale che viene dall'accorciare il frame). Scarta l'eventuale frammento
    // finale piu' corto di un frame intero, cosi' tutti i frame hanno la STESSA lunghezza (e
    // quindi lo stesso padding) e si possono mediare punto per punto senza ambiguita'.
    // ---------------------------------------------------------------------

    private static double[] computeAverageFramedCepstrum(double[] samples, int sampleRate) {
        int frameSizeSamples = Math.max(1, (int) Math.round(sampleRate * FRAME_DURATION_MS / 1000.0));
        int numFrames = samples.length / frameSizeSamples;
        if (numFrames == 0) {
            return null;
        }

        double[] averageCepstrum = null;
        for (int f = 0; f < numFrames; f++) {
            int start = f * frameSizeSamples;
            double[] frame = Arrays.copyOfRange(samples, start, start + frameSizeSamples);
            double[] frameCepstrum = computeRealCepstrum(frame);
            if (averageCepstrum == null) {
                // Ogni frame ha la stessa lunghezza (frameSizeSamples) quindi anche il suo
                // cepstrum paddato ha sempre la stessa lunghezza: l'array si alloca una sola
                // volta, al primo frame.
                averageCepstrum = new double[frameCepstrum.length];
            }
            for (int i = 0; i < frameCepstrum.length; i++) {
                averageCepstrum[i] += frameCepstrum[i];
            }
        }
        for (int i = 0; i < averageCepstrum.length; i++) {
            averageCepstrum[i] /= numFrames;
        }
        return averageCepstrum;
    }

    // ---------------------------------------------------------------------
    // 1. Cepstrum reale: finestratura -> padding -> FFT -> log-modulo -> FFT inversa
    // ---------------------------------------------------------------------

    public static double[] computeRealCepstrum(double[] samples) {
        // 1. Finestra di Hamming: riduce le discontinuita' ai bordi del segnale, che altrimenti
        // introdurrebbero energia spuria a tutte le frequenze nello spettro (spreading spettrale).
        double[] windowed = applyHammingWindow(samples);

        // 2. Padding a potenza di due: FastFourierTransformer richiede una lunghezza che sia una
        // potenza di due, si completa con zeri (non altera il contenuto in frequenza del segnale).
        double[] padded = padToPowerOfTwo(windowed);

        FastFourierTransformer fft = new FastFourierTransformer(DftNormalization.STANDARD);

        // 3. FFT: spettro complesso del segnale finestrato e paddato.
        Complex[] spectrum = fft.transform(padded, TransformType.FORWARD);

        // 4. Modulo dello spettro (ampiezza) e logaritmo: il "log-spettro di potenza".
        double[] logMagnitude = new double[padded.length];
        for (int i = 0; i < padded.length; i++) {
            logMagnitude[i] = Math.log(spectrum[i].abs() + LOG_EPSILON);
        }

        // 5. FFT inversa del log-spettro -> cepstrum. Il log-spettro e' una sequenza reale, la
        // sua trasformata e' quindi reale a meno di rumore numerico: si tiene solo la parte reale.
        Complex[] cepstrumComplex = fft.transform(logMagnitude, TransformType.INVERSE);
        double[] cepstrum = new double[cepstrumComplex.length];
        for (int i = 0; i < cepstrum.length; i++) {
            cepstrum[i] = cepstrumComplex[i].getReal();
        }
        return cepstrum;
    }

    private static double[] applyHammingWindow(double[] samples) {
        int n = samples.length;
        double[] windowed = new double[n];
        for (int i = 0; i < n; i++) {
            double w = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (n - 1));
            windowed[i] = samples[i] * w;
        }
        return windowed;
    }

    private static double[] padToPowerOfTwo(double[] samples) {
        int paddedLength = Integer.highestOneBit(samples.length - 1) * 2; // prossima potenza di 2 >= length
        double[] padded = new double[paddedLength];
        System.arraycopy(samples, 0, padded, 0, samples.length);
        return padded;
    }

    // ---------------------------------------------------------------------
    // 2. CPP: altezza del picco rispetto a una retta di base fittata ai minimi quadrati
    // ---------------------------------------------------------------------

    // Parametri (slope, intercept) della retta ai minimi quadrati usata come linea di base per
    // il CPP: raggruppati qui solo per farli restituire insieme da computeLinearRegression.
    private static class LinearRegression {
        final double slope;
        final double intercept;

        LinearRegression(double slope, double intercept) {
            this.slope = slope;
            this.intercept = intercept;
        }
    }

    // Retta ai minimi quadrati cepstrum[q] ~= slope*q + intercept sulla zona di quefrency
    // "di fondo" (regressionFrom..regressionTo): stima dove starebbe il cepstrum in assenza di
    // un picco di periodicita' netto, cioe' la linea di base rispetto a cui si misura quanto il
    // picco vero sporge.
    private static LinearRegression computeLinearRegression(double[] cepstrum, int regressionFrom, int regressionTo) {
        int n = regressionTo - regressionFrom + 1;
        double sumQ = 0, sumC = 0, sumQC = 0, sumQQ = 0;
        for (int q = regressionFrom; q <= regressionTo; q++) {
            sumQ += q;
            sumC += cepstrum[q];
            sumQC += (double) q * cepstrum[q];
            sumQQ += (double) q * q;
        }
        double meanQ = sumQ / n;
        double meanC = sumC / n;
        double slope = (sumQC - n * meanQ * meanC) / (sumQQ - n * meanQ * meanQ);
        double intercept = meanC - slope * meanQ;
        return new LinearRegression(slope, intercept);
    }

    private static int indexOfMax(double[] values, int fromInclusive, int toInclusive) {
        int bestIndex = fromInclusive;
        double bestValue = values[fromInclusive];
        for (int i = fromInclusive + 1; i <= toInclusive; i++) {
            if (values[i] > bestValue) {
                bestValue = values[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

}
