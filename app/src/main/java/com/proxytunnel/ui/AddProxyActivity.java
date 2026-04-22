package com.proxytunnel.ui;

import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.proxytunnel.databinding.ActivityAddProxyBinding;
import com.proxytunnel.model.ProxyProfile;
import com.proxytunnel.util.ProfileManager;

public class AddProxyActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    private ActivityAddProxyBinding binding;
    private ProfileManager profileManager;
    private ProxyProfile editingProfile;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityAddProxyBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        profileManager = ProfileManager.getInstance(this);

        String profileId = getIntent().getStringExtra(EXTRA_PROFILE_ID);
        if (profileId != null) {
            // Edit mode
            for (ProxyProfile p : profileManager.getProfiles()) {
                if (p.getId().equals(profileId)) {
                    editingProfile = p;
                    break;
                }
            }
        }

        setupUI();

        if (editingProfile != null) {
            populateFields();
        }
    }

    private void setupUI() {
        getSupportActionBar();
        if (editingProfile != null) {
            binding.toolbar.setTitle("Edit Proxy");
        } else {
            binding.toolbar.setTitle("Add Proxy");
        }
        setSupportActionBar(binding.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.switchAuth.setOnCheckedChangeListener((btn, checked) -> {
            binding.layoutAuthFields.setVisibility(checked ? View.VISIBLE : View.GONE);
        });

        binding.btnSave.setOnClickListener(v -> saveProfile());
    }

    private void populateFields() {
        binding.editName.setText(editingProfile.getName());
        binding.editHost.setText(editingProfile.getHost());
        binding.editPort.setText(String.valueOf(editingProfile.getPort()));

        if (ProxyProfile.TYPE_SOCKS5.equals(editingProfile.getType())) {
            binding.radioSocks5.setChecked(true);
        } else {
            binding.radioHttp.setChecked(true);
        }

        if (editingProfile.isRequiresAuth()) {
            binding.switchAuth.setChecked(true);
            binding.editUsername.setText(editingProfile.getUsername());
            binding.editPassword.setText(editingProfile.getPassword());
        }
    }

    private void saveProfile() {
        String name = binding.editName.getText().toString().trim();
        String host = binding.editHost.getText().toString().trim();
        String portStr = binding.editPort.getText().toString().trim();

        if (name.isEmpty()) {
            binding.editName.setError("Name is required");
            return;
        }
        if (host.isEmpty()) {
            binding.editHost.setError("Host is required");
            return;
        }
        if (portStr.isEmpty()) {
            binding.editPort.setError("Port is required");
            return;
        }

        int port;
        try {
            port = Integer.parseInt(portStr);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            binding.editPort.setError("Invalid port (1–65535)");
            return;
        }

        String type = binding.radioSocks5.isChecked()
            ? ProxyProfile.TYPE_SOCKS5
            : ProxyProfile.TYPE_HTTP;

        ProxyProfile profile = editingProfile != null ? editingProfile : new ProxyProfile();
        profile.setName(name);
        profile.setHost(host);
        profile.setPort(port);
        profile.setType(type);

        if (binding.switchAuth.isChecked()) {
            String username = binding.editUsername.getText().toString().trim();
            String password = binding.editPassword.getText().toString();
            if (username.isEmpty()) {
                binding.editUsername.setError("Username required");
                return;
            }
            profile.setRequiresAuth(true);
            profile.setUsername(username);
            profile.setPassword(password);
        } else {
            profile.setRequiresAuth(false);
            profile.setUsername(null);
            profile.setPassword(null);
        }

        if (editingProfile != null) {
            profileManager.updateProfile(profile);
            Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show();
        } else {
            profileManager.addProfile(profile);
            Toast.makeText(this, "Profile added", Toast.LENGTH_SHORT).show();
        }
        finish();
    }
}
