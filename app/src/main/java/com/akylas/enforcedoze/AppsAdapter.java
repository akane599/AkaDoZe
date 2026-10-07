package com.akylas.enforcedoze;

import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.akylas.enforcedoze.ui.amber.SquircleShapes;
import com.google.android.material.imageview.ShapeableImageView;

import java.util.ArrayList;

public class AppsAdapter extends RecyclerView.Adapter<AppsAdapter.ViewHolder> {

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ShapeableImageView appIcon;
        TextView appName;
        TextView appPackageName;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            appIcon = itemView.findViewById(R.id.appIcon);
            appName = itemView.findViewById(R.id.appName);
            appPackageName = itemView.findViewById(R.id.appPackageName);
        }
    }
    private ArrayList<AppsItem> listData;

    public AppsAdapter(Context aContext, ArrayList<AppsItem> listData) {
        this.listData = listData;
    }

    @Override
    public int getItemCount() {
        return listData.size();
    }

    @Nullable
    public AppsItem getItem(int position) {
        return listData.get(position);
    }

    @Override
    public long getItemId(int position) {
        return position;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.list_row_layout, parent, false);
        ViewHolder holder = new ViewHolder(view);
        holder.appIcon.setShapeAppearanceModel(
                SquircleShapes.model(view.getResources().getDimension(R.dimen.corner_inner)));
        return holder;
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        AppsItem item = getItem(position);
        holder.appName.setText(item.getAppName());
        holder.appPackageName.setText(item.getAppPackageName());
        holder.appIcon.setImageDrawable(appIcon(holder.itemView.getContext().getPackageManager(),
                item.getAppPackageName()));
    }

    /** The package's launcher icon, or the system default icon when it isn't installed (a "System package" row). */
    @NonNull
    static Drawable appIcon(@NonNull PackageManager pm, @NonNull String packageName) {
        try {
            return pm.getApplicationIcon(packageName);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getDefaultActivityIcon();
        }
    }
}
