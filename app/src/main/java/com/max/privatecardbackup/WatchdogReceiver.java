package com.max.privatecardbackup;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class WatchdogReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        ServiceControl.start(context);
        ServiceControl.scheduleRegularWatchdog(context);
    }
}
