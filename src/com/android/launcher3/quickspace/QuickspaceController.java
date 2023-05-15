/*
 * Copyright (C) 2018 CypherOS
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.quickspace;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaController.PlaybackInfo;
import android.media.session.MediaSession;
import android.media.session.MediaSession.QueueItem;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import com.android.internal.util.rising.OmniJawsClient;

import com.android.launcher3.R;
import com.android.launcher3.Utilities;
import com.android.launcher3.notification.NotificationKeyData;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.util.PackageUserKey;

import java.util.ArrayList;
import java.util.List;

public class QuickspaceController implements NotificationListener.NotificationsChangedListener, OmniJawsClient.OmniJawsObserver {

    public final ArrayList<OnDataListener> mListeners = new ArrayList();
    private static final String SETTING_WEATHER_LOCKSCREEN_UNIT = "weather_lockscreen_unit";
    private static final boolean DEBUG = false;
    private static final String TAG = "Launcher3:QuickspaceController";

    private final Context mContext;
    private final Handler mHandler;
    private QuickEventsController mEventsController;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherInfo;
    private Drawable mConditionImage;

    private boolean mUseImperialUnit;

    private MediaController mMediaController;
    private MediaSessionManager mSessionManager;
    private AudioManager mAudioManager;
    
    private String mCurrentArtist;
    private String mCurrentSongName;
    private boolean mClientLost;
    private boolean mSessionDestroyed;

    public interface OnDataListener {
        void onDataUpdated();
    }

    public QuickspaceController(Context context) {
        mContext = context;
        mHandler = new Handler();
        mEventsController = new QuickEventsController(context);
        mWeatherClient = new OmniJawsClient(context);
        
        // now playing
        mAudioManager = (AudioManager) context.getSystemService(AudioManager.class);
        mSessionManager = (MediaSessionManager) context.getSystemService(Context.MEDIA_SESSION_SERVICE);
    }

    public void unregisterCallback() {
    	mMediaController = getMediaController();
       if (mMediaController != null) {
           mMediaController.unregisterCallback(new MediaController.Callback() {});
       }
    }

    private MediaController getMediaController() {
        MediaController controller = null;
        if (mSessionManager != null) {
            for (MediaController activeController : mSessionManager.getActiveSessions(null)) {
            	PlaybackState state = activeController.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                    controller = activeController;
                    break;
                }
            }
        }
        return controller;
    }

    public void registerCallback() {
    	mMediaController = getMediaController();
        if (mMediaController != null) {
            mMediaController.registerCallback(new MediaController.Callback() {
                @Override
                public void onSessionDestroyed() {
                    mClientLost = true;
                    mSessionDestroyed = true;
                    updateMediaInfo();
                }
                @Override
                public void onSessionEvent(String event, Bundle extras) {
                    updateMediaInfo();
                }
                @Override
                public void onQueueChanged(List<MediaSession.QueueItem> queue) {
                    updateMediaInfo();
                }
                @Override
                public void onQueueTitleChanged(CharSequence title) {
                    updateMediaInfo();
                }
                @Override
                public void onExtrasChanged(Bundle extras) {
                    updateMediaInfo();
                }
                @Override
                public void onAudioInfoChanged(PlaybackInfo info) {
                    updateMediaInfo();
                }
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
		    	switch (state.getState()) {
		        case PlaybackState.STATE_PLAYING:
		            mClientLost = false;
		            mSessionDestroyed = false;
		            break;
		        case PlaybackState.STATE_PAUSED:
		        case PlaybackState.STATE_STOPPED:
		        case PlaybackState.STATE_ERROR:
		            mClientLost = true;
		            break;
		        default:
		            break;
        	       }
        	       updateMediaInfo();
        	}
                @Override
                public void onMetadataChanged(MediaMetadata metadata) {
                    mCurrentSongName = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
                    mCurrentArtist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
                    mClientLost = metadata == null || mCurrentSongName == null || mCurrentArtist == null;
                    updateMediaInfo();
                }
            });
        }
    }

    private boolean isMusicActive() {
        if (mSessionManager != null) {
             for (MediaController activeController : mSessionManager.getActiveSessions(null)) {
                PlaybackState state = activeController.getPlaybackState();
                if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        return true;
                    }
                }
        }
        return false;
    }

    private void addWeatherProvider() {
        if (!Utilities.isQuickspaceWeather(mContext)) return;
        mWeatherClient.addObserver(this);
        queryAndUpdateWeather();
    }

    public void addListener(OnDataListener listener) {
        mListeners.add(listener);
        addWeatherProvider();
        registerCallback(); 
        listener.onDataUpdated();
    }

    public void removeListener(OnDataListener listener) {
        if (mWeatherClient != null) {
            mWeatherClient.removeObserver(this);
        }
        mListeners.remove(listener);
    }

    public boolean isQuickEvent() {
        return mEventsController.isQuickEvent();
    }

    public QuickEventsController getEventController() {
        return mEventsController;
    }

    public boolean isWeatherAvailable() {
        return mWeatherClient != null && mWeatherClient.isOmniJawsEnabled();
    }

    public Drawable getWeatherIcon() {
        return mConditionImage;
    }

    public String getWeatherTemp() {
    	boolean isDetailed = Utilities.isQuickSpaceWeatherDetailed(mContext);
        if (mWeatherInfo != null) {
            String formattedCondition = mWeatherInfo.condition;
            if (formattedCondition.toLowerCase().contains("clouds")) {
               formattedCondition = "Cloudy";
            } else if (formattedCondition.toLowerCase().contains("rain")) {
              formattedCondition = "Rainy";
            } else if (formattedCondition.toLowerCase().contains("clear")) {
              formattedCondition = "Sunny";
            } else if (formattedCondition.toLowerCase().contains("storm")) {
              formattedCondition = "Stormy";
            } else if (formattedCondition.toLowerCase().contains("snow")) {
              formattedCondition = "Snowy";
            } else if (formattedCondition.toLowerCase().contains("wind")) {
              formattedCondition = "Windy";
            } else if (formattedCondition.toLowerCase().contains("mist")) {
              formattedCondition = "Misty";
            }
            String weatherTemp = mWeatherInfo.temp + mWeatherInfo.tempUnits + (isDetailed ? " - "  + formattedCondition : "");
            return weatherTemp;
        }
        return null;
    }

    private void updateMediaInfo() {
    	mMediaController = getMediaController();
        MediaMetadata metadata = mMediaController != null ? mMediaController.getMetadata() : null;
        mCurrentSongName = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_TITLE) : null;
        mCurrentArtist = metadata != null ? metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
        mClientLost = metadata == null || mCurrentSongName == null || mCurrentArtist == null;

        if (mEventsController != null) {
            mEventsController.setMediaInfo(mCurrentSongName, mCurrentArtist, mClientLost, isMusicActive());
            mEventsController.updateQuickEvents();
            notifyListeners();
        }
    }

    @Override
    public void onNotificationPosted(PackageUserKey postedPackageUserKey,
                     NotificationKeyData notificationKey) {
        updateMediaInfo();
    }

    @Override
    public void onNotificationRemoved(PackageUserKey removedPackageUserKey,
                      NotificationKeyData notificationKey) {
        updateMediaInfo();
    }

    @Override
    public void onNotificationFullRefresh(List<StatusBarNotification> activeNotifications) {
        updateMediaInfo();
    }

    public void onPause() {
    	unregisterCallback();
        if (mEventsController != null) mEventsController.onPause();
    }

    public void onResume() {
        if (mEventsController != null) {
            registerCallback();
            updateMediaInfo();
            mEventsController.onResume();
            notifyListeners();
        }
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    @Override
    public void weatherError(int errorReason) {
        Log.d(TAG, "weatherError " + errorReason);
        if (errorReason == OmniJawsClient.EXTRA_ERROR_DISABLED) {
            mWeatherInfo = null;
            notifyListeners();
        }
    }

    @Override
    public void updateSettings() {
        Log.i(TAG, "updateSettings");
        queryAndUpdateWeather();
    }

    private void queryAndUpdateWeather() {
        try {
            mWeatherClient.queryWeather();
            mWeatherInfo = mWeatherClient.getWeatherInfo();
            if (mWeatherInfo != null) {
                mConditionImage = mWeatherClient.getWeatherConditionImage(mWeatherInfo.conditionCode);
            }
            notifyListeners();
        } catch(Exception e) {
            // Do nothing
        }
    }

    public void notifyListeners() {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                for (OnDataListener list : mListeners) {
                    registerCallback(); 
                    list.onDataUpdated();
                }
            }
        });
    }
}
