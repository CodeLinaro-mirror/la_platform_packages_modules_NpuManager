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
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class TestNpuManagerClient {
    private static final String TAG = "TestNpuManagerClient";
    private static final String SERVICE_CLASS = "android.npumanager.testapp.NpuManagerTestService";

    private final Context mContext;
    private ITestNpuManagerService mService;
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

    public TestNpuManagerClient(Context context, String packageName) {
        mContext = context;
        Intent intent = new Intent().setClassName(packageName, SERVICE_CLASS);
        mContext.bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    private void waitForConnection() {
        try {
            if (!mConnectionLatch.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Failed to connect to the service");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for service connection", e);
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

    public void setPolicy(int policy, Bundle policyParams) throws RemoteException {
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
}
