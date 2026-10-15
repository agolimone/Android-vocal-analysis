package com.filippo.VoiceTrackingApp.ui.history;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.Session;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

// Adapter tra la lista di Session e le righe della RecyclerView: crea/ricicla le View
// (onCreateViewHolder) e ci scrive dentro i dati della sessione corrente (onBindViewHolder).
public class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

    // Il tap su una riga apre il dettaglio: l'Adapter non conosce la navigazione (Intent,
    // userId...), delega tutto a chi lo costruisce (HistoryActivity) tramite questa callback.
    public interface OnSessionClickListener {
        void onSessionClick(Session session);
    }

    private final List<Session> sessions = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault());
    private final OnSessionClickListener clickListener;

    public SessionAdapter(OnSessionClickListener clickListener) {
        this.clickListener = clickListener;
    }

    public void setData(List<Session> newSessions) {
        sessions.clear();
        if (newSessions != null) {
            sessions.addAll(newSessions);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_session, parent, false);
        return new SessionViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        Session session = sessions.get(position);
        holder.bind(session, dateFormat);
        holder.itemView.setOnClickListener(v -> clickListener.onSessionClick(session));
    }

    @Override
    public int getItemCount() {
        return sessions.size();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {

        private final TextView textDate;
        private final TextView textPreview;

        SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            textDate = itemView.findViewById(R.id.text_session_date);
            textPreview = itemView.findViewById(R.id.text_session_preview);
        }

        void bind(Session session, SimpleDateFormat dateFormat) {
            textDate.setText(dateFormat.format(new Date(session.getTimestamp())));

            String f0Text = session.getF0Mean() != null
                    ? String.format(Locale.getDefault(), "%.0f Hz", session.getF0Mean())
                    : "--";
            String pauseText = session.getPauseRatio() != null
                    ? String.format(Locale.getDefault(), "%.2f", session.getPauseRatio())
                    : "--";
            textPreview.setText(itemView.getContext()
                    .getString(R.string.item_session_preview_format, f0Text, pauseText));
        }
    }
}
