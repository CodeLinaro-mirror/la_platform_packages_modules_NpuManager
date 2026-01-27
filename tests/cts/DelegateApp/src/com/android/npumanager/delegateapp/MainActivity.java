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
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "NnapiDelegateAppSample";
    private static final String TEST_TFLITE_MODEL = "mobilenet_v2_224_100.tflite";
    private static final String INPUT_DATA_FILE = "panda.ndarray";
    public static final String ACTION_INFERENCE_COMPLETE = "com.android.npumanager.delegateapp."
            + "ACTION_INFERENCE_COMPLETE";

    private NpuManager npuManager;
    private byte[] modelBuffer = null;

    private byte[] inputBuffer = null;
    private Button loadModelButton;
    private Button unloadModelButton;
    private Button cancelModelLoadButton;
    private TextView textView;

    public ModelLoadRequestCallback callback =
            new ModelLoadRequestCallback() {
                private NpuManager.ModelLoadStatusListener mListener = null;

                @Override
                public void onCanLoadModel(
                        ModelLoadRequest request,
                        int status,
                        NpuManager.ModelLoadStatusListener listener) {
                    mListener = listener;

                    if (status == NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                        Log.w(TAG, "NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW - loading model now");
                        loadModel();
                        Log.i(
                                TAG,
                                "Finished loading model. Calling notifyModelLoaded() now "
                                        + "and running NPU inference");
                        if (mListener != null) {
                            try {
                                mListener.notifyModelLoaded(request);
                            } catch (RemoteException e) {
                                Log.e(TAG, "RemoteException created");
                            }
                        }

                        runNpuInference();
                    } else {
                        Log.w(
                                TAG,
                                "Status is not NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW. "
                                        + "Not loading");
                    }
                }

                @Override
                public void onRequestUnloadModel(ModelLoadRequest request) {
                    unloadModel();
                    if (mListener != null) {
                        try {
                            mListener.notifyModelUnloaded(request);
                        } catch (RemoteException e) {
                            Log.e(TAG, "RemoteException created");
                        }
                    }
                }

                @Override
                public void onModelLoadRequestComplete(ModelLoadRequest request, int status) {
                    if (status == NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED) {
                        Log.w(TAG, "Model load request cancelled");
                    } else if (status == NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE) {
                        Log.w(TAG, "Model load request complete");
                    }
                }
            };

    public void loadModel() {
        textView.setText("Loading Model...");
        try {
            modelBuffer = loadAssetToByteArray(getAssets(), TEST_TFLITE_MODEL);

            inputBuffer = loadAssetToByteArray(getAssets(), INPUT_DATA_FILE);
            Log.i(TAG, "Input data " + INPUT_DATA_FILE +
                    " loaded (" + inputBuffer.length + " bytes).");

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model", e);
            textView.setText("Failed to load model: " + e.toString());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        npuManager = getApplicationContext().getSystemService(NpuManager.class);
        loadModelButton = findViewById(R.id.load_model_btn);
        unloadModelButton = findViewById(R.id.unload_model_btn);
        textView = findViewById(R.id.text_view_status);
        cancelModelLoadButton = findViewById(R.id.cancel_model_load_btn);

        if (npuManager != null) {
            Log.w(TAG, "NpuManager not null.");
            textView.setText(
                    "NpuManager is available. Use buttons below to trigger " + "callbacks");
        } else {
            Log.w(TAG, "NpuManager null. ");
            textView.setText("NpuManager is null. Make sure NPU is enabled " + "for this device");
            loadModelButton.setEnabled(false);
            unloadModelButton.setEnabled(false);
        }
        ModelLoadRequest request =
                new ModelLoadRequest.Builder(54)
                        .setSize(NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB)
                        .setPriority(NpuManager.NPU_MODEL_PRIORITY_NORMAL)
                        .build();
        loadModelButton.setOnClickListener(
                v -> {
                    try {
                        if (npuManager != null) {
                            Log.w(TAG, "Requesting to load the model now");
                            textView.setText("Request loading model now");
                            npuManager.requestCanLoadModel(
                                    request, callback, Executors.newSingleThreadExecutor());
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "Remote exception: " + e);
                    }
                });

        cancelModelLoadButton.setOnClickListener(
                v -> {
                    try {
                        if (npuManager != null) {
                            Log.w(TAG, "Requesting to load the model now");
                            npuManager.cancelModelLoad(request);
                        }
                    } catch (RemoteException e) {
                        Log.w(TAG, "Remote exception: " + e);
                    }
                    ;
                });

        unloadModelButton.setOnClickListener(
                v -> {
                    Log.i(TAG, "Unload Model button clicked.");
                    callback.onRequestUnloadModel(request);
                });
    }

    // Release buffers.
    public void unloadModel() {
        modelBuffer = null;
        inputBuffer = null;
        textView.setText("Model successfully unloaded");
    }

    public void runNpuInference() {
        if (modelBuffer != null && inputBuffer != null) {
            Log.w(TAG, "Running inference....");
            long startTimeMs = System.currentTimeMillis();
            // Run in background thread to avoid blocking UI
            new Thread(
                            () -> {
                                final String result = runInference(modelBuffer, inputBuffer);
                                long finishedTimeMs = System.currentTimeMillis();
                                long elapsedTimeMs = finishedTimeMs - startTimeMs;
                                Log.w(
                                        TAG,
                                        "Finished running inferences. "
                                                + (getPackageName().contains("foreground")
                                                        ? "foreground"
                                                        : "background")
                                                + "Start Time Ms: "
                                                + startTimeMs
                                                + ", finishedTimeMs: "
                                                + finishedTimeMs
                                                + ", elapsedTimeMs: "
                                                + elapsedTimeMs
                                                + ", result "
                                                + result);
                                textView.setText("Finished running inference");
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
                            })
                    .start();
        } else {
            textView.setText("Model not loaded");
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