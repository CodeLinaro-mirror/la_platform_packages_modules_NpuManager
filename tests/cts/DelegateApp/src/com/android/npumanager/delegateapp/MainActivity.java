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

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public class MainActivity extends Activity {
    private static final String TAG = "NnapiDelegateAppSample";
    private static final String TEST_TFLITE_MODEL = "mobilenet_v2_224_100.tflite";
    private static final String INPUT_DATA_FILE = "panda.ndarray";
    public static final String ACTION_INFERENCE_COMPLETE = "com.android.npumanager.delegateapp."
            + "ACTION_INFERENCE_COMPLETE";


    private byte[] modelBuffer = null;

    private byte[] inputBuffer = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        try {
            modelBuffer = loadAssetToByteArray(getAssets(), TEST_TFLITE_MODEL);

            inputBuffer = loadAssetToByteArray(getAssets(), INPUT_DATA_FILE);
            Log.i(TAG, "Input data " + INPUT_DATA_FILE +
                    " loaded (" + inputBuffer.length + " bytes).");

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model", e);
        }
    }

    public void runNpuInference() {
        if (modelBuffer != null && inputBuffer != null) {
            Log.w(TAG, "Running inference....");
            long startTimeMs = System.currentTimeMillis();
            // Run in background thread to avoid blocking UI
            new Thread(() -> {
                final String result = runInference(modelBuffer, inputBuffer);
                long finishedTimeMs = System.currentTimeMillis();
                long elapsedTimeMs = finishedTimeMs - startTimeMs;
                Log.w(TAG, "Finished running inferences. " +
                        (getPackageName().contains("foreground") ? "foreground" : "background") +
                        "Start Time Ms: " + startTimeMs + ", finishedTimeMs: " + finishedTimeMs +
                        ", elapsedTimeMs: " + elapsedTimeMs + ", result " + result);
                Intent intent = new Intent(ACTION_INFERENCE_COMPLETE);
                intent.putExtra("timestamp", finishedTimeMs);
                intent.putExtra("package", getPackageName());
                Log.i(
                        TAG,
                        "Sending broadcast: "
                                + intent
                                + " with timestamp: "
                                + intent.getLongExtra("timestamp", 0));
                sendBroadcast(intent);
            }).start();
        } else {
            Log.e(TAG, "Model not loaded");
        }
    }

    // Helper method that converts a file asset to byte array.
    private byte[] loadAssetToByteArray(AssetManager assetManager, String fileName)
            throws IOException {
        try (InputStream inputStream = assetManager.open(fileName);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            int len;
            while ((len = inputStream.read(buffer)) > -1) {
                baos.write(buffer, 0, len);
            }
            baos.flush();
            return baos.toByteArray();
        }
    }

    static {
        System.loadLibrary("nnapidelegateappjni");
    }

    // Native method to run inference
    public native String runInference(byte[] modelBuffer, byte[] inputBuffer);
}