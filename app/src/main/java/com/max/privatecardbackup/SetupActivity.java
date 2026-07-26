package com.max.privatecardbackup;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

public final class SetupActivity extends Activity {
    private static final int REQUEST_STORAGE = 10;
    private static final int REQUEST_SOURCE_TREE = 20;
    private static final int REQUEST_SD_TREE = 30;
    private static final int REQUEST_USB_TREE = 40;
    private static final String EXPECTED_SOURCE_TREE_ID =
            "primary:Android/data/ru.devrobots.privateCard/files";

    private AppPrefs prefs;
    private SafStore saf;
    private TextView statusView;
    private Button startButton;
    private Button stopButton;
    private Button batteryButton;
    private Button usbBackupButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new AppPrefs(this);
        saf = new SafStore(this);
        setContentView(buildContent());
        requestStoragePermissionIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private View buildContent() {
        int padding = dp(20);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(this);
        title.setText("PrivateCard Backup Keeper");
        title.setTextSize(24f);
        title.setPadding(0, 0, 0, dp(12));
        content.addView(title);

        TextView explanation = new TextView(this);
        explanation.setText(
                "Android 11 requires one-time folder grants. First select:\n" +
                        "Android/data/ru.devrobots.privateCard/files\n\n" +
                        "Then select Documents on the removable SD card. " +
                        "The service never deletes the source until both copies pass SHA-256 verification."
        );
        explanation.setTextSize(16f);
        explanation.setPadding(0, 0, 0, dp(16));
        content.addView(explanation);

        Button sourceButton = button("1. Select PrivateCard files folder");
        sourceButton.setOnClickListener(view -> selectSourceTree());
        content.addView(sourceButton);

        Button sdButton = button("2. Select SD card Documents folder");
        sdButton.setOnClickListener(view -> selectSdTree());
        content.addView(sdButton);

        batteryButton = button("3. Exclude from battery optimization");
        batteryButton.setOnClickListener(view -> requestBatteryExemption());
        content.addView(batteryButton);

        startButton = button("Start monitoring");
        startButton.setOnClickListener(view -> startMonitoring());
        content.addView(startButton);

        stopButton = button("Stop monitoring");
        stopButton.setOnClickListener(view -> stopMonitoring());
        content.addView(stopButton);

        TextView usbTitle = new TextView(this);
        usbTitle.setText("Offline backup");
        usbTitle.setTextSize(19f);
        usbTitle.setPadding(0, dp(20), 0, dp(6));
        content.addView(usbTitle);

        TextView usbExplanation = new TextView(this);
        usbExplanation.setText(
                "Connect a USB flash drive, press the button, and select its Documents folder. " +
                        "Missing .pc files are copied and every existing or new copy is verified with SHA-256. " +
                        "A different file with the same name is reported and never overwritten."
        );
        usbExplanation.setTextSize(15f);
        usbExplanation.setPadding(0, 0, 0, dp(8));
        content.addView(usbExplanation);

        usbBackupButton = button("Backup to flash drive");
        usbBackupButton.setOnClickListener(view -> selectUsbTree());
        content.addView(usbBackupButton);

        statusView = new TextView(this);
        statusView.setTextSize(15f);
        statusView.setPadding(0, dp(18), 0, 0);
        statusView.setTextIsSelectable(true);
        content.addView(statusView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        return scroll;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(4), 0, dp(4));
        button.setLayoutParams(params);
        return button;
    }

    private void requestStoragePermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    REQUEST_STORAGE
            );
        }
    }

    private Intent folderPickerIntent() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        return intent;
    }

    private void selectSourceTree() {
        Intent intent = folderPickerIntent();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Uri initial = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Android/data/ru.devrobots.privateCard/files"
            );
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial);
        }
        startActivityForResult(intent, REQUEST_SOURCE_TREE);
    }

    private void selectSdTree() {
        startActivityForResult(folderPickerIntent(), REQUEST_SD_TREE);
    }

    private void selectUsbTree() {
        if (!hasStoragePermission()) {
            requestStoragePermissionIfNeeded();
            showError("Storage permission is required to read internal Documents.");
            return;
        }
        startActivityForResult(folderPickerIntent(), REQUEST_USB_TREE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        Uri uri = data.getData();
        int flags = data.getFlags() & (
                Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        );
        try {
            int requiredFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            if ((flags & requiredFlags) != requiredFlags) {
                throw new SecurityException("The selected folder did not grant read and write access.");
            }
            getContentResolver().takePersistableUriPermission(uri, requiredFlags);
            if (requestCode == REQUEST_SOURCE_TREE) {
                validateAndSaveSource(uri);
            } else if (requestCode == REQUEST_SD_TREE) {
                validateAndSaveSd(uri);
            } else if (requestCode == REQUEST_USB_TREE) {
                validateRemovableDocuments(uri, "USB flash drive");
                startUsbBackup(uri);
            }
        } catch (SecurityException | IOException exception) {
            showError(exception.getMessage());
        }
        refreshStatus();
    }

    private void validateAndSaveSource(Uri uri) throws IOException {
        String rootId = saf.rootDocumentId(uri);
        String normalizedRootId = rootId.replaceAll("/+$", "");
        if (!EXPECTED_SOURCE_TREE_ID.equals(normalizedRootId)) {
            throw new IOException(
                    "Wrong folder. Select exactly Android/data/ru.devrobots.privateCard/files."
            );
        }
        SafStore.DocumentInfo backups = saf.findChild(uri, rootId, BackupProcessor.BACKUPS_DIRECTORY);
        if (backups == null || !backups.isDirectory()) {
            throw new IOException(
                    "Wrong folder. Select Android/data/ru.devrobots.privateCard/files, which contains Backups."
            );
        }
        prefs.setSourceTree(uri);
        Toast.makeText(this, "PrivateCard folder access saved", Toast.LENGTH_SHORT).show();
    }

    private void validateAndSaveSd(Uri uri) throws IOException {
        validateRemovableDocuments(uri, "removable SD card");
        prefs.setSdTree(uri);
        Toast.makeText(this, "SD Documents access saved", Toast.LENGTH_SHORT).show();
    }

    private void validateRemovableDocuments(Uri uri, String destinationName) throws IOException {
        String treeId = saf.rootDocumentId(uri);
        int separator = treeId.indexOf(':');
        String volume = separator >= 0 ? treeId.substring(0, separator) : treeId;
        String path = separator >= 0 ? treeId.substring(separator + 1) : "";
        if ("primary".equalsIgnoreCase(volume)) {
            throw new IOException("Select Documents on the " + destinationName + ", not internal storage.");
        }
        if (!("Documents".equalsIgnoreCase(path) || path.toLowerCase(Locale.ROOT).endsWith("/documents"))) {
            throw new IOException("Select the Documents directory on the " + destinationName + ".");
        }
    }

    private void startUsbBackup(Uri uri) {
        Intent serviceIntent = new Intent(this, UsbBackupService.class)
                .putExtra(UsbBackupService.EXTRA_TREE_URI, uri.toString());
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        Toast.makeText(this, "USB backup started", Toast.LENGTH_SHORT).show();
    }

    private void requestBatteryExemption() {
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "Battery optimization is already disabled", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName())
            );
            startActivity(intent);
        } catch (RuntimeException exception) {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        }
    }

    private void startMonitoring() {
        if (!hasStoragePermission()) {
            requestStoragePermissionIfNeeded();
            showError("Storage permission is required for internal Documents.");
            return;
        }
        if (!prefs.isConfigured()) {
            showError("Select both folders first.");
            return;
        }
        prefs.setUserStopped(false);
        ServiceControl.start(this);
        ServiceControl.scheduleRegularWatchdog(this);
        Toast.makeText(this, "Monitoring started", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private void stopMonitoring() {
        prefs.setUserStopped(true);
        ServiceControl.cancelWatchdog(this);
        stopService(new Intent(this, BackupMonitorService.class));
        Toast.makeText(this, "Monitoring stopped", Toast.LENGTH_SHORT).show();
        refreshStatus();
    }

    private boolean hasStoragePermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshStatus() {
        if (statusView == null) {
            return;
        }
        boolean batteryExempt = false;
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            batteryExempt = powerManager.isIgnoringBatteryOptimizations(getPackageName());
        }

        String source = prefs.sourceTree() == null ? "not selected" : prefs.sourceTree().toString();
        String sd = prefs.sdTree() == null ? "not selected" : prefs.sdTree().toString();
        String error = prefs.lastError();
        String status = prefs.userStopped() ? "stopped" : "enabled";

        statusView.setText(
                "Service: " + status +
                        "\nStorage permission: " + (hasStoragePermission() ? "granted" : "missing") +
                        "\nBattery optimization: " + (batteryExempt ? "disabled" : "enabled") +
                        "\nSource: " + source +
                        "\nSD destination: " + sd +
                        "\nLast automatic backup: " + prefs.lastSuccess() +
                        "\nLast USB backup: " + prefs.lastUsbBackup() +
                        (error.isEmpty() ? "" : "\nLast error: " + error)
        );

        startButton.setEnabled(prefs.isConfigured() && hasStoragePermission());
        stopButton.setEnabled(!prefs.userStopped());
        batteryButton.setEnabled(!batteryExempt);
        usbBackupButton.setEnabled(hasStoragePermission());
    }

    private void showError(String message) {
        Toast.makeText(this, message == null ? "Unknown error" : message, Toast.LENGTH_LONG).show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
