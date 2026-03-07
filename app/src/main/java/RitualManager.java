package com.hfm.app;

import android.content.ContentResolver;
import android.content.Context;
import android.location.Location;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.util.Base64;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.spec.KeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

public class RitualManager {

    private static final String TAG = "RitualManager";
    private static final String RITUAL_FILE_NAME = "rituals.dat";
    private static final String HIDDEN_DIR_NAME = "hidden";

    // --- Data Class to represent a single Ritual ---
    public static class Ritual implements Serializable {
        private static final long serialVersionUID = 2L; // Updated version ID due to structure change

        public final int tapCount;
        public final int shakeCount;
        public final float[] magnetometerData;
        public final double latitude;
        public final double longitude;

        // --- FIELDS FOR MAP FALLBACK ---
        public Double fallbackLatitude;
        public Double fallbackLongitude;

        public List<HiddenFile> hiddenFiles;

        public Ritual(int tapCount, int shakeCount, float[] magnetometerData, Location location) {
            this.tapCount = tapCount;
            this.shakeCount = shakeCount;
            this.magnetometerData = magnetometerData.clone();
            this.latitude = location.getLatitude();
            this.longitude = location.getLongitude();
            this.hiddenFiles = new ArrayList<>();
            this.fallbackLatitude = null;
            this.fallbackLongitude = null;
        }

        public void addHiddenFile(HiddenFile file) {
            this.hiddenFiles.add(file);
        }
    }

    // --- Data Class to store info about a hidden file ---
    public static class HiddenFile implements Serializable {
        private static final long serialVersionUID = 3L; // Updated UID

        public final String originalPath;
        public final String encryptedFileName; // The new random name in the hidden directory
        public final boolean isFolder; // NEW: Track if this is a zipped folder

        public HiddenFile(String originalPath, String encryptedFileName, boolean isFolder) {
            this.originalPath = originalPath;
            this.encryptedFileName = encryptedFileName;
            this.isFolder = isFolder;
        }
    }

    // --- Public Method to Start the Hiding Process ---
    public void createAndSaveRitual(Context context, int taps, int shakes, float[] magnetometer, Location location, List<File> filesToHide) {
        Ritual newRitual = new Ritual(taps, shakes, magnetometer, location);
        new HideFilesTask(context, newRitual, filesToHide, -1).execute();
    }

    // --- NEW Public Method to add files to an existing Ritual ---
    public void addFilesToRitual(Context context, int ritualIndex, List<File> filesToHide) {
        List<Ritual> rituals = loadRituals(context);
        if (rituals != null && ritualIndex >= 0 && ritualIndex < rituals.size()) {
            Ritual existingRitual = rituals.get(ritualIndex);
            new HideFilesTask(context, existingRitual, filesToHide, ritualIndex).execute();
        } else {
            Toast.makeText(context, "Error: Could not find the specified ritual to update.", Toast.LENGTH_LONG).show();
        }
    }

    // --- Public Method to Start the Unhiding Process ---
    public void verifyAndDecryptRitual(Context context, Ritual ritual, int ritualIndex) {
        new UnhideFilesTask(context, ritual, ritualIndex).execute();
    }


    // --- File Persistence Methods ---
    public List<Ritual> loadRituals(Context context) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        if (!ritualFile.exists()) {
            return new ArrayList<>();
        }
        try {
            FileInputStream fis = new FileInputStream(ritualFile);
            ObjectInputStream ois = new ObjectInputStream(fis);
            List<Ritual> rituals = (List<Ritual>) ois.readObject();
            ois.close();
            fis.close();
            return rituals;
        } catch (Exception e) {
            Log.e(TAG, "Failed to load rituals", e);
            return new ArrayList<>(); // Return empty list on failure
        }
    }

    public void saveRituals(Context context, List<Ritual> rituals) {
        File ritualFile = new File(context.getFilesDir(), RITUAL_FILE_NAME);
        try {
            FileOutputStream fos = new FileOutputStream(ritualFile);
            ObjectOutputStream oos = new ObjectOutputStream(fos);
            oos.writeObject(rituals);
            oos.close();
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save rituals", e);
        }
    }

    // --- Cryptography and Key Generation ---
    private SecretKeySpec generateKey(Ritual ritual) throws Exception {
        StringBuilder passwordBuilder = new StringBuilder();
        passwordBuilder.append("taps:").append(ritual.tapCount);
        passwordBuilder.append("-shakes:").append(ritual.shakeCount);
        passwordBuilder.append("-mag:");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[0])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[1])).append(",");
        passwordBuilder.append(Float.floatToIntBits(ritual.magnetometerData[2]));
        passwordBuilder.append("-loc:");
        passwordBuilder.append(Double.doubleToLongBits(ritual.latitude)).append(",");
        passwordBuilder.append(Double.doubleToLongBits(ritual.longitude));

        String passwordString = passwordBuilder.toString();
        
        String salt = "hfm_secure_salt"; 
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1");
        KeySpec spec = new PBEKeySpec(passwordString.toCharArray(), salt.getBytes(), 65536, 256); // 256-bit key
        SecretKey tmp = factory.generateSecret(spec);

        return new SecretKeySpec(tmp.getEncoded(), "AES");
    }

    // --- MODIFIED AsyncTask to handle file HIDING ---
    private class HideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final List<File> filesToHide;
        private final SecretKeySpec secretKey;
        private final int ritualIndex;

        HideFilesTask(Context context, Ritual ritual, List<File> filesToHide, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.filesToHide = filesToHide;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key generation failed!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting encryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) {
                return false;
            }

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) {
                hiddenDir.mkdir();
            }

            // Removed ContentResolver here to prevent "Delete?" system popup

            for (int i = 0; i < filesToHide.size(); i++) {
                File originalFile = filesToHide.get(i);
                String path = originalFile.getAbsolutePath();
                boolean isFolder = originalFile.isDirectory();
                
                publishProgress("Encrypting: " + originalFile.getName() + " (" + (i + 1) + "/" + filesToHide.size() + ")");

                File fileToEncrypt = originalFile;
                File tempZipFile = null;

                // --- NEW LOGIC: Zip Folder before encrypting ---
                if (isFolder) {
                    try {
                        tempZipFile = new File(context.getCacheDir(), UUID.randomUUID().toString() + "_temp.zip");
                        zipFolder(originalFile, tempZipFile);
                        fileToEncrypt = tempZipFile;
                    } catch (IOException e) {
                        Log.e(TAG, "Failed to zip folder: " + originalFile.getName(), e);
                        continue; // Skip this file if zipping fails
                    }
                }

                String encryptedFileName = UUID.randomUUID().toString() + ".hfm";
                File encryptedFile = new File(hiddenDir, encryptedFileName);

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.ENCRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(fileToEncrypt);
                    FileOutputStream fos = new FileOutputStream(encryptedFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    ritual.addHiddenFile(new HiddenFile(path, encryptedFileName, isFolder));

                    // Clean up temp zip
                    if (tempZipFile != null && tempZipFile.exists()) {
                        tempZipFile.delete();
                    }

                    // --- STEALTH REMOVAL (Fix for System Popup) ---
                    // 1. Physically delete the file/folder first.
                    if (isFolder) {
                        StorageUtils.deleteRecursive(context, originalFile);
                    } else {
                        if (!originalFile.delete()) {
                            StorageUtils.deleteFile(context, originalFile);
                        }
                    }

                    // 2. Trigger a silent MediaScan on the DELETED path.
                    // When MediaScanner scans a path that doesn't exist, it removes it from the DB silently.
                    MediaScannerConnection.scanFile(context, new String[]{path}, null, null);

                } catch (Exception e) {
                    Log.e(TAG, "Encryption failed for " + originalFile.getName(), e);
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                List<Ritual> rituals = loadRituals(context);
                if (ritualIndex == -1) {
                    rituals.add(ritual);
                } else {
                    rituals.set(ritualIndex, ritual);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "Items successfully hidden!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "A critical error occurred. Hiding process failed.", Toast.LENGTH_LONG).show();
            }
        }
    }

    // --- AsyncTask to handle file UNHIDING ---
    private class UnhideFilesTask extends AsyncTask<Void, String, Boolean> {
        private final Context context;
        private final Ritual ritual;
        private final int ritualIndex;
        private final SecretKeySpec secretKey;

        UnhideFilesTask(Context context, Ritual ritual, int ritualIndex) {
            this.context = context;
            this.ritual = ritual;
            this.ritualIndex = ritualIndex;
            SecretKeySpec key = null;
            try {
                key = generateKey(ritual);
            } catch (Exception e) {
                Log.e(TAG, "Key re-generation failed for decryption!", e);
            }
            this.secretKey = key;
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            Toast.makeText(context, "Starting decryption process...", Toast.LENGTH_SHORT).show();
        }

        @Override
        protected Boolean doInBackground(Void... voids) {
            if (secretKey == null) return false;

            File hiddenDir = new File(context.getFilesDir(), HIDDEN_DIR_NAME);
            if (!hiddenDir.exists()) return false;

            for (int i = 0; i < ritual.hiddenFiles.size(); i++) {
                HiddenFile hiddenFile = ritual.hiddenFiles.get(i);
                File encryptedFile = new File(hiddenDir, hiddenFile.encryptedFileName);
                final File destinationPath = new File(hiddenFile.originalPath);

                publishProgress("Decrypting: " + destinationPath.getName() + " (" + (i + 1) + "/" + ritual.hiddenFiles.size() + ")");

                // Decrypt to a temporary file in internal cache first
                File tempDecryptedFile = new File(context.getCacheDir(), "temp_decrypt_" + UUID.randomUUID().toString());

                try {
                    Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                    IvParameterSpec iv = new IvParameterSpec("hfm_static_iv_16".getBytes(StandardCharsets.UTF_8));
                    cipher.init(Cipher.DECRYPT_MODE, secretKey, iv);

                    FileInputStream fis = new FileInputStream(encryptedFile);
                    FileOutputStream fos = new FileOutputStream(tempDecryptedFile);
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = fis.read(buffer)) != -1) {
                        byte[] output = cipher.update(buffer, 0, bytesRead);
                        if (output != null) {
                            fos.write(output);
                        }
                    }
                    byte[] finalBytes = cipher.doFinal();
                    if (finalBytes != null) {
                        fos.write(finalBytes);
                    }
                    fis.close();
                    fos.flush();
                    fos.close();

                    // --- RESTORE LOGIC ---
                    if (hiddenFile.isFolder) {
                        // If it was a folder, unzip it to the destination
                        unzip(tempDecryptedFile, destinationPath);
                    } else {
                        // If file, move copy safely using StorageUtils (Handles SD Card permissions)
                        copyFileSafely(tempDecryptedFile, destinationPath);
                    }

                    // --- FIX FOR SLOW FILE REAPPEARANCE ---
                    // Force the MediaScanner to index the restored path immediately.
                    MediaScannerConnection.scanFile(context, 
                        new String[]{ destinationPath.getAbsolutePath() }, 
                        null, 
                        new MediaScannerConnection.OnScanCompletedListener() {
                            @Override
                            public void onScanCompleted(String path, Uri uri) {
                                Log.d(TAG, "Scanned restored file: " + path);
                            }
                        }
                    );

                    // Cleanup temp file
                    tempDecryptedFile.delete();

                    // If decryption successful, delete encrypted file
                    encryptedFile.delete();

                } catch (Exception e) {
                    Log.e(TAG, "Decryption/Restore failed for " + encryptedFile.getName(), e);
                    if (tempDecryptedFile.exists()) tempDecryptedFile.delete();
                    return false;
                }
            }
            return true;
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            Toast.makeText(context, values[0], Toast.LENGTH_SHORT).show();
        }

        @Override
        protected void onPostExecute(Boolean success) {
            super.onPostExecute(success);
            if (success) {
                List<Ritual> rituals = loadRituals(context);
                if (rituals.size() > ritualIndex) {
                    rituals.remove(ritualIndex);
                }
                saveRituals(context, rituals);
                Toast.makeText(context, "Items restored successfully!", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(context, "Error: Decryption process failed.", Toast.LENGTH_LONG).show();
            }
        }
        
        // --- Helper: Copy file safely (SD Card Compatible) ---
        private void copyFileSafely(File source, File destination) throws IOException {
            InputStream in = new FileInputStream(source);
            // Use StorageUtils to get a writeable stream, which handles SAF/SD cards
            OutputStream out = StorageUtils.getOutputStream(context, destination);
            
            byte[] buf = new byte[8192];
            int len;
            while ((len = in.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            in.close();
            out.close();
        }

        // --- Helper: Unzip (SD Card Compatible) ---
        private void unzip(File zipFile, File targetDirectory) throws IOException {
            if (!targetDirectory.exists()) {
                StorageUtils.createDirectory(context, targetDirectory);
            }

            ZipInputStream zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zipFile)));
            try {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    File outFile = new File(targetDirectory, ze.getName());
                    
                    if (ze.isDirectory()) {
                        StorageUtils.createDirectory(context, outFile);
                    } else {
                        // Ensure parent exists
                        File parent = outFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            StorageUtils.createDirectory(context, parent);
                        }
                        
                        // Use StorageUtils output stream for SD card support
                        OutputStream out = StorageUtils.getOutputStream(context, outFile);
                        BufferedOutputStream fout = new BufferedOutputStream(out);
                        
                        byte[] buffer = new byte[8192];
                        int count;
                        while ((count = zis.read(buffer)) != -1) {
                            fout.write(buffer, 0, count);
                        }
                        fout.close();
                    }
                }
            } finally {
                zis.close();
            }
        }
    }

    // --- Zipping Helpers (Inside main class for self-containment) ---

    private void zipFolder(File fileToZip, File zipFile) throws IOException {
        ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(zipFile));
        zipFile(fileToZip, fileToZip.getName(), zipOut);
        zipOut.close();
    }

    private void zipFile(File fileToZip, String fileName, ZipOutputStream zipOut) throws IOException {
        if (fileToZip.isHidden()) {
            return;
        }
        if (fileToZip.isDirectory()) {
            if (fileName.endsWith("/")) {
                zipOut.putNextEntry(new ZipEntry(fileName));
                zipOut.closeEntry();
            } else {
                zipOut.putNextEntry(new ZipEntry(fileName + "/"));
                zipOut.closeEntry();
            }
            File[] children = fileToZip.listFiles();
            if (children != null) {
                for (File childFile : children) {
                    zipFile(childFile, fileName + "/" + childFile.getName(), zipOut);
                }
            }
            return;
        }
        FileInputStream fis = new FileInputStream(fileToZip);
        ZipEntry zipEntry = new ZipEntry(fileName);
        zipOut.putNextEntry(zipEntry);
        byte[] bytes = new byte[8192];
        int length;
        while ((length = fis.read(bytes)) >= 0) {
            zipOut.write(bytes, 0, length);
        }
        fis.close();
    }
}