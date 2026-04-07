
/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.gtask.remote;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import net.micode.notes.R;
import net.micode.notes.ui.NotesListActivity;
import net.micode.notes.ui.NotesPreferenceActivity;


public class GTaskASyncTask extends AsyncTask<Void, String, Integer> {

    private static int GTASK_SYNC_NOTIFICATION_ID = 5234235;
    private static final String CHANNEL_ID = "gtask_sync_channel";
    public interface OnCompleteListener {
        void onComplete();
    }

    private Context mContext;

    private NotificationManager mNotifiManager;

    private GTaskManager mTaskManager;

    private OnCompleteListener mOnCompleteListener;

    public GTaskASyncTask(Context context, OnCompleteListener listener) {
        mContext = context;
        mOnCompleteListener = listener;
        mNotifiManager = (NotificationManager) mContext
                .getSystemService(Context.NOTIFICATION_SERVICE);
        mTaskManager = GTaskManager.getInstance();
    }

    public void cancelSync() {
        mTaskManager.cancelSync();
    }

    public void publishProgess(String message) {
        publishProgress(new String[] {
            message
        });
    }

    private void showNotification(int tickerId, String content) {
        // 构建 PendingIntent
        Intent intent;
        if (tickerId != R.string.ticker_success) {
            intent = new Intent(mContext, NotesPreferenceActivity.class);
        } else {
            intent = new Intent(mContext, NotesListActivity.class);
        }
        // 注意：对于 Android 12+，PendingIntent 需要指定 mutability
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;  // API 23+ 必须设置
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(mContext, 0, intent, flags);

        // 使用 NotificationCompat.Builder 构建通知
        NotificationCompat.Builder builder = new NotificationCompat.Builder(mContext, CHANNEL_ID)
                .setSmallIcon(R.drawable.notification)
                .setContentTitle(mContext.getString(R.string.app_name))
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setDefaults(Notification.DEFAULT_LIGHTS);

        // 如果需要自定义 ticker 文本，可以单独设置
        if (tickerId != 0) {
            builder.setTicker(mContext.getString(tickerId));
        }

        // 使用 NotificationManagerCompat 发送通知（推荐）
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(mContext);
        notificationManager.notify(GTASK_SYNC_NOTIFICATION_ID, builder.build());
    }

    @Override
    protected Integer doInBackground(Void... unused) {
        publishProgess(mContext.getString(R.string.sync_progress_login, NotesPreferenceActivity
                .getSyncAccountName(mContext)));
        return mTaskManager.sync(mContext, this);
    }

    @Override
    protected void onProgressUpdate(String... progress) {
        showNotification(R.string.ticker_syncing, progress[0]);
        if (mContext instanceof GTaskSyncService) {
            ((GTaskSyncService) mContext).sendBroadcast(progress[0]);
        }
    }

    @Override
    protected void onPostExecute(Integer result) {
        if (result == GTaskManager.STATE_SUCCESS) {
            showNotification(R.string.ticker_success, mContext.getString(
                    R.string.success_sync_account, mTaskManager.getSyncAccount()));
            NotesPreferenceActivity.setLastSyncTime(mContext, System.currentTimeMillis());
        } else if (result == GTaskManager.STATE_NETWORK_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_network));
        } else if (result == GTaskManager.STATE_INTERNAL_ERROR) {
            showNotification(R.string.ticker_fail, mContext.getString(R.string.error_sync_internal));
        } else if (result == GTaskManager.STATE_SYNC_CANCELLED) {
            showNotification(R.string.ticker_cancel, mContext
                    .getString(R.string.error_sync_cancelled));
        }
        if (mOnCompleteListener != null) {
            new Thread(new Runnable() {

                public void run() {
                    mOnCompleteListener.onComplete();
                }
            }).start();
        }
    }
}
