/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.services.telephony;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Message;
import android.telecomm.CallServiceAdapter;
import android.util.Log;

import com.android.internal.telephony.Call;
import com.android.internal.telephony.CallStateException;
import com.android.internal.telephony.Connection;
import com.android.phone.PhoneGlobals;

/**
 * Manages a single phone call. Listens to the call's state changes and updates the
 * CallServiceAdapter.
 */
class TelephonyCallConnection {
    private static final String TAG = TelephonyCallConnection.class.getSimpleName();
    private static final int EVENT_PRECISE_CALL_STATE_CHANGED = 1;

    private final String mCallId;
    private final StateHandler mHandler = new StateHandler();

    private CallServiceAdapter mCallServiceAdapter;

    private Connection mOriginalConnection;
    private Call.State mState = Call.State.IDLE;

    TelephonyCallConnection(CallServiceAdapter callServiceAdapter, String callId,
            Connection connection) {
        mCallServiceAdapter = callServiceAdapter;
        mCallId = callId;
        mOriginalConnection = connection;
        mOriginalConnection.getCall().getPhone().registerForPreciseCallStateChanged(mHandler,
                EVENT_PRECISE_CALL_STATE_CHANGED, null);
        updateState();
    }

    String getCallId() {
        return mCallId;
    }

    Connection getOriginalConnection() {
        return mOriginalConnection;
    }

    void disconnect(boolean shouldAbort) {
        if (shouldAbort) {
            mCallServiceAdapter = null;
            close();
        }
        if (mOriginalConnection != null) {
            try {
                mOriginalConnection.hangup();
            } catch (CallStateException e) {
                Log.e(TAG, "Call to Connection.hangup failed with exception", e);
            }
        }
    }

    private void updateState() {
        if (mOriginalConnection == null || mCallServiceAdapter == null) {
            return;
        }

        Call.State newState = mOriginalConnection.getState();
        if (mState == newState) {
            return;
        }

        mState = newState;
        switch (newState) {
            case IDLE:
                break;
            case ACTIVE:
                mCallServiceAdapter.setActive(mCallId);
                break;
            case HOLDING:
                break;
            case DIALING:
            case ALERTING:
                mCallServiceAdapter.setDialing(mCallId);
                break;
            case INCOMING:
            case WAITING:
                mCallServiceAdapter.setRinging(mCallId);
                break;
            case DISCONNECTED:
                mCallServiceAdapter.setDisconnected(mCallId);
                close();
                break;
            case DISCONNECTING:
                break;
        }

        setAudioMode(Call.State.ACTIVE);
    }

    private void close() {
        if (mOriginalConnection != null) {
            Call call = mOriginalConnection.getCall();
            if (call != null) {
                call.getPhone().unregisterForPreciseCallStateChanged(mHandler);
            }
            mOriginalConnection = null;
        }
        CallRegistrar.unregister(mCallId);
    }

    /**
     * Sets the audio mode according to the specified state of the call.
     * TODO(santoscordon): This will not be necessary once Telecomm manages audio focus. This does
     * not handle multiple calls well, specifically when there are multiple active call services
     * within services/Telephony.
     *
     * @param state The state of the call.
     */
    private static void setAudioMode(Call.State state) {
        Context context = PhoneGlobals.getInstance();
        AudioManager audioManager =
                (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        if (Call.State.ACTIVE == state) {
            // Set the IN_CALL mode only when the call is active.
            if (audioManager.getMode() != AudioManager.MODE_IN_CALL) {
                audioManager.requestAudioFocusForCall(
                        AudioManager.STREAM_VOICE_CALL, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                audioManager.setMode(AudioManager.MODE_IN_CALL);
            }
        } else {
            // Non active calls go back to normal mode. This breaks down if there are multiple calls
            // due to non-deterministic execution order across call services. But that will be fixed
            // as soon as this moves to Telecomm where it is aware of all active calls.
            if (audioManager.getMode() != AudioManager.MODE_NORMAL) {
                audioManager.abandonAudioFocusForCall();
            }
        }
    }

    private class StateHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_PRECISE_CALL_STATE_CHANGED:
                    updateState();
                    break;
            }
        }
    }
}
