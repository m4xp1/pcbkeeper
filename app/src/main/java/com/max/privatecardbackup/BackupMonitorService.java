package com.max.privatecardbackup;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.database.ContentObserver;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class BackupMonitorService extends Service {
    private static final String TAG = "PrivateCardBackup";
    private static final String CHANNEL_ID = "backup_monitor";
    private static final int NOTIFICATION_ID = 101;
    private static final long POLL_SECONDS = 5L;
    private static final long ERROR_BACKOFF_MS = 30_000L;

    private final AtomicBoolean scanRunning = new AtomicBoolean(false);
    private ScheduledExecutorService executor;
    private BackupProcessor processor;
    private AppPrefs prefs;
    private ContentObserver contentObserver;
    private long nextScanAllowedAt;
    private volatile String currentStatus = "Starting";

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = new AppPrefs(this);
        processor = new BackupProcessor(this);
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, buildNotification(currentStatus));

        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "privatecard-backup-monitor");
            thread.setUncaughtExceptionHandler((ignored, throwable) -> {
                Log.e(TAG, "Worker thread crashed", throwable);
                ServiceControl.scheduleWatchdog(this, 10_000L);
                stopSelf();
            });
            return thread;
        });

        registerSourceObserver();
        executor.scheduleWithFixedDelay(this::scanSafely, 0L, POLL_SECONDS, TimeUnit.SECONDS);
        ServiceControl.scheduleRegularWatchdog(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (prefs.userStopped() || !prefs.isConfigured()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        triggerScan();
        return START_STICKY;
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!prefs.userStopped()) {
            ServiceControl.scheduleWatchdog(this, 5_000L);
        }
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (contentObserver != null) {
            try {
                getContentResolver().unregisterContentObserver(contentObserver);
            } catch (RuntimeException ignored) {
                // Observer may already be unregistered by the framework.
            }
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (prefs != null && !prefs.userStopped()) {
            ServiceControl.scheduleWatchdog(this, 10_000L);
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void registerSourceObserver() {
        Uri sourceTree = prefs.sourceTree();
        if (sourceTree == null) {
            return;
        }
        contentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                triggerScan();
            }
        };
        try {
            getContentResolver().registerContentObserver(sourceTree, true, contentObserver);
        } catch (SecurityException exception) {
            recordError("Source folder permission is missing");
        }
    }

    private void triggerScan() {
        ScheduledExecutorService currentExecutor = executor;
        if (currentExecutor != null && !currentExecutor.isShutdown()) {
            currentExecutor.schedule(this::scanSafely, 250L, TimeUnit.MILLISECONDS);
        }
    }

    private void scanSafely() {
        long now = System.currentTimeMillis();
        if (now < nextScanAllowedAt || !scanRunning.compareAndSet(false, true)) {
            return;
        }

        PowerManager.WakeLock wakeLock = null;
        try {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            if (powerManager != null) {
                wakeLock = powerManager.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK,
                        getPackageName() + ":backup-transaction"
                );
                wakeLock.acquire(30L * 60L * 1000L);
            }

            BackupProcessor.Result result = processor.scanAndProcess();
            currentStatus = result.message;
            if (result.success) {
                String timestamp = DateFormat.getDateTimeInstance().format(new Date());
                prefs.setLastSuccess(timestamp + " — " + result.message);
            }
            nextScanAllowedAt = 0L;
            updateNotification();
        } catch (BackupProcessor.SourceChangedException changed) {
            currentStatus = changed.getMessage();
            Log.w(TAG, currentStatus);
            nextScanAllowedAt = System.currentTimeMillis() + 5_000L;
            updateNotification();
        } catch (Throwable throwable) {
            String message = throwable.getMessage();
            if (TextUtils.isEmpty(message)) {
                message = throwable.getClass().getSimpleName();
            }
            Log.e(TAG, "Backup scan failed", throwable);
            recordError(message);
            nextScanAllowedAt = System.currentTimeMillis() + ERROR_BACKOFF_MS;
        } finally {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            scanRunning.set(false);
            ServiceControl.scheduleRegularWatchdog(this);
        }
    }

    private void recordError(String message) {
        currentStatus = "Error: " + message;
        prefs.setLastError(currentStatus);
        updateNotification();
    }

    private void createNotificationChannel() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(com.max.privatecardbackup.R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(getString(com.max.privatecardbackup.R.string.notification_channel_description));
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String status) {
        Intent setupIntent = new Intent(this, SetupActivity.class);
        PendingIntent setupPendingIntent = PendingIntent.getActivity(
                this,
                0,
                setupIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        Intent stopIntent = new Intent(this, StopReceiver.class);
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this,
                1,
                stopIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        String text = status == null ? "Monitoring is active" : status;
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.max.privatecardbackup.R.drawable.ic_backup)
                .setContentTitle("PrivateCard backup monitor")
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(setupPendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, com.max.privatecardbackup.R.drawable.ic_backup),
                        "Stop",
                        stopPendingIntent
                ).build())
                .build();
    }

    private void updateNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.notify(NOTIFICATION_ID, buildNotification(currentStatus));
        }
    }
}
