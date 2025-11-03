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

import android.npumanager.IModelLoadCallback;

/** A policy for determining when a model can be loaded. */
abstract class NpuModelLoadingPolicy {
    /**
     * Callback will be called when it is advisable to load the model.
     *
     * @param size The size of the model to load.
     * @param priority The priority of the model to load.
     * @param callback The callback to be called when it is advisable to load the model.
     */
    abstract void canLoadModel(int size, int priority, IModelLoadCallback callback);

    /**
     * Inform the system that a model of size sizeMB has been loaded.
     *
     * @param size The size of the model that was loaded.
     * @param callback The callback that was passed to canLoadModel.
     */
    abstract void handleModelLoaded(int size, IModelLoadCallback callback);

    /**
     * Inform the system that a model of sizeMB has been unloaded. Callback should be provided to
     * match with previous calls to notifyModelLoaded.
     *
     * @param size The size of the model that was unload.
     * @param callback The callback to be called when it is advisable to load the model.
     */
    abstract void handleModelUnload(int size, IModelLoadCallback callback);
}
