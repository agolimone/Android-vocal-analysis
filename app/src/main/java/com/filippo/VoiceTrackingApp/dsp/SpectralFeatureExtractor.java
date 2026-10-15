package com.filippo.VoiceTrackingApp.dsp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

// ATTIVA (branch "telefono"): stima F0 tramite autocorrelazione, peak-picking dei cicli,
// jitter, shimmer. Usata in due punti della pipeline reale (SessionFlowActivity):
// 1. estimateF0 e' chiamata da TemporalFeatureExtractor.computeF0Variability per stimare la F0
//    finestra per finestra sul PARLATO (variabilita' dell'intonazione) - funziona bene anche su
//    finestre brevi, perche' cerca solo il picco di correlazione entro una banda, senza bisogno
//    di una FFT su un blocco lungo.
// 2. estimateF0 + analyzeCycles + computeJitterPercent + computeShimmerPercent sono usate sulla
//    VOCALE SOSTENUTA per calcolare jitter e shimmer, in aggiunta all'analisi cepstrale
//    (CepstralFeatureExtractor resta l'unica responsabile di F0 "ufficiale" e CPP sulla vocale:
//    questa classe non la sostituisce, la affianca).
//
// Jitter/shimmer erano stati abbandonati (lasciati a null) quando l'acquisizione avveniva
// dall'Arduino via WiFi a basso sample rate (~1000Hz dichiarato): con periodi di pochi campioni,
// un solo campione di errore nel trovare il picco del ciclo (quantizzazione del periodo)
// gonfiava jitter/shimmer oltre ogni plausibilita'. Recuperati col passaggio al microfono
// interno del telefono a 44100Hz (branch "telefono"), che da' risoluzione temporale a sufficienza
// (centinaia di campioni per periodo tipico) perche' quell'errore di quantizzazione diventi
// trascurabile - verificato con un test sintetico dedicato durante lo sviluppo (rimosso da qui
// una volta confermato il comportamento atteso).
//
// L'unica parte di questa classe NON usata nel flusso attuale e' l'HNR (computeHnrDb) e il
// metodo aggregato analyzeVoiceQuality: il calcolo dell'HNR non e' (ancora) incluso tra le
// feature salvate dall'app. Restano nel codice, semplicemente non richiamati da nessun
// chiamante reale al momento.
// Stessa convenzione dei campioni: double normalizzati in [-1, 1], sample rate passato a parte.
public final class SpectralFeatureExtractor {

    // Range generico (default Praat), usato quando il sesso dell'utente non e' noto/specificato.
    private static final double DEFAULT_MIN_F0_HZ = 75.0;
    private static final double DEFAULT_MAX_F0_HZ = 500.0;

    // Range di ricerca F0 specifici per sesso (valori standard usati in letteratura/Praat).
    // Un range piu' stretto e mirato riduce gli ERRORI DI OTTAVA: la ricerca del picco di
    // autocorrelazione (vedi estimateF0) esplora solo le frequenze plausibili per quella
    // popolazione, invece di un range generico troppo ampio in cui un multiplo/sottomultiplo
    // della vera F0 (es. la meta' esatta, tipica differenza tra un'ottava maschile e la
    // successiva) puo' rientrare comunque nella banda ed essere scambiato per il valore vero.
    public static final double MALE_MIN_F0_HZ = 75.0;
    public static final double MALE_MAX_F0_HZ = 300.0;
    public static final double FEMALE_MIN_F0_HZ = 100.0;
    public static final double FEMALE_MAX_F0_HZ = 500.0;
    // Dopo un picco di ciclo, cerchiamo il successivo in [periodo*(1-w), periodo*(1+w)]: w=0.5
    // tollera una deriva ciclo-ciclo fino al 50% del periodo atteso, ben oltre il jitter reale
    // (che di norma e' <5%), quindi non perdiamo picchi per un jitter plausibile.
    private static final double CYCLE_SEARCH_WINDOW_FRACTION = 0.5;
    // Tra i lag la cui autocorrelazione e' entro il 5% del massimo assoluto trovato nella banda,
    // scegliamo il piu' corto: evita "errori di ottava" (vedi commento su estimateF0).
    private static final double OCTAVE_TOLERANCE = 0.95;

    private SpectralFeatureExtractor() {
        // Solo metodi statici: nessuna istanza ha senso.
    }

    // ---------------------------------------------------------------------
    // 1. Stima F0 tramite autocorrelazione
    // ---------------------------------------------------------------------

    // Risultato della stima F0: frequenza, periodo in campioni (serve al peak-picking dei cicli,
    // punto 2) e il valore normalizzato dell'autocorrelazione al picco (serve all'HNR, punto 5).
    // Tenerli insieme evita di ricalcolare l'autocorrelazione una seconda volta per l'HNR.
    public static class PitchEstimate {
        public final double f0Hz;
        public final int periodSamples;
        public final double normalizedAutocorrelation;

        public PitchEstimate(double f0Hz, int periodSamples, double normalizedAutocorrelation) {
            this.f0Hz = f0Hz;
            this.periodSamples = periodSamples;
            this.normalizedAutocorrelation = normalizedAutocorrelation;
        }
    }

    public static PitchEstimate estimateF0(double[] samples, int sampleRate) {
        return estimateF0(samples, sampleRate, DEFAULT_MIN_F0_HZ, DEFAULT_MAX_F0_HZ);
    }

    // Autocorrelazione: r[lag] = Sum_n x[n]*x[n+lag]. Per un segnale periodico di periodo T,
    // r[lag] e' massima quando lag e' un multiplo di T, perche' li' il segnale si allinea
    // perfettamente con una sua copia traslata (ogni campione moltiplica un campione "gemello"
    // dello stesso punto del ciclo precedente/successivo, invece di un punto casuale di fase).
    //
    // A lag=0 l'allineamento e' banalmente perfetto SEMPRE (ogni segnale correla 1:1 con se
    // stesso), quindi r[0] e' matematicamente il massimo assoluto per qualunque segnale: se si
    // cercasse il massimo su TUTTI i lag da 0 in su si troverebbe sempre lag=0, inutile. Per
    // questo il picco "periodico" si cerca SOLO nella banda [minLag, maxLag] corrispondente al
    // range di F0 plausibile per la voce (qui 75-500 Hz di default): lag=0 non viene proprio
    // esaminato, e il primo lag testato (minLag, il piu' corto = F0 piu' alta) e' gia' ben
    // separato da 0.
    public static PitchEstimate estimateF0(double[] samples, int sampleRate,
                                            double minF0Hz, double maxF0Hz) {
        if (samples == null || samples.length < 2) {
            return new PitchEstimate(0.0, 0, 0.0);
        }

        int minLag = Math.max(1, (int) Math.round(sampleRate / maxF0Hz));
        int maxLag = Math.min(samples.length - 1, (int) Math.round(sampleRate / minF0Hz));
        if (minLag > maxLag) {
            return new PitchEstimate(0.0, 0, 0.0);
        }

        double meanPower = meanProductAtLag(samples, 0); // potenza media (r[0]/N): usata solo per normalizzare
        if (meanPower <= 0) {
            return new PitchEstimate(0.0, 0, 0.0);
        }

        double[] normalizedByLag = new double[maxLag - minLag + 1];
        double globalBestR = Double.NEGATIVE_INFINITY;
        for (int lag = minLag; lag <= maxLag; lag++) {
            double normalizedR = meanProductAtLag(samples, lag) / meanPower;
            normalizedByLag[lag - minLag] = normalizedR;
            if (normalizedR > globalBestR) {
                globalBestR = normalizedR;
            }
        }

        // Un segnale periodico di periodo T correla forte non solo a lag=T, ma anche a lag=2T,
        // 3T, ... (T campioni piu' avanti il segnale si e' semplicemente ripetuto un'altra volta):
        // il massimo assoluto sulla banda potrebbe quindi cadere su un MULTIPLO della vera F0
        // invece che sulla F0 stessa ("errore di ottava" - qui si rischia di stimare 75Hz invece
        // di 150Hz). Per capire QUALI lag sono candidati veri (non semplicemente punti in salita
        // verso un picco vicino) cerchiamo i MASSIMI LOCALI della curva normalizedR(lag) - i punti
        // in cui il valore e' piu' alto di entrambi i vicini, cioe' dove la correlazione smette di
        // crescere e inizia a scendere: solo li' il segnale e' davvero "allineato al meglio" con
        // se stesso, i punti intermedi sono solo il fianco della salita verso quel massimo.
        // Tra i massimi locali trovati, scegliamo il piu' CORTO fra quelli vicini al migliore
        // (entro OCTAVE_TOLERANCE): tra periodicita' equivalenti la piu' corta e' sempre la vera
        // F0, le altre sono suoi multipli interi.
        List<Integer> localMaxLagIndices = new ArrayList<>();
        for (int i = 1; i < normalizedByLag.length - 1; i++) {
            if (normalizedByLag[i] >= normalizedByLag[i - 1] && normalizedByLag[i] >= normalizedByLag[i + 1]) {
                localMaxLagIndices.add(i);
            }
        }

        int bestLag;
        double bestNormalizedR;
        if (localMaxLagIndices.isEmpty()) {
            // Fallback raro (banda di ricerca troppo corta per avere un massimo locale interno):
            // usa il massimo assoluto sull'intera banda.
            bestLag = minLag;
            bestNormalizedR = normalizedByLag[0];
            for (int lag = minLag; lag <= maxLag; lag++) {
                double r = normalizedByLag[lag - minLag];
                if (r > bestNormalizedR) {
                    bestNormalizedR = r;
                    bestLag = lag;
                }
            }
        } else {
            double bestPeakR = Double.NEGATIVE_INFINITY;
            for (int i : localMaxLagIndices) {
                bestPeakR = Math.max(bestPeakR, normalizedByLag[i]);
            }
            double threshold = bestPeakR * OCTAVE_TOLERANCE;

            int chosenIndex = localMaxLagIndices.get(0);
            for (int i : localMaxLagIndices) {
                if (normalizedByLag[i] >= threshold) {
                    chosenIndex = i;
                    break;
                }
            }
            bestLag = minLag + chosenIndex;
            bestNormalizedR = normalizedByLag[chosenIndex];
        }

        double f0Hz = sampleRate / (double) bestLag;
        return new PitchEstimate(f0Hz, bestLag, bestNormalizedR);
    }

    // Media dei prodotti x[n]*x[n+lag] (NON la somma): a lag maggiore restano meno campioni
    // sovrapposti (N-lag invece di N), quindi sommare soltanto penalizzerebbe artificialmente i
    // lag piu' lunghi rispetto a lag=0, anche per un segnale perfettamente periodico. Dividendo
    // per il numero di prodotti effettivamente sommati si confrontano grandezze omogenee
    // (potenza media per campione) a prescindere da quanti campioni si sovrappongono.
    private static double meanProductAtLag(double[] samples, int lag) {
        int count = samples.length - lag;
        if (count <= 0) {
            return 0.0;
        }
        double sum = 0.0;
        for (int n = 0; n < count; n++) {
            sum += samples[n] * samples[n + lag];
        }
        return sum / count;
    }

    // ---------------------------------------------------------------------
    // 2. Individuazione dei cicli (periodi e ampiezze di picco)
    // ---------------------------------------------------------------------

    public static class CycleFeatures {
        public final double[] periodsSamples;
        public final double[] peakAmplitudes;

        public CycleFeatures(double[] periodsSamples, double[] peakAmplitudes) {
            this.periodsSamples = periodsSamples;
            this.peakAmplitudes = peakAmplitudes;
        }
    }

    // Peak-picking guidato dal periodo stimato: si cerca il campione di valore massimo nel primo
    // periodo (primo picco), poi si ripete cercando il prossimo massimo in una finestra centrata
    // un periodo dopo l'ultimo picco trovato. Guidare la ricerca col periodo atteso (invece di
    // cercare "tutti i massimi locali" sull'intero segnale) evita di agganciare massimi spuri
    // dentro lo stesso ciclo (es. armoniche secondarie) o di saltare/duplicare cicli.
    // Limite: se il jitter reale di una voce patologica supera la finestra di tolleranza (50% del
    // periodo) l'algoritmo puo' perdere l'aggancio; per una vocale sostenuta normale non succede.
    public static int[] detectCyclePeaks(double[] samples, int periodSamples) {
        if (samples == null || samples.length == 0 || periodSamples <= 0) {
            return new int[0];
        }

        List<Integer> peaks = new ArrayList<>();
        int firstWindowEnd = Math.min(periodSamples, samples.length);
        int peak = indexOfMax(samples, 0, firstWindowEnd);
        peaks.add(peak);

        // Se la finestra di ricerca dell'ultimo picco trovato e' stata troncata dal bordo
        // dell'array, quel picco (e quindi l'ultimo ciclo che lo usa) e' inaffidabile: potrebbe
        // esserci un massimo piu' "vero" appena fuori dai campioni disponibili. Lo scartiamo.
        boolean lastWindowTruncated = false;
        while (true) {
            int naturalSearchEnd = peak + (int) Math.round(periodSamples * (1 + CYCLE_SEARCH_WINDOW_FRACTION));
            int searchStart = peak + (int) Math.round(periodSamples * (1 - CYCLE_SEARCH_WINDOW_FRACTION));
            searchStart = Math.max(searchStart, peak + 1);
            int searchEnd = Math.min(naturalSearchEnd, samples.length);
            if (searchStart >= searchEnd) {
                break;
            }
            lastWindowTruncated = naturalSearchEnd > samples.length;
            peak = indexOfMax(samples, searchStart, searchEnd);
            peaks.add(peak);
        }
        if (lastWindowTruncated && peaks.size() >= 2) {
            peaks.remove(peaks.size() - 1);
        }

        int[] result = new int[peaks.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = peaks.get(i);
        }
        return result;
    }

    private static int indexOfMax(double[] samples, int fromInclusive, int toExclusive) {
        int bestIndex = fromInclusive;
        double bestValue = samples[fromInclusive];
        for (int i = fromInclusive + 1; i < toExclusive; i++) {
            if (samples[i] > bestValue) {
                bestValue = samples[i];
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    // Dai picchi ricava periodi (distanza tra picchi consecutivi) e ampiezze (valore di ciascun
    // picco): un ciclo i va dal picco i al picco i+1, quindi periodsSamples e peakAmplitudes hanno
    // entrambi lunghezza (numero di picchi - 1), allineati indice per indice.
    public static CycleFeatures analyzeCycles(double[] samples, int periodSamples) {
        int[] peaks = detectCyclePeaks(samples, periodSamples);
        if (peaks.length < 2) {
            return new CycleFeatures(new double[0], new double[0]);
        }

        int numCycles = peaks.length - 1;
        double[] periods = new double[numCycles];
        double[] amplitudes = new double[numCycles];
        for (int i = 0; i < numCycles; i++) {
            periods[i] = peaks[i + 1] - peaks[i];
            amplitudes[i] = samples[peaks[i]];
        }
        return new CycleFeatures(periods, amplitudes);
    }

    // ---------------------------------------------------------------------
    // 3. Jitter (variazione ciclo-ciclo del periodo)
    // ---------------------------------------------------------------------

    // Jitter relativo (%) = (media |T_i - T_i+1|) / (media T_i) * 100.
    // I periodi sono in campioni, non in secondi: essendo un RAPPORTO tra due grandezze con la
    // stessa unita' (differenza di periodi / periodo medio), il risultato e' adimensionale e
    // identico qualunque sia l'unita' scelta per T, quindi non serve convertire in secondi/ms.
    public static double computeJitterPercent(double[] periodsSamples) {
        if (periodsSamples == null || periodsSamples.length < 2) {
            return 0.0;
        }
        double sumAbsDiff = 0.0;
        for (int i = 0; i < periodsSamples.length - 1; i++) {
            sumAbsDiff += Math.abs(periodsSamples[i] - periodsSamples[i + 1]);
        }
        double meanAbsDiff = sumAbsDiff / (periodsSamples.length - 1);
        double meanPeriod = mean(periodsSamples);
        return meanPeriod > 0 ? (meanAbsDiff / meanPeriod) * 100.0 : 0.0;
    }

    // ---------------------------------------------------------------------
    // 4. Shimmer (variazione ciclo-ciclo dell'ampiezza)
    // ---------------------------------------------------------------------

    // Stessa formula del jitter, applicata alle ampiezze di picco invece che ai periodi.
    public static double computeShimmerPercent(double[] peakAmplitudes) {
        if (peakAmplitudes == null || peakAmplitudes.length < 2) {
            return 0.0;
        }
        double sumAbsDiff = 0.0;
        for (int i = 0; i < peakAmplitudes.length - 1; i++) {
            sumAbsDiff += Math.abs(peakAmplitudes[i] - peakAmplitudes[i + 1]);
        }
        double meanAbsDiff = sumAbsDiff / (peakAmplitudes.length - 1);
        double meanAmplitude = mean(peakAmplitudes);
        return meanAmplitude != 0 ? (meanAbsDiff / Math.abs(meanAmplitude)) * 100.0 : 0.0;
    }

    // ---------------------------------------------------------------------
    // 5. HNR (Harmonic-to-Noise Ratio)
    // ---------------------------------------------------------------------

    // r (normalizedAutocorrelation, da estimateF0) e' la frazione di energia del segnale che e'
    // spiegata dalla periodicita' al periodo trovato: r=1 -> segnale perfettamente periodico
    // (nessun rumore), r=0 -> nessuna periodicita' (rumore puro). r/(1-r) e' quindi il rapporto
    // energia-periodica/energia-non-periodica, cioe' letteralmente "armonico su rumore"; *10*log10
    // lo converte in decibel, la scala standard con cui HNR viene riportato in letteratura.
    public static double computeHnrDb(double normalizedAutocorrelationPeak) {
        double r = normalizedAutocorrelationPeak;
        if (r <= 0) {
            return Double.NEGATIVE_INFINITY; // nessuna periodicita' rilevabile
        }
        if (r >= 1.0) {
            r = 1.0 - 1e-9; // r=1 esatto darebbe log(divisione per zero)
        }
        return 10.0 * Math.log10(r / (1.0 - r));
    }

    // ---------------------------------------------------------------------
    // Combinazione: tutte le feature di qualita' vocale in un colpo solo
    // ---------------------------------------------------------------------

    public static class VoiceQualityFeatures {
        public final double f0Hz;
        public final double jitterPercent;
        public final double shimmerPercent;
        public final double hnrDb;

        public VoiceQualityFeatures(double f0Hz, double jitterPercent, double shimmerPercent, double hnrDb) {
            this.f0Hz = f0Hz;
            this.jitterPercent = jitterPercent;
            this.shimmerPercent = shimmerPercent;
            this.hnrDb = hnrDb;
        }

        @Override
        public String toString() {
            return String.format(Locale.US,
                    "F0=%.2f Hz, jitter=%.3f%%, shimmer=%.3f%%, HNR=%.2f dB",
                    f0Hz, jitterPercent, shimmerPercent, hnrDb);
        }
    }

    public static VoiceQualityFeatures analyzeVoiceQuality(double[] samples, int sampleRate) {
        return analyzeVoiceQuality(samples, sampleRate, DEFAULT_MIN_F0_HZ, DEFAULT_MAX_F0_HZ);
    }

    public static VoiceQualityFeatures analyzeVoiceQuality(double[] samples, int sampleRate,
                                                             double minF0Hz, double maxF0Hz) {
        PitchEstimate pitch = estimateF0(samples, sampleRate, minF0Hz, maxF0Hz);
        CycleFeatures cycles = analyzeCycles(samples, pitch.periodSamples);
        double jitter = computeJitterPercent(cycles.periodsSamples);
        double shimmer = computeShimmerPercent(cycles.peakAmplitudes);
        double hnr = computeHnrDb(pitch.normalizedAutocorrelation);
        return new VoiceQualityFeatures(pitch.f0Hz, jitter, shimmer, hnr);
    }

    // ---------------------------------------------------------------------
    // Helper privati
    // ---------------------------------------------------------------------

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

}
