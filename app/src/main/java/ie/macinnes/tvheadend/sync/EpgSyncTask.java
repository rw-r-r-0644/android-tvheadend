/*
 * Copyright (c) 2017 Kiall Mac Innes <kiall@macinnes.ie>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ie.macinnes.tvheadend.sync;

import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.OperationApplicationException;
import android.content.SharedPreferences;
import android.database.sqlite.SQLiteFullException;
import android.media.tv.TvContentRating;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.preference.PreferenceManager;
import android.util.Log;
import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.tvprovider.media.tv.TvContractCompat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArraySet;

import ie.macinnes.htsp.HtspFileInputStream;
import ie.macinnes.htsp.HtspMessage;
import ie.macinnes.htsp.HtspNotConnectedException;
import ie.macinnes.htsp.tasks.Authenticator;
import ie.macinnes.tvheadend.BuildConfig;
import ie.macinnes.tvheadend.Constants;
import ie.macinnes.tvheadend.DvbMappings;
import ie.macinnes.tvheadend.R;
import ie.macinnes.tvheadend.TvContractUtils;

public class EpgSyncTask implements HtspMessage.Listener, Authenticator.Listener {

    private static final String TAG = EpgSyncTask.class.getSimpleName();

    private static final Set<String> HANDLED_METHODS = new HashSet<>(Arrays.asList(
            "channelAdd",
            "channelUpdate",
            "channelDelete",
            "dvrEntryAdd",
            "dvrEntryUpdate",
            "dvrEntryDelete",
            "eventAdd",
            "eventUpdate",
            "eventDelete",
            "initialSyncCompleted"));

    private static final boolean IS_BRAVIA = Build.MODEL.contains("BRAVIA");

    // TODO: Move all these HTSP Lib, Modeled after TvContractCompat.Programs.COLUMN_CHANNEL_ID etc?
    private static final String CHANNEL_ID_KEY = "channelId";
    private static final String CHANNEL_NUMBER_KEY = "channelNumber";
    private static final String CHANNEL_NUMBER_MINOR_KEY = "channelNumberMinor";
    private static final String CHANNEL_NAME_KEY = "channelName";
    private static final String CHANNEL_ICON_KEY = "channelIcon";

    private static final String PROGRAM_DESCRIPTION_KEY = "description";
    private static final String PROGRAM_SUMMARY_KEY = "summary";
    private static final String PROGRAM_TITLE_KEY = "title";
    private static final String PROGRAM_SUBTITLE_KEY = "subtitle";
    private static final String PROGRAM_CONTENT_TYPE_KEY = "contentType";
    private static final String PROGRAM_AGE_RATING_KEY = "ageRating";
    private static final String PROGRAM_START_TIME_KEY = "start";
    private static final String PROGRAM_FINISH_TIME_KEY = "stop";
    private static final String PROGRAM_SEASON_NUMBER_KEY = "seasonNumber";
    private static final String PROGRAM_EPISODE_NUMBER_KEY = "episodeNumber";
    private static final String PROGRAM_IMAGE = "image";

    private static final String DVR_ENTRY_ID_KEY = "id";
    private static final String DVR_ENTRY_CHANNEL_KEY = "channel";
    private static final String DVR_ENTRY_START_KEY = "start";
    private static final String DVR_ENTRY_STOP_KEY = "stop";
    private static final String DVR_ENTRY_EVENT_ID_KEY = "eventId";
    private static final String DVR_ENTRY_TITLE_KEY = "title";
    private static final String DVR_ENTRY_SUBTITLE_KEY = "subtitle";
    private static final String DVR_ENTRY_CONTENT_TYPE_KEY = "contentType";
    private static final String DVR_ENTRY_STATE_KEY = "state";

    private static final String EVENT_ID_KEY = "eventId";

    private static final int TWO_HOURS = 2 * 60 * 60;

    /**
     * A listener for EpgSync events
     */
    public interface Listener {
        /**
         * Returns the Handler on which to execute the callback.
         *
         * @return Handler, or null.
         */
        Handler getHandler();

        /**
         * Called when the initial sync has completed
         */
        void onInitialSyncCompleted();
    }

    private final Context mContext;
    private final HtspMessage.Dispatcher mDispatcher;
    private boolean mQuickSync = false;

    private final ContentResolver mContentResolver;

    private final HandlerThread mHandlerThread;
    private final Handler mHandler;

    private final Set<Listener> mListeners = new CopyOnWriteArraySet<>();

    private boolean mInitialSyncCompleted = false;

    private final SparseArray<Uri> mChannelUriMap;
    private final SparseArray<Uri> mRecordedProgramUriMap;
    private final SparseArray<Uri> mProgramUriMap;

    private final ArrayList<PendingChannelAddUpdate> mPendingChannelOps = new ArrayList<>();
    private final ArrayList<PendingDvrEntryAddUpdate> mPendingRecordedProgramOps = new ArrayList<>();
    private final ArrayList<PendingEventAddUpdate> mPendingProgramOps = new ArrayList<>();

    private int mCompletedChannelOps = 0;
    private int mCompletedRecordedProgramOps = 0;
    private int mCompletedProgramOps = 0;

    private final Queue<PendingChannelLogoFetch> mPendingChannelLogoFetches = new ConcurrentLinkedQueue<>();
    private final byte[] mImageBytes = new byte[102400];

    private final Set<Integer> mSeenChannels = new HashSet<>();
    private final Set<Integer> mSeenRecordedPrograms = new HashSet<>();
    private final Set<Integer> mSeenPrograms = new HashSet<>();

    private final class PendingChannelAddUpdate {
        public final int channelId;
        public final int channelNumber;
        public final ContentProviderOperation operation;

        public PendingChannelAddUpdate(int channelId, int channelNumber, ContentProviderOperation operation) {
            this.channelId = channelId;
            this.channelNumber = channelNumber;
            this.operation = operation;
        }
    }

    private final class PendingChannelLogoFetch {
        public final int channelId;
        public final Uri logoUri;

        public PendingChannelLogoFetch(int channelId, Uri logoUri) {
            this.channelId = channelId;
            this.logoUri = logoUri;
        }
    }

    private final class PendingDvrEntryAddUpdate {
        public final int dvrEntryId;
        public final ContentProviderOperation operation;

        public PendingDvrEntryAddUpdate(int dvrEntryId, ContentProviderOperation operation) {
            this.dvrEntryId = dvrEntryId;
            this.operation = operation;
        }
    }

    private final class PendingEventAddUpdate {
        public final int eventId;
        public final ContentProviderOperation operation;

        public PendingEventAddUpdate(int eventId, ContentProviderOperation operation) {
            this.eventId = eventId;
            this.operation = operation;
        }

    }

    public EpgSyncTask(Context context, @NonNull HtspMessage.Dispatcher dispatcher, boolean quickSync) {
        this(context, dispatcher);

        mQuickSync = quickSync;
    }

    public EpgSyncTask(Context context, @NonNull HtspMessage.Dispatcher dispatcher) {
        mContext = context;
        mDispatcher = dispatcher;
        mContentResolver = context.getContentResolver();

        mHandlerThread = new HandlerThread("EpgSyncTask Handler Thread");
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());

        mChannelUriMap = TvContractUtils.buildChannelUriMap(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            mRecordedProgramUriMap = TvContractUtils.buildRecordedProgramUriMap(context);
            Log.d(TAG, "DVR mRecordedProgramUriMap size: " + mRecordedProgramUriMap.size());
        } else {
            mRecordedProgramUriMap = new SparseArray<>();
        }
        mProgramUriMap = TvContractUtils.buildProgramUriMap(context);
    }

    public void addEpgSyncListener(Listener listener) {
        if (mListeners.contains(listener)) {
            Log.w(TAG, "Attempted to add duplicate epg sync listener");
            return;
        }
        mListeners.add(listener);
    }

    public void removeEpgSyncListener(Listener listener) {
        if (!mListeners.contains(listener)) {
            Log.w(TAG, "Attempted to remove non existing epg sync listener");
            return;
        }
        mListeners.remove(listener);
    }

    // Authenticator.Listener Methods
    @Override
    public void onAuthenticationStateChange(@NonNull Authenticator.State state) {
        if (state == Authenticator.State.AUTHENTICATED) {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());

            long epgMaxTime = Long.parseLong(
                    sharedPreferences.getString(
                            Constants.KEY_EPG_MAX_TIME,
                            mContext.getResources().getString(R.string.pref_default_epg_max_time)
                    )
            );
            final boolean lastUpdateEnabled = sharedPreferences.getBoolean(
                    Constants.KEY_EPG_LAST_UPDATE_ENABLED,
                    mContext.getResources().getBoolean(R.bool.pref_default_epg_last_update_enabled)
            );

            Log.i(TAG, "Enabling Async Metadata: maxTime: " + epgMaxTime + ", quickSync: " + mQuickSync);

            // Reset the InitialSyncCompleted flag
            mInitialSyncCompleted = false;

            HtspMessage enableAsyncMetadataRequest = new HtspMessage();

            enableAsyncMetadataRequest.put("method", "enableAsyncMetadata");
            enableAsyncMetadataRequest.put("epg", 1);

            if (mQuickSync) {
                // Quick sync ignores the epg time preference, and syncs 2 hours of data
                epgMaxTime = TWO_HOURS;
            }

            epgMaxTime = epgMaxTime + (System.currentTimeMillis() / 1000L);
            enableAsyncMetadataRequest.put("epgMaxTime", epgMaxTime);

            if (lastUpdateEnabled) {
                final long lastUpdate = sharedPreferences.getLong(Constants.KEY_EPG_LAST_UPDATE, 0);
                enableAsyncMetadataRequest.put("lastUpdate", lastUpdate);
                Log.d(TAG, "Setting lastUpdate field to " + lastUpdate);
            } else {
                Log.d(TAG, "Skipping lastUpdate field, disabled by preference");
            }

            try {
                mDispatcher.sendMessage(enableAsyncMetadataRequest);
            } catch (HtspNotConnectedException e) {
                Log.d(TAG, "Failed to enable async metadata, HTSP not connected", e);
            }
        }
    }

    // HtspMessage.Listener Methods
    @Override
    public Handler getHandler() {
        return mHandler;
    }

    @Override
    public void onMessage(@NonNull HtspMessage message) {
        final String method = message.getString("method");

        if (HANDLED_METHODS.contains(method)) {
            switch (method) {
                case "channelAdd":
                case "channelUpdate":
                    handleChannelAddUpdate(message);
                    break;
                case "channelDelete":
                    // Do Something
                    break;
                case "dvrEntryAdd":
                case "dvrEntryUpdate":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        handleDvrEntryAddUpdate(message);
                    }
                    break;
                case "dvrEntryDelete":
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        handleDvrEntryDelete(message);
                    }
                    break;
                case "eventAdd":
                case "eventUpdate":
                    handleEventAddUpdate(message);
                    storeLastUpdate();
                    break;
                case "eventDelete":
                    // Do Something
                    break;
                case "initialSyncCompleted":
                    handleInitialSyncCompleted(message);
                    break;
                default:
                    throw new RuntimeException("Unknown message method: " + method);
            }
        }
    }

    // Internal Methods
    private void storeLastUpdate() {
        long unixTime = System.currentTimeMillis() / 1000L;
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
        sharedPreferences.edit().putLong(Constants.KEY_EPG_LAST_UPDATE, unixTime).apply();
    }

    private ContentValues channelToContentValues(@NonNull HtspMessage message) {
        ContentValues values = new ContentValues();

        values.put(TvContractCompat.Channels.COLUMN_INPUT_ID, TvContractUtils.getInputId());
        values.put(TvContractCompat.Channels.COLUMN_TYPE, TvContractCompat.Channels.TYPE_OTHER);
        values.put(TvContractCompat.Channels.COLUMN_ORIGINAL_NETWORK_ID, message.getInteger(CHANNEL_ID_KEY));

        if (message.containsKey(CHANNEL_NUMBER_KEY) && message.containsKey(CHANNEL_NUMBER_MINOR_KEY)) {
            final int channelNumber = message.getInteger(CHANNEL_NUMBER_KEY);
            final int channelNumberMinor = message.getInteger(CHANNEL_NUMBER_MINOR_KEY);
            values.put(TvContractCompat.Channels.COLUMN_DISPLAY_NUMBER, channelNumber + "." + channelNumberMinor);
        } else if (message.containsKey(CHANNEL_NUMBER_KEY)) {
            final int channelNumber = message.getInteger(CHANNEL_NUMBER_KEY);
            values.put(TvContractCompat.Channels.COLUMN_DISPLAY_NUMBER, String.valueOf(channelNumber));
        }

        if (message.containsKey(CHANNEL_NAME_KEY)) {
            values.put(TvContractCompat.Channels.COLUMN_DISPLAY_NAME, message.getString(CHANNEL_NAME_KEY));
        }

        // TODO
//        values.put(TvContractCompat.Channels.COLUMN_INTERNAL_PROVIDER_DATA, accountName);

        return values;
    }

    private void handleChannelAddUpdate(@NonNull HtspMessage message) {
        final int channelId = message.getInteger(CHANNEL_ID_KEY);
        final ContentValues values = channelToContentValues(message);
        final Uri channelUri = TvContractUtils.getChannelUri(mContext, channelId);

        if (channelUri == null) {
            // Insert the channel
            if (Constants.DEBUG)
                Log.v(TAG, "Insert channel " + channelId);
            final int channelNumber = message.getInteger(CHANNEL_NUMBER_KEY);
            mPendingChannelOps.add(new PendingChannelAddUpdate(
                    channelId, channelNumber,
                    ContentProviderOperation.newInsert(TvContractCompat.Channels.CONTENT_URI)
                            .withValues(values)
                            .build()
            ));
        } else {
            // Update the channel
            if (Constants.DEBUG)
                Log.v(TAG, "Update channel " + channelId + " (URI: " + channelUri + ")");
            mPendingChannelOps.add(new PendingChannelAddUpdate(
                    channelId, -1,
                    ContentProviderOperation.newUpdate(channelUri)
                            .withValues(values)
                            .build()
            ));
        }

        if (mInitialSyncCompleted) {
            flushPendingChannelOps();
        } else if (!IS_BRAVIA && mPendingChannelOps.size() >= 100) {
            // Throttle the batch operation not to cause TransactionTooLargeException. If the initial
            // sync has already completed, flush for every message. Additionally, we have no choice
            // on a Sony set but to not flush until we have all the channels, as sony's EPG view
            // is buggy.
            flushPendingChannelOps();
        }

        if (message.containsKey(CHANNEL_ICON_KEY)) {
            mPendingChannelLogoFetches.add(new PendingChannelLogoFetch(channelId, Uri.parse(message.getString(CHANNEL_ICON_KEY))));
        }

        mSeenChannels.add(channelId);
    }

    private void flushPendingChannelOps() {
        if (mPendingChannelOps.isEmpty()) {
            return;
        }

        Log.d(TAG, "Flushing " + mPendingChannelOps.size() + " channel operations (" + mCompletedChannelOps + ")");

        if (IS_BRAVIA) {
            // Sort the Pending Add/Updates by their channel numbers as Sony fails to do this in
            // their EPG view.
            Collections.sort(mPendingChannelOps, new Comparator<PendingChannelAddUpdate>() {
                @Override
                public int compare(PendingChannelAddUpdate o1, PendingChannelAddUpdate o2) {
                    if (o1.channelNumber == -1 || o2.channelNumber == -1) {
                        // One of the events is a channelUpdate, no need (or ability) to sort it
                        return 0;
                    }

                    return Integer.compare(o1.channelNumber, o2.channelNumber);
                }
            });
        }

        // Build out an ArrayList of Operations needed for applyBatch()
        ArrayList<ContentProviderOperation> operations = new ArrayList<>(mPendingChannelOps.size());
        for (PendingChannelAddUpdate pcau : mPendingChannelOps) {
            operations.add(pcau.operation);
        }

        // Apply the batch of Operations
        ContentProviderResult[] results;
        try {
            results = mContext.getContentResolver().applyBatch(
                    Constants.CONTENT_AUTHORITY, operations);
        } catch (RemoteException | OperationApplicationException | SQLiteFullException e) {
            Log.e(TAG, "Failed to flush pending channel operations", e);
            return;
        }

        if (operations.size() != results.length) {
            Log.e(TAG, "Failed to flush pending channels, discarding and moving on, batch size " +
                    "does not match resultset size");

            // Reset the pending operations list
            mCompletedChannelOps += mPendingChannelOps.size();
            mPendingChannelOps.clear();
            return;
        }

        // Update the Channel Uri Map based on the results
        for (int i = 0; i < mPendingChannelOps.size(); i++) {
            final int channelId = mPendingChannelOps.get(i).channelId;
            final ContentProviderResult result = results[i];

            mChannelUriMap.put(channelId, result.uri);
        }

        // Finally, reset the pending operations list
        mCompletedChannelOps += mPendingChannelOps.size();
        mPendingChannelOps.clear();
    }

    private void flushPendingChannelLogoFetches() {
        if (mPendingChannelLogoFetches.isEmpty()) {
            return;
        }

        Log.d(TAG, "Flushing " + mPendingChannelLogoFetches.size() + " channel logo fetches");

        for (PendingChannelLogoFetch pendingChannelLogoFetch : mPendingChannelLogoFetches) {
            final int channelId = pendingChannelLogoFetch.channelId;
            final long androidChannelId = TvContractUtils.getChannelId(mContext, channelId);

            if (androidChannelId == TvContractUtils.INVALID_CHANNEL_ID) {
                Log.e(TAG, "Failed to find channel in android DB, channel ID: " + channelId);
                continue;
            }

            final Uri channelLogoSourceUri = pendingChannelLogoFetch.logoUri;
            final Uri channelLogoDestUri = TvContractCompat.buildChannelLogoUri(androidChannelId);

            InputStream is = null;
            OutputStream os = null;

            try {
                final String logoScheme = channelLogoSourceUri.getScheme();
                if (logoScheme != null && (logoScheme.equalsIgnoreCase("http") || logoScheme.equalsIgnoreCase("https"))) {
                    is = new URL(channelLogoSourceUri.toString()).openStream();
                } else {
                    is = new HtspFileInputStream(mDispatcher, channelLogoSourceUri.getPath());
                }

                os = mContentResolver.openOutputStream(channelLogoDestUri);

                int read;
                int totalRead = 0;

                while ((read = is.read(mImageBytes)) != -1) {
                    os.write(mImageBytes, 0, read);
                    totalRead += read;
                }

                Log.d(TAG, "Successfully fetched logo from " + channelLogoSourceUri + " to " + channelLogoDestUri + " (" + totalRead + " bytes)");

            } catch (IOException e) {
                Log.e(TAG, "Failed to fetch logo from " + channelLogoSourceUri + " to " + channelLogoDestUri, e);

            } finally {
                if (is != null) {
                    try {
                        is.close();
                    } catch (IOException e) {
                        // Ignore...
                    }
                }
                if (os != null) {
                    try {
                        os.close();
                    } catch (IOException e) {
                        // Ignore...
                    }
                }
            }

            mPendingChannelLogoFetches.remove(pendingChannelLogoFetch);
        }
    }

    private void deleteChannels() {
        // Dirty
        int[] existingChannelIds = new int[mChannelUriMap.size()];

        for (int i = 0; i < mChannelUriMap.size(); i++) {
            int key = mChannelUriMap.keyAt(i);
            existingChannelIds[i] = key;
        }

        for (int existingChannelId : existingChannelIds) {
            if (!mSeenChannels.contains(existingChannelId)) {
                if (Constants.DEBUG)
                    Log.d(TAG, "Deleting channel " + existingChannelId);
                Uri channelUri = mChannelUriMap.get(existingChannelId);
                mChannelUriMap.remove(existingChannelId);
                mContentResolver.delete(channelUri, null, null);
            }
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private ContentValues dvrEntryToContentValues(@NonNull HtspMessage message) {
        ContentValues values = new ContentValues();

        values.put(TvContractCompat.RecordedPrograms.COLUMN_INPUT_ID, TvContractUtils.getInputId());
        values.put(TvContractCompat.RecordedPrograms.COLUMN_INTERNAL_PROVIDER_DATA, String.valueOf(message.getInteger(DVR_ENTRY_ID_KEY)));

        values.put(TvContractCompat.RecordedPrograms.COLUMN_CHANNEL_ID, TvContractUtils.getChannelId(mContext, message.getInteger(DVR_ENTRY_CHANNEL_KEY)));

        // COLUMN_TITLE, COLUMN_EPISODE_TITLE, and COLUMN_SHORT_DESCRIPTION are used in the
        // Live Channels app EPG Grid. COLUMN_LONG_DESCRIPTION appears unused.
        // On Sony TVs, COLUMN_LONG_DESCRIPTION is used for the "more info" display.

        if (message.containsKey(DVR_ENTRY_TITLE_KEY)) {
            // The title of this TV program.
            values.put(TvContractCompat.RecordedPrograms.COLUMN_TITLE, message.getString(DVR_ENTRY_TITLE_KEY));
        }

        if (message.containsKey(DVR_ENTRY_SUBTITLE_KEY)) {
            // The episode title of this TV program for episodic TV shows.
            values.put(TvContractCompat.RecordedPrograms.COLUMN_EPISODE_TITLE, message.getString(DVR_ENTRY_SUBTITLE_KEY));
        }

        if (message.containsKey(DVR_ENTRY_START_KEY)) {
            values.put(TvContractCompat.RecordedPrograms.COLUMN_START_TIME_UTC_MILLIS, message.getLong(DVR_ENTRY_START_KEY) * 1000);
        }

        if (message.containsKey(DVR_ENTRY_STOP_KEY)) {
            values.put(TvContractCompat.RecordedPrograms.COLUMN_END_TIME_UTC_MILLIS, message.getLong(DVR_ENTRY_STOP_KEY) * 1000);
        }

        HtspMessage[] files = message.getHtspMessageArray("files", null);
        if (files != null) {
            // Key: files / Value: [{start=1497123818, stop=1497124024, filename=/Archive on 4.ts, size=5714636, info=[{audio_type=0, type=MPEG2AUDIO, language=eng}], fsid=17113954249355398951}]
            long recordingStart = -1;
            long recordingStop = -1;

            for (HtspMessage file : files) {
                long fileStart = file.getLong("start", -1);
                long fileStop = file.getLong("stop", -1);

                if (fileStart > 0 && fileStop > 0) {
                    if (recordingStart == -1 || fileStart < recordingStart) {
                        recordingStart = fileStart;
                    }
                    if (recordingStop == -1 || fileStop < recordingStop) {
                        recordingStop = fileStop;
                    }
                }
            }

            if (recordingStart > 0 && recordingStop > 0) {
                // TODO: Duration is meant to be an int...
                long duration = recordingStop - recordingStart;
                values.put(TvContractCompat.RecordedPrograms.COLUMN_RECORDING_DURATION_MILLIS, duration * 1000);
            }
        }

        if (message.containsKey(DVR_ENTRY_CONTENT_TYPE_KEY)) {
            values.put(TvContractCompat.RecordedPrograms.COLUMN_CANONICAL_GENRE,
                    DvbMappings.PROGRAM_GENRE.get(message.getInteger(DVR_ENTRY_CONTENT_TYPE_KEY)));
        }

        return values;
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void handleDvrEntryAddUpdate(@NonNull HtspMessage message) {
        // Ensure we wrap up any pending channel operations. This is no-op once there are no pending
        // operations.
        flushPendingChannelOps();

        final int dvrEntryId = message.getInteger(DVR_ENTRY_ID_KEY);
        final String state = message.getString(DVR_ENTRY_STATE_KEY, "unknown");

        if (!state.equals("completed")) {
            // Skip anything that's not completed for now
            return;
        }

        final ContentValues values = dvrEntryToContentValues(message);
        final Uri dvrEntryUri = TvContractUtils.getRecordedProgramUri(mContext, dvrEntryId);

        if (dvrEntryUri == null) {
            // Insert the DVR Entry
            if (Constants.DEBUG)
                Log.v(TAG, "Insert dvrEntry " + dvrEntryId);
            mPendingRecordedProgramOps.add(new PendingDvrEntryAddUpdate(
                    dvrEntryId,
                    ContentProviderOperation.newInsert(TvContractCompat.RecordedPrograms.CONTENT_URI)
                            .withValues(values)
                            .build()
            ));
        } else {
            // Update the DVR entry
            if (Constants.DEBUG)
                Log.v(TAG, "Update dvrEntry " + dvrEntryId + " (URI: " + dvrEntryUri + ")");
            mPendingRecordedProgramOps.add(new PendingDvrEntryAddUpdate(
                    dvrEntryId,
                    ContentProviderOperation.newUpdate(dvrEntryUri)
                            .withValues(values)
                            .build()
            ));
        }

        // Throttle the batch operation not to cause TransactionTooLargeException. If the initial
        // sync has already completed, flush for every message.
        if (mInitialSyncCompleted || mPendingProgramOps.size() >= 100) {
            flushPendingDvrEntryOps();
        }

        mSeenRecordedPrograms.add(dvrEntryId);
    }

    @RequiresApi(api = Build.VERSION_CODES.N)
    private void handleDvrEntryDelete(@NonNull HtspMessage message) {
        final int dvrEntryId = message.getInteger(DVR_ENTRY_ID_KEY);
        Uri recordedProgramUri = TvContractUtils.getRecordedProgramUri(mContext, dvrEntryId);

        if (recordedProgramUri != null) {
            mContentResolver.delete(recordedProgramUri, null, null);
        }
    }

    private void flushPendingDvrEntryOps() {
        if (mPendingRecordedProgramOps.isEmpty()) {
            return;
        }

        Log.d(TAG, "Flushing " + mPendingRecordedProgramOps.size() + " dvrEntry operations (" + mCompletedRecordedProgramOps + ")");

        // Build out an ArrayList of Operations needed for applyBatch()
        ArrayList<ContentProviderOperation> operations = new ArrayList<>(mPendingRecordedProgramOps.size());
        for (PendingDvrEntryAddUpdate prpau : mPendingRecordedProgramOps) {
            operations.add(prpau.operation);
        }

        // Apply the batch of Operations
        ContentProviderResult[] results;
        try {
            results = mContext.getContentResolver().applyBatch(
                    Constants.CONTENT_AUTHORITY, operations);
        } catch (RemoteException | OperationApplicationException | SQLiteFullException e) {
            Log.e(TAG, "Failed to flush pending dvrEntry operations", e);
            return;
        }

        if (operations.size() != results.length) {
            Log.e(TAG, "Failed to flush pending dvrEntrys, discarding and moving on, batch size " +
                    "does not match resultset size");

            // Reset the pending operations list
            mCompletedRecordedProgramOps += mPendingRecordedProgramOps.size();
            mPendingRecordedProgramOps.clear();
            return;
        }

        // Update the Channel Uri Map based on the results
        for (int i = 0; i < mPendingRecordedProgramOps.size(); i++) {
            final int dvrEntryId = mPendingRecordedProgramOps.get(i).dvrEntryId;
            final ContentProviderResult result = results[i];

            mRecordedProgramUriMap.put(dvrEntryId, result.uri);
        }

        // Finally, reset the pending operations list
        mCompletedRecordedProgramOps += mPendingRecordedProgramOps.size();
        mPendingRecordedProgramOps.clear();
    }

    private void deleteRecordedPrograms() {
        // Dirty
        int[] existingRecordedProgramIds = new int[mRecordedProgramUriMap.size()];

        for (int i = 0; i < mRecordedProgramUriMap.size(); i++) {
            int key = mRecordedProgramUriMap.keyAt(i);
            existingRecordedProgramIds[i] = key;
        }

        for (int existingRecordedProgramId : existingRecordedProgramIds) {
            if (!mSeenRecordedPrograms.contains(existingRecordedProgramId)) {
                if (Constants.DEBUG)
                    Log.d(TAG, "DVR Deleting recorded program " + existingRecordedProgramId);
                Uri recordedProgramUri = mRecordedProgramUriMap.get(existingRecordedProgramId);
                mRecordedProgramUriMap.remove(existingRecordedProgramId);
                mContentResolver.delete(recordedProgramUri, null, null);
            }
        }
    }

    private ContentValues eventToContentValues(@NonNull HtspMessage message) {
        ContentValues values = new ContentValues();

        values.put(TvContractCompat.Programs.COLUMN_CHANNEL_ID, TvContractUtils.getChannelId(mContext, message.getInteger(CHANNEL_ID_KEY)));
        values.put(TvContractCompat.Programs.COLUMN_INTERNAL_PROVIDER_DATA, String.valueOf(message.getInteger(EVENT_ID_KEY)));

        // COLUMN_TITLE, COLUMN_EPISODE_TITLE, and COLUMN_SHORT_DESCRIPTION are used in the
        // Live Channels app EPG Grid. COLUMN_LONG_DESCRIPTION appears unused.
        // On Sony TVs, COLUMN_LONG_DESCRIPTION is used for the "more info" display.

        if (message.containsKey(PROGRAM_TITLE_KEY)) {
            // The title of this TV program.
            values.put(TvContractCompat.Programs.COLUMN_TITLE, message.getString(PROGRAM_TITLE_KEY));
        }

        if (message.containsKey(PROGRAM_SUMMARY_KEY) && message.containsKey(PROGRAM_DESCRIPTION_KEY)) {
            // If we have both summary and description... use them both
            values.put(TvContractCompat.Programs.COLUMN_SHORT_DESCRIPTION, message.getString(PROGRAM_SUMMARY_KEY));
            values.put(TvContractCompat.Programs.COLUMN_LONG_DESCRIPTION, message.getString(PROGRAM_DESCRIPTION_KEY));

        } else if (message.containsKey(PROGRAM_SUMMARY_KEY) && !message.containsKey(PROGRAM_DESCRIPTION_KEY)) {
            // If we have only summary, use it.
            values.put(TvContractCompat.Programs.COLUMN_SHORT_DESCRIPTION, message.getString(PROGRAM_SUMMARY_KEY));

        } else if (!message.containsKey(PROGRAM_SUMMARY_KEY) && message.containsKey(PROGRAM_DESCRIPTION_KEY)) {
            // If we have only description, use it.
            values.put(TvContractCompat.Programs.COLUMN_SHORT_DESCRIPTION, message.getString(PROGRAM_DESCRIPTION_KEY));
        }

        if (message.containsKey(PROGRAM_CONTENT_TYPE_KEY)) {
            values.put(TvContractCompat.Programs.COLUMN_CANONICAL_GENRE,
                    DvbMappings.PROGRAM_GENRE.get(message.getInteger(PROGRAM_CONTENT_TYPE_KEY)));
        }

        if (message.containsKey(PROGRAM_AGE_RATING_KEY)) {
            final int ageRating = message.getInteger(PROGRAM_AGE_RATING_KEY);
            if (ageRating >= 4 && ageRating <= 18) {
                TvContentRating rating = TvContentRating.createRating("com.android.tv", "DVB", "DVB_" + ageRating);
                values.put(TvContractCompat.Programs.COLUMN_CONTENT_RATING, rating.flattenToString());
            }
        }

        if (message.containsKey(PROGRAM_START_TIME_KEY)) {
            values.put(TvContractCompat.Programs.COLUMN_START_TIME_UTC_MILLIS, message.getLong(PROGRAM_START_TIME_KEY) * 1000);
        }

        if (message.containsKey(PROGRAM_FINISH_TIME_KEY)) {
            values.put(TvContractCompat.Programs.COLUMN_END_TIME_UTC_MILLIS, message.getLong(PROGRAM_FINISH_TIME_KEY) * 1000);
        }

        if (message.containsKey(PROGRAM_EPISODE_NUMBER_KEY) && message.containsKey(PROGRAM_SUBTITLE_KEY)) {
            // The episode title of this TV program for episodic TV shows.
            values.put(TvContractCompat.Programs.COLUMN_EPISODE_TITLE, message.getString(PROGRAM_SUBTITLE_KEY));
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (message.containsKey(PROGRAM_SEASON_NUMBER_KEY)) {
                values.put(TvContractCompat.Programs.COLUMN_SEASON_DISPLAY_NUMBER, message.getInteger(PROGRAM_SEASON_NUMBER_KEY));
            }

            if (message.containsKey(PROGRAM_EPISODE_NUMBER_KEY)) {
                values.put(TvContractCompat.Programs.COLUMN_EPISODE_DISPLAY_NUMBER, message.getInteger(PROGRAM_EPISODE_NUMBER_KEY));
            }
        } else {
            if (message.containsKey(PROGRAM_SEASON_NUMBER_KEY)) {
                //noinspection deprecation
                values.put(TvContractCompat.Programs.COLUMN_SEASON_NUMBER, message.getInteger(PROGRAM_SEASON_NUMBER_KEY));
            }

            if (message.containsKey(PROGRAM_EPISODE_NUMBER_KEY)) {
                //noinspection deprecation
                values.put(TvContractCompat.Programs.COLUMN_EPISODE_NUMBER, message.getInteger(PROGRAM_EPISODE_NUMBER_KEY));
            }
        }

        if (message.containsKey(PROGRAM_IMAGE)) {
            values.put(TvContractCompat.Programs.COLUMN_POSTER_ART_URI, message.getString(PROGRAM_IMAGE));
        } else {
            SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(mContext.getApplicationContext());
            final boolean defaultPosterArtEnabled = sharedPreferences.getBoolean(
                    Constants.KEY_EPG_DEFAULT_POSTER_ART_ENABLED,
                    mContext.getResources().getBoolean(R.bool.pref_default_epg_default_poster_art_enabled)
            );
            if (defaultPosterArtEnabled) {
                values.put(TvContractCompat.Programs.COLUMN_POSTER_ART_URI, "android.resource://" + BuildConfig.APPLICATION_ID + "/" + R.drawable.default_event_icon);
            }
        }

        return values;
    }

    private void handleEventAddUpdate(@NonNull HtspMessage message) {
        // Ensure we wrap up any pending channel operations. This is no-op once there are no pending
        // operations. This should only be needed when there were no DVR entries provided at all.
        flushPendingChannelOps();

        // Ensure we wrap up any pending dvr entry operations. This is no-op once there are no pending
        // operations.
        flushPendingDvrEntryOps();

        final int channelId = message.getInteger(CHANNEL_ID_KEY);
        final int eventId = message.getInteger(EVENT_ID_KEY);
        final ContentValues values = eventToContentValues(message);
        final Uri eventUri = TvContractUtils.getProgramUri(mContext, channelId, eventId);

        if (eventUri == null) {
            // Insert the event
            if (Constants.DEBUG)
                Log.v(TAG, "Insert event " + eventId + " on channel " + channelId);
            mPendingProgramOps.add(new PendingEventAddUpdate(
                    eventId,
                    ContentProviderOperation.newInsert(TvContractCompat.Programs.CONTENT_URI)
                            .withValues(values)
                            .build()
            ));
        } else {
            // Update the event
            if (Constants.DEBUG)
                Log.v(TAG, "Update event " + eventId + " on channel " + channelId + " (URI: " + eventUri + ")");
            mPendingProgramOps.add(new PendingEventAddUpdate(
                    eventId,
                    ContentProviderOperation.newUpdate(eventUri)
                            .withValues(values)
                            .build()
            ));
        }

        // Throttle the batch operation not to cause TransactionTooLargeException. If the initial
        // sync has already completed, flush for every message.
        if (mInitialSyncCompleted || mPendingProgramOps.size() >= 100) {
            flushPendingEventOps();
        }

        mSeenPrograms.add(eventId);
    }

    private void flushPendingEventOps() {
        if (mPendingProgramOps.isEmpty()) {
            return;
        }

        Log.d(TAG, "Flushing " + mPendingProgramOps.size() + " event operations (" + mCompletedProgramOps + ")");

        // Build out an ArrayList of Operations needed for applyBatch()
        ArrayList<ContentProviderOperation> operations = new ArrayList<>(mPendingProgramOps.size());
        for (PendingEventAddUpdate peau : mPendingProgramOps) {
            operations.add(peau.operation);
        }

        // Apply the batch of Operations
        ContentProviderResult[] results;

        try {
            results = mContext.getContentResolver().applyBatch(
                    Constants.CONTENT_AUTHORITY, operations);
        } catch (RemoteException | OperationApplicationException | SQLiteFullException e) {
            Log.e(TAG, "Failed to flush pending event operations", e);
            return;
        }

        if (operations.size() != results.length) {
            Log.e(TAG, "Failed to flush pending events, discarding and moving on, batch size " +
                    "does not match resultset size");

            // Reset the pending operations list
            mCompletedProgramOps += mPendingProgramOps.size();
            mPendingProgramOps.clear();
            return;
        }

        // Update the Event Uri Map based on the results
        for (int i = 0; i < mPendingProgramOps.size(); i++) {
            final int eventId = mPendingProgramOps.get(i).eventId;
            final ContentProviderResult result = results[i];

            mProgramUriMap.put(eventId, result.uri);
        }

        // Finally, reset the pending operations list
        mCompletedProgramOps += mPendingProgramOps.size();
        mPendingProgramOps.clear();
    }

    private void deletePrograms() {
        // Dirty
        int[] existingProgramIds = new int[mProgramUriMap.size()];

        for (int i = 0; i < mProgramUriMap.size(); i++) {
            int key = mProgramUriMap.keyAt(i);
            existingProgramIds[i] = key;
        }

        for (int existingProgramId : existingProgramIds) {
            if (!mSeenPrograms.contains(existingProgramId)) {
                if (Constants.DEBUG)
                    Log.d(TAG, "Deleting program " + existingProgramId);
                Uri programUri = mProgramUriMap.get(existingProgramId);
                mProgramUriMap.remove(existingProgramId);
                mContentResolver.delete(programUri, null, null);
            }
        }
    }

    private void handleInitialSyncCompleted(@NonNull HtspMessage message) {
        // Ensure we wrap up any pending channel operations. This is no-op once there are no pending
        // operations. This should only be needed when there were no events provided at all.
        flushPendingChannelOps();

        // Ensure we wrap up any pending dvr entry operations. This is no-op once there are no
        // pending operations.  This should only be needed when there were no dvr entries provided at all.
        flushPendingDvrEntryOps();

        // Ensure we wrap up any pending event operations. This is no-op once there are no pending
        // operations.
        flushPendingEventOps();

        // Clear out any stale date
        deleteChannels();
        deleteRecordedPrograms();
        deletePrograms();

        // Fetch all the channel logos
        flushPendingChannelLogoFetches();

        Log.i(TAG, "Initial sync completed");
        mInitialSyncCompleted = true;

        // Let our listeners know
        for (final Listener listener : mListeners) {
            Handler handler = listener.getHandler();
            if (handler == null) {
                listener.onInitialSyncCompleted();
            } else {
                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onInitialSyncCompleted();
                    }
                });
            }
        }
    }
}
