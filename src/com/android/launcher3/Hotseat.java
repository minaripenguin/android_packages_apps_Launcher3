/*
 * Copyright (C) 2011 The Android Open Source Project
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

package com.android.launcher3;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.RemoteControlClient;
import android.media.RemoteController;
import android.media.session.MediaSessionLegacyHelper;
import android.os.Handler;
import android.os.Looper;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewDebug;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.android.launcher3.util.ShakeUtils;
import com.android.launcher3.util.VibratorWrapper;

import com.android.internal.util.rising.systemUtils;

import androidx.annotation.Nullable;

import java.util.function.Consumer;

/**
 * View class that represents the bottom row of the home screen.
 */
public class Hotseat extends CellLayout implements Insettable, ShakeUtils.OnShakeListener {

    // Ratio of empty space, qsb should take up to appear visually centered.
    public static final float QSB_CENTER_FACTOR = .325f;

    @ViewDebug.ExportedProperty(category = "launcher")
    private boolean mHasVerticalHotseat;
    private Workspace<?> mWorkspace;
    private boolean mSendTouchToWorkspace;
    @Nullable
    private Consumer<Boolean> mOnVisibilityAggregatedCallback;

    private final View mQsb;
    
    private ShakeUtils mShakeUtils;
    private final AudioManager mAudioManager;

    private boolean mClientIdLost = true;
    private String mCurrentTrack = null;

    private Metadata mMetadata = new Metadata();
    private RemoteController mRemoteController;
    
    private Context mContext;
    private int mGestureAction;
    private int mGestureIntensity;

    public Hotseat(Context context) {
        this(context, null);
        mContext = context;
    }

    public Hotseat(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
         mContext = context;
    }

    public Hotseat(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
         mContext = context;

        if (Utilities.showQSB(context)) {
            mQsb = LayoutInflater.from(mContext).inflate(R.layout.search_container_hotseat, this, false);
        } else {
            mQsb = LayoutInflater.from(mContext).inflate(R.layout.empty_view, this, false);
        }
        addView(mQsb);
        
        mRemoteController = new RemoteController(mContext, mRCClientUpdateListener);
        mAudioManager = mContext.getSystemService(AudioManager.class);
        mAudioManager.registerRemoteController(mRemoteController);

	mGestureAction = Utilities.shakeGestureAction(mContext);
        mGestureIntensity = Utilities.shakeGestureActionIntensity(mContext);
        mShakeUtils = new ShakeUtils(mContext, mGestureIntensity);
    }

    /**
     * Returns orientation specific cell X given invariant order in the hotseat
     */
    public int getCellXFromOrder(int rank) {
        return mHasVerticalHotseat ? 0 : rank;
    }

    /**
     * Returns orientation specific cell Y given invariant order in the hotseat
     */
    public int getCellYFromOrder(int rank) {
        return mHasVerticalHotseat ? (getCountY() - (rank + 1)) : 0;
    }

    public void resetLayout(boolean hasVerticalHotseat) {
        removeAllViewsInLayout();
        mHasVerticalHotseat = hasVerticalHotseat;
        DeviceProfile dp = mActivity.getDeviceProfile();
        resetCellSize(dp);
        if (hasVerticalHotseat) {
            setGridSize(1, dp.numShownHotseatIcons);
        } else {
            setGridSize(dp.numShownHotseatIcons, 1);
        }
    }

    @Override
    public void setInsets(Rect insets) {
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) getLayoutParams();
        DeviceProfile grid = mActivity.getDeviceProfile();

        if (grid.isVerticalBarLayout()) {
            mQsb.setVisibility(View.GONE);
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
            if (grid.isSeascape()) {
                lp.gravity = Gravity.LEFT;
                lp.width = grid.hotseatBarSizePx + insets.left;
            } else {
                lp.gravity = Gravity.RIGHT;
                lp.width = grid.hotseatBarSizePx + insets.right;
            }
        } else {
            mQsb.setVisibility(View.VISIBLE);
            lp.gravity = Gravity.BOTTOM;
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
            lp.height = grid.hotseatBarSizePx;
        }

        Rect padding = grid.getHotseatLayoutPadding(mContext);
        setPadding(padding.left, padding.top, padding.right, padding.bottom);
        setLayoutParams(lp);
        InsettableFrameLayout.dispatchInsets(this, insets);
    }

    public void setWorkspace(Workspace<?> w) {
        mWorkspace = w;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        // We allow horizontal workspace scrolling from within the Hotseat. We do this by delegating
        // touch intercept the Workspace, and if it intercepts, delegating touch to the Workspace
        // for the remainder of the this input stream.
        int yThreshold = getMeasuredHeight() - getPaddingBottom();
        if (mWorkspace != null && ev.getY() <= yThreshold) {
            mSendTouchToWorkspace = mWorkspace.onInterceptTouchEvent(ev);
            return mSendTouchToWorkspace;
        }
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // See comment in #onInterceptTouchEvent
        if (mSendTouchToWorkspace) {
            final int action = event.getAction();
            switch (action & MotionEvent.ACTION_MASK) {
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    mSendTouchToWorkspace = false;
            }
            return mWorkspace.onTouchEvent(event);
        }
        // Always let touch follow through to Workspace.
        return false;
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);

	
        if (mOnVisibilityAggregatedCallback != null) {
            mOnVisibilityAggregatedCallback.accept(isVisible);
        }

        boolean mGestureEnabled = Utilities.shakeGestureAction(mContext) != 0;
        mShakeUtils.bindShakeListener(this, mGestureEnabled);
    }

    /** Sets a callback to be called onVisibilityAggregated */
    public void setOnVisibilityAggregatedCallback(@Nullable Consumer<Boolean> callback) {
        mOnVisibilityAggregatedCallback = callback;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        DeviceProfile dp = mActivity.getDeviceProfile();
        
        int qsbWidth = dp.isQsbInline
                ? dp.hotseatQsbWidth
                : getShortcutsAndWidgets().getMeasuredWidth();

        mQsb.measure(MeasureSpec.makeMeasureSpec(qsbWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(dp.hotseatQsbHeight, MeasureSpec.EXACTLY));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);

        int qsbMeasuredWidth = mQsb.getMeasuredWidth();
        int left;
        DeviceProfile dp = mActivity.getDeviceProfile();
        if (dp.isQsbInline) {
            int qsbSpace = dp.hotseatBorderSpace;
            left = Utilities.isRtl(getResources()) ? r - getPaddingRight() + qsbSpace
                    : l + getPaddingLeft() - qsbMeasuredWidth - qsbSpace;
        } else {
            left = (r - l - qsbMeasuredWidth) / 2;
        }
        int right = left + qsbMeasuredWidth;

        int bottom = b - t - dp.getQsbOffsetY();
        int top = bottom - dp.hotseatQsbHeight;
        mQsb.layout(left, top, right, bottom);
    }

    /**
     * Sets the alpha value of just our ShortcutAndWidgetContainer.
     */
    public void setIconsAlpha(float alpha) {
        getShortcutsAndWidgets().setAlpha(alpha);
    }

    /**
     * Sets the alpha value of just our QSB.
     */
    public void setQsbAlpha(float alpha) {
        mQsb.setAlpha(alpha);
    }

    public float getIconsAlpha() {
        return getShortcutsAndWidgets().getAlpha();
    }

    /**
     * Returns the QSB inside hotseat
     */
    public View getQsb() {
        return mQsb;
    }

    private boolean isMusicActive() {
        return mAudioManager != null && mAudioManager.isMusicActive() && mCurrentTrack != null;
    }

    private void sendMediaButtonClick(int keyCode) {
        if (!mClientIdLost) {
            mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            mRemoteController.sendMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        } else {
            long eventTime = SystemClock.uptimeMillis();
            KeyEvent key = new KeyEvent(eventTime, eventTime, KeyEvent.ACTION_DOWN, keyCode, 0);
            dispatchMediaKeyWithWakeLockToAudioService(key);
            dispatchMediaKeyWithWakeLockToAudioService(
                KeyEvent.changeAction(key, KeyEvent.ACTION_UP));
        }
    }

    private void dispatchMediaKeyWithWakeLockToAudioService(KeyEvent event) {
        MediaSessionLegacyHelper.getHelper(mContext).sendMediaButtonEvent(event, true);
    }

    private RemoteController.OnClientUpdateListener mRCClientUpdateListener =
            new RemoteController.OnClientUpdateListener() {

        @Override
        public void onClientChange(boolean clearing) {
            if (clearing) {
                mMetadata.clear();
                mCurrentTrack = null;
                mClientIdLost = true;
            }
        }

        @Override
        public void onClientPlaybackStateUpdate(int state, long stateChangeTimeMs,
                long currentPosMs, float speed) {
            mClientIdLost = false;
        }

        @Override
        public void onClientPlaybackStateUpdate(int state) {
            mClientIdLost = false;
        }


        @Override
        public void onClientMetadataUpdate(RemoteController.MetadataEditor data) {
            mMetadata.trackTitle = data.getString(MediaMetadataRetriever.METADATA_KEY_TITLE,
                    mMetadata.trackTitle);
            mClientIdLost = false;
            if (mMetadata.trackTitle != null
                    && !mMetadata.trackTitle.equals(mCurrentTrack)) {
                mCurrentTrack = mMetadata.trackTitle;
            }
        }

        @Override
        public void onClientTransportControlUpdate(int transportControlFlags) {
        }
    };

    class Metadata {
        private String trackTitle;

        public void clear() {
            trackTitle = null;
        }
    }

    public void performShakeAction() {
        switch (mGestureAction) {
            case 1:
                systemUtils.toggleCameraFlash();
                break;
            case 2:
        	sendMediaButtonClick(isMusicActive() ? KeyEvent.KEYCODE_MEDIA_NEXT : KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
        	break;
            case 0:
            default:
                break;
	}
    }
   @Override
    public void onShake(double speed) {
	performShakeAction();
	VibratorWrapper.INSTANCE.get(mContext).vibrate(VibratorWrapper.EFFECT_CLICK);
    }

}
