package com.proxytunnel.util;

import android.content.Context;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.proxytunnel.model.ProxyProfile;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class ProfileManager {
    private static final String PREFS_NAME = "proxy_tunnel_prefs";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE_PROFILE_ID = "active_profile_id";

    private static ProfileManager instance;
    private final SharedPreferences prefs;
    private final Gson gson;

    private ProfileManager(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        gson = new Gson();
    }

    public static ProfileManager getInstance(Context context) {
        if (instance == null) {
            instance = new ProfileManager(context);
        }
        return instance;
    }

    public List<ProxyProfile> getProfiles() {
        String json = prefs.getString(KEY_PROFILES, null);
        if (json == null) return new ArrayList<>();
        Type type = new TypeToken<List<ProxyProfile>>(){}.getType();
        List<ProxyProfile> profiles = gson.fromJson(json, type);
        return profiles != null ? profiles : new ArrayList<>();
    }

    public void saveProfiles(List<ProxyProfile> profiles) {
        prefs.edit().putString(KEY_PROFILES, gson.toJson(profiles)).apply();
    }

    public void addProfile(ProxyProfile profile) {
        List<ProxyProfile> profiles = getProfiles();
        profiles.add(profile);
        saveProfiles(profiles);
    }

    public void updateProfile(ProxyProfile updated) {
        List<ProxyProfile> profiles = getProfiles();
        for (int i = 0; i < profiles.size(); i++) {
            if (profiles.get(i).getId().equals(updated.getId())) {
                profiles.set(i, updated);
                break;
            }
        }
        saveProfiles(profiles);
    }

    public void deleteProfile(String id) {
        List<ProxyProfile> profiles = getProfiles();
        profiles.removeIf(p -> p.getId().equals(id));
        saveProfiles(profiles);
        // Clear active if deleted
        if (id.equals(getActiveProfileId())) {
            setActiveProfileId(null);
        }
    }

    public String getActiveProfileId() {
        return prefs.getString(KEY_ACTIVE_PROFILE_ID, null);
    }

    public void setActiveProfileId(String id) {
        if (id == null) {
            prefs.edit().remove(KEY_ACTIVE_PROFILE_ID).apply();
        } else {
            prefs.edit().putString(KEY_ACTIVE_PROFILE_ID, id).apply();
        }
    }

    public ProxyProfile getActiveProfile() {
        String activeId = getActiveProfileId();
        if (activeId == null) return null;
        for (ProxyProfile p : getProfiles()) {
            if (p.getId().equals(activeId)) return p;
        }
        return null;
    }
}
