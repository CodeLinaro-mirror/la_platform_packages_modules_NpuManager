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

import android.annotation.NonNull;
import android.annotation.SystemService;
import android.content.Context;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.npumanager.aidl.INpuManagerService;

/**
 * NpuManager provides access to NPU related services.
 *
 * @hide
 */
@SystemService(Context.NPU_SERVICE)
public final class NpuManager {
    private final INpuManagerService mService;
    private final Context mContext;

    /**
     * @hide
     */
    public NpuManager(@NonNull Context context, @NonNull INpuManagerService service) {
        mContext = context;
        mService = service;
    }
}
