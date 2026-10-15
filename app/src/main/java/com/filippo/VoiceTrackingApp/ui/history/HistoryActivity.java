package com.filippo.VoiceTrackingApp.ui.history;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.Session;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;

import java.util.Collections;
import java.util.List;

public class HistoryActivity extends AppCompatActivity {

    private RecyclerView recyclerSessions;
    private TextView textEmpty;
    private SessionAdapter adapter;
    private DatabaseHelper db;
    private int userId;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_history);

        userId = getIntent().getIntExtra(HomeActivity.EXTRA_USER_ID, -1);

        recyclerSessions = findViewById(R.id.recycler_sessions);
        textEmpty = findViewById(R.id.text_empty_history);

        adapter = new SessionAdapter(session -> {
            Intent intent = new Intent(HistoryActivity.this, SessionDetailActivity.class);
            intent.putExtra(HomeActivity.EXTRA_USER_ID, userId);
            intent.putExtra(SessionDetailActivity.EXTRA_SESSION_ID, session.getId());
            startActivity(intent);
        });
        recyclerSessions.setLayoutManager(new LinearLayoutManager(this));
        recyclerSessions.setAdapter(adapter);

        db = DatabaseHelper.getInstance(getApplicationContext());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    private void loadSessions() {
        if (userId == -1) {
            onSessionsLoaded(Collections.emptyList());
            return;
        }
        new LoadSessionsTask(db).execute(userId);
    }

    // Chiamato da LoadSessionsTask.onPostExecute, quindi già sul main thread.
    private void onSessionsLoaded(List<Session> sessions) {
        adapter.setData(sessions);

        boolean isEmpty = sessions == null || sessions.isEmpty();
        textEmpty.setVisibility(isEmpty ? View.VISIBLE : View.GONE);
        recyclerSessions.setVisibility(isEmpty ? View.GONE : View.VISIBLE);
    }

    private class LoadSessionsTask
            extends AsyncTask<Integer, Void, List<Session>> {

        private final DatabaseHelper db;

        LoadSessionsTask(DatabaseHelper db) {
            this.db = db;
        }

        @Override
        protected List<Session> doInBackground(Integer... userIds) {
            return db.getSessionsForUser(userIds[0]);
        }

        @Override
        protected void onPostExecute(List<Session> sessions) {
            if (isFinishing()) {
                return;
            }

            onSessionsLoaded(sessions);
        }
    }
}
