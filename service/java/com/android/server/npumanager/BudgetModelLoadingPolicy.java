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

import static android.npumanager.NpuManager.KEY_MAX_BUDGET;
import static android.npumanager.NpuManager.KEY_MODEL_SIZE_WEIGHTS;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.npu.EndReason;
import android.hardware.npu.WorkInfo;
import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;
import android.os.Binder;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;

import java.io.PrintWriter;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * A model loading policy that allows multiple models to be loaded at a time as long as they all fit
 * within a specified memory budget. When the requested loads exceed this budget, lower priority
 * UIDs will have their models unloaded.
 */
class BudgetModelLoadingPolicy extends NpuModelLoadingPolicy {
    private static final String TAG = "NpuBudgetPolicy";

    private static final Map<Integer, Integer> DEFAULT_MODEL_WEIGHTS =
            Map.of(
                    NPU_MODEL_SIZE_LESS_THAN_1GB, 1,
                    NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB, 2,
                    NPU_MODEL_SIZE_GREATER_THAN_2G, 4);

    private final Map<Integer, Integer> mModelSizeWeights;

    private final int mMaxBudget;

    @GuardedBy("this")
    private final Map<ModelLoadRequest, ModelLoadRequestInfo> mRequests = new HashMap<>();

    @GuardedBy("this")
    Map<Integer, Set<ModelLoadRequest>> mUidsToRequests = new HashMap<>();

    @GuardedBy("this")
    final Map<Integer, Long> mTimeUidLastCompleted = new HashMap<>();

    class BinderDeathRecipientUid implements IBinder.DeathRecipient {
        private final int callingUid;

        BinderDeathRecipientUid(int uid) {
            this.callingUid = uid;
        }

        @Override
        public synchronized void binderDied() {
            Log.d(TAG, "Binder died for callingUid: " + callingUid);

            Set<ModelLoadRequest> requestsForCallingUid = mUidsToRequests.get(callingUid);
            if (requestsForCallingUid == null) {
                return;
            }
            for (ModelLoadRequest request : requestsForCallingUid) {
                mRequests.remove(request);
            }
            mUidsToRequests.remove(callingUid);

            evaluateAndLoadHighestPriorityModels();
        }
    }

    BudgetModelLoadingPolicy(
            PriorityManager priorityManager, @Nullable PersistableBundle policyParams) {
        super(priorityManager);

        if (policyParams == null) {
            mModelSizeWeights = DEFAULT_MODEL_WEIGHTS;
            mMaxBudget = mModelSizeWeights.getOrDefault(NPU_MODEL_SIZE_GREATER_THAN_2G, 4);
            return;
        }

        Map<Integer, Integer> modelSizeWeights = new HashMap<>(DEFAULT_MODEL_WEIGHTS);
        if (policyParams.containsKey(KEY_MODEL_SIZE_WEIGHTS)) {
            PersistableBundle weightsBundle =
                    policyParams.getPersistableBundle(KEY_MODEL_SIZE_WEIGHTS);
            if (weightsBundle != null) {
                for (Integer npuModelSize : DEFAULT_MODEL_WEIGHTS.keySet()) {
                    String sizeKey = String.valueOf(npuModelSize);
                    if (weightsBundle.containsKey(sizeKey)) {
                        int weight = weightsBundle.getInt(sizeKey);
                        if (weight <= 0) {
                            throw new IllegalArgumentException(
                                    "Model weight for size "
                                            + npuModelSize
                                            + " must be a positive integer.");
                        }
                        modelSizeWeights.put(npuModelSize, weight);
                    }
                }
            }
        }
        mModelSizeWeights = Map.copyOf(modelSizeWeights);

        int maxBudget =
                policyParams.containsKey(KEY_MAX_BUDGET)
                        ? policyParams.getInt(KEY_MAX_BUDGET)
                        : mModelSizeWeights.getOrDefault(NPU_MODEL_SIZE_GREATER_THAN_2G, 4);
        if (maxBudget <= 0) {
            throw new IllegalArgumentException(
                    "Maximum budget must be a positive, non-zero integer.");
        }
        mMaxBudget = maxBudget;
    }

    BudgetModelLoadingPolicy(
            PriorityManager priorityManager,
            Map<Integer, Integer> modelSizeWeights,
            int maxBudget) {
        super(priorityManager);
        mModelSizeWeights = modelSizeWeights;
        mMaxBudget = maxBudget;
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
                            ModelLoadRequestInfo.RequestState.PENDING_LOAD));
            requests = mRequests;
            mUidsToRequests.computeIfAbsent(callingUid, k -> new HashSet<>()).add(request);
            uidsToRequests = mUidsToRequests;
        }

        // Budget that has been requested or loaded, excluding the model request we're currently
        // processing.
        int requestedAndLoadedBudget =
                requests.keySet().stream()
                        .filter(x -> request.id != x.id)
                        .mapToInt(
                                modelLoadRequest ->
                                        getModelWeightFromSizeOrThrow(modelLoadRequest.size))
                        .sum();
        int availableBudget = mMaxBudget - requestedAndLoadedBudget;

        try {
            callback.asBinder()
                    .linkToDeath(
                            new BudgetModelLoadingPolicy.BinderDeathRecipientUid(callingUid), 0);

            if (availableBudget >= getModelWeightFromSizeOrThrow(request.size)) {
                Log.d(TAG, "canLoadModel: CAN_LOAD_NOW");
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                return;
            }

            // Go through all loaded requests in order of least important UIDs, and start unloading
            // models or cancelling requests until we have the necessary budget.
            int neededBudget = getModelWeightFromSizeOrThrow(request.size) - availableBudget;
            Set<ModelLoadRequest> requestsToCancelOrUnload = new HashSet<>();
            for (int uid : getLeastImportantUids()) {
                if (uid == callingUid) {
                    continue;
                }
                int callingImportance = mPriorityManager.getPriorityForUid(callingUid);
                int otherUidImportance = mPriorityManager.getPriorityForUid(uid);

                // Don't attempt to unload models more important than the caller.
                if (callingImportance > otherUidImportance) {
                    break;
                }

                // If the importances are equal, give preference to the UID that has not completed a
                // task recently.
                if (callingImportance == otherUidImportance
                        && mTimeUidLastCompleted.getOrDefault(callingUid, 0L)
                                >= mTimeUidLastCompleted.getOrDefault(uid, 0L)) {
                    continue;
                }

                for (ModelLoadRequest r : uidsToRequests.getOrDefault(uid, new HashSet<>())) {
                    // If model is already loaded, unload it. Otherwise, cancel the request.
                    requestsToCancelOrUnload.add(r);

                    neededBudget -= getModelWeightFromSizeOrThrow(r.size);
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
                boolean unloadingModels = false;
                for (ModelLoadRequest r : requestsToCancelOrUnload) {
                    ModelLoadRequestInfo modelRequestInfo = mRequests.get(r);
                    if (modelRequestInfo == null || modelRequestInfo.getCallback() == null) {
                        Log.w(TAG, "No callback for request " + r);
                        continue;
                    }
                    synchronized (this) {
                        if (modelRequestInfo.getState()
                                == ModelLoadRequestInfo.RequestState.LOADED) {
                            requestUnloadModel(r);
                            unloadingModels = true;
                        } else if (modelRequestInfo.getState()
                                == ModelLoadRequestInfo.RequestState.PENDING_LOAD) {
                            Log.w(TAG, "Cancelling pending model r: " + r);
                            handleModelLoadCancelled(r);
                        }
                    }
                }

                if (unloadingModels) {
                    Log.d(TAG, "canLoadModel: WAIT_FOR_UNLOAD");
                    callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD);
                }
            } else {
                Log.d(TAG, "canLoadModel: NOT_PRIORITIZED");
                synchronized (this) {
                    ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
                    if (modelLoadRequestInfo != null) {
                        modelLoadRequestInfo.setState(
                                ModelLoadRequestInfo.RequestState.NOT_PRIORITIZED);
                    }
                }
                callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to call onCanLoadModel", e);
            synchronized (this) {
                ModelLoadRequestInfo modelLoadRequestInfo = mRequests.get(request);
                if (modelLoadRequestInfo != null) {
                    modelLoadRequestInfo.setState(
                            ModelLoadRequestInfo.RequestState.NOT_PRIORITIZED);
                }
            }
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
        evaluateAndLoadHighestPriorityModels();
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
                            "Removed request " + request.id + " from UID " + entry.getKey());
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
    void onUidPriorityInternal(int uid, int priority) {
        synchronized (this) {
            evaluateAndLoadHighestPriorityModels();
        }
    }

    @Override
    void handleWorkEnded(WorkInfo workInfo, @EndReason byte reason) {
        if (reason != EndReason.COMPLETED) {
            Log.d(TAG, "Work ended with reason: " + reason);
            return;
        }

        Map<Integer, Integer> priorityMap = mPriorityManager.createUidPriorityMap();

        synchronized (this) {
            int completedUid = workInfo.originalUid;
            Log.d(TAG, "Work completed for UID: " + completedUid);
            mTimeUidLastCompleted.put(completedUid, SystemClock.elapsedRealtime());
            Set<Integer> equalPriorityUids =
                    priorityMap.entrySet().stream()
                            .filter(
                                    e ->
                                            e.getKey() != completedUid
                                                    && e.getValue()
                                                            .equals(
                                                                    mPriorityManager
                                                                            .getPriorityForUid(
                                                                                    completedUid)))
                            .map(Map.Entry::getKey)
                            .collect(Collectors.toSet());
            Set<ModelLoadRequest> equalPriorityRequests =
                    mUidsToRequests.entrySet().stream()
                            .filter(e -> equalPriorityUids.contains(e.getKey()))
                            .map(Map.Entry::getValue)
                            .flatMap(Set::stream)
                            .collect(Collectors.toSet());

            // If any equal priority requests are not loaded, ask the current UID to unload all of
            // its requests.
            boolean shouldUnload = false;
            for (ModelLoadRequest request : equalPriorityRequests) {
                if (mRequests.get(request) != null
                        && mRequests.get(request).getState()
                                == ModelLoadRequestInfo.RequestState.PENDING_LOAD) {
                    shouldUnload = true;
                    break;
                }
            }

            // TODO(b/462125442) We should try and determine the last model that completed and only
            // unload that, instead of unloading all models for the UID.
            if (shouldUnload) {
                mRequests.values().stream()
                        .filter(
                                info ->
                                        info.getUid() == completedUid
                                                && info.getState()
                                                        == ModelLoadRequestInfo.RequestState.LOADED)
                        .forEach(info -> requestUnloadModel(info.getRequest()));
            }
        }
    }

    @Override
    void dump(@NonNull Context context, @NonNull PrintWriter pw, @Nullable String[] args) {
        pw.println("Policy: budget");
        dumpInternal(context, pw, args);
    }

    protected void dumpInternal(
            @NonNull Context context, @NonNull PrintWriter pw, @Nullable String[] args) {
        synchronized (this) {
            pw.println("    Max: " + mMaxBudget);
            pw.println("    Requests:");
            for (ModelLoadRequestInfo info : mRequests.values()) {
                pw.println(
                        String.format(
                                "        %d [%s]: %s",
                                info.getUid(),
                                DumpUtils.getPackageNameForUid(context, info.getUid()),
                                info.getState()));
            }
        }
    }

    private synchronized void evaluateAndLoadHighestPriorityModels() {
        Log.d(TAG, "Evaluating highest priority models");
        List<Integer> sortedUids =
                getMostImportantUids(
                        // Compares entries of UID -> Importance
                        new Comparator<Map.Entry<Integer, Integer>>() {

                            @Override
                            public int compare(
                                    Map.Entry<Integer, Integer> o1,
                                    Map.Entry<Integer, Integer> o2) {
                                if (Objects.equals(o1.getValue(), o2.getValue())) {
                                    // Lower timestamp is older, and should get higher priority.
                                    return -1
                                            * Long.compare(
                                                    mTimeUidLastCompleted.getOrDefault(
                                                            o1.getKey(), 0L),
                                                    mTimeUidLastCompleted.getOrDefault(
                                                            o2.getKey(), 0L));
                                }

                                // Higher importance is lower priority.
                                return o1.getValue().compareTo(o2.getValue());
                            }
                        });

        // Determine ideal requests that should be loaded.
        int budget = mMaxBudget;
        Set<ModelLoadRequest> idealRequests =
                new HashSet<>(); // The models that should ideally be loaded;
        for (int uid : sortedUids) {
            for (ModelLoadRequest request : mUidsToRequests.getOrDefault(uid, new HashSet<>())) {
                int weight = getModelWeightFromSizeOrThrow(request.size);
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
                    switch (modelLoadRequestInfo.getState()) {
                        case LOADED:
                            Log.d(
                                    TAG,
                                    "Requesting unload request="
                                            + request
                                            + ", ModelRequestInfo="
                                            + modelLoadRequestInfo);
                            requestsToUnload.add(request);
                            break;
                        case PENDING_LOAD:
                            Log.w(
                                    TAG,
                                    "Cancelling request="
                                            + request
                                            + ", ModelRequestInfo="
                                            + modelLoadRequestInfo);
                            requestsToCancel.add(request);
                            break;
                        case NOT_PRIORITIZED:
                        default:
                            // Do nothing.
                            break;
                    }
                } else {
                    Log.w(TAG, "No callback for request " + request);
                }
            }
        }

        // Call unload
        for (ModelLoadRequest request : requestsToUnload) {
            requestUnloadModel(request);
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

    private void requestUnloadModel(ModelLoadRequest request) {
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
                try {
                    cb.onRequestUnloadModel(request);
                } catch (RemoteException e) {
                    Log.e(TAG, "Failed to call onRequestUnloadModel", e);
                }
            } else {
                Log.w(TAG, "No callback for request " + request);
            }
        } else {
            Log.w(TAG, "No model info for request=" + request);
        }
    }

    private Integer getModelWeightFromSizeOrThrow(Integer size) throws IllegalArgumentException {
        if (!mModelSizeWeights.containsKey(size)) {
            throw new IllegalArgumentException("Invalid model size: " + size);
        }
        return mModelSizeWeights.get(size);
    }
}
