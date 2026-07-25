package com.max.privatecardbackup;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.SystemClock;

final class ServiceControl {
    private static final int WATCHDOG_REQUEST = 701;
    private static final long WATCHDOG_INTERVAL_MS = 15L * 60L * 1000L;

    private ServiceControl() {
    }

    static void start(Context context) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.isConfigured() || prefs.userStopped()) {
            return;
        }
        Intent intent = new Intent(context, BackupMonitorService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    static void scheduleWatchdog(Context context, long delayMs) {
        AppPrefs prefs = new AppPrefs(context);
        if (!prefs.isConfigured() || prefs.userStopped()) {
            cancelWatchdog(context);
            return;
        }
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        PendingIntent pendingIntent = watchdogPendingIntent(context);
        long triggerAt = SystemClock.elapsedRealtime() + Math.max(delayMs, 5_000L);
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pendingIntent);
    }

    static void scheduleRegularWatchdog(Context context) {
        scheduleWatchdog(context, WATCHDOG_INTERVAL_MS);
    }

    static void cancelWatchdog(Context context) {
        AlarmManager alarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarmManager != null) {
            alarmManager.cancel(watchdogPendingIntent(context));
        }
    }

    private static PendingIntent watchdogPendingIntent(Context context) {
        Intent intent = new Intent(context, WatchdogReceiver.class);
        return PendingIntent.getBroadcast(
                context,
                WATCHDOG_REQUEST,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
    }
}
