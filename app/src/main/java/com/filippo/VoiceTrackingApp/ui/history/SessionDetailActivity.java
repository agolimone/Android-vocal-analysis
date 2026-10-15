package com.filippo.VoiceTrackingApp.ui.history;

import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.Session;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class SessionDetailActivity extends AppCompatActivity {

    public static final String EXTRA_SESSION_ID = "com.filippo.progetto.EXTRA_SESSION_ID";

    // Sotto questo numero di sessioni precedenti CON un dato valido per un parametro, il
    // confronto non è affidabile (una media su 1-2 punti non dice nulla su un "solito"): si
    // mostra solo il valore, con una nota invece del confronto.
    private static final int MIN_SESSIONS_FOR_BASELINE = 3;

    private static final double COMPARISON_THRESHOLD_SIGMA = 1.0;

    private int userId;
    private int sessionId;
    private DatabaseHelper db;
    private TextView textDetailDate;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_session_detail);

        userId = getIntent().getIntExtra(HomeActivity.EXTRA_USER_ID, -1);
        sessionId = getIntent().getIntExtra(EXTRA_SESSION_ID, -1);
        if (userId == -1 || sessionId == -1) {
            Toast.makeText(this, R.string.error_session_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        textDetailDate = findViewById(R.id.text_detail_date);
        db = DatabaseHelper.getInstance(getApplicationContext());

        new LoadDetailTask(db, sessionId, userId).execute();
    }

    private void onDetailLoaded(DetailResult result) {
        if (result == null) {
            Toast.makeText(this, R.string.error_session_not_found, Toast.LENGTH_LONG).show();
            finish();
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
        textDetailDate.setText(dateFormat.format(new Date(result.session.getTimestamp())));

        for (Parameter parameter : Parameter.values()) {
            TextView textView = findViewById(parameter.viewId);
            populateParameterLine(textView, parameter, result.session, result.earlierSessions);
        }
    }

    // Scrive "Etichetta: valore (nota)" nella TextView del parametro: la nota e' il confronto
    // col baseline, oppure l'avviso "servono più sessioni" se non ce ne sono abbastanza, oppure
    // nulla (solo "--") se questa sessione non ha proprio quel valore.
    private void populateParameterLine(TextView textView, Parameter parameter,
                                        Session session, List<Session> earlierSessions) {
        Double value = parameter.extractor.extract(session);
        String label = getString(parameter.labelResId);

        if (value == null) {
            textView.setText(getString(R.string.detail_param_no_value, label));
            return;
        }
        String formattedValue = String.format(Locale.getDefault(), parameter.valueFormat, value);

        BaselineResult baseline = computeBaseline(earlierSessions, parameter.extractor);
        String note = baseline.count < MIN_SESSIONS_FOR_BASELINE
                ? getString(R.string.detail_baseline_insufficient)
                : compareToBaseline(value, baseline);

        textView.setText(getString(R.string.detail_param_format, label, formattedValue, note));
    }

    // Media e deviazione standard di un parametro sulle sessioni passate, ignorando quelle in cui
    // il valore e' null (es. un errore nel DSP su quella specifica sessione): il conteggio
    // riflette quanti valori sono REALMENTE disponibili per QUESTO parametro, non quante sessioni
    // precedenti esistono in totale - due parametri della stessa sessione possono avere basi diverse.
    private static BaselineResult computeBaseline(List<Session> earlierSessions, ValueExtractor extractor) {
        List<Double> values = new ArrayList<>();
        for (Session session : earlierSessions) {
            Double value = extractor.extract(session);
            if (value != null) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            return new BaselineResult(0.0, 0.0, 0);
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        double mean = sum / values.size();
        double sumSquaredDiff = 0.0;
        for (double v : values) {
            sumSquaredDiff += (v - mean) * (v - mean);
        }
        double stdDev = Math.sqrt(sumSquaredDiff / values.size());
        return new BaselineResult(mean, stdDev, values.size());
    }

    // Confronto: scarto dalla media oltre +-COMPARISON_THRESHOLD_SIGMA deviazioni standard =
    // "più alto"/"più basso", altrimenti "in linea". Se le sessioni precedenti hanno tutte lo
    // stesso valore (deviazione standard nulla) non c'e' una variabilita' storica su cui misurare
    // uno scarto significativo: si mostra "in linea" invece di segnalare come "diverso" qualunque
    // minima differenza. Puramente descrittivo (non un giudizio): stessa filosofia gia' seguita
    // in InfoActivity, il confronto e' col PROPRIO solito, non con una soglia clinica assoluta.
    private String compareToBaseline(double value, BaselineResult baseline) {
        if (baseline.stdDev <= 0) {
            return getString(R.string.detail_baseline_in_line);
        }
        double diff = value - baseline.mean;
        double threshold = COMPARISON_THRESHOLD_SIGMA * baseline.stdDev;
        if (diff > threshold) {
            return getString(R.string.detail_baseline_above);
        } else if (diff < -threshold) {
            return getString(R.string.detail_baseline_below);
        } else {
            return getString(R.string.detail_baseline_in_line);
        }
    }

    private interface ValueExtractor {
        Double extract(Session session);
    }

    // Un parametro mostrato in dettaglio: etichetta, formato del valore, come estrarlo dalla
    // Session, e la TextView di destinazione nel layout (stesso spirito dell'enum Feature in
    // StatsChartFragment, tenuto separato qui perche' questa schermata mostra TUTTI i parametri
    // insieme invece di uno alla volta scelto da uno Spinner).

    private enum Parameter {
        F0(R.string.feature_f0_mean, "%.1f Hz", Session::getF0Mean, R.id.text_detail_f0),
        F0_STD(R.string.feature_f0_std, "%.1f Hz", Session::getF0Std, R.id.text_detail_f0_std),
        CPP(R.string.feature_cpp, "%.3f", Session::getCpp, R.id.text_detail_cpp),
        JITTER(R.string.feature_jitter, "%.1f %%", Session::getJitter, R.id.text_detail_jitter),
        SHIMMER(R.string.feature_shimmer, "%.1f %%", Session::getShimmer, R.id.text_detail_shimmer),
        ENERGY_MEAN(R.string.feature_energy_mean, "%.4f", Session::getEnergyMean, R.id.text_detail_energy_mean),
        ENERGY_STD(R.string.feature_energy_std, "%.4f", Session::getEnergyStd, R.id.text_detail_energy_std),
        PAUSE_RATIO(R.string.feature_pause_ratio, "%.2f", Session::getPauseRatio, R.id.text_detail_pause_ratio);

        final int labelResId;
        final String valueFormat;
        final ValueExtractor extractor;
        final int viewId;

        Parameter(int labelResId, String valueFormat, ValueExtractor extractor, int viewId) {
            this.labelResId = labelResId;
            this.valueFormat = valueFormat;
            this.extractor = extractor;
            this.viewId = viewId;
        }
    }

    private static class BaselineResult {
        final double mean;
        final double stdDev;
        final int count;

        BaselineResult(double mean, double stdDev, int count) {
            this.mean = mean;
            this.stdDev = stdDev;
            this.count = count;
        }
    }

    // Risultato di LoadDetailTask: la sessione richiesta + le sessioni precedenti dello stesso
    // utente (per calcolare il baseline), gia' filtrate per timestamp < quella corrente.
    private static class DetailResult {
        final Session session;
        final List<Session> earlierSessions;

        DetailResult(Session session, List<Session> earlierSessions) {
            this.session = session;
            this.earlierSessions = earlierSessions;
        }
    }

    private class LoadDetailTask
            extends AsyncTask<Void, Void, DetailResult> {

        private final DatabaseHelper db;
        private final int sessionId;
        private final int userId;

        LoadDetailTask(DatabaseHelper db, int sessionId, int userId) {
            this.db = db;
            this.sessionId = sessionId;
            this.userId = userId;
        }

        @Override
        protected DetailResult doInBackground(Void... voids) {

            Session target = db.findSessionById(sessionId);

            if (target == null) {
                return null;
            }

            List<Session> allSessions =
                    db.getSessionsForUser(userId);

            List<Session> earlierSessions =
                    new ArrayList<>();

            for (Session session : allSessions) {
                if (session.getId() != target.getId()
                        && session.getTimestamp()
                        < target.getTimestamp()) {

                    earlierSessions.add(session);
                }
            }

            return new DetailResult(
                    target,
                    earlierSessions
            );
        }

        @Override
        protected void onPostExecute(DetailResult result) {

            if (isFinishing()) {
                return;
            }

            onDetailLoaded(result);
        }
    }
}
