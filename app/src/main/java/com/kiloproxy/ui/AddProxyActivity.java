package com.kiloproxy.ui;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.kiloproxy.databinding.ActivityAddProxyBinding;
import com.kiloproxy.model.ProxyProfile;
import com.kiloproxy.util.ProfileManager;

public class AddProxyActivity extends AppCompatActivity {

    public static final String EXTRA_PROFILE_ID = "profile_id";

    // Supported paste formats (detected automatically):
    //   A) Host:Port:Username:Password
    //   B) Username:Password@Host:Port
    //   C) Username:Password:Host:Port
    //   D) Host:Port@Username:Password
    private static final int FMT_NONE = -1;
    private static final int FMT_A    =  0; // Host:Port:User:Pass
    private static final int FMT_B    =  1; // User:Pass@Host:Port
    private static final int FMT_C    =  2; // User:Pass:Host:Port
    private static final int FMT_D    =  3; // Host:Port@User:Pass

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
            for (ProxyProfile p : profileManager.getProfiles()) {
                if (p.getId().equals(profileId)) {
                    editingProfile = p;
                    break;
                }
            }
        }

        setupUI();
        setupProxyStringPaste();

        if (editingProfile != null) {
            populateFields();
        }
    }

    // ── UI setup ──────────────────────────────────────────────────────────────

    private void setupUI() {
        if (editingProfile != null) {
            binding.toolbar.setTitle("Edit Proxy");
        } else {
            binding.toolbar.setTitle("Add Proxy");
        }
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());

        binding.switchAuth.setOnCheckedChangeListener((btn, checked) ->
            binding.layoutAuthFields.setVisibility(checked ? View.VISIBLE : View.GONE));

        binding.btnSave.setOnClickListener(v -> saveProfile());
    }

    // ── Proxy string paste ────────────────────────────────────────────────────

    private void setupProxyStringPaste() {
        binding.editProxyString.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) {}
            @Override
            public void afterTextChanged(Editable s) {
                String raw = s.toString().trim();
                if (raw.isEmpty()) {
                    binding.layoutProxyStringHint.setError(null);
                    return;
                }
                ParsedProxy parsed = detectAndParse(raw);
                if (parsed != null) {
                    binding.layoutProxyStringHint.setError(null);
                    fillFromParsed(parsed);
                } else {
                    binding.layoutProxyStringHint.setError("Unrecognised proxy format");
                }
            }
        });

        binding.btnFormatHelp.setOnClickListener(v -> showFormatHelp());
    }

    /**
     * Detect which format the pasted string is and parse it.
     * Returns null if no format matches.
     *
     * Formats:
     *   A  Host:Port:Username:Password         e.g.  1.2.3.4:8080:user:pass
     *   B  Username:Password@Host:Port          e.g.  user:pass@1.2.3.4:8080
     *   C  Username:Password:Host:Port          e.g.  user:pass:1.2.3.4:8080  (ambiguous; port must be numeric)
     *   D  Host:Port@Username:Password          e.g.  1.2.3.4:8080@user:pass
     */
    private ParsedProxy detectAndParse(String raw) {
        // ── Format B: User:Pass@Host:Port ─────────────────────────────────────
        // Split on first '@'
        int atB = raw.indexOf('@');
        if (atB > 0 && atB < raw.length() - 1) {
            String left  = raw.substring(0, atB);
            String right = raw.substring(atB + 1);
            int colonLeft = left.lastIndexOf(':');
            int colonRight = right.lastIndexOf(':');
            if (colonLeft > 0 && colonRight > 0) {
                String user = left.substring(0, colonLeft);
                String pass = left.substring(colonLeft + 1);
                String host = right.substring(0, colonRight);
                String portStr = right.substring(colonRight + 1);
                Integer port = parsePort(portStr);
                if (port != null && !host.isEmpty() && !user.isEmpty()) {
                    return new ParsedProxy(host, port, user, pass, FMT_B);
                }
            }
        }

        // ── Format D: Host:Port@User:Pass ─────────────────────────────────────
        int atD = raw.indexOf('@');
        if (atD > 0 && atD < raw.length() - 1) {
            String left  = raw.substring(0, atD);
            String right = raw.substring(atD + 1);
            int colonLeft = left.lastIndexOf(':');
            int colonRight = right.indexOf(':');
            if (colonLeft > 0 && colonRight > 0) {
                String host   = left.substring(0, colonLeft);
                String portStr = left.substring(colonLeft + 1);
                String user   = right.substring(0, colonRight);
                String pass   = right.substring(colonRight + 1);
                Integer port  = parsePort(portStr);
                if (port != null && !host.isEmpty() && !user.isEmpty()) {
                    return new ParsedProxy(host, port, user, pass, FMT_D);
                }
            }
        }

        // ── Format A: Host:Port:User:Pass  or  Format C: User:Pass:Host:Port ──
        // Both are 4 colon-separated tokens (port is always numeric, second token for A / fourth for C)
        String[] parts = raw.split(":", 4);
        if (parts.length == 4) {
            // Try Format A: tokens[1] = port (numeric), tokens[0] = host
            Integer portA = parsePort(parts[1]);
            if (portA != null) {
                // second token is a valid port → assume Host:Port:User:Pass
                return new ParsedProxy(parts[0], portA, parts[2], parts[3], FMT_A);
            }
            // Try Format C: tokens[3] = port (numeric), tokens[2] = host
            Integer portC = parsePort(parts[3]);
            if (portC != null) {
                // fourth token is a valid port → assume User:Pass:Host:Port
                return new ParsedProxy(parts[2], portC, parts[0], parts[1], FMT_C);
            }
        }

        return null; // no match
    }

    private void fillFromParsed(ParsedProxy p) {
        binding.editHost.setText(p.host);
        binding.editPort.setText(String.valueOf(p.port));

        if (p.username != null && !p.username.isEmpty()) {
            binding.switchAuth.setChecked(true);
            binding.layoutAuthFields.setVisibility(View.VISIBLE);
            binding.editUsername.setText(p.username);
            binding.editPassword.setText(p.password);
        }

        // Auto-fill a profile name if the field is empty
        if (binding.editName.getText().toString().trim().isEmpty()) {
            binding.editName.setText(p.host + ":" + p.port);
        }

        Toast.makeText(this, "Proxy string parsed successfully", Toast.LENGTH_SHORT).show();
    }

    private void showFormatHelp() {
        new AlertDialog.Builder(this)
            .setTitle("Supported Paste Formats")
            .setMessage(
                "Paste a proxy string in any of these formats:\n\n" +
                "① Host:Port:Username:Password\n" +
                "   1.2.3.4:8080:user:pass\n\n" +
                "② Username:Password@Host:Port\n" +
                "   user:pass@1.2.3.4:8080\n\n" +
                "③ Username:Password:Host:Port\n" +
                "   user:pass:1.2.3.4:8080\n\n" +
                "④ Host:Port@Username:Password\n" +
                "   1.2.3.4:8080@user:pass\n\n" +
                "The fields below will be filled automatically."
            )
            .setPositiveButton("OK", null)
            .show();
    }

    // ── Field population (edit mode) ──────────────────────────────────────────

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
            binding.layoutAuthFields.setVisibility(View.VISIBLE);
            binding.editUsername.setText(editingProfile.getUsername());
            binding.editPassword.setText(editingProfile.getPassword());
        }
    }

    // ── Save ──────────────────────────────────────────────────────────────────

    private void saveProfile() {
        String name    = binding.editName.getText().toString().trim();
        String host    = binding.editHost.getText().toString().trim();
        String portStr = binding.editPort.getText().toString().trim();

        if (name.isEmpty())    { binding.editName.setError("Name is required"); return; }
        if (host.isEmpty())    { binding.editHost.setError("Host is required"); return; }
        if (portStr.isEmpty()) { binding.editPort.setError("Port is required"); return; }

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
            if (username.isEmpty()) { binding.editUsername.setError("Username required"); return; }
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static Integer parsePort(String s) {
        if (s == null || s.isEmpty()) return null;
        try {
            int p = Integer.parseInt(s.trim());
            return (p >= 1 && p <= 65535) ? p : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static class ParsedProxy {
        final String host;
        final int    port;
        final String username;
        final String password;
        final int    format;

        ParsedProxy(String host, int port, String username, String password, int format) {
            this.host     = host;
            this.port     = port;
            this.username = username;
            this.password = password;
            this.format   = format;
        }
    }
}
