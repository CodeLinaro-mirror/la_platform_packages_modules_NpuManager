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

import android.npumanager.IModelLoadCallback;
import android.os.RemoteException;

/**
 * A model loading policy that mimics the behavior prior to the introduction of the NpuModelManager.
 */
class StatusQuoModelLoadingPolicy extends NpuModelLoadingPolicy {
    /** Callback will be called when it is advisable to load the model. */
    void canLoadModel(int size, int priority, IModelLoadCallback callback) {
        try {
            callback.onCanLoadModel(size, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
        } catch (RemoteException e) {
            // ignore
        }
    }

    /** Inform the system that a model of size sizeMB has been loaded. */
    void handleModelLoaded(int size, IModelLoadCallback callback) {}

    /** Inform the system that a model of sizeMB has been unloaded. */
    void handleModelUnload(int size, IModelLoadCallback callback) {}
}
