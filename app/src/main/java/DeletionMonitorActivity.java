package com.hfm.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class DeletionMonitorActivity extends Activity {

    private TextView logTextView;
    private ScrollView scrollView;
    private Button closeButton;
    private BroadcastReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_deletion_monitor);

        // Initialize UI Elements
        logTextView = findViewById(R.id.monitor_log_text);
        scrollView = findViewById(R.id.monitor_scroll);
        closeButton = findViewById(R.id.btn_close_monitor);

        // Setup the Close Button
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        // Initialize the receiver to catch logs from the DeleteService
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (intent != null && DeleteService.ACTION_DELETE_LOG.equals(intent.getAction())) {
                    String message = intent.getStringExtra(DeleteService.EXTRA_LOG_MESSAGE);
                    if (message != null) {
                        updateLogUI(message);
                    }
                }
            }
        };
    }

    private void updateLogUI(String message) {
        // Append the new log line with a prefix
        logTextView.append("\n> " + message);

        // Auto-scroll the ScrollView to the bottom so the latest file is visible
        scrollView.post(new Runnable() {
            @Override
            public void run() {
                scrollView.fullScroll(View.FOCUS_DOWN);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Register the receiver using LocalBroadcastManager for high performance
        IntentFilter filter = new IntentFilter(DeleteService.ACTION_DELETE_LOG);
        LocalBroadcastManager.getInstance(this).registerReceiver(logReceiver, filter);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Unregister the receiver when the window is closed to prevent memory leaks
        LocalBroadcastManager.getInstance(this).unregisterReceiver(logReceiver);
    }

    @Override
    public void onBackPressed() {
        // Allow the user to close the popup with the back button
        super.onBackPressed();
    }
}