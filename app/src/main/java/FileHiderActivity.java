package com.hfm.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public class FileHiderActivity extends Activity {

    private static final String TAG = "FileHiderActivity";

    // UI Elements
    private ImageButton closeButton, filterButton;
    private AutoCompleteTextView searchInput;
    private RecyclerView hiderResultsGrid;
    private Button selectAllButton, hideButton;
    private TextView selectionCountText, scanStatusText;
    private LinearLayout loadingView;
    private RelativeLayout resultsView;

    private FileHiderAdapter adapter;
    private List<File> masterFileList = new ArrayList<>();
    private String currentFilterType = "all";
    private boolean isAllSelected = false;

    private FileHiderAdapter.OnItemClickListener fileItemClickListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        ThemeManager.applyTheme(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_hider);

        initializeViews();
        setupListeners();
        setupRecyclerView();

        // --- NEW LOGIC: Check for pre-selected files from Intent (e.g., from StorageBrowserActivity) ---
        Serializable fileListSerializable = getIntent().getSerializableExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE);
        if (fileListSerializable instanceof List) {
            List<File> preSelectedFiles = (List<File>) fileListSerializable;
            if (!preSelectedFiles.isEmpty()) {
                // If we received a list, display it directly.
                // This list can now contain Folder objects, which the Adapter handles correctly.
                handlePreSelectedFiles(preSelectedFiles);
                return; // Stop further execution (skip the full device scan)
            }
        }

        // --- ORIGINAL LOGIC: Fallback to full scan if no pre-selected files are found ---
        new ScanFilesTask().execute(Environment.getExternalStorageDirectory());
    }

    private void initializeViews() {
        closeButton = findViewById(R.id.close_button_hider);
        filterButton = findViewById(R.id.filter_button_hider);
        searchInput = findViewById(R.id.search_input_hider);
        hiderResultsGrid = findViewById(R.id.hider_results_grid);
        selectAllButton = findViewById(R.id.select_all_button_hider);
        hideButton = findViewById(R.id.hide_button_final);
        selectionCountText = findViewById(R.id.selection_count_text_hider);
        scanStatusText = findViewById(R.id.scan_status_text_hider);
        loadingView = findViewById(R.id.loading_view_hider);
        resultsView = findViewById(R.id.results_view_hider);
    }

    // --- NEW METHOD to handle incoming file/folder list ---
    private void handlePreSelectedFiles(List<File> files) {
        // We don't need the loading view for this mode
        loadingView.setVisibility(View.GONE);
        resultsView.setVisibility(View.VISIBLE);
        scanStatusText.setText(files.size() + " item(s) selected for hiding.");
        
        // Hide filter/search controls as we are showing a fixed selection list
        filterButton.setVisibility(View.GONE);
        searchInput.setVisibility(View.GONE);

        masterFileList.clear();
        masterFileList.addAll(files);

        // Create the adapter with the provided list
        adapter = new FileHiderAdapter(this, masterFileList, fileItemClickListener);
        hiderResultsGrid.setAdapter(adapter);

        // Automatically select all the files/folders that were passed in
        adapter.selectAll(true);
        isAllSelected = true;
        selectAllButton.setText("Deselect All");
        updateSelectionCount();
    }

    private void setupRecyclerView() {
        this.fileItemClickListener = new FileHiderAdapter.OnItemClickListener() {
            @Override
            public void onItemClick(int position) {
                FileHiderAdapter.FileItem item = adapter.getOriginalItems().get(position);
                item.setSelected(!item.isSelected());
                adapter.notifyDataSetChanged();
                updateSelectionCount();
            }

            @Override
            public void onSelectionChanged() {
                updateSelectionCount();
            }
        };

        adapter = new FileHiderAdapter(this, new ArrayList<File>(), this.fileItemClickListener);
        hiderResultsGrid.setLayoutManager(new GridLayoutManager(this, 3));
        hiderResultsGrid.setAdapter(adapter);
    }

    private void setupListeners() {
        closeButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });

        selectAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                isAllSelected = !isAllSelected;
                adapter.selectAll(isAllSelected);
                if (isAllSelected) {
                    selectAllButton.setText("Deselect All");
                } else {
                    selectAllButton.setText("Select All");
                }
            }
        });

        hideButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startHidingProcess();
            }
        });

        filterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterMenu(v);
            }
        });

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (adapter != null) {
                    adapter.getFilter().filter(s);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });
    }

    private void updateSelectionCount() {
        int count = 0;
        for (FileHiderAdapter.FileItem item : adapter.getOriginalItems()) {
            if (item.isSelected()) {
                count++;
            }
        }
        selectionCountText.setText(count + " items selected");

        int visibleItemCount = adapter.getItemCount();
        int visibleSelectedItemCount = 0;
        for (FileHiderAdapter.FileItem item : adapter.getItems()) {
            if(item.isSelected()) {
                visibleSelectedItemCount++;
            }
        }

        if (visibleItemCount > 0 && visibleSelectedItemCount == visibleItemCount) {
            isAllSelected = true;
            selectAllButton.setText("Deselect All");
        } else {
            isAllSelected = false;
            selectAllButton.setText("Select All");
        }
    }

    private void showFilterMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.getMenuInflater().inflate(R.menu.filter_menu, popup.getMenu());
        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                int itemId = item.getItemId();
                if (itemId == R.id.filter_all) currentFilterType = "all";
                else if (itemId == R.id.filter_images) currentFilterType = "images";
                else if (itemId == R.id.filter_videos) currentFilterType = "videos";
                else if (itemId == R.id.filter_documents) currentFilterType = "documents";
                else if (itemId == R.id.filter_archives) currentFilterType = "archives";
                else if (itemId == R.id.filter_other) currentFilterType = "other";

                filterMasterList();
                return true;
            }
        });
        popup.show();
    }

    private void filterMasterList() {
        List<File> filteredList = new ArrayList<>();
        if (currentFilterType.equals("all")) {
            filteredList.addAll(masterFileList);
        } else {
            for (File file : masterFileList) {
                if (isFileTypeMatch(file.getName())) {
                    filteredList.add(file);
                }
            }
        }

        adapter = new FileHiderAdapter(this, filteredList, this.fileItemClickListener);
        hiderResultsGrid.setAdapter(adapter);

        String currentSearch = searchInput.getText().toString();
        if (!currentSearch.isEmpty()) {
            adapter.getFilter().filter(currentSearch);
        }

        updateSelectionCount();
    }

    private boolean isFileTypeMatch(String fileName) {
        String extension = "";
        int i = fileName.lastIndexOf('.');
        if (i > 0) {
            extension = fileName.substring(i + 1).toLowerCase();
        }
        switch (currentFilterType) {
            case "images": return Arrays.asList("jpg", "jpeg", "png", "gif", "bmp", "webp").contains(extension);
            case "videos": return Arrays.asList("mp4", "3gp", "mkv", "webm", "avi").contains(extension);
            case "documents": return Arrays.asList("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt").contains(extension);
            case "archives": return Arrays.asList("zip", "rar", "7z", "tar", "gz").contains(extension);
            case "other": return !isFileTypeMatch(fileName, "images") && !isFileTypeMatch(fileName, "videos") && !isFileTypeMatch(fileName, "documents") && !isFileTypeMatch(fileName, "archives");
            default: return true;
        }
    }

    private boolean isFileTypeMatch(String fileName, String type) {
        String originalFilter = this.currentFilterType;
        this.currentFilterType = type;
        boolean match = isFileTypeMatch(fileName);
        this.currentFilterType = originalFilter;
        return match;
    }

    private void startHidingProcess() {
        final List<File> filesToHide = new ArrayList<>();
        for (FileHiderAdapter.FileItem item : adapter.getOriginalItems()) {
            if (item.isSelected()) {
                filesToHide.add(item.getFile());
            }
        }

        if (filesToHide.isEmpty()) {
            Toast.makeText(this, "No items selected to hide.", Toast.LENGTH_SHORT).show();
            return;
        }

        final RitualManager ritualManager = new RitualManager();
        final List<RitualManager.Ritual> existingRituals = ritualManager.loadRituals(this);

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        LayoutInflater inflater = this.getLayoutInflater();
        View dialogView = inflater.inflate(R.layout.dialog_ritual_choice, null);
        builder.setView(dialogView);

        final TextView message = dialogView.findViewById(R.id.ritual_dialog_message);
        final Button newRitualButton = dialogView.findViewById(R.id.button_new_ritual);
        final Button existingRitualButton = dialogView.findViewById(R.id.button_existing_ritual);

        message.setText("You have selected " + filesToHide.size() + " item(s). How would you like to secure them?");

        final AlertDialog choiceDialog = builder.create();

        newRitualButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Pass the files directly. If they are folders, the RitualManager handles zipping.
                Intent intent = new Intent(FileHiderActivity.this, RitualRecordTapsActivity.class);
                intent.putExtra(RitualRecordTapsActivity.EXTRA_FILES_TO_HIDE, (Serializable) filesToHide);
                startActivity(intent);
                choiceDialog.dismiss();
                finish();
            }
        });

        if (existingRituals != null && !existingRituals.isEmpty()) {
            existingRitualButton.setEnabled(true);
            existingRitualButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    choiceDialog.dismiss();
                    showExistingRitualsDialog(existingRituals, filesToHide, ritualManager);
                }
            });
        } else {
            existingRitualButton.setEnabled(false);
            existingRitualButton.setAlpha(0.5f);
        }

        choiceDialog.show();
    }

    private void showExistingRitualsDialog(List<RitualManager.Ritual> rituals, final List<File> filesToHide, final RitualManager ritualManager) {
        CharSequence[] ritualChoices = new CharSequence[rituals.size()];
        for (int i = 0; i < rituals.size(); i++) {
            ritualChoices[i] = "Ritual #" + (i + 1) + " (" + rituals.get(i).hiddenFiles.size() + " files)";
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add to which Ritual?");
        builder.setItems(ritualChoices, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                ritualManager.addFilesToRitual(FileHiderActivity.this, which, filesToHide);
                dialog.dismiss();
                finish();
            }
        });
        builder.show();
    }

    // --- AsyncTask for scanning files (Fallback mode only) ---
    private class ScanFilesTask extends AsyncTask<File, String, List<File>> {
        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            loadingView.setVisibility(View.VISIBLE);
            resultsView.setVisibility(View.GONE);
        }

        @Override
        protected List<File> doInBackground(File... roots) {
            List<File> foundFiles = new ArrayList<>();
            for (File root : roots) {
                scanDirectory(root, foundFiles);
            }
            Collections.sort(foundFiles, new Comparator<File>() {
                @Override
                public int compare(File f1, File f2) {
                    return Long.compare(f2.lastModified(), f1.lastModified());
                }
            });
            return foundFiles;
        }

        private void scanDirectory(File directory, List<File> fileList) {
            if (directory == null || !directory.isDirectory()) {
                return;
            }
            if (directory.getAbsolutePath().startsWith(Environment.getExternalStorageDirectory().getAbsolutePath() + "/Android")) {
                return;
            }

            publishProgress("Scanning: " + directory.getName());
            File[] files = directory.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (isCancelled()) {
                        return;
                    }
                    if (file.isDirectory()) {
                        scanDirectory(file, fileList);
                    } else {
                        fileList.add(file);
                    }
                }
            }
        }

        @Override
        protected void onProgressUpdate(String... values) {
            super.onProgressUpdate(values);
            if (values.length > 0) {
                scanStatusText.setText(values[0]);
            }
        }

        @Override
        protected void onPostExecute(List<File> result) {
            super.onPostExecute(result);
            loadingView.setVisibility(View.GONE);
            resultsView.setVisibility(View.VISIBLE);

            if (result.isEmpty()) {
                Toast.makeText(FileHiderActivity.this, "No files found on device.", Toast.LENGTH_LONG).show();
            } else {
                scanStatusText.setText("Analysis complete. " + result.size() + " files found.");
                masterFileList.clear();
                masterFileList.addAll(result);
                adapter = new FileHiderAdapter(FileHiderActivity.this, masterFileList, fileItemClickListener);
                hiderResultsGrid.setAdapter(adapter);
            }
        }
    }
}