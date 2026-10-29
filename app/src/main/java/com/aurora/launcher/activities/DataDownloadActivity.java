package com.aurora.launcher.activities;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.aurora.game.R;
import com.aurora.launcher.data.GameDataManager;

/**
 * Downloads the game data package (files.zip via the Kallzzxa/hehehaha config),
 * extracts it to the engine storage root, then hands over to the game.
 */
public class DataDownloadActivity extends Activity {

    private ProgressBar progress;
    private TextView status, percent, detail;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install_data);

        progress = findViewById(R.id.progress);
        status = findViewById(R.id.status);
        percent = findViewById(R.id.percent);

        startFetch();
    }

    private void startFetch() {
        status.setText("Menghubungkan ke server konfigurasi...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final GameDataManager.DataInfo info = GameDataManager.fetchConfig();

                    if (GameDataManager.isInstalled(DataDownloadActivity.this, info)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                launchGame();
                            }
                        });
                        return;
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            startInstall(info);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            showError(e.getMessage() == null ? e.toString() : e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }

    private void startInstall(final GameDataManager.DataInfo info) {
        status.setText("Mengunduh data game " + info.version + "...");
        GameDataManager.install(this, info, new GameDataManager.Progress() {
            @Override
            public void phase(final String text) {
                runOnUiThread(() -> status.setText(text));
            }

            @Override
            public void percent(final int p) {
                runOnUiThread(() -> progress.setProgress(p));
            }

            @Override
            public void detail(final String text) {
                runOnUiThread(() -> {
                    if (percent != null) percent.setText(text);
                });
            }

            @Override
            public void done(final boolean success, final String error) {
                runOnUiThread(() -> {
                    if (success) {
                        Toast.makeText(DataDownloadActivity.this,
                                "Data game siap", Toast.LENGTH_SHORT).show();
                        launchGame();
                    } else {
                        showError(error);
                    }
                });
            }
        });
    }

    private void showError(String msg) {
        new AlertDialog.Builder(this)
                .setTitle("Gagal")
                .setMessage(msg == null ? "Coba lagi." : msg)
                .setCancelable(false)
                .setPositiveButton("COBA LAGI", (d, w) -> startFetch())
                .setNegativeButton("KELUAR", (d, w) -> finishAffinity())
                .show();
    }

    private void launchGame() {
        // default server (from the config's servers list); the user can change
        // it in-game. SAMP/settings.ini is what the native engine reads at boot.
        GameDataManager.writeSettingsIni(this, "Player", "43.153.205.238", 7777, "0.3.DL-R1");
        startActivity(new Intent(this, LoadGameActivity.class));
        finish();
    }
}
