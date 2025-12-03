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

import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;
import android.os.Binder;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

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
    private int mAvailableBudget = MAX_BUDGET;

    @GuardedBy("this")
    private final Set<ModelLoadRequest> mLoadedRequests = new HashSet<>();

    @GuardedBy("this")
    Map<Integer, Set<ModelLoadRequest>> mUidsToRequests = new HashMap<>();

    @GuardedBy("this")
    private final Map<ModelLoadRequest, IModelLoadCallback> mRequestsToCallbacks = new HashMap<>();

    BudgetModelLoadingPolicy(Map<Integer, Integer> initialUidImportances) {
        super(initialUidImportances);
    }

    @Override
    void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback) {
        Log.d(TAG, "canLoadModel: request=" + request);
        int callingUid = Binder.getCallingUid();
        Set<ModelLoadRequest> loadedRequests;
        Map<Integer, Set<ModelLoadRequest>> uidsToRequests;
        Map<ModelLoadRequest, IModelLoadCallback> requestsToCallbacks;

        int availableBudget;

        synchronized (this) {
            mRequestsToCallbacks.put(request, callback);
            Set<ModelLoadRequest> uidRequests =
                    mUidsToRequests.getOrDefault(callingUid, new HashSet<>());
            uidRequests.add(request);
            mUidsToRequests.put(callingUid, uidRequests);
            uidsToRequests = mUidsToRequests;
            requestsToCallbacks = mRequestsToCallbacks;
            loadedRequests = new HashSet<>(mLoadedRequests);
            availableBudget = mAvailableBudget;
        }

        try {
            // If there is budget, we can load now
            // TODO(b/465268119) Since available budget doesn't get decreased until the model loads,
            // it's possible to exceed the budget if multiple models ask to load quickly. Consider
            // tracking the available budget we've told CAN_LOAD_NOW as well as the actual loaded
            // budget.
            if (availableBudget
                    >= mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE)) {
                Log.d(TAG, "canLoadModel: CAN_LOAD_NOW");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                return;
            }

            // Get a map of UIDs to only their loaded requests.
            Map<Integer, Set<ModelLoadRequest>> uidsToLoadedRequests = new HashMap<>();
            uidsToRequests
                    .entrySet()
                    .forEach(
                            e -> {
                                int uid = e.getKey();
                                Set<ModelLoadRequest> loaded =
                                        e.getValue().stream()
                                                .filter(loadedRequests::contains)
                                                .collect(Collectors.toSet());
                                uidsToLoadedRequests.put(uid, loaded);
                            });

            // Go through all loaded requests in order of least important UIDs, and start unloading
            // models until we have the necessary budget.
            int neededBudget =
                    mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE)
                            - availableBudget;
            Set<ModelLoadRequest> modelsToUnload = new HashSet<>();
            for (int uid : getLeastImportantUids()) {
                // Don't attempt to unload models more important than the caller.
                if (getUidImportance(uid) <= getUidImportance(callingUid)) {
                    break;
                }
                for (ModelLoadRequest r : uidsToLoadedRequests.getOrDefault(uid, new HashSet<>())) {
                    modelsToUnload.add(r);
                    neededBudget -= mModelSizeWeights.getOrDefault(r.getSize(), Integer.MAX_VALUE);
                    if (neededBudget <= 0) {
                        break;
                    }
                }
                if (neededBudget <= 0) {
                    break;
                }
            }

            // If we found the budget, ask the models to unload, otherwise the new request is
            // not prioritized.
            if (neededBudget <= 0) {
                for (ModelLoadRequest r : modelsToUnload) {
                    IModelLoadCallback cb = requestsToCallbacks.get(r);
                    if (cb != null) {
                        cb.onRequestUnloadModel(r);
                    } else {
                        Log.w(TAG, "No callback for request " + r);
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
        int callingUid = Binder.getCallingUid();
        synchronized (this) {
            mRequestsToCallbacks.remove(request);
            Set<ModelLoadRequest> uidRequests =
                    mUidsToRequests.getOrDefault(callingUid, new HashSet<>());
            uidRequests.remove(request);
            mUidsToRequests.put(callingUid, uidRequests);
            if (mLoadedRequests.contains(request)) {
                mLoadedRequests.remove(request);
                mAvailableBudget -=
                        mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE);
            }
        }
    }

    @Override
    void handleModelLoaded(ModelLoadRequest request) {
        Log.d(TAG, "handleModelLoaded: request=" + request);
        // TODO consider throwing an error if this model should not load.
        synchronized (this) {
            mLoadedRequests.add(request);
            mAvailableBudget -=
                    mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE);
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
        synchronized (this) {
            mLoadedRequests.remove(request);
            mAvailableBudget +=
                    mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE);
            evaluateAndLoadHighestPriorityModels();
        }
    }

    @Override
    void onUidImportanceInternal(int uid, int importance) {
        synchronized (this) {
            evaluateAndLoadHighestPriorityModels();
        }
    }

    private synchronized void evaluateAndLoadHighestPriorityModels() {
        Log.d(TAG, "Evaluating highest priority models");
        List<Integer> sortedUids = getMostImportantUids();

        // Determine ideal requests that should be loaded.
        int budget = MAX_BUDGET;
        Set<ModelLoadRequest> idealRequests =
                new HashSet<>(); // The models that should ideally be loaded;
        for (int uid : sortedUids) {
            for (ModelLoadRequest request : mUidsToRequests.getOrDefault(uid, new HashSet<>())) {
                int weight = mModelSizeWeights.getOrDefault(request.getSize(), Integer.MAX_VALUE);
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

        // Determine which requests need to be unloaded.
        Set<ModelLoadRequest> requestsToUnload = new HashSet<>();
        try {
            for (ModelLoadRequest request : mLoadedRequests) {
                if (!idealRequests.contains(request)) {
                    Log.d(TAG, "Requesting unload for request=" + request);
                    IModelLoadCallback cb = mRequestsToCallbacks.get(request);
                    if (cb != null) {
                        cb.onRequestUnloadModel(request);
                    } else {
                        Log.w(TAG, "No callback for request " + request);
                    }
                    requestsToUnload.add(request);
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onRequestUnloadModel", e);
        }

        // Tell models that should be loaded, they can load. If there are models being unloaded,
        // they should wait for unload, otherwise they can load immediately.
        int statusForIdealModels =
                requestsToUnload.isEmpty()
                        ? NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW
                        : NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
        try {
            for (ModelLoadRequest request : idealRequests) {
                if (!mLoadedRequests.contains(request)) {
                    Log.d(
                            TAG,
                            String.format(
                                    "%s for request=%s",
                                    requestsToUnload.isEmpty() ? "CAN_LOAD_NOW" : "WAIT_FOR_UNLOAD",
                                    request.toString()));
                    IModelLoadCallback cb = mRequestsToCallbacks.get(request);
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
}
