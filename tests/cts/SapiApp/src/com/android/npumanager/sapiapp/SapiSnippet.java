/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.npumanager.sapiapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import com.google.android.mobly.snippet.Snippet;
import com.google.android.mobly.snippet.event.EventCache;
import com.google.android.mobly.snippet.event.SnippetEvent;
import com.google.android.mobly.snippet.rpc.AsyncRpc;
import com.google.android.mobly.snippet.rpc.Rpc;

/** SapiSnippet is a Mobly Snippet class that provides RPCs for the SAPI test app. */
public class SapiSnippet implements Snippet {

    private static MainActivity activity;
    private static final String TAG = "SAPISnippet";
    private final Context context;
    private final UiDevice mDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

    public SapiSnippet() {
        context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @AsyncRpc(description = "Wait for indication that the inference is complete")
    public void asyncWaitForInferenceComplete(String callbackId, String eventName) {
        context.registerReceiver(
                new SnippetBroadcastReceiver(
                        context,
                        new SnippetEvent(callbackId, eventName),
                        eventName,
                        MainActivity.ACTION_INFERENCE_COMPLETE),
                new IntentFilter(MainActivity.ACTION_INFERENCE_COMPLETE),
                Context.RECEIVER_EXPORTED);
    }

    @Rpc(description = "Launches the SAPI app.")
    public void startActivity() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(context, MainActivity.class.getName());

        activity =
                (MainActivity)
                        InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    @Rpc(description = "Close activity if one was opened.")
    public void closeActivity() {
        if (activity != null) {
            activity.finish();
        }
    }

    @Rpc(description = "Triggers the rewriteText() function in MainActivity.")
    public void rewriteText() {
        if (activity != null) {
            Log.w(TAG, "Preparing and starting rewriteText() now");
            activity.prepareAndStartRewrite();
        } else {
            Log.w(TAG, "rewriteText() failed: MainActivity instance is null.");
        }
    }

    /** Turns device screen on */
    @Rpc(description = "Turns device screen on")
    public void turnScreenOn() {
        try {
            mDevice.wakeUp();
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException", e);
        }
    }

    /** Press device menu button to return device to home screen between tests. */
    @Rpc(description = "Press menu button")
    public void pressMenu() {
        mDevice.pressMenu();
    }

    /** BroadcastReceiver subclass that posts SnippetEvent when intent is received. */
    public static class SnippetBroadcastReceiver extends BroadcastReceiver {
        private static final String TAG = "SnippetBroadcastReceiver";
        private final SnippetEvent mEvent;
        private final Context mContext;
        private final EventCache mEventCache;
        private final String eventName;
        private final String mAction;

        public SnippetBroadcastReceiver(
                Context context, SnippetEvent event, String eventName, String action) {
            this.eventName = eventName;
            mEvent = event;
            mContext = context;
            mEventCache = EventCache.getInstance();
            mAction = action;
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            if (mAction.equals(intent.getAction())) {
                Log.d(TAG, "Received intent: " + mAction);
                SnippetEvent event = new SnippetEvent(mEvent.getCallbackId(), eventName);
                Log.d(TAG, "Posting event: " + event);
                mEventCache.postEvent(event);
                mContext.unregisterReceiver(this);
            }
        }
    }
    ;
}
