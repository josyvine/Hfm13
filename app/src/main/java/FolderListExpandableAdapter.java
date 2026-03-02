package com.hfm.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ThumbnailUtils;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter for the Daily Dashboard Folder List view (Enhancement 1).
 * Supports expandable/collapsible folder headers.
 */
public class FolderListExpandableAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_ITEM = 1;

    private final Context context;
    private List<Object> displayList; 
    private final OnItemClickListener itemClickListener;
    private final OnHeaderClickListener headerClickListener;
    
    // Executor for manual thumbnail generation if needed (preserving existing logic patterns)
    private final ExecutorService thumbnailExecutor = Executors.newFixedThreadPool(4);

    public interface OnItemClickListener {
        void onItemClick(File file);
    }

    public interface OnHeaderClickListener {
        void onHeaderClick(FolderListActivity.FolderHeader header);
    }

    public FolderListExpandableAdapter(Context context, List<Object> displayList, 
                                       OnItemClickListener itemClickListener, 
                                       OnHeaderClickListener headerClickListener) {
        this.context = context;
        this.displayList = displayList;
        this.itemClickListener = itemClickListener;
        this.headerClickListener = headerClickListener;
    }

    public void updateData(List<Object> newDisplayList) {
        this.displayList = newDisplayList;
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        if (displayList.get(position) instanceof FolderListActivity.FolderHeader) {
            return TYPE_HEADER;
        } else {
            return TYPE_ITEM;
        }
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == TYPE_HEADER) {
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_folder_header, parent, false);
            return new HeaderViewHolder(view);
        } else {
            // Using existing list_item_folder_file layout or creating a new one if needed.
            // Based on analysis, we need a layout for file rows.
            // Using a generic row layout for files inside folders.
            View view = LayoutInflater.from(context).inflate(R.layout.list_item_folder_file, parent, false);
            return new FileViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull final RecyclerView.ViewHolder holder, int position) {
        int viewType = getItemViewType(position);
        
        if (viewType == TYPE_HEADER) {
            HeaderViewHolder headerHolder = (HeaderViewHolder) holder;
            final FolderListActivity.FolderHeader folderHeader = (FolderListActivity.FolderHeader) displayList.get(position);

            headerHolder.folderNameText.setText(folderHeader.getFolderName());
            headerHolder.fileCountText.setText("(" + folderHeader.getFileCount() + ")");
            
            // Minimize/Expand Arrow
            headerHolder.arrowIcon.setRotation(folderHeader.isExpanded() ? 0f : 180f);
            
            // Whole header is clickable to toggle expansion
            headerHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (headerClickListener != null) {
                        headerClickListener.onHeaderClick(folderHeader);
                    }
                }
            });

        } else {
            final FileViewHolder itemHolder = (FileViewHolder) holder;
            final File file = (File) displayList.get(position);

            itemHolder.fileName.setText(file.getName());
            
            // Use Glide for thumbnails to match other updated adapters
            int fallbackIcon = getIconForFileType(file.getName());
            
            Glide.with(context)
                .load(file)
                .apply(new RequestOptions()
                    .placeholder(fallbackIcon)
                    .error(fallbackIcon)
                    .diskCacheStrategy(DiskCacheStrategy.ALL)
                    .centerCrop())
                .into(itemHolder.fileIcon);

            itemHolder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (itemClickListener != null) itemClickListener.onItemClick(file);
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return displayList.size();
    }

    private int getIconForFileType(String fileName) {
        if (fileName == null) return android.R.drawable.ic_menu_info_details;
        String lower = fileName.toLowerCase();
        
        if (lower.endsWith(".doc") || lower.endsWith(".docx")) return android.R.drawable.ic_menu_save;
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return android.R.drawable.ic_menu_agenda;
        if (lower.endsWith(".ppt") || lower.endsWith(".pptx")) return android.R.drawable.ic_menu_slideshow;
        if (lower.endsWith(".pdf")) return android.R.drawable.ic_menu_view;
        if (lower.endsWith(".txt") || lower.endsWith(".log")) return android.R.drawable.ic_menu_edit;
        if (lower.endsWith(".zip") || lower.endsWith(".rar")) return android.R.drawable.ic_menu_set_as;
        if (lower.endsWith(".mp3") || lower.endsWith(".wav")) return android.R.drawable.ic_media_play;
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".mp4")) return android.R.drawable.ic_menu_gallery;
        
        return android.R.drawable.ic_menu_info_details;
    }

    // --- ViewHolders ---

    public static class FileViewHolder extends RecyclerView.ViewHolder {
        ImageView fileIcon;
        TextView fileName;

        public FileViewHolder(@NonNull View itemView) {
            super(itemView);
            fileIcon = itemView.findViewById(R.id.file_item_icon);
            fileName = itemView.findViewById(R.id.file_item_name);
        }
    }

    public static class HeaderViewHolder extends RecyclerView.ViewHolder {
        TextView folderNameText;
        TextView fileCountText;
        ImageView arrowIcon;

        public HeaderViewHolder(@NonNull View itemView) {
            super(itemView);
            folderNameText = itemView.findViewById(R.id.folder_header_name);
            fileCountText = itemView.findViewById(R.id.folder_header_count);
            arrowIcon = itemView.findViewById(R.id.folder_header_arrow);
        }
    }
}