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

package android.npumanager.testapp;

import android.annotation.RequiresNoPermission;
import android.app.Service;
import android.content.Intent;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.npumanager.testing.ITestModelLoadRequestCallback;
import android.npumanager.testing.ITestModelLoadStatusListener;
import android.npumanager.testing.ITestNpuManagerService;
import android.npumanager.testing.TestModelLoadRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashSet;
import java.util.Set;

public class NpuManagerTestService extends Service {
    private static final String TAG = "NpuManagerTestService";
    private final Set<ModelLoadRequest> mRequests = new HashSet<ModelLoadRequest>();
    private NpuManager mNpuManager;

    @Override
    public void onCreate() {
        super.onCreate();
        mNpuManager = getSystemService(NpuManager.class);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private static class ModelLoadRequestCallbackWrapper implements ModelLoadRequestCallback {
        private final ITestModelLoadRequestCallback mCallback;
        private NpuManager.ModelLoadStatusListener mListener;

        ModelLoadRequestCallbackWrapper(ITestModelLoadRequestCallback callback) {
            mCallback = callback;
        }

        @Override
        public void onCanLoadModel(
                ModelLoadRequest request, int status, NpuManager.ModelLoadStatusListener listener) {
            Log.d(TAG, "onCanLoadModel: request=" + request + ", status=" + status);
            mListener = listener;

            try {
                TestModelLoadRequest testRequest =
                        new TestModelLoadRequest(
                                request.getId(), request.getSize(), request.getPriority());
                mCallback.onCanLoadModel(
                        testRequest,
                        status,
                        new ITestModelLoadStatusListener.Stub() {
                            @RequiresNoPermission
                            @Override
                            public void notifyModelLoaded(TestModelLoadRequest req) {
                                Log.d(TAG, "notifyModelLoaded: request=" + req);
                                try {
                                    mListener.notifyModelLoaded(
                                            new ModelLoadRequest.Builder(req.id)
                                                    .setSize(req.size)
                                                    .setPriority(req.priority)
                                                    .build());
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Failed to notify model loaded", e);
                                }
                            }

                            @RequiresNoPermission
                            @Override
                            public void notifyModelUnloaded(TestModelLoadRequest req) {
                                Log.d(TAG, "notifyModelUnloaded: request=" + req);
                                try {
                                    mListener.notifyModelUnloaded(
                                            new ModelLoadRequest.Builder(req.id)
                                                    .setSize(req.size)
                                                    .setPriority(req.priority)
                                                    .build());
                                } catch (RemoteException e) {
                                    Log.e(TAG, "Failed to notify model unloaded", e);
                                }
                            }
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call onCanLoadModel", e);
            }
        }

        @Override
        public void onRequestUnloadModel(ModelLoadRequest request) {
            Log.d(TAG, "onRequestUnloadModel: request=" + request);
            try {
                TestModelLoadRequest testRequest =
                        new TestModelLoadRequest(
                                request.getId(), request.getSize(), request.getPriority());
                mCallback.onRequestUnloadModel(testRequest);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call onRequestUnloadModel", e);
            }
        }

        @Override
        public void onModelLoadRequestComplete(ModelLoadRequest request, int status) {
            Log.d(TAG, "onModelLoadRequestComplete: request=" + request + ", status=" + status);
            try {
                TestModelLoadRequest testRequest =
                        new TestModelLoadRequest(
                                request.getId(), request.getSize(), request.getPriority());
                mCallback.onModelLoadRequestComplete(testRequest, status);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to call onModelLoadRequestComplete", e);
            }
        }
    }

    private final ITestNpuManagerService.Stub mBinder =
            new ITestNpuManagerService.Stub() {
                @RequiresNoPermission
                @Override
                public void requestLoadModel(
                        TestModelLoadRequest request, ITestModelLoadRequestCallback callback)
                        throws RemoteException {
                    Log.d(TAG, "requestLoadModel: request=" + request);
                    if (mNpuManager == null) {
                        Log.e(TAG, "NpuManager not available");
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    ModelLoadRequest frameworkRequest =
                            new ModelLoadRequest.Builder(request.id)
                                    .setSize(request.size)
                                    .setPriority(request.priority)
                                    .build();
                    mNpuManager.requestLoadModel(
                            frameworkRequest, new ModelLoadRequestCallbackWrapper(callback));
                    mRequests.add(frameworkRequest);
                    Binder.restoreCallingIdentity(identity);
                }

                @RequiresNoPermission
                @Override
                public void cancelLoadModel(TestModelLoadRequest request) throws RemoteException {
                    Log.d(TAG, "cancelLoadModel: request=" + request);
                    if (mNpuManager == null) {
                        Log.e(TAG, "NpuManager not available");
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    ModelLoadRequest frameworkRequest =
                            new ModelLoadRequest.Builder(request.id)
                                    .setSize(request.size)
                                    .setPriority(request.priority)
                                    .build();
                    mNpuManager.cancelModelLoad(frameworkRequest);
                    Binder.restoreCallingIdentity(identity);
                }

                @RequiresNoPermission
                @Override
                public void setPolicy(int policy, Bundle policyParams) throws RemoteException {
                    Log.d(TAG, "setPolicy: policy=" + policy);
                    if (mNpuManager == null) {
                        Log.e(TAG, "NpuManager not available");
                        return;
                    }
                    final long identity = Binder.clearCallingIdentity();
                    mNpuManager.setPolicy(policy, policyParams);
                    Binder.restoreCallingIdentity(identity);
                }

                @RequiresNoPermission
                @Override
                public void reset() throws RemoteException {
                    Log.d(TAG, "reset");
                    for (ModelLoadRequest mRequest : mRequests) {
                        mNpuManager.cancelModelLoad(mRequest);
                    }
                    mRequests.clear();
                }
            };
}
