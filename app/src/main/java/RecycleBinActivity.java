package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Bundle;
import android.os.Environment;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class RecycleBinActivity extends Activity {

    private static final String RECYCLE_BIN_FOLDER_NAME = "HFMRecycleBin";
    // UPDATED: Removed the dot to match the visible folder on SD Card
    private static final String SD_RECYCLE_BIN_FOLDER_NAME = "HFMRecycleBin";

    private ImageButton backButton;
    private TextView titleTextView;
    private RecyclerView recyclerView;
    private TextView emptyView;

    private File currentDirectory;
    private final File rootStorageDir = Environment.getExternalStorageDirectory();
    private final File phoneRecycleBinDir = new File(rootStorageDir, RECYCLE_BIN_FOLDER_NAME);
    private File sdCardRecycleBinDir = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_recycle_bin);

        initializeViews();
        setupListeners();
        
        // Identify SD Card Recycle Bin path using current StorageUtils logic
        String sdCardPath = StorageUtils.getSdCardPath(this);
        if (sdCardPath != null) {
            sdCardRecycleBinDir = new File(sdCardPath, SD_RECYCLE_BIN_FOLDER_NAME);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Always start by showing the root view which lists available bins
        currentDirectory = null; 
        refreshList();
    }

    private void initializeViews() {
        backButton = findViewById(R.id.back_button_recycle_bin);
        titleTextView = findViewById(R.id.title_recycle_bin);
        recyclerView = findViewById(R.id.recycle_bin_recycler_view);
        emptyView = findViewById(R.id.empty_view_recycle_bin);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
    }

    private void setupListeners() {
        backButton.setOnClickListener(new View.OnClickListener() {
				@Override
				public void onClick(View v) {
					handleBackNavigation();
				}
			});
    }

    private void refreshList() {
        createRecycleBinIfNeeded();
        if (currentDirectory == null) {
            listRootBins();
        } else {
            listFiles(currentDirectory);
        }
    }

    private void createRecycleBinIfNeeded() {
        if (!phoneRecycleBinDir.exists()) {
            phoneRecycleBinDir.mkdir();
        }
    }

    private void listRootBins() {
        titleTextView.setText("Recycle Bins");
        List<File> binList = new ArrayList<>();
        
        // Always add Phone Bin
        if (phoneRecycleBinDir.exists()) {
            binList.add(phoneRecycleBinDir);
        }
        
        // Add SD Card Bin if it exists and is not the same as phone bin
        if (sdCardRecycleBinDir != null && sdCardRecycleBinDir.exists()) {
            if (!sdCardRecycleBinDir.getAbsolutePath().equals(phoneRecycleBinDir.getAbsolutePath())) {
                binList.add(sdCardRecycleBinDir);
            }
        }
        
        setupAdapter(binList);
    }

    private void listFiles(File directory) {
        currentDirectory = directory;
        
        if (directory.equals(phoneRecycleBinDir)) {
            titleTextView.setText("Phone Recycle Bin");
        } else if (sdCardRecycleBinDir != null && directory.equals(sdCardRecycleBinDir)) {
            titleTextView.setText("SD Card Recycle Bin");
        } else {
            titleTextView.setText(directory.getName());
        }

        File[] files = directory.listFiles();
        List<File> fileList = new ArrayList<>();
        if (files != null) {
            fileList.addAll(Arrays.asList(files));
        }
        
        Collections.sort(fileList, new Comparator<File>() {
            @Override
            public int compare(File f1, File f2) {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareToIgnoreCase(f2.getName());
            }
        });
        
        setupAdapter(fileList);
    }

    private void setupAdapter(List<File> files) {
        if (files.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyView.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        RecycleBinAdapter adapter = new RecycleBinAdapter(this, files, new RecycleBinAdapter.OnItemClickListener() {
				@Override
				public void onItemClick(File file) {
					if (file.isDirectory()) {
						listFiles(file);
					} else {
						Toast.makeText(RecycleBinActivity.this, "Cannot open deleted files. Restore feature coming soon.", Toast.LENGTH_SHORT).show();
					}
				}

				@Override
				public void onItemLongClick(File file) {
					showDeleteConfirmationDialog(file);
				}
			});
        recyclerView.setAdapter(adapter);
    }

    private void showDeleteConfirmationDialog(final File fileOrFolder) {
        String message = fileOrFolder.isDirectory() && (fileOrFolder.equals(phoneRecycleBinDir) || fileOrFolder.equals(sdCardRecycleBinDir)) ? 
            "Empty this Recycle Bin? All files inside will be permanently deleted." : 
            "Permanently delete this item?";

        new AlertDialog.Builder(this)
			.setTitle("Confirm Deletion")
			.setMessage(message)
			.setPositiveButton("Delete", new DialogInterface.OnClickListener() {
				@Override
				public void onClick(DialogInterface dialog, int which) {
					deleteRecursive(fileOrFolder);
					refreshList();
				}
			})
			.setNegativeButton("Cancel", null)
			.show();
    }

    private void deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
            // Logic to prevent deleting the root bin folders themselves
            if (!fileOrDirectory.equals(phoneRecycleBinDir) && !fileOrDirectory.equals(sdCardRecycleBinDir)) {
                StorageUtils.deleteFile(this, fileOrDirectory);
            }
        } else {
            StorageUtils.deleteFile(this, fileOrDirectory);
        }
    }

    private void handleBackNavigation() {
        if (currentDirectory != null) {
            currentDirectory = null;
            listRootBins();
        } else {
            finish();
        }
    }

    @Override
    public void onBackPressed() {
        handleBackNavigation();
    }
}