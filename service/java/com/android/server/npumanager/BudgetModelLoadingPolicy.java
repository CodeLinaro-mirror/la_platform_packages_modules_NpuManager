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
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.hardware.npu.EndReason;
import android.hardware.npu.WorkInfo;
import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A model loading policy that allows multiple models to be loaded at a time as long as they all fit
 * within a specified memory budget. When the requested loads exceed this budget, lower priority
 * UIDs will have their models unloaded.
 *
 * <p>TODO(b/462125442) Use onWorkEnded() status callback to switch between models of equal
 * priority.
 */
class BudgetModelLoadingPolicy extends NpuModelLoadingPolicy {
    private static final String TAG = "NpuBudgetPolicy";

    // TODO accept weights in policy params Bundle.
    private final Map<Integer, Integer> mModelSizeWeights =
            Map.of(
                    NPU_MODEL_SIZE_LESS_THAN_1GB, 1,
                    NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB, 2,
                    NPU_MODEL_SIZE_GREATER_THAN_2G, 4);

    // By default budget one large model
    // TODO accept this budget in policy params Bundle.
    private final int MAX_BUDGET =
            mModelSizeWeights.getOrDefault(NPU_MODEL_SIZE_GREATER_THAN_2G, 4);

    @GuardedBy("this")
    private final Map<ModelLoadRequest, ModelLoadRequestInfo> mRequests = new HashMap<>();

    @GuardedBy("this")
    Map<Integer, Set<ModelLoadRequest>> mUidsToRequests = new HashMap<>();

    BudgetModelLoadingPolicy(Map<Integer, Integer> initialUidImportances) {
        super(initialUidImportances);
    }

    @Override
    void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback) {
        int callingUid = Binder.getCallingUid();
        Log.d(TAG, "canLoadModel: request=" + request + ", callingUid=" + callingUid);
        Map<Integer, Set<ModelLoadRequest>> uidsToRequests;
        Map<ModelLoadRequest, ModelLoadRequestInfo> requests;

        synchronized (this) {
            mRequests.put(
                    request,
                    new ModelLoadRequestInfo(
                            request,
                            callingUid,
                            callback,
                            ModelLoadRequestInfo.RequestState.PENDING));
            requests = mRequests;
            mUidsToRequests.computeIfAbsent(callingUid, k -> new HashSet<>()).add(request);
            uidsToRequests = mUidsToRequests;
        }
        // Budget that has been requested or loaded, excluding the model request we're currently
        // processing.
        int requestedAndLoadedBudget =
                requests.keySet().stream()
                        .filter(x -> request.getId() != x.getId())
                        .mapToInt(
                                modelLoadRequest ->
                                        getModelWeightFromSizeOrThrow(modelLoadRequest.getSize()))
                        .sum();
        int availableBudget = MAX_BUDGET - requestedAndLoadedBudget;

        try {
            if (availableBudget >= getModelWeightFromSizeOrThrow(request.getSize())) {
                Log.d(TAG, "canLoadModel: CAN_LOAD_NOW");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                return;
            }

            // Go through all loaded requests in order of least important UIDs, and start unloading
            // models or cancelling requests until we have the necessary budget.
            int neededBudget = getModelWeightFromSizeOrThrow(request.getSize()) - availableBudget;
            Set<ModelLoadRequest> requestsToCancelOrUnload = new HashSet<>();
            for (int uid : getLeastImportantUids()) {
                // Don't attempt to unload models more important than the caller.
                if (getUidImportance(uid) <= getUidImportance(callingUid)) {
                    break;
                }
                for (ModelLoadRequest r : uidsToRequests.getOrDefault(uid, new HashSet<>())) {
                    // If model is already loaded, unload it. Otherwise, cancel the request.
                    requestsToCancelOrUnload.add(r);

                    neededBudget -= getModelWeightFromSizeOrThrow(r.getSize());
                    if (neededBudget <= 0) {
                        break;
                    }
                }
                if (neededBudget <= 0) {
                    break;
                }
            }

            // If we found the budget, ask the models to unload or cancel the requests, otherwise
            // the new request is not prioritized.
            if (neededBudget <= 0) {
                for (ModelLoadRequest r : requestsToCancelOrUnload) {
                    ModelLoadRequestInfo modelRequestInfo = mRequests.get(r);
                    if (modelRequestInfo == null || modelRequestInfo.getCallback() == null) {
                        Log.w(TAG, "No callback for request " + r);
                        continue;
                    }
                    IModelLoadCallback cb = modelRequestInfo.getCallback();
                    synchronized (this) {
                        if (modelRequestInfo.getState()
                                == ModelLoadRequestInfo.RequestState.LOADED) {
                            cb.onRequestUnloadModel(r);
                        } else {
                            Log.w(TAG, "Cancelling pending model r: " + r);
                            handleModelLoadCancelled(r);
                        }
                    }
                }

                Log.d(TAG, "canLoadModel: WAIT_FOR_UNLOAD");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD);
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
        try {
            IModelLoadCallback callback =
                    mRequests.get(request) != null ? mRequests.get(request).getCallback() : null;
            if (callback != null) {
                callback.onModelLoadRequestComplete(
                        request, NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED);
            }
        } catch (RemoteException e) {
            // Ignore
        }

        removeRequest(request);
    }

    @Override
    void handleModelLoaded(ModelLoadRequest request) {
        Log.d(TAG, "handleModelLoaded: request=" + request);
        // TODO consider throwing an error if this model should not load.
        synchronized (this) {
            ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
            if (modelLoadRequestInfo == null) {
                return;
            }
            modelLoadRequestInfo.setState(ModelLoadRequestInfo.RequestState.LOADED);
        }
    }

    /**
     * Called when a model is unloaded.
     *
     * <p>If there are UIDs waiting to load models, the highest priority one will be notified that
     * it can now load.
     */
    @Override
    void handleModelUnloaded(ModelLoadRequest request) {
        // TODO consider what to do if this model wasn't loaded.
        Log.d(TAG, "handleModelUnloaded: request=" + request);
        try {
            IModelLoadCallback callback =
                    mRequests.get(request) != null ? mRequests.get(request).getCallback() : null;
            if (callback != null) {
                callback.onModelLoadRequestComplete(
                        request, NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE);
            }
        } catch (RemoteException e) {
            // ignore
        }

        removeRequest(request);
        evaluateAndLoadHighestPriorityModels();
    }

    private void removeRequest(ModelLoadRequest request) {
        synchronized (this) {
            mRequests.remove(request);

            Iterator<Map.Entry<Integer, Set<ModelLoadRequest>>> iterator =
                    mUidsToRequests.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Set<ModelLoadRequest>> entry = iterator.next();
                Set<ModelLoadRequest> uidRequests = entry.getValue();
                if (uidRequests.remove(request)) {
                    Log.d(
                            TAG,
                            "Removed request " + request.getId() + " from UID " + entry.getKey());
                    if (uidRequests.isEmpty()) {
                        iterator.remove();
                        Log.d(TAG, "Removed empty UID " + entry.getKey() + " from mUidsToRequests");
                    }
                    break;
                }
            }
        }
    }

    @Override
    void onUidImportanceInternal(int uid, int importance) {
        synchronized (this) {
            evaluateAndLoadHighestPriorityModels();
        }
    }

    @Override
    void handleWorkEnded(WorkInfo workInfo, @EndReason byte reason) {}

    private synchronized void evaluateAndLoadHighestPriorityModels() {
        Log.d(TAG, "Evaluating highest priority models");
        List<Integer> sortedUids = getMostImportantUids();

        // Determine ideal requests that should be loaded.
        int budget = MAX_BUDGET;
        Set<ModelLoadRequest> idealRequests =
                new HashSet<>(); // The models that should ideally be loaded;
        for (int uid : sortedUids) {
            for (ModelLoadRequest request : mUidsToRequests.getOrDefault(uid, new HashSet<>())) {
                int weight = getModelWeightFromSizeOrThrow(request.getSize());
                if (weight <= budget) {
                    idealRequests.add(request);
                    budget -= weight;
                }
                if (budget <= 0) {
                    break;
                }
            }
            if (budget <= 0) {
                break;
            }
        }

        // Determine which requests need to be unloaded or cancelled.
        Set<ModelLoadRequest> requestsToUnload = new HashSet<>();
        Set<ModelLoadRequest> requestsToCancel = new HashSet<>();

        for (ModelLoadRequest request : mRequests.keySet()) {
            ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
            if (!idealRequests.contains(request)) {
                IModelLoadCallback cb = modelLoadRequestInfo.getCallback();
                if (cb != null) {
                    if (modelLoadRequestInfo.getState()
                            == ModelLoadRequestInfo.RequestState.LOADED) {
                        Log.d(
                                TAG,
                                "Requesting unload request="
                                        + request
                                        + ", ModelRequestInfo="
                                        + modelLoadRequestInfo);
                        requestsToUnload.add(request);
                    } else {
                        Log.w(
                                TAG,
                                "Cancelling request="
                                        + request
                                        + ", ModelRequestInfo="
                                        + modelLoadRequestInfo);
                        requestsToCancel.add(request);
                    }
                } else {
                    Log.w(TAG, "No callback for request " + request);
                }
            }
        }

        // Call unload
        try {
            for (ModelLoadRequest request : requestsToUnload) {
                ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
                if (modelLoadRequestInfo != null) {
                    IModelLoadCallback cb = modelLoadRequestInfo.getCallback();
                    if (cb != null) {
                        Log.d(
                                TAG,
                                "Requesting unload request="
                                        + request
                                        + ", ModelRequestInfo="
                                        + modelLoadRequestInfo);
                        cb.onRequestUnloadModel(request);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onRequestUnloadModel", e);
        }

        // Call cancel
        for (ModelLoadRequest request : requestsToCancel) {
            ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
            Log.w(
                    TAG,
                    "Cancelling request=" + request + ", ModelRequestInfo=" + modelLoadRequestInfo);
            handleModelLoadCancelled(request);
        }

        // Tell models that should be loaded, they can load. If there are models being unloaded,
        // they should wait for unload, otherwise they can load immediately.
        int statusForIdealModels =
                requestsToUnload.isEmpty()
                        ? NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW
                        : NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
        try {
            for (ModelLoadRequest request : idealRequests) {
                ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
                if (modelLoadRequestInfo == null) {
                    Log.w(TAG, "No model info for request=" + request);
                    continue;
                }
                if (modelLoadRequestInfo.getState() != ModelLoadRequestInfo.RequestState.LOADED) {
                    Log.d(
                            TAG,
                            String.format(
                                    "%s for request=%s",
                                    requestsToUnload.isEmpty() ? "CAN_LOAD_NOW" : "WAIT_FOR_UNLOAD",
                                    request.toString()));
                    IModelLoadCallback cb = modelLoadRequestInfo.getCallback();
                    if (cb != null) {
                        cb.onCanLoadModel(request, statusForIdealModels);
                    } else {
                        Log.w(TAG, "No callback for request " + request);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onCanLoadModel", e);
        }
    }

    private Integer getModelWeightFromSizeOrThrow(Integer size) throws IllegalArgumentException {
        if (!mModelSizeWeights.containsKey(size)) {
            throw new IllegalArgumentException("Invalid model size: " + size);
        }
        return mModelSizeWeights.get(size);
    }
}
