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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresPermission;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.content.Context;
import android.npumanager.aidl.INpuManagerService;
import android.os.Bundle;
import android.os.RemoteException;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * NpuManager provides access to NPU related services.
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
            mListener = new ModelLoadStatusListener(this);
        }

        /**
         * The app can load the model with the specified size.
         *
         * @param sizeMB The size of the model to load in megabytes.
         * @param status The status of the model load.
         * @hide
         */
        @RequiresNoPermission
        public void onCanLoadModel(int sizeMB, @NpuModelLoadStatus int status) {
            mCallback.onCanLoadModel(sizeMB, status, mListener);
        }

        /**
         * The app should unload the model to free at least sizeMB.
         *
         * @param sizeMB The size of the model to unload in megabytes.
         * @hide
         */
        @RequiresNoPermission
        public void onRequestUnloadModel(int sizeMB) {
            mCallback.onRequestUnloadModel(sizeMB, mListener);
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
            prefix = {"NPU_MODEL_SIZE_"},
            value = {
                NPU_MODEL_SIZE_LESS_THAN_1GB,
                NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB,
                NPU_MODEL_SIZE_GREATER_THAN_2G,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelSize {}

    /**
     * A small model that is one that is less than 1GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_LESS_THAN_1GB = 0;

    /**
     * A medium model that is one that is between 1GB and 2GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB = 1;

    /**
     * A large model that is one that is greater than 2GB in size.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_SIZE_GREATER_THAN_2G = 2;

    /** @hide */
    @SystemApi
    @IntDef(
            prefix = {"NPU_MODEL_PRIORITY_"},
            value = {
                NPU_MODEL_PRIORITY_NORMAL,
                NPU_MODEL_PRIORITY_BACKGROUND,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelPriority {}

    /**
     * Normal priority models are loaded at a higher priority than background priority models.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_PRIORITY_NORMAL = 0;

    /**
     * Background priority models are loaded at a lower priority than normal priority models.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_PRIORITY_BACKGROUND = 1000;

    /** @hide */
    @SystemApi
    @IntDef(
            prefix = {"NPU_MODEL_POLICY_"},
            value = {
                NPU_MODEL_POLICY_STATUS_QUO,
            })
    @Retention(RetentionPolicy.SOURCE)
    @interface NpuModelPolicy {}

    /**
     * A model loading policy that mimics the behavior prior to the introduction of the NpuManager.
     *
     * @hide
     */
    @SystemApi public static final int NPU_MODEL_POLICY_STATUS_QUO = 0;

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

    private void enforceModelManagerPermissions() {
        if (mContext.checkSelfPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Model Manager permission denied");
        }
    }

    /**
     * Check if the model of the specified size can be loaded.
     *
     * @param size The size of the model to load.
     * @param priority The priority of the model to load.
     * @param callback The callback to be called when it is advisable to load the model and
     *     intermediary status updates when it is not yet advisable to load the model.
     * @hide
     */
    @SystemApi
    @RequiresPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
    public void requestLoadModel(
            @NpuModelSize int size,
            @NpuModelPriority int priority,
            @NonNull ModelLoadRequestCallback callback)
            throws RemoteException {
        enforceModelManagerPermissions();
        mNpuManagerService.canLoadModel(size, priority, getWrapperForCallback(callback));
    }

    public class ModelLoadStatusListener {
        IModelLoadCallback.Stub mCallback;

        ModelLoadStatusListener(IModelLoadCallback.Stub callback) {
            mCallback = callback;
        }

        /**
         * Inform the system that a model of size {@code size} has been loaded.
         *
         * @param size The size of the model to load.
         * @param callback The callback to be called when it is advisable to load the model.
         * @hide
         */
        @SystemApi
        @PermissionManuallyEnforced
        public void notifyModelLoaded(@NpuModelSize int size) throws RemoteException {
            enforceModelManagerPermissions();
            mNpuManagerService.notifyModelLoaded(size, mCallback);
        }

        /**
         * Inform the system that a model of size {@code size} has been unloaded.
         *
         * @param size The size of the model to unload.
         * @param callback The callback to be called when it is advisable to load the model.
         * @hide
         */
        @SystemApi
        @PermissionManuallyEnforced
        public void notifyModelUnloaded(@NpuModelSize int size) throws RemoteException {
            enforceModelManagerPermissions();
            mNpuManagerService.notifyModelUnloaded(size, mCallback);
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
        enforceModelManagerPermissions();
        mNpuManagerService.setPolicy(policy, policyParams);
    }
}
