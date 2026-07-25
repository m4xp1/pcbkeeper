package com.max.privatecardbackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UsbBackupService extends Service {
    static final String EXTRA_TREE_URI = "usb_tree_uri";

    private static final String TAG = "PrivateCardUsbBackup";
    private static final String CHANNEL_ID = "usb_backup";
    private static final int NOTIFICATION_ID = 102;
    private static final long WAKE_LOCK_TIMEOUT_MS = 60L * 60L * 1000L;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private ExecutorService executor;
    private AppPrefs prefs;
    private SafStore saf;

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AppPrefs(this);
        saf = new SafStore(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification("Preparing USB backup", true));
        executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "privatecard-usb-backup");
            thread.setUncaughtExceptionHandler((ignored, throwable) -> {
                Log.e(TAG, "USB backup worker crashed", throwable);
                finishWithError(messageOf(throwable));
            });
            return thread;
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String treeValue = intent == null ? null : intent.getStringExtra(EXTRA_TREE_URI);
        if (TextUtils.isEmpty(treeValue)) {
            finishWithError("USB destination was not provided");
            return START_NOT_STICKY;
        }
        if (!running.compareAndSet(false, true)) {
            return START_NOT_STICKY;
        }
        Uri treeUri = Uri.parse(treeValue);
        executor.execute(() -> runBackup(treeUri, startId));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void runBackup(Uri treeUri, int startId) {
        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":usb-backup"
                );
                wakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
            }

            File documents = internalDocumentsDirectory();
            UsbBackupSynchronizer synchronizer = new UsbBackupSynchronizer();
            UsbBackupSynchronizer.Destination destination = new UsbDestination(treeUri);
            UsbBackupSynchronizer.Summary summary = synchronizer.synchronize(
                    documents,
                    destination,
                    (processed, total, fileName) -> updateNotification(
                            total == 0
                                    ? "No .pc files found"
                                    : "Checking " + Math.min(processed + 1, total) + "/" + total
                                    + ": " + fileName,
                            true
                    )
            );

            String timestamp = DateFormat.getDateTimeInstance().format(new Date());
            String message = summary.message();
            prefs.setLastUsbBackup(timestamp + " — " + message);
            finishNotification(message);
        } catch (Throwable throwable) {
            Log.e(TAG, "USB backup failed", throwable);
            finishWithError(messageOf(throwable));
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            running.set(false);
            stopSelf(startId);
        }
    }

    private final class UsbDestination implements UsbBackupSynchronizer.Destination {
        private final Uri treeUri;

        UsbDestination(Uri treeUri) {
            this.treeUri = treeUri;
        }

        @Override
        public Hashing.Result hashExisting(String fileName) throws IOException {
            SafStore.DocumentInfo existing = saf.findChildIgnoreCase(
                    treeUri,
                    saf.rootDocumentId(treeUri),
                    fileName
            );
            if (existing == null) {
                return null;
            }
            if (existing.isDirectory()) {
                throw new IOException("USB destination contains a directory named " + fileName);
            }
            return saf.hashDocument(saf.documentUri(treeUri, existing.documentId));
        }

        @Override
        public void copyVerified(File source, String fileName, long expectedSize, String expectedSha256)
                throws IOException {
            saf.copyFileToTree(source, treeUri, fileName, expectedSize, expectedSha256);
        }
    }

    private void finishWithError(String message) {
        String safeMessage = TextUtils.isEmpty(message) ? "Unknown USB backup error" : message;
        String timestamp = DateFormat.getDateTimeInstance().format(new Date());
        prefs.setLastUsbBackup(timestamp + " — Error: " + safeMessage);
        finishNotification("USB backup failed: " + safeMessage);
        stopSelf();
    }

    private void finishNotification(String message) {
        stopForeground(false);
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message, false));
        }
    }

    private void updateNotification(String message, boolean ongoing) {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(message, ongoing));
        }
    }

    private Notification buildNotification(String message, boolean ongoing) {
        Intent setupIntent = new Intent(this, SetupActivity.class);
        PendingIntent setupPendingIntent = PendingIntent.getActivity(
                this,
                2,
                setupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        Notification.Builder builder = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.max.privatecardbackup.R.drawable.ic_backup)
                .setContentTitle("PrivateCard USB backup")
                .setContentText(message)
                .setStyle(new Notification.BigTextStyle().bigText(message))
                .setContentIntent(setupPendingIntent)
                .setOnlyAlertOnce(ongoing)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setCategory(Notification.CATEGORY_SERVICE);
        if (ongoing) {
            builder.setProgress(0, 0, true);
        }
        return builder.build();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(com.max.privatecardbackup.R.string.usb_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(com.max.privatecardbackup.R.string.usb_notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private static String messageOf(Throwable throwable) {
        String message = throwable == null ? null : throwable.getMessage();
        if (!TextUtils.isEmpty(message)) {
            return message;
        }
        return throwable == null ? "Unknown error" : throwable.getClass().getSimpleName();
    }

    @SuppressWarnings("deprecation")
    private static File internalDocumentsDirectory() {
        return Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS);
    }
}
