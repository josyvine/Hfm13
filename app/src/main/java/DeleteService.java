package com.hfm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import android.Manifest;

public class DeleteService extends Service {

    private static final String TAG = "DeleteService";

    public static final String ACTION_DELETE_COMPLETE = "com.hfm.app.action.DELETE_COMPLETE";
    public static final String EXTRA_FILES_TO_DELETE = "com.hfm.app.extra.FILES_TO_DELETE";
    public static final String EXTRA_DELETED_COUNT = "com.hfm.app.extra.DELETED_COUNT";

    private static final String NOTIFICATION_CHANNEL_ID = "DeleteServiceChannel";
    
    private NotificationManager notificationManager;
    private ExecutorService executorService;
    private final AtomicInteger activeTasks = new AtomicInteger(0);
    private boolean isFirstTask = true;

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        // Create a ThreadPool to allow parallel deletions
        executorService = Executors.newCachedThreadPool();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        // --- FIX: CLONE BRIDGE IMMEDIATELY ---
        // We capture the list and clear the bridge synchronously in onStartCommand 
        // to prevent the next incoming Intent from overwriting the list before we process it.
        final ArrayList<String> filePathsToProcess;
        ArrayList<String> bridgedFiles = FileBridge.mFilesToDelete;
        
        if (bridgedFiles != null && !bridgedFiles.isEmpty()) {
            filePathsToProcess = new ArrayList<>(bridgedFiles);
            FileBridge.mFilesToDelete = new ArrayList<>(); // Clear immediately
        } else {
            ArrayList<String> extraFiles = intent.getStringArrayListExtra(EXTRA_FILES_TO_DELETE);
            filePathsToProcess = (extraFiles != null) ? new ArrayList<>(extraFiles) : new ArrayList<String>();
        }

        if (filePathsToProcess.isEmpty()) {
            checkAndStop();
            return START_NOT_STICKY;
        }

        final int batchSize = intent.getIntExtra("batch_size", 1);
        // Generate a unique ID for this specific deletion job
        final int uniqueJobId = (int) System.currentTimeMillis() + startId;

        activeTasks.incrementAndGet();

        // --- REQUIREMENT: PARALLEL NOTIFICATIONS ---
        Notification initialNotif = createNotification("Preparing deletion...", 0, filePathsToProcess.size());
        
        // The first task starts the foreground service. 
        // Subsequent tasks just add new notifications using their unique Job IDs.
        if (isFirstTask) {
            startForeground(uniqueJobId, initialNotif);
            isFirstTask = false;
        } else {
            notificationManager.notify(uniqueJobId, initialNotif);
        }

        // Submit the deletion task to the ThreadPool
        executorService.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    performDeletionTask(filePathsToProcess, batchSize, uniqueJobId);
                } finally {
                    activeTasks.decrementAndGet();
                    checkAndStop();
                }
            }
        });

        return START_STICKY;
    }

    private void performDeletionTask(List<String> filePaths, int batchSize, int jobId) {
        int totalFiles = filePaths.size();
        int deletedCount = 0;
        ContentResolver resolver = getContentResolver();

        boolean canUpdateNotification = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            canUpdateNotification = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
        }

        // Processing Logic
        for (int i = 0; i < totalFiles; i += batchSize) {
            int end = Math.min(i + batchSize, totalFiles);
            List<String> batchPaths = filePaths.subList(i, end);

            // A. Batch Database Delete (FAST SQL)
            try {
                StringBuilder selection = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
                String[] selectionArgs = new String[batchPaths.size()];
                for (int j = 0; j < batchPaths.size(); j++) {
                    selection.append("?");
                    if (j < batchPaths.size() - 1) selection.append(",");
                    selectionArgs[j] = batchPaths.get(j);
                }
                selection.append(")");
                resolver.delete(MediaStore.Files.getContentUri("external"), selection.toString(), selectionArgs);
            } catch (Exception e) {
                Log.e(TAG, "Database batch delete error in Job " + jobId, e);
            }

            // B. Physical File Delete
            for (String path : batchPaths) {
                File file = new File(path);
                boolean success = false;

                if (file.exists()) {
                    if (file.delete()) {
                        success = true;
                    } else if (StorageUtils.deleteFile(DeleteService.this, file)) {
                        success = true;
                    }
                } else {
                    success = true; // File already wiped by MediaStore/OS
                }

                if (success) {
                    deletedCount++;
                }
            }

            // Update specific notification for this task
            if (canUpdateNotification) {
                String progressText = "Deleted " + end + " of " + totalFiles + "...";
                notificationManager.notify(jobId, createNotification(progressText, end, totalFiles));
            }
        }

        // Final notification update for this job
        notificationManager.notify(jobId, createNotification("Finished: " + deletedCount + " files removed", totalFiles, totalFiles));

        // Broadcast completion for the specific task
        Intent broadcastIntent = new Intent(ACTION_DELETE_COMPLETE);
        broadcastIntent.putExtra(EXTRA_DELETED_COUNT, deletedCount);
        LocalBroadcastManager.getInstance(this).sendBroadcast(broadcastIntent);
        
        // Remove the notification after a short delay or let it stay as "Finished"
        // Here we keep it for 2 seconds then clear it
        try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
        notificationManager.cancel(jobId);
    }

    private void checkAndStop() {
        if (activeTasks.get() <= 0) {
            stopForeground(true);
            stopSelf();
        }
    }

    private Notification createNotification(String contentText, int progress, int max) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("HFM Deletion Task")
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_menu_delete)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOnlyAlertOnce(true)
                .setOngoing(progress < max);

        if (max > 0) {
            builder.setProgress(max, progress, false);
        } else {
            builder.setProgress(0, 0, true);
        }

        return builder.build();
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (notificationManager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        NOTIFICATION_CHANNEL_ID,
                        "File Deletion Service",
                        NotificationManager.IMPORTANCE_LOW
                );
                channel.setDescription("Shows progress for concurrent background deletion tasks");
                notificationManager.createNotificationChannel(channel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (executorService != null) {
            executorService.shutdown();
        }
        super.onDestroy();
    }
}