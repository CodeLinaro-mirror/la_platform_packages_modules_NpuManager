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
import static android.os.Process.SYSTEM_UID;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.app.ActivityManager.RunningAppProcessInfo;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
        List<PackageInfo> packages = pm.getInstalledPackages(0);
        for (PackageInfo packageInfo : packages) {
            if (doesPackageUseNpuFeature(packageInfo)) {
                ApplicationInfo appInfo = packageInfo.applicationInfo;
                if (appInfo != null) {
                    mNpuPackages.put(packageInfo.packageName, appInfo.uid);
                }
                break;
            }
        }
        ActivityManager activityManager = context.getSystemService(ActivityManager.class);
        int[] uids =
                Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                        .mapToInt(Integer::intValue)
                        .toArray();
        activityManager.addOnUidImportanceListener(this, 0, uids);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        context.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
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
        Log.d(TAG, "onUidImportance: " + uid + " " + importance);
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

    BroadcastReceiver mReceiver =
            new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent != null) {
                        PackageManager pm = context.getPackageManager();

                        String action = intent.getAction();
                        String packageName = intent.getData().getSchemeSpecificPart();
                        try {
                            PackageInfo packageInfo = pm.getPackageInfo(packageName, 0);

                            boolean updateListener = false;
                            if (Intent.ACTION_PACKAGE_ADDED.equals(action)) {
                                if (doesPackageUseNpuFeature(packageInfo)) {
                                    ApplicationInfo appInfo = packageInfo.applicationInfo;
                                    if (appInfo != null) {
                                        mNpuPackages.put(packageInfo.packageName, appInfo.uid);
                                        updateListener = true;
                                    }
                                }
                            } else if (Intent.ACTION_PACKAGE_REMOVED.equals(action)) {
                                if (mNpuPackages.containsKey(packageName)) {
                                    mNpuPackages.remove(packageName);
                                    updateListener = true;
                                }
                            } else if (Intent.ACTION_PACKAGE_REPLACED.equals(action)) {
                                if (mNpuPackages.containsKey(packageName)) {
                                    if (!doesPackageUseNpuFeature(packageInfo)) {
                                        mNpuPackages.remove(packageName);
                                        updateListener = true;
                                    }
                                } else {
                                    if (doesPackageUseNpuFeature(packageInfo)) {
                                        ApplicationInfo appInfo = packageInfo.applicationInfo;
                                        if (appInfo != null) {
                                            mNpuPackages.put(packageInfo.packageName, appInfo.uid);
                                            updateListener = true;
                                        }
                                    }
                                }
                            }
                            if (updateListener) {
                                ActivityManager activityManager =
                                        context.getSystemService(ActivityManager.class);
                                int[] uids =
                                        Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                                                .mapToInt(Integer::intValue)
                                                .toArray();
                                activityManager.removeOnUidImportanceListener(
                                        NpuManagerServiceImpl.this);
                                activityManager.addOnUidImportanceListener(
                                        NpuManagerServiceImpl.this, 0, uids);
                            }

                        } catch (PackageManager.NameNotFoundException nnfe) {
                            Log.e(TAG, "package name from broadcast not found", nnfe);
                        }
                    }
                }
            };

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
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy.canLoadModel(request, callback);
    }

    /** Cancel the request to load the model. */
    @Override
    @PermissionManuallyEnforced
    public void cancelModelLoad(ModelLoadRequest request) {
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy.cancelModelLoad(request);
    }

    /** Inform the system that the model for the request has been loaded. */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelLoaded(ModelLoadRequest request) {
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy.handleModelLoaded(request);
    }

    /**
     * Inform the system that the model has been unloaded. Callback should be provided to match with
     * previous calls to notifyModelLoaded.
     */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelUnloaded(ModelLoadRequest request) {
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy.handleModelUnload(request);
    }

    /** Set the model loading policy. */
    @Override
    @PermissionManuallyEnforced
    public void setPolicy(int policy, Bundle policyParams) {
        enforceModelManagerPermissions(mContext);
        mNpuModelLoadingPolicy =
                switch (policy) {
                    case NPU_MODEL_POLICY_STATUS_QUO -> new StatusQuoModelLoadingPolicy();
                    default -> throw new IllegalArgumentException("Unsupported policy: " + policy);
                };
    }
}
