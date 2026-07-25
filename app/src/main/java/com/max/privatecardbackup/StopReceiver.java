package com.max.privatecardbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class StopReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        AppPrefs prefs = new AppPrefs(context);
        prefs.setUserStopped(true);
        ServiceControl.cancelWatchdog(context);
        context.stopService(new Intent(context, BackupMonitorService.class));
    }
}
