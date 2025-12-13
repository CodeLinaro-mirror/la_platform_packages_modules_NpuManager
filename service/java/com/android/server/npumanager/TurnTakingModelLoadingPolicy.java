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

package com.android.server.npumanager;

import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;

import android.annotation.Nullable;
import android.hardware.npu.EndReason;
import android.hardware.npu.WorkInfo;
import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A model loading policy that allows one UID to have a model loaded at a time. UIDs with higher
 * importance (lower value) will preempt lower importance UIDs.
 *
 * <p>TODO(b/462125442) Use onWorkEnded() status callback to switch between models of equal
 * priority.
 */
class TurnTakingModelLoadingPolicy extends NpuModelLoadingPolicy {
    private static final String TAG = "NpuTurnTakingPolicy";
    private final int INVALID_UID = -1;

    @GuardedBy("this")
    private int mLoadedUid = INVALID_UID;

    @GuardedBy("this")
    @Nullable
    private ModelLoadRequest mLoadedRequest = null;

    @GuardedBy("this")
    @Nullable
    private IModelLoadCallback mLoadedCallback = null;

    @GuardedBy("this")
    private final Map<ModelLoadRequest, Integer> mRequestsToUids = new HashMap<>();

    @GuardedBy("this")
    private final Map<ModelLoadRequest, IModelLoadCallback> mRequestsToCallbacks = new HashMap<>();

    @GuardedBy("this")
    private final Set<ModelLoadRequest> mWaitingRequests = new HashSet<>();

    TurnTakingModelLoadingPolicy(Map<Integer, Integer> initialUidImportances) {
        super(initialUidImportances);
    }

    class BinderDeathRecipientUid implements IBinder.DeathRecipient {
        private final int callingUid;

        BinderDeathRecipientUid(int uid) {
            this.callingUid = uid;
        }

        @Override
        public void binderDied() {
            Log.d(TAG, "Binder died for callingUid: " + callingUid);

            // If the uid's model is currently loaded, unload it.
            if (mLoadedUid == callingUid && mLoadedRequest != null) {
                handleModelUnloaded(mLoadedRequest);
            }
            Set<ModelLoadRequest> requestsForCallingUid =
                    mRequestsToUids.entrySet().stream()
                            .filter((it) -> it.getValue() == callingUid)
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toSet());

            for (ModelLoadRequest request : requestsForCallingUid) {
                mRequestsToUids.remove(request);
                mRequestsToCallbacks.remove(request);
            }
        }
    }

    @Override
    void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback) {
        Log.d(TAG, "canLoadModel: request=" + request);
        int callingUid = Binder.getCallingUid();
        IModelLoadCallback loadedCallback;
        ModelLoadRequest loadedRequest;
        int loadedUid;

        synchronized (this) {
            mRequestsToCallbacks.put(request, callback);
            mRequestsToUids.put(request, callingUid);
            loadedUid = mLoadedUid;
            loadedCallback = mLoadedCallback;
            loadedRequest = mLoadedRequest;
        }

        try {
            callback.asBinder().linkToDeath(new BinderDeathRecipientUid(callingUid), 0);

            // Either nothing is loaded, or this UID already loaded a model.
            if (loadedUid == INVALID_UID || loadedUid == callingUid) {
                Log.d(TAG, "canLoadModel: CAN_LOAD_NOW");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                return;
            }

            // Model needs to wait.
            // If this is an important UID, we will ask the loaded model to unload.
            synchronized (this) {
                mWaitingRequests.add(request);
            }

            if (getUidImportance(callingUid) < getUidImportance(loadedUid)) {
                Log.d(TAG, "canLoadModel: WAIT_FOR_UNLOAD");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD);
                if (loadedCallback != null) {
                    loadedCallback.onRequestUnloadModel(loadedRequest);
                }
            } else {
                Log.d(TAG, "canLoadModel: NOT_PRIORITIZED");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onCanLoadModel", e);
        }
    }

    @Override
    void handleModelLoadCancelled(ModelLoadRequest request) {
        Log.d(TAG, "handleModelLoadCancelled: request=" + request);
        synchronized (this) {
            try {
                IModelLoadCallback callback = mRequestsToCallbacks.get(request);
                if (callback != null) {
                    callback.onModelLoadRequestComplete(
                            request, NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED);
                    mRequestsToCallbacks.remove(request);
                    mWaitingRequests.remove(request);
                    mRequestsToUids.remove(request);
                }
            } catch (RemoteException e) {
                // ignore
            }
        }
    }

    @Override
    void handleModelLoaded(ModelLoadRequest request) {
        Log.d(TAG, "handleModelLoaded: request=" + request);
        // TODO consider throwing an error if this model should not load.
        synchronized (this) {
            mLoadedUid = mRequestsToUids.getOrDefault(request, INVALID_UID);
            mLoadedRequest = request;
            mLoadedCallback = mRequestsToCallbacks.get(request);
            mWaitingRequests.remove(request);
        }
    }

    /**
     * Called when a model is loaded.
     *
     * <p>If there are UIDs waiting to load models, the highest priority one will be notified that
     * it can now load.
     */
    @Override
    void handleModelUnloaded(ModelLoadRequest request) {
        // TODO consider what to do if this model wasn't loaded.
        Log.d(TAG, "handleModelUnloaded: request=" + request);
        Optional<Integer> highestPriorityUid;
        synchronized (this) {
            mLoadedUid = INVALID_UID;
            mLoadedRequest = null;
            mLoadedCallback = null;
            try {
                IModelLoadCallback callback = mRequestsToCallbacks.get(request);
                if (callback != null) {
                    callback.onModelLoadRequestComplete(
                            request, NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE);
                }
                mRequestsToCallbacks.remove(request);
                mWaitingRequests.remove(request);
                mRequestsToUids.remove(request);
            } catch (RemoteException e) {
                // ignore
            }
            highestPriorityUid = getHighestPriorityWaitingUid();
        }

        highestPriorityUid.ifPresent(
                uid -> callOnCanLoadCallbackForUid(uid, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW));
    }

    @Override
    void onUidImportanceInternal(int uid, int importance) {
        IModelLoadCallback loadedCallback;
        ModelLoadRequest loadedRequest;
        Optional<Integer> highestPriorityUid;

        synchronized (this) {
            // Nothing is loaded, so no callbacks need to be made.
            if (mLoadedUid == INVALID_UID) {
                return;
            }

            highestPriorityUid = getHighestPriorityWaitingUid();

            // Either nothing is waiting, or the highest priority UID is already loaded. Do nothing.
            if (highestPriorityUid.isEmpty() || highestPriorityUid.get() == mLoadedUid) {
                return;
            }
            loadedCallback = mLoadedCallback;
            loadedRequest = mLoadedRequest;
        }

        // Otherwise tell the loaded UID to unload, and the now highest is waiting for an unload.
        try {
            if (loadedCallback != null) {
                loadedCallback.onRequestUnloadModel(loadedRequest);
            }
            // TODO Decide which request should get called here if UID has multiple.
            callOnCanLoadCallbackForUid(uid, NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onRequestUnloadModel", e);
        }
    }

    @Override
    void handleWorkEnded(WorkInfo workInfo, @EndReason byte reason) {}

    @GuardedBy("this")
    private Optional<Integer> getHighestPriorityWaitingUid() {
        return mRequestsToUids.entrySet().stream()
                .filter(entry -> mWaitingRequests.contains(entry.getKey()))
                .map(Map.Entry::getValue)
                .min(Comparator.comparing(this::getUidImportance));
    }

    private void callOnCanLoadCallbackForUid(Integer uid, int status) {
        Optional<Map.Entry<ModelLoadRequest, IModelLoadCallback>> entry;

        synchronized (this) {
            entry =
                    mRequestsToCallbacks.keySet().stream()
                            .filter(
                                    k ->
                                            mRequestsToUids.containsKey(k)
                                                    && Integer.valueOf(uid)
                                                            .equals(mRequestsToUids.get(k)))
                            .findFirst()
                            .map(k -> Map.entry(k, mRequestsToCallbacks.get(k)));
        }

        entry.ifPresent(
                e -> {
                    try {
                        e.getValue().onCanLoadModel(e.getKey(), status);
                    } catch (RemoteException ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }
}
