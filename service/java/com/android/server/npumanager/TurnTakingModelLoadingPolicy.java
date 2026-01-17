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

import static android.npumanager.NpuManager.NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;

import java.io.PrintWriter;
import java.util.Map;

/**
 * A model loading policy that allows one UID to have a model loaded at a time. UIDs with higher
 * importance (lower value) will preempt lower importance UIDs.
 */
class TurnTakingModelLoadingPolicy extends BudgetModelLoadingPolicy {
    TurnTakingModelLoadingPolicy(PriorityManager priorityManager) {
        super(
                priorityManager,
                Map.of(
                        NPU_MODEL_SIZE_LESS_THAN_1GB, 1,
                        NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB, 1,
                        NPU_MODEL_SIZE_GREATER_THAN_2G, 1),
                1);
    }

    @Override
    void dump(@NonNull Context context, @NonNull PrintWriter pw, @Nullable String[] args) {
        pw.println("Policy: turntaking");
        dumpInternal(context, pw, args);
    }
}
