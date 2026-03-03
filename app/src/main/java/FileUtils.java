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
     * Deletes a single file.
     * Prioritizes the fast MediaStore database delete.
     */
    public static boolean deleteFile(Context context, File file) {
        if (file == null || !file.exists()) {
            return true;
        }

        String path = file.getAbsolutePath();
        ContentResolver resolver = context.getContentResolver();
        String where = MediaStore.Files.FileColumns.DATA + " = ?";
        String[] selectionArgs = new String[]{ path };

        try {
            // Fast Database Delete
            int rowsDeleted = resolver.delete(MediaStore.Files.getContentUri("external"), where, selectionArgs);
            if (rowsDeleted > 0) {
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file via ContentResolver", e);
        }

        // Physical Fallback (Works for Internal or pre-Android 11)
        if (file.delete()) {
            return true;
        }

        return false;
    }

    /**
     * UPDATED: Restored Bulk SQL Delete to fix the 3-minute lag.
     * This method avoids SAF "findFile()" folder scans entirely.
     * It uses a single "IN" query to wipe the batch from the database instantly.
     */
    public static int deleteFileBatch(Context context, List<File> files) {
        if (files == null || files.isEmpty()) return 0;

        ContentResolver resolver = context.getContentResolver();
        int totalToProcess = files.size();
        
        // 1. Prepare Bulk SQL: WHERE _data IN (?, ?, ?, ...)
        // This is the "Fast Way" that processes the whole batch in one shot.
        StringBuilder where = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
        String[] selectionArgs = new String[totalToProcess];
        
        for (int i = 0; i < totalToProcess; i++) {
            where.append("?");
            if (i < totalToProcess - 1) where.append(",");
            selectionArgs[i] = files.get(i).getAbsolutePath();
        }
        where.append(")");

        int dbDeletedCount = 0;
        try {
            // UPDATE: This executes ONE command for all files. No more SAF loops!
            dbDeletedCount = resolver.delete(MediaStore.Files.getContentUri("external"), where.toString(), selectionArgs);
        } catch (Exception e) {
            Log.e(TAG, "Bulk MediaStore delete failed", e);
        }

        // 2. Physical cleanup and verification
        int physicalDeletedCount = 0;
        for (File file : files) {
            if (file.exists()) {
                // Try physical delete. On Android 11+ Approval, the OS often 
                // handles this, but we check to be sure.
                if (file.delete()) {
                    physicalDeletedCount++;
                }
            } else {
                // File is already gone (Approved by User in the popup prompt)
                physicalDeletedCount++;
            }
        }
        
        // Return the count that matches reality (whichever is higher)
        return Math.max(dbDeletedCount, physicalDeletedCount);
    }
}