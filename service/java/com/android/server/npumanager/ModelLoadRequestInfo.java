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
import android.npumanager.ModelLoadRequest;

/** Stores information about a ModelLoadRequest. */
public class ModelLoadRequestInfo {

    public enum RequestState {
        // Model load request has been received, but model has not yet been loaded.
        PENDING_LOAD,
        // Model is now loaded.
        LOADED,
        // The model is not prioritized for loading.
        NOT_PRIORITIZED
    }

    private final ModelLoadRequest request;
    private final int uid;
    private final IModelLoadCallback callback;
    private RequestState state;

    /**
     * Constructor for ModelLoadRequestInfo.
     *
     * @param request The request that this information is about.
     * @param uid The UID of the process that made the request.
     * @param callback The callback associated with the request.
     * @param state The state to set the request to.
     */
    public ModelLoadRequestInfo(
            ModelLoadRequest request, int uid, IModelLoadCallback callback, RequestState state) {
        this.request = request;
        this.uid = uid;
        this.callback = callback;
        this.state = state;
    }

    @Override
    public String toString() {
        return "ModelRequestInfo{" + "uid=" + uid + ", state=" + state + "}";
    }

    /** Gets the current state of the model request. */
    public RequestState getState() {
        return state;
    }

    /**
     * Sets the current state of the model request.
     *
     * @param newState The new state.
     */
    public void setState(RequestState newState) {
        if (this.state != newState) {
            this.state = newState;
        }
    }

    /** Gets the UID of the request. */
    public int getUid() {
        return uid;
    }

    /** Gets the callback. */
    public IModelLoadCallback getCallback() {
        return callback;
    }

    /** Gets the request this information is about. */
    public ModelLoadRequest getRequest() {
        return request;
    }
}
