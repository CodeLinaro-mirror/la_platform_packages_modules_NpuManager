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

import android.npumanager.IModelLoadCallback;
import android.npumanager.ModelLoadRequest;
import android.os.RemoteException;
import com.android.internal.annotations.GuardedBy;
import java.util.HashMap;

/**
 * A model loading policy that mimics the behavior prior to the introduction of the NpuModelManager.
 */
class StatusQuoModelLoadingPolicy extends NpuModelLoadingPolicy {
    @GuardedBy("this")
    private HashMap<ModelLoadRequest, IModelLoadCallback> mCallbacks = new HashMap<>();

    /** Callback will be called when it is advisable to load the model. */
    void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback) {
        try {
            synchronized (this) {
                mCallbacks.put(request, callback);
            }
            callback.onCanLoadModel(request, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
        } catch (RemoteException e) {
            // ignore
        }
    }

    void cancelModelLoad(ModelLoadRequest request) {
        try {
            IModelLoadCallback callback;
            synchronized (this) {
                callback = mCallbacks.get(request);
            }
            if (callback != null) {
                callback.onModelLoadRequestComplete(
                        request, NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED);
                synchronized (this) {
                    mCallbacks.remove(request);
                }
            }
        } catch (RemoteException e) {
            // ignore
        }
    }

    /** Inform the system that a model of size sizeMB has been loaded. */
    void handleModelLoaded(ModelLoadRequest request) {}

    /** Inform the system that a model of sizeMB has been unloaded. */
    void handleModelUnload(ModelLoadRequest request) {
        try {
            IModelLoadCallback callback;
            synchronized (this) {
                callback = mCallbacks.get(request);
            }
            if (callback != null) {
                callback.onModelLoadRequestComplete(
                        request, NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE);
                synchronized (this) {
                    mCallbacks.remove(request);
                }
            }
        } catch (RemoteException e) {
            // ignore
        }
    }
}
