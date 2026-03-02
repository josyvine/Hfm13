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
     * Deletes a file from internal storage using the most reliable method available.
     * It first tries to delete via the Android MediaStore's ContentResolver, which is the
     * preferred method. If that fails (e.g., the file is not in the MediaStore), it
     * falls back to a direct file system deletion and then triggers a media scan to
     * ensure the system's index is updated.
     *
     * @param context The application context.
     * @param file    The file to be deleted.
     * @return true if the file was successfully deleted, false otherwise.
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
            int rowsDeleted = resolver.delete(MediaStore.Files.getContentUri("external"), where, selectionArgs);
            if (rowsDeleted > 0) {
                Log.d(TAG, "Successfully deleted file via ContentResolver: " + path);
                return true;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error deleting file via ContentResolver for path: " + path, e);
        }

        if (file.delete()) {
            Log.d(TAG, "Successfully deleted file directly. Requesting media scan for: " + path);
            context.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, Uri.fromFile(file)));
            return true;
        }

        return false;
    }

    /**
     * NEW: Batch deletion method for Enhancement 4.
     * Deletes a list of files efficiently by batching the database operation.
     *
     * @param context The application context.
     * @param files   The list of files to be deleted.
     * @return The number of files successfully deleted.
     */
    public static int deleteFileBatch(Context context, List<File> files) {
        if (files == null || files.isEmpty()) return 0;

        int deletedCount = 0;
        ContentResolver resolver = context.getContentResolver();
        
        // 1. Prepare bulk SQL query: WHERE _data IN (?, ?, ?, ...)
        StringBuilder where = new StringBuilder(MediaStore.Files.FileColumns.DATA + " IN (");
        String[] selectionArgs = new String[files.size()];
        
        for (int i = 0; i < files.size(); i++) {
            where.append("?");
            if (i < files.size() - 1) where.append(",");
            selectionArgs[i] = files.get(i).getAbsolutePath();
        }
        where.append(")");

        // 2. Execute bulk delete on MediaStore
        try {
            resolver.delete(MediaStore.Files.getContentUri("external"), where.toString(), selectionArgs);
        } catch (Exception e) {
            Log.e(TAG, "Bulk MediaStore delete failed", e);
        }

        // 3. Physically delete the files from storage
        for (File file : files) {
            if (file.exists() && file.delete()) {
                deletedCount++;
            }
        }
        return deletedCount;
    }
}