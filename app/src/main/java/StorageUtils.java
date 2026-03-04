package com.hfm.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.DocumentsContract;
import android.util.Log;
import androidx.documentfile.provider.DocumentFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StorageUtils {

    private static final String TAG = "StorageUtils";
    private static final String PREFS_NAME = "SDCardPrefs";
    private static final String KEY_SDCARD_URI = "sdcard_uri";
    public static final int REQUEST_CODE_SDCARD_PERMISSION = 101;
    
    public static final String SD_RECYCLE_BIN_NAME = "HFMRecycleBin";

    // UPDATE: Static cache variable to prevent ANR crash on large file selections
    private static String cachedSdCardPath = null;

    public static void saveSdCardUri(Context context, Uri uri) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_SDCARD_URI, uri.toString()).apply();
    }

    public static Uri getSdCardUri(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String uriString = prefs.getString(KEY_SDCARD_URI, null);
        if (uriString != null) {
            return Uri.parse(uriString);
        }
        return null;
    }

    public static boolean hasSdCardPermission(Context context) {
        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) return false;
        try {
            int takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            context.getContentResolver().takePersistableUriPermission(sdCardUri, takeFlags);
            return true;
        } catch (SecurityException e) {
            saveSdCardUri(context, null);
            return false;
        }
    }

    public static void requestSdCardPermission(Activity activity) {
        StorageManager sm = (StorageManager) activity.getSystemService(Context.STORAGE_SERVICE);
        if (sm == null) {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
            return;
        }
        StorageVolume sdCardVolume = null;
        for (StorageVolume volume : sm.getStorageVolumes()) {
            if (volume.isRemovable() && volume.getState().equals("mounted")) {
                sdCardVolume = volume;
                break;
            }
        }
        Intent intent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && sdCardVolume != null) {
            intent = sdCardVolume.createAccessIntent(null);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        }
        if (intent == null) intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        try {
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
        } catch (Exception e) {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            activity.startActivityForResult(intent, REQUEST_CODE_SDCARD_PERMISSION);
        }
    }

    public static boolean isFileOnSdCard(Context context, File file) {
        // UPDATE: This method is now instant because it uses the cached path variable
        String sdCardPath = getSdCardPath(context);
        if (sdCardPath != null && file != null) {
            try {
                return file.getCanonicalPath().startsWith(sdCardPath);
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    public static boolean deleteFile(Context context, File file) {
        if (file == null || !file.exists()) return true;

        if (file.delete()) {
            return true;
        }

        if (isFileOnSdCard(context, file)) {
            DocumentFile docFile = getDocumentFile(context, file, false);
            if (docFile != null && docFile.exists()) {
                if (docFile.delete()) {
                    return true;
                }
            }
        }
        return false;
    }

    public static void deleteRecursive(Context context, File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(context, child);
                }
            }
        }
        deleteFile(context, fileOrDirectory);
    }

    public static String getSdCardPath(Context context) {
        // UPDATE: Check cache first. If we already know the path, return it immediately.
        // This avoids the expensive 'getExternalFilesDirs' Binder call inside loops.
        if (cachedSdCardPath != null) {
            return cachedSdCardPath;
        }

        File[] storageVolumes = context.getExternalFilesDirs(null);
        if (storageVolumes.length > 1 && storageVolumes[1] != null) {
            String fullPath = storageVolumes[1].getAbsolutePath();
            if (fullPath.contains("/Android/data")) {
                try {
                    String rootPath = fullPath.substring(0, fullPath.indexOf("/Android/data"));
                    cachedSdCardPath = new File(rootPath).getCanonicalPath();
                    return cachedSdCardPath;
                } catch (IOException e) {
                    return null;
                }
            }
        }
        return null;
    }

    public static DocumentFile getDocumentFile(Context context, File file, boolean isDirectory) {
        String sdCardPath = getSdCardPath(context);
        if (sdCardPath == null) return null;

        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) return null;

        DocumentFile rootDocFile = DocumentFile.fromTreeUri(context, sdCardUri);
        if (rootDocFile == null) return null;

        String relativePath;
        try {
            String canonicalFilePath = file.getCanonicalPath();
            if (!canonicalFilePath.startsWith(sdCardPath)) return null;
            relativePath = canonicalFilePath.substring(sdCardPath.length());
        } catch (IOException e) {
            return null;
        }

        if (relativePath.startsWith(File.separator)) relativePath = relativePath.substring(1);

        String[] pathSegments = relativePath.split(File.separator);
        DocumentFile result = rootDocFile;

        for (int i = 0; i < pathSegments.length; i++) {
            String segment = pathSegments[i];
            if (segment.isEmpty()) continue;

            DocumentFile next = result.findFile(segment);
            if (next == null) {
                if (i < pathSegments.length - 1 || !isDirectory) {
                    return null;
                }
            }
            result = next;
        }
        return result;
    }

    public static OutputStream getOutputStream(Context context, File targetFile) throws IOException {
        if (!isFileOnSdCard(context, targetFile)) {
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            return new FileOutputStream(targetFile);
        }

        DocumentFile parentDoc = getDocumentFile(context, targetFile.getParentFile(), true);
        if (parentDoc == null || !parentDoc.canWrite()) {
            throw new IOException("Cannot get writable parent directory on SD card.");
        }

        String mimeType = "application/octet-stream";
        DocumentFile newDocFile = parentDoc.createFile(mimeType, targetFile.getName());
        if (newDocFile == null) {
            newDocFile = parentDoc.findFile(targetFile.getName());
            if (newDocFile == null) throw new IOException("Failed to create file on SD card.");
        }
        return context.getContentResolver().openOutputStream(newDocFile.getUri());
    }

    public static boolean createDirectory(Context context, File dir) {
        if (!isFileOnSdCard(context, dir)) return dir.mkdirs();
        DocumentFile parentDoc = getDocumentFile(context, dir.getParentFile(), true);
        if (parentDoc == null || !parentDoc.canWrite()) return false;
        DocumentFile newDir = parentDoc.createDirectory(dir.getName());
        return newDir != null && newDir.exists();
    }

    public static boolean copyFile(Context context, File source, File destination) {
        InputStream in = null;
        OutputStream out = null;
        try {
            Uri sourceUri = Uri.fromFile(source);
            in = context.getContentResolver().openInputStream(sourceUri);
            if (in == null) return false;
            out = getOutputStream(context, destination);
            if (out == null) return false;

            byte[] buf = new byte[131072]; 
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            return true;
        } catch (IOException e) {
            Log.e(TAG, "File copy failed", e);
            return false;
        } finally {
            try {
                if (in != null) in.close();
                if (out != null) out.close();
            } catch (IOException e) {}
        }
    }

    public static DocumentFile getOrCreateSdCardRecycleBin(Context context) {
        Uri sdCardUri = getSdCardUri(context);
        if (sdCardUri == null) return null;
        DocumentFile rootDocFile = DocumentFile.fromTreeUri(context, sdCardUri);
        if (rootDocFile == null) return null;
        DocumentFile recycleBin = rootDocFile.findFile(SD_RECYCLE_BIN_NAME);
        if (recycleBin == null) {
            recycleBin = rootDocFile.createDirectory(SD_RECYCLE_BIN_NAME);
        }
        return recycleBin;
    }

    public static boolean moveFileOnSdCardSafely(Context context, File sourceFile, DocumentFile recycleBinDoc) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                DocumentFile sourceDoc = getDocumentFile(context, sourceFile, false);
                if (sourceDoc != null && recycleBinDoc != null) {
                    try {
                        Uri movedUri = DocumentsContract.moveDocument(context.getContentResolver(), 
                                sourceDoc.getUri(), sourceDoc.getParentFile().getUri(), recycleBinDoc.getUri());
                        return movedUri != null;
                    } catch (Exception e) {
                        Log.w(TAG, "Native moveDocument failed, attempting rename fallback", e);
                    }
                    if (sourceDoc.renameTo(sourceFile.getName())) {
                        return true; 
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "SAF Move failed", e);
            }
        }
        return false;
    }

    public static boolean moveFileOnSdCardSafely(Context context, File sourceFile) {
        return moveFileOnSdCardSafely(context, sourceFile, getOrCreateSdCardRecycleBin(context));
    }
}