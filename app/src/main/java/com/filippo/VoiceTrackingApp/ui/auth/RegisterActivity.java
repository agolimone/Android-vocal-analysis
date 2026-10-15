package com.filippo.VoiceTrackingApp.ui.auth;

import android.app.DatePickerDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.User;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class RegisterActivity extends AppCompatActivity {

    private static final SimpleDateFormat DISPLAY_DATE_FORMAT =
            new SimpleDateFormat("dd/MM/yyyy", Locale.getDefault());
    private static final SimpleDateFormat DB_DATE_FORMAT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);

    private EditText editUsername;
    private EditText editPassword;
    private EditText editEmail;
    private EditText editDataNascita;
    private RadioGroup radioGroupSesso;
    private DatabaseHelper db;

    // data scelta col DatePicker.
    private String dataNascitaIso;
    private final Calendar dataNascitaCalendar = Calendar.getInstance();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_register);

        editUsername = findViewById(R.id.edit_username);
        editPassword = findViewById(R.id.edit_password);
        editEmail = findViewById(R.id.edit_email);
        editDataNascita = findViewById(R.id.edit_data_nascita);
        radioGroupSesso = findViewById(R.id.radio_group_sesso);
        Button buttonRegister = findViewById(R.id.button_register);

        db = DatabaseHelper.getInstance(getApplicationContext());

        editDataNascita.setOnClickListener(v -> showDatePickerDialog());
        buttonRegister.setOnClickListener(v -> attemptRegister());
    }

    private void showDatePickerDialog() {
        DatePickerDialog dialog = new DatePickerDialog(
                this,
                (view, year, month, dayOfMonth) -> {
                    dataNascitaCalendar.set(year, month, dayOfMonth);
                    editDataNascita.setText(DISPLAY_DATE_FORMAT.format(dataNascitaCalendar.getTime()));
                    dataNascitaIso = DB_DATE_FORMAT.format(dataNascitaCalendar.getTime());
                },
                dataNascitaCalendar.get(Calendar.YEAR),
                dataNascitaCalendar.get(Calendar.MONTH),
                dataNascitaCalendar.get(Calendar.DAY_OF_MONTH)
        );
        dialog.getDatePicker().setMaxDate(System.currentTimeMillis());
        dialog.show();
    }

    private void attemptRegister() {
        String username = editUsername.getText().toString().trim();
        String password = editPassword.getText().toString();
        String email = editEmail.getText().toString().trim();

        if (username.isEmpty() || password.isEmpty() || email.isEmpty() || dataNascitaIso == null) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show();
            return;
        }

        int checkedId = radioGroupSesso.getCheckedRadioButtonId();
        if (checkedId == -1) {
            Toast.makeText(this, R.string.error_select_sesso, Toast.LENGTH_SHORT).show();
            return;
        }
        RadioButton radioButtonSesso = findViewById(checkedId);

        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        user.setEmail(email);
        user.setDataNascita(dataNascitaIso);
        user.setSesso(radioButtonSesso.getText().toString());

        new RegisterTask(db).execute(user);
    }

    // Chiamato da RegisterTask.onPostExecute, quindi già sul main thread.
    // success=false quando insertUser fallisce per username duplicato (vincolo UNIQUE).
    private void onRegisterResult(boolean success, User user) {
        if (success) {
            Toast.makeText(this, R.string.register_success, Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(RegisterActivity.this, HomeActivity.class);
            intent.putExtra(HomeActivity.EXTRA_USERNAME, user.getUsername());
            intent.putExtra(HomeActivity.EXTRA_USER_ID, user.getId());
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, R.string.error_username_taken, Toast.LENGTH_SHORT).show();
        }
    }

    private class RegisterTask extends AsyncTask<User, Void, Boolean> {

        private final DatabaseHelper db;
        private User user;

        RegisterTask(DatabaseHelper db) {
            this.db = db;
        }

        @Override
        protected Boolean doInBackground(User... users) {
            user = users[0];
            return db.insertUser(user);
        }

        @Override
        protected void onPostExecute(Boolean success) {
            if (isFinishing()) {
                return;
            }

            onRegisterResult(success, user);
        }
    }
}
