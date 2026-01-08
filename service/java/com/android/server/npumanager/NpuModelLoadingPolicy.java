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


import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.hardware.npu.EndReason;
import android.hardware.npu.WorkInfo;
import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** A policy for determining when a model can be loaded. */
abstract class NpuModelLoadingPolicy {
    private static final String TAG = "NpuModelLoadingPolicy";

    protected PriorityManager mPriorityManager;

    NpuModelLoadingPolicy(PriorityManager priorityManager) {
        mPriorityManager = priorityManager;
    }

    /**
     * Callback will be called when it is advisable to load the model.
     *
     * @param request Request options for loading models.
     * @param callback The callback to be called when it is advisable to load the model.
     */
    abstract void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback);

    /**
     * Called when a model load is cancelled.
     *
     * @param request The request that was passed to {@link canLoadModel}.
     */
    abstract void handleModelLoadCancelled(ModelLoadRequest request);

    /**
     * Called when a model is loaded.
     *
     * @param request The request that was passed to {@link canLoadModel}.
     */
    abstract void handleModelLoaded(ModelLoadRequest request);

    /**
     * Called when a model is unloaded.
     *
     * @param request The request that was passed to {@link canLoadModel}.
     */
    abstract void handleModelUnloaded(ModelLoadRequest request);

    /**
     * Called when the HAL notifies the service work has completed.
     *
     * @param workInfo The work info associated with this work.
     * @param reason The reason why the work ended.
     */
    abstract void handleWorkEnded(WorkInfo workInfo, @EndReason byte reason);

    /**
     * Called when doing a dump (via dumpsys) of the service. Generally this should print
     * information related to the policy (configuration, etc).
     *
     * <p>See {@link android.os.IBinder#dump(FileDescriptor, String[])}
     *
     * @param context the Context from the service being dumped
     * @param pw a PrintWriter for easily printing text into the dump
     * @param args a String[] of args, may be null or empty
     */
    abstract void dump(@NonNull Context context, @NonNull PrintWriter pw, @Nullable String[] args);

    /**
     * Inform the policy of a change in UID priority.
     *
     * @param uid The uid that has changed priority.
     * @param priority The new priority value.
     */
    final void onUidPriority(int uid, int priority) {
        onUidPriorityInternal(uid, priority);
    }

    /**
     * Called when a UID priority changes. Should be overridden by subclasses to execute any policy
     * specific logic when UID priority changes.
     *
     * @param uid The uid that has changed priority.
     * @param priority The new priority value.
     */
    void onUidPriorityInternal(int uid, int priority) {}

    protected List<Integer> getMostImportantUids() {
        return getMostImportantUids(Map.Entry.comparingByValue());
    }

    protected List<Integer> getMostImportantUids(
            Comparator<Map.Entry<Integer, Integer>> comparator) {
        Map<Integer, Integer> priorities = mPriorityManager.createUidPriorityMap();
        return priorities.entrySet().stream()
                .sorted(comparator)
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    protected List<Integer> getLeastImportantUids() {
        return getMostImportantUids().reversed();
    }
}
