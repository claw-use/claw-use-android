package com.clawuse.android;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.accessibility.AccessibilityManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;

import com.clawuse.android.auth.TokenManager;
import com.google.zxing.BarcodeFormat;
import com.journeyapps.barcodescanner.BarcodeEncoder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Setup screen: bridge on/off switch, service status, connection info, QR code,
 * one-shot batch permission requests and shortcuts for special access.
 */
public class MainActivity extends AppCompatActivity {
    private static final int REQ_ALL_PERMISSIONS = 1001;
    /** Extra round to catch permissions the OEM kept pending after round 1. */
    private static final int MAX_PERMISSION_ROUNDS = 2;

    private TokenManager tokenManager;
    private ConfigStore configStore;

    private SwitchCompat swBridge;
    private TextView tvBridgeStatus, tvAccessibility, tvNotification, tvUrl, tvToken, tvPermissions;
    private ImageView ivQr;
    private Button btnAccessibility, btnNotification, btnCopyUrl, btnCopyToken, btnResetToken;
    private Button btnGrantAll, btnOpenSettings, btnOverlay, btnBattery, btnUnknownSources, btnMiuiPerms;

    private boolean suppressSwitchCallback = false;
    private boolean pendingAutoPrompt = false;
    private boolean permissionFlowActive = false;
    private int permissionRound = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        tokenManager = new TokenManager(this);
        configStore = ConfigStore.get(this);

        swBridge = findViewById(R.id.sw_bridge);
        tvBridgeStatus = findViewById(R.id.tv_bridge_status);
        tvAccessibility = findViewById(R.id.tv_accessibility);
        tvNotification = findViewById(R.id.tv_notification);
        tvUrl = findViewById(R.id.tv_url);
        tvToken = findViewById(R.id.tv_token);
        tvPermissions = findViewById(R.id.tv_permissions);
        ivQr = findViewById(R.id.iv_qr);
        btnAccessibility = findViewById(R.id.btn_accessibility);
        btnNotification = findViewById(R.id.btn_notification);
        btnCopyUrl = findViewById(R.id.btn_copy_url);
        btnCopyToken = findViewById(R.id.btn_copy_token);
        btnResetToken = findViewById(R.id.btn_reset_token);
        btnGrantAll = findViewById(R.id.btn_grant_all);
        btnOpenSettings = findViewById(R.id.btn_open_settings);
        btnOverlay = findViewById(R.id.btn_overlay);
        btnBattery = findViewById(R.id.btn_battery);
        btnUnknownSources = findViewById(R.id.btn_unknown_sources);
        btnMiuiPerms = findViewById(R.id.btn_miui_perms);

        // ── Bridge on/off switch ────────────────────────────────
        swBridge.setOnCheckedChangeListener((v, checked) -> {
            if (suppressSwitchCallback) return;
            if (checked) {
                BridgeService.start(this);
                Toast.makeText(this, "Bridge started", Toast.LENGTH_SHORT).show();
            } else {
                BridgeService.stop(this);
                Toast.makeText(this, "Bridge stopped — port 7333 closed", Toast.LENGTH_SHORT).show();
            }
            updateUI();
            // The service starts/stops asynchronously; refresh once it settled.
            new Handler(Looper.getMainLooper()).postDelayed(this::updateUI, 700);
        });

        btnAccessibility.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
            startActivity(intent);
        });

        btnNotification.setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            startActivity(intent);
        });

        btnCopyUrl.setOnClickListener(v -> copyToClipboard("Bridge URL", getServerUrl()));
        btnCopyToken.setOnClickListener(v -> copyToClipboard("Bridge Token", tokenManager.getToken()));

        btnResetToken.setOnClickListener(v -> {
            tokenManager.regenerateToken();
            updateUI();
            Toast.makeText(this, "Token regenerated", Toast.LENGTH_SHORT).show();
        });

        // ── Batch permissions ───────────────────────────────────
        btnGrantAll.setOnClickListener(v -> {
            if (missingRuntimePermissions().isEmpty()) {
                openAppDetails();
            } else {
                grantAllPermissions();
            }
        });

        btnOpenSettings.setOnClickListener(v -> openAppDetails());

        // ── Special access shortcuts ────────────────────────────
        btnOverlay.setOnClickListener(v -> openOverlaySettings());
        btnBattery.setOnClickListener(v -> requestBatteryOptimizationExemption());
        btnUnknownSources.setOnClickListener(v -> openUnknownSourcesSettings());
        btnMiuiPerms.setOnClickListener(v -> openMiuiPermissionEditor());

        // Start the bridge only if the user left it enabled.
        if (configStore.isBridgeEnabled()) {
            BridgeService.start(this);
        }

        // First launch: ask for everything in one flow (dialogs need a resumed
        // activity, so the actual request happens in onResume), then for the
        // battery exemption. Otherwise keep requesting the exemption until granted.
        boolean needsAutoPrompt = !configStore.werePermissionsAsked()
                && !missingRuntimePermissions().isEmpty();
        if (needsAutoPrompt) {
            configStore.setPermissionsAsked(true);
            pendingAutoPrompt = true;
        } else {
            requestBatteryOptimizationExemption();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        if (pendingAutoPrompt) {
            pendingAutoPrompt = false;
            grantAllPermissions();
        }
    }

    // ── Bridge / service state ──────────────────────────────────

    private void updateUI() {
        // Bridge switch reflects persisted preference; status reflects the service.
        boolean enabled = configStore.isBridgeEnabled();
        boolean running = BridgeService.getInstance() != null;

        suppressSwitchCallback = true;
        swBridge.setChecked(enabled);
        suppressSwitchCallback = false;

        if (enabled && running) {
            tvBridgeStatus.setText("● Bridge running — agents can connect");
            tvBridgeStatus.setTextColor(0xFF4CAF50);
        } else if (enabled) {
            tvBridgeStatus.setText("⏳ Bridge starting...");
            tvBridgeStatus.setTextColor(0xFFFFAB40);
        } else {
            tvBridgeStatus.setText("○ Bridge stopped — port 7333 is closed");
            tvBridgeStatus.setTextColor(0xFFF44336);
        }

        // Accessibility service status
        boolean a11yRunning = isAccessibilityServiceEnabled();
        tvAccessibility.setText(a11yRunning ? "● Accessibility Service: ON" : "○ Accessibility Service: OFF");
        tvAccessibility.setTextColor(a11yRunning ? 0xFF4CAF50 : 0xFFF44336);

        // Notification listener status
        boolean notifRunning = NotificationBridge.isRunning();
        tvNotification.setText(notifRunning ? "● Notification Listener: ON" : "○ Notification Listener: OFF");
        tvNotification.setTextColor(notifRunning ? 0xFF4CAF50 : 0xFFF44336);

        // URL and token
        tvUrl.setText(enabled && running ? getServerUrl() : "— (bridge stopped)");
        tvToken.setText(tokenManager.getToken());

        // QR code (encode URL + token for easy setup)
        try {
            String qrContent = getServerUrl() + "\n" + tokenManager.getToken();
            BarcodeEncoder encoder = new BarcodeEncoder();
            ivQr.setImageBitmap(encoder.encodeBitmap(qrContent, BarcodeFormat.QR_CODE, 400, 400));
        } catch (Exception e) {
            // QR generation failed, not critical
        }

        // Permissions summary + buttons
        tvPermissions.setText(buildPermissionSummary());
        boolean missing = !missingRuntimePermissions().isEmpty();
        btnGrantAll.setText(missing ? "Grant All Permissions" : "All Permissions Granted ✓");
        btnOpenSettings.setVisibility(missing ? Button.VISIBLE : Button.GONE);
    }

    private String getServerUrl() {
        BridgeService service = BridgeService.getInstance();
        if (service != null) {
            return service.getServerUrl();
        }
        return "http://...:7333";
    }

    private boolean isAccessibilityServiceEnabled() {
        AccessibilityManager am = (AccessibilityManager) getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am == null) return false;
        List<AccessibilityServiceInfo> services = am.getEnabledAccessibilityServiceList(
                AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
        ComponentName myService = new ComponentName(this, AccessibilityBridge.class);
        for (AccessibilityServiceInfo info : services) {
            ComponentName component = new ComponentName(info.getResolveInfo().serviceInfo.packageName,
                    info.getResolveInfo().serviceInfo.name);
            if (component.equals(myService)) return true;
        }
        return false;
    }

    // ── Batch runtime permissions ───────────────────────────────

    /** All runtime permissions the app declares, filtered by SDK level. */
    private List<String> runtimePermissionsForSdk() {
        List<String> perms = new ArrayList<>(Arrays.asList(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.SEND_SMS,
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.READ_CALENDAR
        ));
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(Manifest.permission.POST_NOTIFICATIONS);
            perms.add(Manifest.permission.READ_MEDIA_IMAGES);
            perms.add(Manifest.permission.READ_MEDIA_VIDEO);
            perms.add(Manifest.permission.READ_MEDIA_AUDIO);
        } else {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            if (Build.VERSION.SDK_INT <= 29) {
                perms.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }
        return perms;
    }

    private List<String> missingRuntimePermissions() {
        List<String> missing = new ArrayList<>();
        for (String perm : runtimePermissionsForSdk()) {
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                missing.add(perm);
            }
        }
        return missing;
    }

    /** Show the system dialogs for every missing permission, group by group. */
    private void grantAllPermissions() {
        permissionRound = 0;
        permissionFlowActive = true;
        requestMissingPermissions();
    }

    private void requestMissingPermissions() {
        List<String> missing = missingRuntimePermissions();
        if (missing.isEmpty() || permissionRound >= MAX_PERMISSION_ROUNDS) {
            finishPermissionFlow();
            return;
        }
        permissionRound++;
        ActivityCompat.requestPermissions(this, missing.toArray(new String[0]), REQ_ALL_PERMISSIONS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_ALL_PERMISSIONS) {
            updateUI();
            if (permissionFlowActive) {
                requestMissingPermissions();
            }
        }
    }

    private void finishPermissionFlow() {
        if (!permissionFlowActive) return;
        permissionFlowActive = false;
        updateUI();
        int missing = missingRuntimePermissions().size();
        if (missing == 0) {
            Toast.makeText(this, "All permissions granted 🎉", Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, missing + " permission(s) denied — tap \"Open App Settings\"", Toast.LENGTH_LONG).show();
        }
        // The battery dialog is requested after the permission chain so the two
        // system dialogs never fight for focus.
        requestBatteryOptimizationExemption();
    }

    private String buildPermissionSummary() {
        StringBuilder sb = new StringBuilder();
        List<String[]> perms = new ArrayList<>();
        perms.add(new String[]{"Camera", Manifest.permission.CAMERA});
        perms.add(new String[]{"Microphone", Manifest.permission.RECORD_AUDIO});
        perms.add(new String[]{"Location", Manifest.permission.ACCESS_FINE_LOCATION});
        perms.add(new String[]{"SMS", Manifest.permission.SEND_SMS});
        perms.add(new String[]{"Phone", Manifest.permission.CALL_PHONE});
        perms.add(new String[]{"Contacts", Manifest.permission.READ_CONTACTS});
        perms.add(new String[]{"Calendar", Manifest.permission.READ_CALENDAR});
        if (Build.VERSION.SDK_INT >= 33) {
            perms.add(new String[]{"Files", Manifest.permission.READ_MEDIA_IMAGES});
            perms.add(new String[]{"Notifications", Manifest.permission.POST_NOTIFICATIONS});
        } else {
            perms.add(new String[]{"Files", Manifest.permission.READ_EXTERNAL_STORAGE});
        }
        for (String[] p : perms) {
            boolean granted = checkSelfPermission(p[1]) == PackageManager.PERMISSION_GRANTED;
            sb.append(granted ? "✅ " : "❌ ").append(p[0]).append("  ");
        }
        return sb.toString().trim();
    }

    // ── Special access deep links ───────────────────────────────

    private void openAppDetails() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void openOverlaySettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openAppDetails();
        }
    }

    private void openUnknownSourcesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            openAppDetails();
        }
    }

    /** MIUI/HyperOS "Other permissions" editor; falls back to app details. */
    private void openMiuiPermissionEditor() {
        try {
            Intent intent = new Intent("miui.intent.action.APP_PERM_EDITOR");
            intent.setClassName("com.miui.securitycenter",
                    "com.miui.permcenter.permissions.PermissionsEditorActivity");
            intent.putExtra("extra_pkgname", getPackageName());
            startActivity(intent);
        } catch (Exception e) {
            openAppDetails();
        }
    }

    private void requestBatteryOptimizationExemption() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName())) {
            try {
                Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
                startActivity(intent);
            } catch (Exception e) {
                // Some ROMs don't support this intent
            }
        }
    }

    private void copyToClipboard(String label, String text) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, label + " copied", Toast.LENGTH_SHORT).show();
    }
}
