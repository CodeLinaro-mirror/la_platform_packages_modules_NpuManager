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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_GONE;

import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;

import java.util.HashMap;
import java.util.Map;

/** A policy for determining when a model can be loaded. */
abstract class NpuModelLoadingPolicy {
    private static final String TAG = "NpuModelLoadingPolicy";
    protected Map<Integer, Integer> mUidImportanceMap = new HashMap<>();

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
     * Inform the policy of a change in UID importance.
     *
     * @param uid The uid that has changed importance.
     * @param importance The new importance value.
     */
    final void onUidImportance(int uid, int importance) {
        mUidImportanceMap.put(uid, importance);
        onUidImportanceInternal(uid, importance);
    }

    /**
     * Called when a UID importance changes. Should be overridden by subclasses to execute any
     * policy specific logic when UID priority changes.
     *
     * @param uid The uid that has changed importance.
     * @param importance The new importance value.
     */
    void onUidImportanceInternal(int uid, int importance) {}

    protected int getUidImportance(int uid) {
        return mUidImportanceMap.getOrDefault(uid, IMPORTANCE_GONE);
    }
}
