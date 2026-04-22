package com.kiloproxy.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.kiloproxy.R;
import com.kiloproxy.model.ProxyProfile;

import java.util.ArrayList;
import java.util.List;

public class ProfileAdapter extends RecyclerView.Adapter<ProfileAdapter.ViewHolder> {

    public interface ProfileListener {
        void onProfileSelected(ProxyProfile profile);
        void onProfileEdit(ProxyProfile profile);
        void onProfileDelete(ProxyProfile profile);
    }

    private List<ProxyProfile> profiles = new ArrayList<>();
    private String activeId;
    private final ProfileListener listener;

    public ProfileAdapter(ProfileListener listener) {
        this.listener = listener;
    }

    public void setProfiles(List<ProxyProfile> profiles) {
        this.profiles = profiles;
        notifyDataSetChanged();
    }

    public void setActiveId(String activeId) {
        this.activeId = activeId;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_profile, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        ProxyProfile profile = profiles.get(position);
        boolean isActive = profile.getId().equals(activeId);

        holder.radioButton.setChecked(isActive);
        holder.txtName.setText(profile.getName());
        holder.txtAddress.setText(profile.getDisplayAddress());
        holder.txtType.setText(profile.getType());

        holder.itemView.setOnClickListener(v -> listener.onProfileSelected(profile));
        holder.radioButton.setOnClickListener(v -> listener.onProfileSelected(profile));
        holder.btnEdit.setOnClickListener(v -> listener.onProfileEdit(profile));
        holder.btnDelete.setOnClickListener(v -> listener.onProfileDelete(profile));
    }

    @Override
    public int getItemCount() {
        return profiles.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        RadioButton radioButton;
        TextView txtName, txtAddress, txtType;
        ImageButton btnEdit, btnDelete;

        ViewHolder(View itemView) {
            super(itemView);
            radioButton = itemView.findViewById(R.id.radioButton);
            txtName = itemView.findViewById(R.id.txtProfileName);
            txtAddress = itemView.findViewById(R.id.txtProfileAddress);
            txtType = itemView.findViewById(R.id.txtProfileType);
            btnEdit = itemView.findViewById(R.id.btnEdit);
            btnDelete = itemView.findViewById(R.id.btnDelete);
        }
    }
}
