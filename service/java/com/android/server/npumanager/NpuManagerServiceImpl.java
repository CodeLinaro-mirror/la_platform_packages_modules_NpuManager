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
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_BUDGET;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_STATUS_QUO;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_TURN_TAKING;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.PermissionManuallyEnforced;
import android.annotation.RequiresNoPermission;
import android.annotation.SystemService;
import android.content.Context;
import android.hardware.npu.EndReason;
import android.hardware.npu.IScheduling;
import android.hardware.npu.ISchedulingCallback;
import android.hardware.npu.StartReason;
import android.hardware.npu.WorkInfo;
import android.npumanager.IModelLoadCallback;
import android.npumanager.INpuAllocator;
import android.npumanager.INpuAllocatorCallback;
import android.npumanager.INpuManagerService;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestParcelable;
import android.npumanager.ModelLoadRequestUtils;
import android.os.Binder;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.Trace;
import android.util.Log;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.DumpUtils;
import com.android.modules.utils.BasicShellCommandHandler;
import com.android.npumanager.Flags;
import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Objects;

@SystemService(Context.NPU_SERVICE)
public final class NpuManagerServiceImpl extends INpuManagerService.Stub {
    private static final String TAG = "NpuManagerService";

    @NonNull private final Context mContext;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private @Nullable IScheduling mScheduling;

    @GuardedBy("mLock")
    private NpuModelLoadingPolicy mNpuModelLoadingPolicy;

    @GuardedBy("mLock")
    private final PriorityManager mPriorityManager;

    private final ISchedulingCallback mSchedulingCallback =
            new ISchedulingCallback.Stub() {
                private final MetricsLogger mMetricsLogger = new MetricsLogger();

                @RequiresNoPermission
                @Override
                public void onWorkRequested(WorkInfo workInfo) {
                    Binder.clearCallingIdentity();
                    synchronized (mLock) {
                        mPriorityManager.handleWorkRequested(workInfo);
                    }
                    mMetricsLogger.logWorkRequested(workInfo);
                }

                @RequiresNoPermission
                @Override
                public void onWorkStarted(WorkInfo workInfo, @StartReason byte reason) {
                    Binder.clearCallingIdentity();
                    mMetricsLogger.logWorkStarted(workInfo, reason);
                }

                @RequiresNoPermission
                @Override
                public void onWorkEnded(WorkInfo workInfo, @EndReason byte reason) {
                    Binder.clearCallingIdentity();
                    synchronized (mLock) {
                        mNpuModelLoadingPolicy.handleWorkEnded(workInfo, reason);
                    }

                    mMetricsLogger.logWorkEnded(workInfo, reason);
                }

                @RequiresNoPermission
                @Override
                public int getInterfaceVersion() {
                    return ISchedulingCallback.VERSION;
                }

                @RequiresNoPermission
                @Override
                public String getInterfaceHash() {
                    return ISchedulingCallback.HASH;
                }
            };

    public NpuManagerServiceImpl(@NonNull Context context) {
        Trace.beginSection("NpuManagerServiceImpl#constructor");
        mContext = Objects.requireNonNull(context);

        mPriorityManager = new PriorityManager(mContext);
        mNpuModelLoadingPolicy = new StatusQuoModelLoadingPolicy(mPriorityManager);

        if (!Flags.npumanagerEnabled()) {
            return;
        }

        ensureHalService();

        mPriorityManager.addPriorityChangeListener(
                (uid, priority) -> {
                    synchronized (mLock) {
                        mNpuModelLoadingPolicy.onUidPriority(uid, priority);
                    }
                });
        mPriorityManager.start();
        mNpuModelLoadingPolicy = new StatusQuoModelLoadingPolicy(mPriorityManager);
        Trace.endSection();
    }

    private void ensureHalService() {
        synchronized (mLock) {
            if (mScheduling != null) {
                return;
            }

            IBinder binder =
                    ServiceManager.waitForDeclaredService(IScheduling.DESCRIPTOR + "/default");
            IScheduling service = IScheduling.Stub.asInterface(binder);
            if (service == null) {
                throw new IllegalStateException("Failed to get IScheduling service");
            }

            Log.d(TAG, "Connected to IScheduling");
            setSchedulingService(service);

            try {
                // This is impossible but NullAway doesn't think so
                if (mScheduling == null) {
                    throw new IllegalStateException("null scheduling service");
                }

                mScheduling.setCallback(mSchedulingCallback);
                mScheduling
                        .asBinder()
                        .linkToDeath(
                                () -> {
                                    Log.w(TAG, "Lost connection to IScheduling");
                                    synchronized (mLock) {
                                        setSchedulingService(null);
                                    }

                                    // Try to reconnect
                                    ensureHalService();
                                },
                                0);
            } catch (RemoteException e) {
                throw new IllegalStateException("Failed to setup IScheduling service");
            }
        }
    }

    @GuardedBy("mLock")
    private void setSchedulingService(@Nullable IScheduling service) {
        mScheduling = service;
        mPriorityManager.setScheduling(service);
    }

    static void enforceModelManagerPermissions(Context context) {
        if (context.checkCallingPermission(android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API)
                != PERMISSION_GRANTED) {
            throw new SecurityException("Model Manager permission denied");
        }
    }

    /** Callback will be called when it is advisable to load the model. */
    @Override
    @PermissionManuallyEnforced
    public void canLoadModel(
            ModelLoadRequestParcelable requestParcelable, IModelLoadCallback callback) {
        ModelLoadRequest request = ModelLoadRequestUtils.fromParcelable(requestParcelable);
        Log.d(TAG, "canLoadModel: request=" + request);
        mNpuModelLoadingPolicy.canLoadModel(request, callback);
    }

    /** Cancel the request to load the model. */
    @Override
    @PermissionManuallyEnforced
    public void cancelModelLoad(ModelLoadRequestParcelable requestParcelable) {
        ModelLoadRequest request = ModelLoadRequestUtils.fromParcelable(requestParcelable);
        Trace.beginSection("NpuManagerServiceImpl#cancelModelLoad");
        Log.d(TAG, "cancelModelLoad: request=" + request);
        mNpuModelLoadingPolicy.handleModelLoadCancelled(request);
        Trace.endSection();
    }

    /** Inform the system that the model for the request has been loaded. */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelLoaded(ModelLoadRequestParcelable requestParcelable) {
        ModelLoadRequest request = ModelLoadRequestUtils.fromParcelable(requestParcelable);
        Trace.beginSection("NpuManagerServiceImpl#notifyModelLoaded");
        Log.d(TAG, "notifyModelLoaded: request=" + request);
        mNpuModelLoadingPolicy.handleModelLoaded(request);
        Trace.endSection();
    }

    /**
     * Inform the system that the model has been unloaded. Callback should be provided to match with
     * previous calls to notifyModelLoaded.
     */
    @Override
    @PermissionManuallyEnforced
    public void notifyModelUnloaded(ModelLoadRequestParcelable requestParcelable) {
        ModelLoadRequest request = ModelLoadRequestUtils.fromParcelable(requestParcelable);
        Trace.beginSection("NpuManagerServiceImpl#notifyModelUnloaded");
        Log.d(TAG, "notifyModelUnloaded: request=" + request);
        mNpuModelLoadingPolicy.handleModelUnloaded(request);
        Trace.endSection();
    }

    /** Set the model loading policy. */
    @Override
    @PermissionManuallyEnforced
    public void setPolicy(int policy, @Nullable PersistableBundle policyParams) {
        Trace.beginSection("NpuManagerServiceImpl#setPolicy");
        Log.d(TAG, "setPolicy: policy=" + policy);
        enforceModelManagerPermissions(mContext);
        synchronized (mLock) {
            mNpuModelLoadingPolicy =
                    switch (policy) {
                        case NPU_MODEL_POLICY_STATUS_QUO ->
                                new StatusQuoModelLoadingPolicy(mPriorityManager);
                        case NPU_MODEL_POLICY_TURN_TAKING ->
                                new TurnTakingModelLoadingPolicy(mPriorityManager);
                        case NPU_MODEL_POLICY_BUDGET ->
                                new BudgetModelLoadingPolicy(mPriorityManager, policyParams);
                        default ->
                                throw new IllegalArgumentException("Unsupported policy: " + policy);
                    };
        }
        Trace.endSection();
    }

    @Override
    @RequiresNoPermission
    public INpuAllocator createAllocator(INpuAllocatorCallback callback) {
        return new NpuAllocator(callback);
    }

    @Override
    @PermissionManuallyEnforced
    public int handleShellCommand(
            @NonNull ParcelFileDescriptor in,
            @NonNull ParcelFileDescriptor out,
            @NonNull ParcelFileDescriptor err,
            @NonNull String[] args) {
        return new BasicShellCommandHandler() {
            @Override
            public int onCommand(String cmd) {
                switch (cmd != null ? cmd : "") {
                    case "set-status-quo-policy" -> setPolicy(NPU_MODEL_POLICY_STATUS_QUO, null);
                    case "set-budget-policy" -> setPolicy(NPU_MODEL_POLICY_BUDGET, null);
                    case "set-turn-taking-policy" -> setPolicy(NPU_MODEL_POLICY_TURN_TAKING, null);
                    case "info" -> dumpInternal(getOutPrintWriter(), null);
                    default -> {
                        handleDefaultCommands(cmd);
                        return 1;
                    }
                }
                return 0;
            }

            @Override
            public void onHelp() {
                getOutPrintWriter()
                        .println(
                                "usage: cmd npu set-status-quo-policy: use the status quo policy\n"
                                    + "usage: cmd npu set-budget-policy: use the budget policy\n"
                                    + "usage: cmd npu set-turn-taking-policy: use the turn-taking"
                                    + " policy\n"
                                    + "usage: cmd npu info: Shows the current policy, requests,"
                                    + " priorities, etc.");
            }
        }.exec(
                NpuManagerServiceImpl.this,
                in.getFileDescriptor(),
                out.getFileDescriptor(),
                err.getFileDescriptor(),
                args);
    }

    @Override
    @PermissionManuallyEnforced
    protected void dump(
            @NonNull FileDescriptor fd, @NonNull PrintWriter pw, @Nullable String[] args) {
        dumpInternal(pw, args);
    }

    private void dumpInternal(PrintWriter pw, @Nullable String[] args) {
        if (!DumpUtils.checkDumpPermission(mContext, TAG, pw)) return;

        synchronized (mLock) {
            mNpuModelLoadingPolicy.dump(mContext, pw, args);

            pw.println();
            mPriorityManager.dump(pw, args);
        }
    }
}
