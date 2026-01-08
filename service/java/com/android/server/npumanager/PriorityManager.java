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

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.hardware.npu.IScheduling;
import android.hardware.npu.SchedulingConfig;
import android.hardware.npu.WorkInfo;
import android.os.Build;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.content.PackageMonitor;
import com.android.internal.util.ArrayUtils;
import com.android.npumanager.Flags;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@SystemService(Context.NPU_SERVICE)
public final class PriorityManager implements ActivityManager.OnUidImportanceListener {
    private static final String TAG = "PriorityManager";
    private static final int SYSTEM_UID_PRIORITY = 100;
    private static final int ROOT_UID_PRIORITY = 100;

    @NonNull private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private @Nullable IScheduling mScheduling;

    @GuardedBy("mLock")
    private final Map<Integer, PriorityInfo> mPriorities = new HashMap<>();

    @GuardedBy("mLock")
    private final Set<PriorityChangeListener> mChangeListeners = new HashSet<>();

    private record PriorityInfo(SchedulingConfig mSchedulingConfig, boolean mIsStatic) {
        int getUid() {
            return mSchedulingConfig.uid;
        }

        boolean isStatic() {
            return mIsStatic;
        }

        SchedulingConfig getSchedulingConfig() {
            return mSchedulingConfig;
        }
    }

    public PriorityManager(@NonNull Context context) {
        mContext = Objects.requireNonNull(context);
    }

    public void setScheduling(@Nullable IScheduling scheduling) {
        synchronized (mLock) {
            mScheduling = scheduling;
            if (mScheduling != null) {
                setInitialPriorities();
            }
        }
    }

    public void start() {
        SchedulingConfig systemConfig = new SchedulingConfig();
        systemConfig.uid = Process.SYSTEM_UID;
        systemConfig.priority = SYSTEM_UID_PRIORITY;
        systemConfig.hasDirectAccess = true;
        systemConfig.canAttributeOtherUid = true;

        SchedulingConfig rootConfig = new SchedulingConfig();
        rootConfig.priority = ROOT_UID_PRIORITY;
        rootConfig.uid = Process.ROOT_UID;
        rootConfig.hasDirectAccess = true;
        rootConfig.canAttributeOtherUid = true;

        synchronized (mLock) {
            mPriorities.put(Process.SYSTEM_UID, new PriorityInfo(systemConfig, true));
            mPriorities.put(Process.ROOT_UID, new PriorityInfo(rootConfig, true));
        }

        NpuPackageMonitor npuPackageMonitor = new NpuPackageMonitor();
        npuPackageMonitor.register(mContext, UserHandle.ALL, mContext.getMainThreadHandler());
    }

    public void dump(@NonNull PrintWriter pw, @Nullable String[] args) {
        pw.println("Priorities:");
        synchronized (mLock) {
            mPriorities.values().stream()
                    .sorted(Comparator.comparingInt(a -> a.getSchedulingConfig().priority))
                    .forEach(
                            info -> {
                                pw.println(
                                        String.format(
                                                "    %d [%s] %d",
                                                info.getUid(),
                                                DumpUtils.getPackageNameForUid(
                                                        mContext, info.getUid()),
                                                info.getSchedulingConfig().priority));
                            });
        }
    }

    public void handleWorkRequested(WorkInfo workInfo) {
        maybePrioritizeLegacyApp(workInfo.originalUid >= 0 ? workInfo.originalUid : workInfo.uid);
    }

    public Map<Integer, Integer> createUidPriorityMap() {
        synchronized (mLock) {
            HashMap<Integer, Integer> result = new HashMap<>();

            for (PriorityInfo info : mPriorities.values()) {
                result.put(info.getUid(), info.getSchedulingConfig().priority);
            }

            return result;
        }
    }

    public int getPriorityForUid(int uid) {
        synchronized (mLock) {
            if (!mPriorities.containsKey(uid)) {
                // Unknown uids get the lowest (highest value) priority
                return SchedulingConfig.MAX_PRIORITY;
            }
            return mPriorities.get(uid).getSchedulingConfig().priority;
        }
    }

    public void addPriorityChangeListener(PriorityChangeListener listener) {
        synchronized (mLock) {
            mChangeListeners.add(listener);
        }
    }

    public void removePriorityChangeListener(PriorityChangeListener listener) {
        synchronized (mLock) {
            mChangeListeners.remove(listener);
        }
    }

    private PriorityInfo createPriorityInfo(@NonNull PackageInfo packageInfo, int priority) {
        Objects.requireNonNull(packageInfo);
        Objects.requireNonNull(packageInfo.applicationInfo);

        SchedulingConfig schedulingConfig = new SchedulingConfig();
        schedulingConfig.uid = packageInfo.applicationInfo.uid;
        schedulingConfig.priority = priority;
        schedulingConfig.canAttributeOtherUid = canAttributeOtherUid(packageInfo);
        schedulingConfig.hasDirectAccess = true;

        boolean isStatic = false;

        if (packageInfo.applicationInfo.targetSdkVersion >= Build.VERSION_CODES.CINNAMON_BUN
                && !doesPackageUseNpuFeature(packageInfo)) {
            if (Flags.npumanagerBlockMissingFeature()) {
                Log.w(
                        TAG,
                        packageInfo.packageName
                                + " does not declare "
                                + Context.FEATURE_FLAGS_SERVICE
                                + ". NPU access is blocked.");
            } else {
                // TODO(b/474056678) log metrics when we block an app
                Log.w(
                        TAG,
                        packageInfo.packageName
                                + " does not declare "
                                + Context.FEATURE_FLAGS_SERVICE
                                + " and will soon be blocked from NPU access in Android 17.");
            }
        }

        return new PriorityInfo(schedulingConfig, isStatic);
    }

    private boolean canAttributeOtherUid(PackageInfo packageInfo) {
        return mContext.getPackageManager()
                        .checkPermission(
                                packageInfo.packageName,
                                Manifest.permission.ATTRIBUTE_WORK_TO_OTHER_APPS)
                == PackageManager.PERMISSION_GRANTED;
    }

    @GuardedBy("mLock")
    private void notifyPriorityChange(int uid, int priority) {
        Set<PriorityChangeListener> listeners;

        synchronized (mLock) {
            listeners = new HashSet<>(mChangeListeners);
        }

        for (PriorityChangeListener listener : listeners) {
            listener.onPriorityChanged(uid, priority);
        }
    }

    @GuardedBy("mLock")
    private void setInitialPriorities() {
        if (mScheduling == null) {
            return;
        }

        Trace.beginSection("NpuManager_PriorityManager#setInitialPriorities");
        PackageManager pm = mContext.getPackageManager();
        ActivityManager am = mContext.getSystemService(ActivityManager.class);
        List<PackageInfo> packages = pm.getInstalledPackages(PackageManager.GET_CONFIGURATIONS);

        for (PackageInfo packageInfo : packages) {
            if (doesPackageUseNpuFeature(packageInfo)) {
                int uid = Objects.requireNonNull(packageInfo.applicationInfo).uid;
                PriorityInfo info =
                        createPriorityInfo(
                                packageInfo,
                                getPriorityForImportance(uid, am.getUidImportance(uid)));
                mPriorities.put(uid, info);
            }
        }

        updateUidListener();

        SchedulingConfig[] schedulingConfigs =
                mPriorities.values().stream()
                        .map(PriorityInfo::getSchedulingConfig)
                        .toArray(SchedulingConfig[]::new);

        try {
            mScheduling.setSchedulingConfigs(schedulingConfigs);
        } catch (RemoteException e) {
            Log.e(TAG, "Failed to set scheduling configs", e);
        }
        Trace.endSection();
    }

    private @Nullable PackageInfo getPackageInfoForUid(int uid) {
        PackageManager packageManager = mContext.getPackageManager();
        try {
            String[] names = packageManager.getPackagesForUid(uid);
            if (ArrayUtils.isEmpty(names)) {
                Log.w(TAG, "No package found for uid " + uid);
                return null;
            }
            String name = names[0];
            return packageManager.getPackageInfo(name, PackageManager.GET_CONFIGURATIONS);
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Failed to find package info for uid " + uid);
            return null;
        }
    }

    private void maybePrioritizeLegacyApp(int uid) {
        synchronized (mLock) {
            if (mPriorities.containsKey(uid)) {
                return;
            }
        }

        PackageInfo packageInfo = getPackageInfoForUid(uid);
        if (packageInfo == null) {
            return;
        }

        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);
        int priority = getPriorityForImportance(uid, activityManager.getUidImportance(uid));

        PriorityInfo priorityInfo = createPriorityInfo(packageInfo, priority);
        if (!priorityInfo.getSchedulingConfig().hasDirectAccess) {
            Log.d(
                    TAG,
                    "Blocking "
                            + packageInfo.packageName
                            + " because it does not declare "
                            + PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);
        }

        synchronized (mLock) {
            if (mPriorities.put(uid, priorityInfo) == null && !priorityInfo.isStatic()) {
                Log.d(TAG, "Adding " + packageInfo.packageName + " to list of monitored apps");
                updateUidListener();
            }

            updateSchedulingConfig(priorityInfo.getSchedulingConfig());
        }
    }

    @GuardedBy("mLock")
    private void updateSchedulingConfig(SchedulingConfig config) {
        try {
            if (mScheduling != null) {
                mScheduling.updateSchedulingConfigs(new SchedulingConfig[] {config});
            } else {
                Log.w(TAG, "No HAL connection, not updating scheduling config");
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Failed to update scheduling config for " + config.uid, e);
        }
    }

    @GuardedBy("mLock")
    private void updateUidListener() {
        ActivityManager activityManager = mContext.getSystemService(ActivityManager.class);

        int[] uids =
                mPriorities.values().stream()
                        .filter(info -> !info.isStatic())
                        .mapToInt(PriorityInfo::getUid)
                        .toArray();

        try {
            activityManager.removeOnUidImportanceListener(this);
        } catch (IllegalArgumentException e) {
            // Intentionally ignored. This is thrown if the listener is not registered.
        }
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
                    synchronized (mLock) {
                        if (mPriorities.containsKey(uid)) {
                            return;
                        }

                        mPriorities.put(
                                uid,
                                createPriorityInfo(packageInfo, SchedulingConfig.MAX_PRIORITY));
                        updateUidListener();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Package not found after onPackageAdded: " + packageName, e);
            }
        }

        @Override
        public void onPackageRemoved(String packageName, int uid) {
            synchronized (mLock) {
                if (mPriorities.containsKey(uid) && !mPriorities.get(uid).isStatic()) {
                    mPriorities.remove(uid);
                    updateUidListener();
                }
            }
        }

        @Override
        public void onPackageModified(String packageName) {
            try {
                PackageManager pm = mContext.getPackageManager();
                PackageInfo packageInfo =
                        pm.getPackageInfo(packageName, PackageManager.GET_CONFIGURATIONS);
                if (packageInfo.applicationInfo == null) {
                    Log.w(TAG, "No application info for " + packageName);
                    return;
                }
                int uid = packageInfo.applicationInfo.uid;

                synchronized (mLock) {
                    int initialPriority = SchedulingConfig.MAX_PRIORITY;
                    if (mPriorities.containsKey(uid)) {
                        PriorityInfo currentInfo = mPriorities.get(uid);
                        if (currentInfo.isStatic()) {
                            return;
                        }

                        initialPriority = currentInfo.getSchedulingConfig().priority;
                    }

                    PriorityInfo newInfo = createPriorityInfo(packageInfo, initialPriority);
                    if (mPriorities.put(uid, newInfo) == null) {
                        updateUidListener();
                    }
                }
            } catch (PackageManager.NameNotFoundException e) {
                Log.e(TAG, "Package not found after onPackageModified: " + packageName, e);
            }
        }
    }

    static boolean doesPackageUseNpuFeature(PackageInfo packageInfo) {
        if (packageInfo.reqFeatures == null) return false;
        for (FeatureInfo featureInfo : packageInfo.reqFeatures) {
            if (PackageManager.FEATURE_NEURAL_PROCESSING_UNIT.equals(featureInfo.name)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("UnusedVariable") // We will probably use uid, just not today
    private int getPriorityForImportance(int uid, int importance) {
        return importance;
    }

    @Override
    public void onUidImportance(int uid, int importance) {
        Trace.beginSection("NpuManager_PriorityManager#onUidImportance");
        Log.d(
                TAG,
                "onUidImportance: uid="
                        + uid
                        + ", importance="
                        + importance
                        + ", packages="
                        + Arrays.toString(mContext.getPackageManager().getPackagesForUid(uid)));

        int priority = getPriorityForImportance(uid, importance);

        PriorityInfo priorityInfo;
        synchronized (mLock) {
            priorityInfo = mPriorities.get(uid);
            if (priorityInfo == null) {
                Trace.endSection();
                return;
            }

            priorityInfo.getSchedulingConfig().priority = priority;
        }

        updateSchedulingConfig(priorityInfo.getSchedulingConfig());
        notifyPriorityChange(uid, priority);
        Trace.endSection();
    }

    public interface PriorityChangeListener {
        void onPriorityChanged(int uid, int priority);
    }
}
