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

import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_STATUS_QUO;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_TURN_TAKING;
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.npu.IScheduling;
import android.hardware.npu.SchedulingConfig;
import android.npumanager.IModelLoadCallback;
import android.npumanager.INpuManagerService;
import android.npumanager.ModelLoadRequest;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Process;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.content.PackageMonitor;
import com.android.npumanager.Flags;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

@SystemService(Context.NPU_SERVICE)
public final class NpuManagerServiceImpl extends INpuManagerService.Stub
        implements ActivityManager.OnUidImportanceListener {
    private static final String TAG = "NpuManagerService";
    @NonNull private final Context mContext;
    private final HashMap<String, Integer> mNpuPackages = new HashMap<>();
    private NpuModelLoadingPolicy mNpuModelLoadingPolicy;
    @Nullable private IScheduling mScheduling;

    public NpuManagerServiceImpl(@NonNull Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context must not be null");
        }
        mNpuModelLoadingPolicy = new StatusQuoModelLoadingPolicy();
        mContext = context;
        if (!Flags.npumanagerEnabled()) {
            return;
        }
        PackageManager pm = context.getPackageManager();
        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_CONFIGURATIONS);
        for (PackageInfo packageInfo : packages) {
            if (doesPackageUseNpuFeature(packageInfo)) {
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                if (appInfo != null) {
                    mNpuPackages.put(packageInfo.packageName, appInfo.uid);
                }
            }
        }

        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        int[] uids =
                Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                        .mapToInt(Integer::intValue)
                        .toArray();
        activityManager.addOnUidImportanceListener(this, 0, uids);
        List<RunningAppProcessInfo> processes = activityManager.getRunningAppProcesses();
        if (processes == null) {
            return;
        }
        ArrayList<SchedulingConfig> configs = new ArrayList<>();
        for (RunningAppProcessInfo process : processes) {
            if (mNpuPackages.containsValue(process.uid)) {
                SchedulingConfig config = new SchedulingConfig();
                config.priority = process.importance;
                config.uid = process.uid;
                config.hasDirectAccess = true;
                config.canAttributeOtherUid = canUidAttributeOtherUid(process.uid);
                configs.add(config);
            }
        }
        ensureHalService();
        try {
            if (mScheduling != null) {
                mScheduling.setSchedulingConfigs(
                        configs.toArray(new SchedulingConfig[configs.size()]));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update scheduling configs", e);
        }

        NpuPackageMonitor npuPackageMonitor = new NpuPackageMonitor();
        npuPackageMonitor.register(context, UserHandle.ALL, context.getMainThreadHandler());
    }

    private void updateUidListener() {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        int[] uids =
                Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                        .mapToInt(Integer::intValue)
                        .toArray();
        activityManager.removeOnUidImportanceListener(this);
        activityManager.addOnUidImportanceListener(this, 0, uids);
    }

    private class NpuPackageMonitor extends PackageMonitor {

        NpuPackageMonitor() {
            super(true);
        }

        @Override
        public void onPackageAdded(String packageName, int uid) {
            try {
                PackageManager pm = mContext.getPackageManager();
                PackageInfo packageInfo =
                        pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS);
                if (doesPackageUseNpuFeature(packageInfo)) {
                    mNpuPackages.put(packageName, uid);
                    updateUidListener();
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Package not found after onPackageAdded: " + packageName, e);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            if (mNpuPackages.containsKey(packageName)) {
                mNpuPackages.remove(packageName);
                updateUidListener();
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            try {
                PackageManager pm = mContext.getPackageManager();
                PackageInfo packageInfo =
                        pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS);
                boolean hasNpuFeature = doesPackageUseNpuFeature(packageInfo);
                boolean wasInMap = mNpuPackages.containsKey(packageName);

                if (wasInMap && !hasNpuFeature) {
                    mNpuPackages.remove(packageName);
                    updateUidListener();
                } else if (!wasInMap && hasNpuFeature) {
                    ApplicationInfo appInfo = packageInfo.applicationInfo;
                    if (appInfo != null) {
                        mNpuPackages.put(packageName, appInfo.uid);
                        updateUidListener();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Package not found after onPackageModified: " + packageName, e);
            }
        }
    }

    boolean doesPackageUseNpuFeature(PackageInfo packageInfo) {
        if (!Flags.npumanagerEnabled()) {
            return false;
        }
        if (packageInfo.reqFeatures == null) return false;
        for (FeatureInfo featureInfo : packageInfo.reqFeatures) {
            if (PackageManager.FEATURE_NEURAL_PROCESSING_UNIT.equals(featureInfo.name)) {
                return true;
            }
        }
        return false;
    }

    private void ensureHalService() {
        if (mScheduling != null) {
            return;
        }
        IBinder binder = ServiceManager.waitForDeclaredService(IScheduling.DESCRIPTOR + "/default");
        mScheduling = IScheduling.Stub.asInterface(binder);
        if (mScheduling == null) {
            throw new IllegalStateException("Failed to get IScheduling service");
        }
    }

    @Override
    public void onUidImportance(int uid, int importance) {
        Log.d(
                TAG,
                "onUidImportance: uid="
                        + uid
                        + ", importance="
                        + importance
                        + ", packages="
                        + Arrays.toString(mContext.getPackageManager().getPackagesForUid(uid)));

        mNpuModelLoadingPolicy.onUidImportance(uid, importance);

        ensureHalService();
        SchedulingConfig[] configs = new SchedulingConfig[1];
        configs[0] = new SchedulingConfig();
        configs[0].priority = importance;
        configs[0].uid = uid;
        configs[0].hasDirectAccess = true;
        configs[0].canAttributeOtherUid = canUidAttributeOtherUid(uid);
        try {
            if (mScheduling != null) {
                mScheduling.updateSchedulingConfigs(configs);
            }
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to update scheduling configs", e);
        }
    }

    private boolean canUidAttributeOtherUid(int uid) {
        return uid == Process.SYSTEM_UID || uid == Process.ROOT_UID;
    }

    static void enforceModelManagerPermissions(Context context) {
        if (UserHandle.getAppId(Binder.getCallingUid()) == SYSTEM_UID) {
            return;
        }

        if (context.checkCallingPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Model Manager permission denied");
        }
    }

    /** Callback will be called when it is advisable to load the model. */
    @Override
    @PermissionManuallyEnforced
    public void canLoadModel(ModelLoadRequest request, IModelLoadCallback callback) {
        Log.d(TAG, "canLoadModel: request=" + request);
        mNpuModelLoadingPolicy.canLoadModel(request, callback);
    }

    /** Cancel the request to load the model. */
    @Override
    @PermissionManuallyEnforced
    public void cancelModelLoad(ModelLoadRequest request) {
        Log.d(TAG, "cancelModelLoad: request=" + request);
        mNpuModelLoadingPolicy.handleModelLoadCancelled(request);
    }

    /** Inform the system that the model for the request has been loaded. */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelLoaded(ModelLoadRequest request) {
        Log.d(TAG, "notifyModelLoaded: request=" + request);
        mNpuModelLoadingPolicy.handleModelLoaded(request);
    }

    /**
     * Inform the system that the model has been unloaded. Callback should be provided to match with
     * previous calls to notifyModelLoaded.
     */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelUnloaded(ModelLoadRequest request) {
        Log.d(TAG, "notifyModelUnloaded: request=" + request);
        mNpuModelLoadingPolicy.handleModelUnloaded(request);
    }

    /** Set the model loading policy. */
    @Override
    @PermissionManuallyEnforced
    public void setPolicy(int policy, Bundle policyParams) {
        Log.d(TAG, "setPolicy: policy=" + policy);
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy =
                switch (policy) {
                    case NPU_MODEL_POLICY_STATUS_QUO -> new StatusQuoModelLoadingPolicy();
                    case NPU_MODEL_POLICY_TURN_TAKING -> new TurnTakingModelLoadingPolicy();
                    default -> throw new IllegalArgumentException("Unsupported policy: " + policy);
                };
    }
}
