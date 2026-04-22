package com.kiloproxy.ui;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.kiloproxy.R;
import com.kiloproxy.databinding.ActivityMainBinding;
import com.kiloproxy.model.ProxyProfile;
import com.kiloproxy.service.ProxyVpnService;
import com.kiloproxy.util.ProfileManager;

import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements ProfileAdapter.ProfileListener {

    private ActivityMainBinding binding;
    private ProfileManager profileManager;
    private ProfileAdapter adapter;
    private boolean vpnRunning = false;
    private boolean receiverRegistered = false;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable statsUpdater;

    private final ActivityResultLauncher<Intent> vpnPermissionLauncher =
        registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            if (result.getResultCode() == RESULT_OK) {
                startVpn();
            } else {
                Toast.makeText(this, "VPN permission denied", Toast.LENGTH_SHORT).show();
            }
        });

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean running = intent.getBooleanExtra(ProxyVpnService.EXTRA_RUNNING, false);
            String error = intent.getStringExtra(ProxyVpnService.EXTRA_ERROR);
            updateVpnState(running, error);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());

        profileManager = ProfileManager.getInstance(this);

        setupRecyclerView();
        setupClickListeners();
        syncVpnState();
    }

    private void setupRecyclerView() {
        adapter = new ProfileAdapter(this);
        binding.recyclerProfiles.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerProfiles.setAdapter(adapter);
        refreshProfiles();
    }

    private void setupClickListeners() {
        binding.fabAddProfile.setOnClickListener(v ->
            startActivity(new Intent(this, AddProxyActivity.class)));

        binding.btnConnect.setOnClickListener(v -> {
            if (vpnRunning) {
                stopVpn();
            } else {
                checkAndStartVpn();
            }
        });
    }

    private void refreshProfiles() {
        List<ProxyProfile> profiles = profileManager.getProfiles();
        adapter.setProfiles(profiles);
        String activeId = profileManager.getActiveProfileId();
        adapter.setActiveId(activeId);

        binding.layoutEmptyState.setVisibility(profiles.isEmpty() ? View.VISIBLE : View.GONE);
        binding.recyclerProfiles.setVisibility(profiles.isEmpty() ? View.GONE : View.VISIBLE);
        binding.btnConnect.setEnabled(!profiles.isEmpty() && activeId != null);
    }

    private void checkAndStartVpn() {
        if (profileManager.getActiveProfile() == null) {
            Toast.makeText(this, "Select a proxy profile first", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent permIntent = VpnService.prepare(this);
        if (permIntent != null) {
            vpnPermissionLauncher.launch(permIntent);
        } else {
            startVpn();
        }
    }

    private void startVpn() {
        Intent intent = new Intent(this, ProxyVpnService.class);
        intent.setAction(ProxyVpnService.ACTION_START);
        startService(intent);
        binding.btnConnect.setEnabled(false);
        binding.statusText.setText("Connecting…");
    }

    private void stopVpn() {
        Intent intent = new Intent(this, ProxyVpnService.class);
        intent.setAction(ProxyVpnService.ACTION_STOP);
        startService(intent);
    }

    private void syncVpnState() {
        updateVpnState(ProxyVpnService.isRunning(), null);
    }

    private void updateVpnState(boolean running, String error) {
        vpnRunning = running;
        if (running) {
            binding.btnConnect.setText("Disconnect");
            binding.btnConnect.setEnabled(true);
            binding.statusIndicator.setImageResource(R.drawable.ic_status_connected);
            ProxyProfile active = profileManager.getActiveProfile();
            String name = active != null ? active.getName() : "Unknown";
            binding.statusText.setText("Connected · " + name);
            binding.cardStats.setVisibility(View.VISIBLE);
            startStatsUpdater();
        } else {
            binding.btnConnect.setText("Connect");
            binding.statusIndicator.setImageResource(R.drawable.ic_status_disconnected);
            if (error != null) {
                binding.statusText.setText("Error: " + error);
                Toast.makeText(this, "Error: " + error, Toast.LENGTH_LONG).show();
            } else {
                binding.statusText.setText("Not connected");
            }
            binding.cardStats.setVisibility(View.GONE);
            stopStatsUpdater();
            refreshProfiles();
        }
    }

    private void startStatsUpdater() {
        statsUpdater = new Runnable() {
            @Override
            public void run() {
                binding.txtBytesSent.setText("↑ " + formatBytes(ProxyVpnService.getBytesSent()));
                binding.txtBytesReceived.setText("↓ " + formatBytes(ProxyVpnService.getBytesReceived()));
                handler.postDelayed(this, 1000);
            }
        };
        handler.post(statsUpdater);
    }

    private void stopStatsUpdater() {
        if (statsUpdater != null) handler.removeCallbacks(statsUpdater);
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }

    @Override
    public void onProfileSelected(ProxyProfile profile) {
        if (vpnRunning) {
            Toast.makeText(this, "Disconnect first to change profile", Toast.LENGTH_SHORT).show();
            return;
        }
        profileManager.setActiveProfileId(profile.getId());
        refreshProfiles();
    }

    @Override
    public void onProfileEdit(ProxyProfile profile) {
        Intent intent = new Intent(this, AddProxyActivity.class);
        intent.putExtra(AddProxyActivity.EXTRA_PROFILE_ID, profile.getId());
        startActivity(intent);
    }

    @Override
    public void onProfileDelete(ProxyProfile profile) {
        new AlertDialog.Builder(this)
            .setTitle("Delete Profile")
            .setMessage("Delete \"" + profile.getName() + "\"?")
            .setPositiveButton("Delete", (d, w) -> {
                profileManager.deleteProfile(profile.getId());
                refreshProfiles();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!receiverRegistered) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(statusReceiver,
                    new IntentFilter(ProxyVpnService.ACTION_STATUS),
                    Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(statusReceiver,
                    new IntentFilter(ProxyVpnService.ACTION_STATUS));
            }
            receiverRegistered = true;
        }
        refreshProfiles();
        syncVpnState();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (receiverRegistered) {
            try { unregisterReceiver(statusReceiver); } catch (IllegalArgumentException ignored) {}
            receiverRegistered = false;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopStatsUpdater();
    }
}
