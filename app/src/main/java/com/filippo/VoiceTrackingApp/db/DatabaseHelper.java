package com.filippo.VoiceTrackingApp.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;

// schema e CREATE TABLE scritti come stringhe SQL,
// accesso tramite ContentValues in scrittura e Cursor in lettura. Singleton per avere
// un'unica istanza per tutta l'app.
public class DatabaseHelper extends SQLiteOpenHelper {

    private static final String DB_NAME = "vocaltrack.db";
    private static final int DB_VERSION = 6; // v6: rimossa la colonna speechRate

    // --- Tabella users ---
    private static final String TABLE_USERS = "users";
    private static final String COL_USER_ID = "id";
    private static final String COL_USERNAME = "username";
    private static final String COL_PASSWORD = "password";
    private static final String COL_EMAIL = "email";
    private static final String COL_DATA_NASCITA = "dataNascita";
    private static final String COL_SESSO = "sesso";

    private static final String CREATE_TABLE_USERS =
            "CREATE TABLE " + TABLE_USERS + " (" +
                    COL_USER_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_USERNAME + " TEXT UNIQUE NOT NULL, " +
                    COL_PASSWORD + " TEXT NOT NULL, " +
                    COL_EMAIL + " TEXT NOT NULL, " +
                    COL_DATA_NASCITA + " TEXT NOT NULL, " +
                    COL_SESSO + " TEXT NOT NULL" +
                    ");";

    // --- Tabella sessions ---
    private static final String TABLE_SESSIONS = "sessions";
    private static final String COL_SESSION_ID = "id";
    private static final String COL_SESSION_USER_ID = "userId";
    private static final String COL_TIMESTAMP = "timestamp";
    private static final String COL_DURATA = "durata";
    private static final String COL_F0_MEAN = "f0Mean";
    private static final String COL_F0_STD = "f0Std";
    private static final String COL_PAUSE_RATIO = "pauseRatio";
    private static final String COL_ENERGY_MEAN = "energyMean";
    private static final String COL_ENERGY_STD = "energyStd";

    private static final String COL_JITTER = "jitter";
    private static final String COL_SHIMMER = "shimmer";
    // hnr resta non calcolato per le sessioni nuove (non richiesto insieme a jitter/shimmer).
    private static final String COL_HNR = "hnr";
    // Analisi cepstrale della vocale sostenuta (CepstralFeatureExtractor): F0/CPP.
    private static final String COL_CPP = "cpp";

    private static final String CREATE_TABLE_SESSIONS =
            "CREATE TABLE " + TABLE_SESSIONS + " (" +
                    COL_SESSION_ID + " INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    COL_SESSION_USER_ID + " INTEGER NOT NULL, " +
                    COL_TIMESTAMP + " INTEGER NOT NULL, " +
                    COL_DURATA + " INTEGER NOT NULL, " +
                    COL_F0_MEAN + " REAL, " +
                    COL_F0_STD + " REAL, " +
                    COL_PAUSE_RATIO + " REAL, " +
                    COL_ENERGY_MEAN + " REAL, " +
                    COL_ENERGY_STD + " REAL, " +
                    COL_JITTER + " REAL, " +
                    COL_SHIMMER + " REAL, " +
                    COL_HNR + " REAL, " +
                    COL_CPP + " REAL" +
                    ");";

    private static volatile DatabaseHelper instance;

    public static DatabaseHelper getInstance(Context context) {
        if (instance == null) {
            synchronized (DatabaseHelper.class) {
                if (instance == null) {
                    instance = new DatabaseHelper(context.getApplicationContext());
                }
            }
        }
        return instance;
    }

    private DatabaseHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_USERS);
        db.execSQL(CREATE_TABLE_SESSIONS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_SESSIONS + ";");
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_USERS + ";");
        onCreate(db);
    }

    // -----------------------------------------------------------------
    // Users
    // -----------------------------------------------------------------

    public boolean insertUser(User user) {
        ContentValues values = new ContentValues();
        values.put(COL_USERNAME, user.getUsername());
        values.put(COL_PASSWORD, user.getPassword());
        values.put(COL_EMAIL, user.getEmail());
        values.put(COL_DATA_NASCITA, user.getDataNascita());
        values.put(COL_SESSO, user.getSesso());

        SQLiteDatabase db = getWritableDatabase();
        // db.insert) restituisce -1 in caso di
        // fallimento se username duplicato
        long rowId = db.insert(TABLE_USERS, null, values);
        if (rowId == -1) {
            return false;
        }
        user.setId((int) rowId);
        return true;
    }

    public User findUserByUsername(String username) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_USERS + " WHERE " + COL_USERNAME + " = ?",
                new String[]{username}
        );
        try {
            if (!cursor.moveToFirst()) {
                return null; // nessun utente con questo username
            }
            return userFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    // Serve a recuperare il sesso dell'utente dato solo il suo userId (es. per scegliere
    // il range F0 specifico per sesso nel DSP spettrale), senza dover passare l'intero User
    // in giro nell'app.
    public User findUserById(int id) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_USERS + " WHERE " + COL_USER_ID + " = ?",
                new String[]{String.valueOf(id)}
        );
        try {
            if (!cursor.moveToFirst()) {
                return null; // nessun utente con questo id
            }
            return userFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    private User userFromCursor(Cursor cursor) {
        User user = new User();
        user.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COL_USER_ID)));
        user.setUsername(cursor.getString(cursor.getColumnIndexOrThrow(COL_USERNAME)));
        user.setPassword(cursor.getString(cursor.getColumnIndexOrThrow(COL_PASSWORD)));
        user.setEmail(cursor.getString(cursor.getColumnIndexOrThrow(COL_EMAIL)));
        user.setDataNascita(cursor.getString(cursor.getColumnIndexOrThrow(COL_DATA_NASCITA)));
        user.setSesso(cursor.getString(cursor.getColumnIndexOrThrow(COL_SESSO)));
        return user;
    }

    // -----------------------------------------------------------------
    // Sessions
    // -----------------------------------------------------------------

    public boolean insertSession(Session session) {
        ContentValues values = new ContentValues();
        values.put(COL_SESSION_USER_ID, session.getUserId());
        values.put(COL_TIMESTAMP, session.getTimestamp());
        values.put(COL_DURATA, session.getDurata());
        values.put(COL_F0_MEAN, session.getF0Mean());
        values.put(COL_F0_STD, session.getF0Std());
        values.put(COL_PAUSE_RATIO, session.getPauseRatio());
        values.put(COL_ENERGY_MEAN, session.getEnergyMean());
        values.put(COL_ENERGY_STD, session.getEnergyStd());
        values.put(COL_JITTER, session.getJitter());
        values.put(COL_SHIMMER, session.getShimmer());
        values.put(COL_HNR, session.getHnr());
        values.put(COL_CPP, session.getCpp());

        SQLiteDatabase db = getWritableDatabase();
        long rowId = db.insert(TABLE_SESSIONS, null, values);
        if (rowId == -1) {
            return false;
        }
        session.setId((int) rowId);
        return true;
    }

    // Serve alla schermata di dettaglio di una sessione (SessionDetailActivity): recupera UNA
    // sessione dato solo il suo id, stesso pattern di findUserById.
    public Session findSessionById(int sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_SESSIONS + " WHERE " + COL_SESSION_ID + " = ?",
                new String[]{String.valueOf(sessionId)}
        );
        try {
            if (!cursor.moveToFirst()) {
                return null; // nessuna sessione con questo id
            }
            return sessionFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    public List<Session> getSessionsForUser(int userId) {
        List<Session> sessions = new ArrayList<>();

        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM " + TABLE_SESSIONS + " WHERE " + COL_SESSION_USER_ID + " = ? "
                        + "ORDER BY " + COL_TIMESTAMP + " DESC",
                new String[]{String.valueOf(userId)}
        );
        try {
            while (cursor.moveToNext()) {
                sessions.add(sessionFromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
        return sessions;
    }

    private Session sessionFromCursor(Cursor cursor) {
        Session session = new Session();
        session.setId(cursor.getInt(cursor.getColumnIndexOrThrow(COL_SESSION_ID)));
        session.setUserId(cursor.getInt(cursor.getColumnIndexOrThrow(COL_SESSION_USER_ID)));
        session.setTimestamp(cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP)));
        session.setDurata(cursor.getLong(cursor.getColumnIndexOrThrow(COL_DURATA)));
        session.setF0Mean(getNullableDouble(cursor, COL_F0_MEAN));
        session.setF0Std(getNullableDouble(cursor, COL_F0_STD));
        session.setPauseRatio(getNullableDouble(cursor, COL_PAUSE_RATIO));
        session.setEnergyMean(getNullableDouble(cursor, COL_ENERGY_MEAN));
        session.setEnergyStd(getNullableDouble(cursor, COL_ENERGY_STD));
        session.setJitter(getNullableDouble(cursor, COL_JITTER));
        session.setShimmer(getNullableDouble(cursor, COL_SHIMMER));
        session.setHnr(getNullableDouble(cursor, COL_HNR));
        session.setCpp(getNullableDouble(cursor, COL_CPP));
        return session;
    }


    private static Double getNullableDouble(Cursor cursor, String column) {
        int columnIndex = cursor.getColumnIndexOrThrow(column);
        if (cursor.isNull(columnIndex)) {
            return null;
        }
        return cursor.getDouble(columnIndex);
    }
}
