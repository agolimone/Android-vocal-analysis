package com.filippo.VoiceTrackingApp.ui.stats;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.Session;
import com.filippo.VoiceTrackingApp.ui.info.InfoActivity;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Fragment che mostra il trend nel tempo di UNA feature vocale alla volta (scelta dallo Spinner),
// con la media personale come linea di riferimento orizzontale. Ospitato da StatsActivity.
public class StatsChartFragment extends Fragment {

    private static final String ARG_USER_ID = "user_id";

    public static StatsChartFragment newInstance(int userId) {
        StatsChartFragment fragment = new StatsChartFragment();
        Bundle args = new Bundle();
        args.putInt(ARG_USER_ID, userId);
        fragment.setArguments(args);
        return fragment;
    }

    private int userId;
    private List<Session> sessions = new ArrayList<>();
    private Feature selectedFeature = Feature.F0_MEAN;

    private Spinner spinnerFeature;
    private LineChart lineChart;
    private TextView textEmptyStats;
    private TextView textSingleSessionNote;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Bundle args = getArguments();
        userId = args != null ? args.getInt(ARG_USER_ID, -1) : -1;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                              @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_stats_chart, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        spinnerFeature = view.findViewById(R.id.spinner_feature);
        lineChart = view.findViewById(R.id.line_chart);
        textEmptyStats = view.findViewById(R.id.text_empty_stats);
        textSingleSessionNote = view.findViewById(R.id.text_single_session_note);
        Button buttonInfo = view.findViewById(R.id.button_info);

        buttonInfo.setOnClickListener(v ->
                startActivity(new Intent(requireContext(), InfoActivity.class)));

        setupChartAppearance();
        setupFeatureSpinner();

        if (userId == -1) {
            showEmptyState();
            return;
        }
        DatabaseHelper db = DatabaseHelper.getInstance(requireContext().getApplicationContext());
        new LoadSessionsTask(db).execute(userId);
    }

    private void setupChartAppearance() {
        lineChart.getDescription().setEnabled(false);
        lineChart.setNoDataText(getString(R.string.text_no_stats_data));
        lineChart.getXAxis().setPosition(XAxis.XAxisPosition.BOTTOM);
        lineChart.getXAxis().setGranularity(1f);
    }

    private void setupFeatureSpinner() {
        Feature[] features = Feature.values();
        String[] labels = new String[features.length];
        for (int i = 0; i < features.length; i++) {
            labels[i] = getString(features[i].labelResId);
        }

        ArrayAdapter<String> adapter = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerFeature.setAdapter(adapter);

        spinnerFeature.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View itemView, int position, long id) {
                selectedFeature = Feature.values()[position];
                updateChart();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Lo Spinner ha sempre una voce selezionata di default: nulla da fare qui.
            }
        });
    }

    // Chiamato da LoadSessionsTask.onPostExecute, quindi gia' sul main thread.
    private void onSessionsLoaded(List<Session> loadedSessions) {
        sessions = loadedSessions;
        updateChart();
    }

    private void updateChart() {
        SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM", Locale.getDefault());

        List<Entry> entries = new ArrayList<>();
        List<String> dateLabels = new ArrayList<>();
        List<Double> valuesForBaseline = new ArrayList<>();

        int index = 0;
        for (Session session : sessions) {
            Double value = selectedFeature.extractor.extract(session);
            if (value == null) {
                continue; // punto saltato: la feature non e' disponibile per questa sessione
            }
            entries.add(new Entry(index, value.floatValue()));
            dateLabels.add(dateFormat.format(new Date(session.getTimestamp())));
            valuesForBaseline.add(value);
            index++;
        }

        textSingleSessionNote.setVisibility(entries.size() == 1 ? View.VISIBLE : View.GONE);

        if (entries.isEmpty()) {
            showEmptyState();
            return;
        }

        lineChart.setVisibility(View.VISIBLE);
        textEmptyStats.setVisibility(View.GONE);

        LineDataSet dataSet = new LineDataSet(entries, getString(selectedFeature.labelResId));
        dataSet.setDrawCircles(true);
        dataSet.setDrawValues(false);
        dataSet.setLineWidth(2f);
        dataSet.setCircleRadius(4f);
        lineChart.setData(new LineData(dataSet));

        lineChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(dateLabels));

        // Baseline = media dei valori disponibili per la feature selezionata, sulle sessioni caricate.
        double baseline = mean(valuesForBaseline);
        LimitLine baselineLine = new LimitLine((float) baseline, getString(R.string.label_baseline));
        baselineLine.setLineWidth(1.5f);
        baselineLine.setTextSize(10f);
        lineChart.getAxisLeft().removeAllLimitLines();
        lineChart.getAxisLeft().addLimitLine(baselineLine);
        lineChart.getAxisLeft().setDrawLimitLinesBehindData(true);

        lineChart.invalidate();
    }

    private void showEmptyState() {
        lineChart.clear();
        lineChart.setVisibility(View.GONE);
        textEmptyStats.setVisibility(View.VISIBLE);
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    // Una voce per feature selezionabile: etichetta (risorsa stringa) + come estrarre il valore
    // da una Session. L'uso di un'interfaccia funzionale evita un grosso switch/if-else ripetuto
    // in piu' punti (Spinner, grafico, baseline).
    private enum Feature {
        F0_MEAN(R.string.feature_f0_mean, Session::getF0Mean),
        F0_STD(R.string.feature_f0_std, Session::getF0Std),
        CPP(R.string.feature_cpp, Session::getCpp),
        JITTER(R.string.feature_jitter, Session::getJitter),
        SHIMMER(R.string.feature_shimmer, Session::getShimmer),
        ENERGY_MEAN(R.string.feature_energy_mean, Session::getEnergyMean),
        ENERGY_STD(R.string.feature_energy_std, Session::getEnergyStd),
        PAUSE_RATIO(R.string.feature_pause_ratio, Session::getPauseRatio);

        final int labelResId;
        final ValueExtractor extractor;

        Feature(int labelResId, ValueExtractor extractor) {
            this.labelResId = labelResId;
            this.extractor = extractor;
        }

        interface ValueExtractor {
            Double extract(Session session);
        }
    }

    private class LoadSessionsTask
            extends AsyncTask<Integer, Void, List<Session>> {

        private final DatabaseHelper db;

        LoadSessionsTask(DatabaseHelper db) {
            this.db = db;
        }

        @Override
        protected List<Session> doInBackground(Integer... userIds) {

            List<Session> result =
                    db.getSessionsForUser(userIds[0]);

            Collections.reverse(result);

            return result;
        }

        @Override
        protected void onPostExecute(List<Session> result) {

            if (!isAdded()) {
                return;
            }

            onSessionsLoaded(result);
        }
    }
}
