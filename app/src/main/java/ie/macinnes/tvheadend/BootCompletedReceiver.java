package ie.macinnes.tvheadend;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import ie.macinnes.tvheadend.sync.EpgSyncService;
import ie.macinnes.tvheadend.tv.TvheadendTvInputService;

public class BootCompletedReceiver extends BroadcastReceiver {

    private static final String TAG = BootCompletedReceiver.class.getName();

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "Received intent: " + intent.getAction());

        if (intent.getAction().equals(Intent.ACTION_BOOT_COMPLETED) && MiscUtils.isSetupComplete(context)) {
            Log.d(TAG, "Starting TVHeadend Services");
            context.startService(new Intent(context, TvheadendTvInputService.class));
            context.startService(new Intent(context, EpgSyncService.class));
        }
    }
}
