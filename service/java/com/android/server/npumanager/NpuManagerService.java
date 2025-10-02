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

import com.android.server.SystemService;
import com.android.npumanager.Flags;

import android.annotation.FlaggedApi;
import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.FeatureInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

/**
 */
public class NpuManagerService extends SystemService
        implements ActivityManager.OnUidImportanceListener{
    final String TAG = "NpuManagerService";
    private HashMap<String, Integer> mNpuPackages = new HashMap<>();
    NpuManagerService(Context context) {
        super(context);
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
        int[] uids = Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                .mapToInt(Integer::intValue)
                .toArray();
        activityManager.addOnUidImportanceListener(this,0, uids);
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REMOVED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED);
        context.registerReceiver(mReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
    }

    @FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    boolean doesPackageUseNpuFeature(PackageInfo packageInfo) {
        for (FeatureInfo featureInfo : packageInfo.reqFeatures) {
            if (PackageManager.FEATURE_NEURAL_PROCESSING_UNIT.equals(featureInfo.name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onUidImportance(int uid, int importance) {
        Log.d(TAG, "onUidImportance: " + uid + " " + importance);
    }

    @Override
    public void onStart() {

    }
    BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null) {
                PackageManager pm = context.getPackageManager();

                String action = intent.getAction();
                String packageName = intent.getDataString(); // Get the package URI
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
                        ActivityManager activityManager = context.getSystemService(
                                ActivityManager.class);
                        int[] uids = Arrays.stream(mNpuPackages.values().toArray(new Integer[0]))
                                .mapToInt(Integer::intValue)
                                .toArray();
                        activityManager.removeOnUidImportanceListener(NpuManagerService.this);
                        activityManager.addOnUidImportanceListener(NpuManagerService.this, 0, uids);
                    }

                } catch (PackageManager.NameNotFoundException nnfe) {
                    Log.e(TAG, "package name from broadcast not found", nnfe);
                }
            }
        }
    };
}
