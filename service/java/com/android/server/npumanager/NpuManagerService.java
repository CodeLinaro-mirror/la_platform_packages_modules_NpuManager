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

import android.annotation.NonNull;
import android.content.Context;
import android.util.Log;

import com.android.server.SystemService;

/** */
public class NpuManagerService extends SystemService {
    final String TAG = "NpuManagerService";
    private NpuManagerServiceImpl mNpuManagerServiceImpl;

    public NpuManagerService(@NonNull Context context) {
        super(context);
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        mNpuManagerServiceImpl = new NpuManagerServiceImpl(context);
    }

    @Override
    public void onStart() {
        Log.d(TAG, "onStart");
        if (mNpuManagerServiceImpl != null) {
            publishBinderService(Context.NPU_SERVICE, mNpuManagerServiceImpl);
        }
    }
}
