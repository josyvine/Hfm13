package com.hfm.app;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import java.io.File;
import java.util.List;

public class FileUtils { 

    private static final String TAG = "FileUtils";

    /**
     * Deletes a file.
     * Checks if the file is on the SD Card. If so, routes to SAF.
     * Otherwise, attempts ContentResolver delete followed by File.delete().
     */
    public static boolean deleteFile(Context context, File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        // CRITICAL FIX: Always check SD card first. 
        // Standard file.delete() ALWAYS fails on Android 11+ SD cards.
        // We must route this to the Storage Access Framework.
        if (StorageUtils.isFileOnSdCard(context, file)) {
            return StorageUtils.deleteFile(context, file);
        }

        // --- Internal Storage Logic ---
        String path = file.getAbsolutePath();
        ContentResolver resolver = context.getContentResolver();
        String where = MediaStore.Files.FileColumns.DATA + " = ?";
        String[] selectionArgs = new String[]{ path };

        try {
            // Try to delete from MediaStore (Database)
            int rowsDeleted = resolver.delete(MediaStore.Files.getContentUri("external"), where, selectionArgs);
            if (rowsDeleted > 0) {
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file via ContentResolver", e);
        }

        // Try to delete from Filesystem (Disk)
        if (file.delete()) {
            // Broadcast so the gallery updates immediately
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
            return true;
        }

        return false;
    }

    /**
     * Batch deletion.
     * Routes SD card files to SAF one-by-one (required).
     * Routes Internal files to bulk DB delete (fast).
     */
    public static int deleteFileBatch(Context context, List<File> files) {
        if (files == null || files.isEmpty()) return 0;

        int deletedCount = 0;

        for (File file : files) {
            if (!file.exists()) {
                deletedCount++;
                continue;
            }

            // CRITICAL FIX: Detect SD Card per file
            if (StorageUtils.isFileOnSdCard(context, file)) {
                // Route to StorageUtils for SAF DocumentFile logic
                if (StorageUtils.deleteFile(context, file)) {
                    deletedCount++;
                }
            } else {
                // Route to Internal Storage logic
                if (deleteFile(context, file)) {
                    deletedCount++;
                }
            }
        }
        
        return deletedCount;
    }
}