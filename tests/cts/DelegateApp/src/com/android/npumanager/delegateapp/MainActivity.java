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

import static android.os.Process.myPid;
import static android.os.Process.myUid;

import android.app.Activity;
import android.content.Intent;
import android.content.res.AssetManager;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String TAG = "NpuDelegateApp";
    // For using NNAPI to run inference.
    private static final String TEST_TFLITE_MODEL = "mobilenet_v2_224_100.tflite";
    private static final String INPUT_DATA_FILE = "panda.ndarray";
    public static final String ACTION_INFERENCE_COMPLETE = "com.android.npumanager.delegateapp."
            + "ACTION_INFERENCE_COMPLETE";
    public static final String ACTION_INFERENCE_FAILED =
            "com.android.npumanager.delegateapp." + "ACTION_INFERENCE_FAILED";

    private byte[] modelBuffer = null;

    private byte[] inputBuffer = null;
    private NpuManager npuManager;

    public static final String ACTION_ON_RESUME =
            "com.android.npumanager.delegateapp.ACTION_ON_RESUME";

    // Run NPU test inference script
    public static final String RUN_INFERENCE_TOOL_PATH =
            "/apex/com.android.hardware.npu/bin/run-test-inference";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        npuManager = getApplicationContext().getSystemService(NpuManager.class);

        if (npuManager != null) {
            Log.w(TAG, "NpuManager not null.");
        } else {
            Log.w(TAG, "NpuManager null. ");
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.w(TAG, "onResume() for : " + getPackageName());
        Intent intent = new Intent(ACTION_ON_RESUME);
        intent.putExtra("package", getPackageName());
        Log.i(
                TAG,
                "Sending broadcast: "
                        + intent
                        + " with timestamp: "
                        + intent.getLongExtra("timestamp", 0));
        sendBroadcast(intent);
    }

    public boolean checkRunInferenceExists() {
        return new File(RUN_INFERENCE_TOOL_PATH).exists();
    }

    private int runTestInference() throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(RUN_INFERENCE_TOOL_PATH + " --job-priority=1");
        return process.waitFor();
    }

    public void requestLoadModel(boolean useNnapi) {
        ModelLoadRequestCallback callback =
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
                            if (useNnapi) {
                                loadNnapiModel();
                            }
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

                            if (useNnapi) {
                                runNnapiInference();
                            } else {
                                runNpuInference();
                            }
                        } else {
                            Log.w(
                                    TAG,
                                    "Status is not NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW. "
                                            + "Not loading");
                        }
                    }

                    @Override
                    public void onRequestUnloadModel(ModelLoadRequest request) {
                        Log.w(TAG, "Received onRequestUnloadModel for id: " + request.getId());
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
                            Log.w(
                                    TAG,
                                    "Model load request complete for id: "
                                            + request.getId()
                                            + ", status: "
                                            + status);
                        }
                    }
                };

        int id = getPackageName().contains("foreground") ? 1 : 2;
        ModelLoadRequest request =
                new ModelLoadRequest.Builder(id)
                        .setSize(NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB)
                        .setPriority(NpuManager.NPU_MODEL_PRIORITY_NORMAL)
                        .build();
        try {
            npuManager.requestCanLoadModel(request, callback, Executors.newSingleThreadExecutor());
        } catch (RemoteException e) {
            Log.e(TAG, "Could not request load model: ", e);
        }
    }

    public void runNnapiInference() {
        // Run NNAPI Inference Code.
        if (modelBuffer != null && inputBuffer != null) {
            runNnapiInference(modelBuffer, inputBuffer);
            Intent intent = new Intent(ACTION_INFERENCE_COMPLETE);
            intent.putExtra("package", getPackageName());
            sendBroadcast(intent);
        }
    }

    public void runNpuInference() {
        Log.w(TAG, "Running inference.... for: " + getPackageName() + " uid=" + myUid());
        long startTimeMs = System.currentTimeMillis();
        // Run in background thread to avoid blocking UI
        new Thread(
                        () -> {
                            try {
                                int exitCode = runTestInference();
                                Log.i(
                                        TAG,
                                        "run-test-inference exited with code: "
                                                + exitCode
                                                + " for uid: "
                                                + myUid());
                                if (exitCode != 0) {
                                    Log.e(TAG, "Inference failed.");
                                    Intent intent = new Intent(ACTION_INFERENCE_FAILED);
                                    intent.putExtra("package", getPackageName());
                                    sendBroadcast(intent);
                                    return;
                                }
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
                                                + ", pid: "
                                                + myPid());
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
                            } catch (IOException | InterruptedException e) {
                                Log.e(TAG, "Error running test inference. Test might fail", e);
                            }
                        })
                .start();
    }

    public void loadNnapiModel() {
        try {
            modelBuffer = loadAssetToByteArray(getAssets(), TEST_TFLITE_MODEL);

            inputBuffer = loadAssetToByteArray(getAssets(), INPUT_DATA_FILE);
            Log.i(
                    TAG,
                    "Input data "
                            + INPUT_DATA_FILE
                            + " loaded ("
                            + inputBuffer.length
                            + " bytes).");

        } catch (IOException e) {
            Log.e(TAG, "Failed to load model", e);
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
    public native String runNnapiInference(byte[] modelBuffer, byte[] inputBuffer);
}