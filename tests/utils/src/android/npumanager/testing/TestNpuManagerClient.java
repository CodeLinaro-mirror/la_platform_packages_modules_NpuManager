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

package android.npumanager.testing;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestNpuManagerClient {
    private static final String TAG = "TestNpuManagerClient";
    private static final String SERVICE_CLASS = "android.npumanager.testapp.NpuManagerTestService";
    // TODO(b/462125442) Update this to common path when available, this only exists on cuttlefish.
    private static final String RUN_INFERENCE_TOOL_PATH =
            "/apex/com.android.hardware.npu/bin/run-test-inference";
    private static final int MAX_ATTEMPTS = 3;

    private final Context mContext;
    private ITestNpuManagerService mService;
    private final Intent mIntent;
    private final int mBindServiceFlags;
    private final CountDownLatch mConnectionLatch = new CountDownLatch(1);

    private final ServiceConnection mConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    mService = ITestNpuManagerService.Stub.asInterface(service);
                    mConnectionLatch.countDown();
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    mService = null;
                }
            };

    public TestNpuManagerClient(Context context, String packageName, int bindServiceFlags) {
        mContext = context;
        Intent intent = new Intent().setClassName(packageName, SERVICE_CLASS);
        mIntent = intent;
        mBindServiceFlags = bindServiceFlags;
        mContext.bindService(intent, mConnection, bindServiceFlags);
    }

    private void waitForConnection() {
        int attempts = 0;
        while (attempts < MAX_ATTEMPTS) {
            try {
                if (!mConnectionLatch.await(5, TimeUnit.SECONDS)) {
                    ++attempts;
                    if (attempts == MAX_ATTEMPTS) {
                        throw new IllegalStateException(
                                "Failed to connect to the service after "
                                        + MAX_ATTEMPTS
                                        + " retries");
                    }
                    mContext.bindService(mIntent, mConnection, mBindServiceFlags);
                } else {
                    // Successful binding
                    break;
                }
            } catch (InterruptedException e) {
                attempts++;
                Thread.currentThread().interrupt();
                if (attempts == MAX_ATTEMPTS) {
                    throw new IllegalStateException(
                            "Interrupted while waiting for service connection", e);
                }
                mContext.bindService(mIntent, mConnection, mBindServiceFlags);
            }
        }
    }

    public void requestLoadModel(
            TestModelLoadRequest request, ITestModelLoadRequestCallback callback)
            throws RemoteException {
        waitForConnection();
        if (mService != null) {
            mService.requestLoadModel(request, callback);
        } else {
            Log.e(TAG, "Service not connected");
        }
    }

    public void cancelLoadModel(TestModelLoadRequest request) throws RemoteException {
        waitForConnection();
        if (mService != null) {
            mService.cancelLoadModel(request);
        } else {
            Log.e(TAG, "Service not connected");
        }
    }

    public void setPolicy(int policy, PersistableBundle policyParams) throws RemoteException {
        waitForConnection();
        if (mService != null) {
            mService.setPolicy(policy, policyParams);
        } else {
            Log.e(TAG, "Service not connected");
        }
    }

    public void reset() throws RemoteException {
        waitForConnection();
        if (mService != null) {
            mService.reset();
        } else {
            Log.e(TAG, "Service not connected");
        }
    }

    public void unbind() {
        mContext.unbindService(mConnection);
    }

    public void runTestInference() throws IOException {
        waitForConnection();
        if (mService != null) {
            try {
                mService.runTestInference();
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    public boolean checkRunInferenceExists() {
        return new File(RUN_INFERENCE_TOOL_PATH).exists();
    }
}
