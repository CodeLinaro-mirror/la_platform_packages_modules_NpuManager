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

package android.npumanager;


import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.os.Trace;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * NpuManager provides access to NPU (Neural Processing Unit) related services.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
@SystemService(Context.NPU_SERVICE)
public final class NpuManager {

    class ModelLoadCallbackWrapper extends IModelLoadCallback.Stub {
        ModelLoadRequestCallback mCallback;
        ModelLoadStatusListener mListener;

        ModelLoadCallbackWrapper(ModelLoadRequestCallback callback) {
            mCallback = callback;
            mListener = new ModelLoadStatusListener();
        }

        /**
         * The app can load the model with the specified size.
         *
         * @param request Object containing the model load request information.
         * @param status The status of the model load.
         * @hide
         */
        @RequiresNoPermission
        public void onCanLoadModel(ModelLoadRequest request, @NpuModelLoadStatus int status) {
            mCallback.onCanLoadModel(request, status, mListener);
            Trace.endAsyncSection("NpuManager#requestLoadModel", request.id);
        }

        /**
         * The app should unload the model to free at least size.
         *
         * @param request Object containing the model load request information.
         * @hide
         */
        @RequiresNoPermission
        public void onRequestUnloadModel(ModelLoadRequest request) {
            Trace.beginAsyncSection("NpuManager#onRequestUnloadModel", request.id);
            mCallback.onRequestUnloadModel(request);
        }

        /**
         * The model request has completed either successfully or due to cancellation and there will
         * be no further status updates to the request.
         *
         * @param request The model load request that has completed.
         * @hide
         */
        @RequiresNoPermission
        public void onModelLoadRequestComplete(
                ModelLoadRequest request, @NpuModelLoadRequestStatus int status) {
            mCallback.onModelLoadRequestComplete(request, status);
            if (status == NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED) {
                Trace.endAsyncSection("NpuManager#cancelModelLoad", request.id);
            }
        }
    }

    /** @hide */
    @SystemApi
    @IntDef(
            prefix = {"NPU_MODEL_LOAD_STATUS_"},
            value = {
                NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW,
                NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD,
                NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelLoadStatus {}

    /**
     * The model can be loaded now.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW = 0;

    /**
     * The model cannot be loaded now, but the model manager will attempt to free memory such that
     * it can load the model soon.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD = 1;

    /**
     * The model cannot be loaded now because it is not prioritized.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED = 2;

    /** @hide */
    @SystemApi
    @IntDef(
            prefix = {"NPU_MODEL_LOAD_REQUEST_STATUS_"},
            value = {
                NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED,
                NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelLoadRequestStatus {}

    /**
     * The model load request has been cancelled.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED = 3;

    /**
     * The model load request has completed and the model has been unloaded.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_LOAD_REQUEST_STATUS_COMPLETE = 4;

    /**
     * A small model that is one that is less than 1GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_LESS_THAN_1GB = NpuModelSize.LESS_THAN_1GB;

    /**
     * A medium model that is one that is between 1GB and 2GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB = NpuModelSize.BETWEEN_1GB_AND_2GB;

    /**
     * A large model that is one that is greater than 2GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_GREATER_THAN_2G = NpuModelSize.GREATER_THAN_2G;

    /**
     * Normal priority models are loaded at a higher priority than background priority models.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_PRIORITY_NORMAL = ModelLoadRequest.PRIORITY_NORMAL;

    /**
     * Background priority models are loaded at a lower priority than normal priority models.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_PRIORITY_BACKGROUND = ModelLoadRequest.PRIORITY_BACKGROUND;

    /** @hide */
    @SystemApi
    @IntDef(
            prefix = {"NPU_MODEL_POLICY_"},
            value = {
                NPU_MODEL_POLICY_STATUS_QUO,
                NPU_MODEL_POLICY_TURN_TAKING,
                NPU_MODEL_POLICY_BUDGET,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelPolicy {}

    /**
     * A model loading policy that mimics the behavior prior to the introduction of the NpuManager.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_POLICY_STATUS_QUO = 0;

    /**
     * A model loading policy that allows one model to be loaded at a time.
     *
     * <p>UIDs with higher {@link ActivityManager} priority will get higher model load priority.
     * When a model is unloaded, the next highest priority UID will be allowed to load a model.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_POLICY_TURN_TAKING = 1;

    /**
     * A model loading policy that allows multiple models to be loaded at a time, up to a certain
     * budget.
     *
     * <p>UIDs with higher {@link ActivityManager} priority will get higher model load priority.
     * When a model is unloaded, the next highest priority UID will be allowed to load a model.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_POLICY_BUDGET = 2;

    private final INpuManagerService mNpuManagerService;

    private ModelLoadCallbackWrapper getWrapperForCallback(ModelLoadRequestCallback callback) {
        return new ModelLoadCallbackWrapper(callback);
    }

    private Context mContext;

    /** @hide */
    public NpuManager(@NonNull Context context, @NonNull INpuManagerService service) {
        mContext = context;
        mNpuManagerService = service;
    }

    /**
     * Check if the model of the specified size can be loaded. A request object can only be passed
     * to requestLoadModel once. The callback object can be reused for multiple requests.
     *
     * @param request The model load request.
     * @param callback The callback to be called when it is advisable to load the model and
     *     intermediary status updates when it is not yet advisable to load the model.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
    public void requestLoadModel(
            ModelLoadRequest request, @NonNull ModelLoadRequestCallback callback)
            throws RemoteException {
        Trace.beginAsyncSection("NpuManager#requestLoadModel", request.id);
        try {
            mNpuManagerService.canLoadModel(request, getWrapperForCallback(callback));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Cancel a model load request.
     *
     * @param request The model load request to cancel.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
    public void cancelModelLoad(@NonNull ModelLoadRequest request) throws RemoteException {
        Trace.beginAsyncSection("NpuManager#cancelModelLoad", request.id);
        try {
            mNpuManagerService.cancelModelLoad(request);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    public class ModelLoadStatusListener {

        ModelLoadStatusListener() {}

        /**
         * Inform the system that a model of size {@code size} has been loaded.
         *
         * @param request The model load request for which this model has been loaded.
         * @hide
         */
        @SystemApi
        @PermissionManuallyEnforced
        public void notifyModelLoaded(ModelLoadRequest request) throws RemoteException {
            try {
                mNpuManagerService.notifyModelLoaded(request);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }

        /**
         * Inform the system that a model of size {@code size} has been unloaded.
         *
         * @param request The model load request for which this model has been unloaded.
         * @hide
         */
        @SystemApi
        @PermissionManuallyEnforced
        public void notifyModelUnloaded(ModelLoadRequest request) throws RemoteException {
            try {
                mNpuManagerService.notifyModelUnloaded(request);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            Trace.endAsyncSection("NpuManager#onRequestUnloadModel", request.id);
        }
    }

    /**
     * Set the model loading policy.
     *
     * @param policy The policy to set.
     * @param policyParams The policy parameters.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
    public void setPolicy(@NpuModelPolicy int policy, Bundle policyParams) throws RemoteException {
        try {
            mNpuManagerService.setPolicy(policy, policyParams);
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }
}
