/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.npumanager.delegateapp;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

import com.google.android.mobly.snippet.Snippet;
import com.google.android.mobly.snippet.event.EventCache;
import com.google.android.mobly.snippet.event.SnippetEvent;
import com.google.android.mobly.snippet.rpc.AsyncRpc;
import com.google.android.mobly.snippet.rpc.Rpc;

/** DelegateSnippet is a Mobly Snippet class that provides RPCs for the DelegateApp. */
public class DelegateSnippet implements Snippet {

    private static MainActivity activity;
    private static final String TAG = "DelegateSnippet";
    private final Context context;

    public DelegateSnippet() {
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

    @Rpc(description = "Request can load model")
    public void requestCanLoadModel(boolean useNnapi) {
        if (activity != null) {
            activity.requestLoadModel(useNnapi);
        }
    }

    @AsyncRpc(description = "Wait for indication that the inference is complete")
    public void asyncWaitForAppResume(String callbackId, String eventName) {
        context.registerReceiver(
                new SnippetBroadcastReceiver(
                        context,
                        new SnippetEvent(callbackId, eventName),
                        eventName,
                        MainActivity.ACTION_ON_RESUME),
                new IntentFilter(MainActivity.ACTION_ON_RESUME),
                Context.RECEIVER_EXPORTED);
    }

    @Rpc(description = "Launches the NPU delegate app.")
    public void startActivity() {
        Intent intent = new Intent(context, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClassName(context, MainActivity.class.getName());

        activity =
            (MainActivity) InstrumentationRegistry.getInstrumentation().startActivitySync(intent);
    }

    /** Closes emulator activity */
    @Rpc(description = "Close activity if one was opened.")
    public void closeActivity() {
        if (activity != null) {
            activity.finish();
        }
    }

    @Rpc(description = "Triggers the runInference function in MainActivity.")
    public void runNpuInference() {
        new Thread(
                () -> {
                    if (activity != null) {
                        Log.i(TAG, "MainActivity instance found, posting runNpuInference to "
                                + "main thread.");
                        try {
                            activity.runNpuInference();
                            Log.i(TAG, "runNpuInference execution posted.");

                        } catch (Exception e) {
                            Log.e(TAG, "Exception while running runNpuInference", e);
                        }
                    } else {
                        Log.w(TAG, "runNpuInference failed: MainActivity instance is null.");
                    }
                })
                .start();
    }

    @Rpc(description = "Check if run inference tool exists")
    public boolean checkRunInferenceExists() {
        if (activity != null) {
            return activity.checkRunInferenceExists();
        }
        return false;
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

                String packageName = intent.getStringExtra("package");
                String intendedPackage =
                        mEvent.getName().contains("background")
                                ? "com.android.npumanager.delegateapp"
                                : "com.android.npumanager.delegateapp.foreground";

                if (packageName == null || !packageName.equals(intendedPackage)) {
                    Log.d(TAG, "Received intent from package: that is incorrect. return");
                    return;
                }

                SnippetEvent event = new SnippetEvent(mEvent.getCallbackId(), eventName);
                long timestamp = intent.getLongExtra("timestamp", 0);
                if (timestamp != 0) {
                    event.getData().putLong("timestamp", timestamp);
                }

                Log.d(TAG, "Posting event: " + event);
                mEventCache.postEvent(event);

                mContext.unregisterReceiver(this);
            }
        }
    }
    ;
}