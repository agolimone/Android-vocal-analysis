package com.filippo.VoiceTrackingApp.ui.auth;

import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.filippo.VoiceTrackingApp.R;
import com.filippo.VoiceTrackingApp.db.DatabaseHelper;
import com.filippo.VoiceTrackingApp.db.User;
import com.filippo.VoiceTrackingApp.ui.home.HomeActivity;


public class LoginActivity extends AppCompatActivity {

    private EditText editUsername;
    private EditText editPassword;
    private DatabaseHelper db;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        editUsername = findViewById(R.id.edit_username);
        editPassword = findViewById(R.id.edit_password);
        Button buttonLogin = findViewById(R.id.button_login);
        TextView textGoToRegister = findViewById(R.id.text_go_to_register);

        db = DatabaseHelper.getInstance(getApplicationContext());

        buttonLogin.setOnClickListener(v -> attemptLogin());
        textGoToRegister.setOnClickListener(v ->
                startActivity(new Intent(LoginActivity.this, RegisterActivity.class)));
    }

    private void attemptLogin() {
        String username = editUsername.getText().toString().trim();
        String password = editPassword.getText().toString();

        if (username.isEmpty() || password.isEmpty()) {
            Toast.makeText(this, R.string.error_empty_fields, Toast.LENGTH_SHORT).show();
            return;
        }

        new LoginTask(db).execute(username, password);
    }

    // Chiamato da LoginTask.onPostExecute, quindi già sul main thread.
    private void onLoginResult(User user, String enteredPassword) {
        if (user != null && user.getPassword().equals(enteredPassword)) {
            Intent intent = new Intent(LoginActivity.this, HomeActivity.class);
            intent.putExtra(HomeActivity.EXTRA_USERNAME, user.getUsername());
            intent.putExtra(HomeActivity.EXTRA_USER_ID, user.getId());
            startActivity(intent);
            finish();
        } else {
            Toast.makeText(this, R.string.error_login_failed, Toast.LENGTH_SHORT).show();
        }
    }

    private class LoginTask extends AsyncTask<String, Void, User> {

        private final DatabaseHelper db;
        private String enteredPassword;

        LoginTask(DatabaseHelper db) {
            this.db = db;
        }

        @Override
        protected User doInBackground(String... params) {
            String username = params[0];
            enteredPassword = params[1];
            return db.findUserByUsername(username);
        }

        @Override
        protected void onPostExecute(User user) {
            if (isFinishing()) {
                return;
            }

            onLoginResult(user, enteredPassword);
        }
    }
}
