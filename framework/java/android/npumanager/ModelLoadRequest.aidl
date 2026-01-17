/*
 * Copyright 2025 The Android Open Source Project
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

import android.npumanager.NpuModelSize;

/**
 * A simple Parcelable representing a model load request.
 *
 * @hide
 */
@JavaPassthrough(annotation="@android.annotation.SystemApi")
@JavaPassthrough(annotation="@android.annotation.FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)")
parcelable ModelLoadRequest {
    /** The id of the model load request. */
    @JavaPassthrough(annotation="@android.annotation.IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE)")
    int id;

    /** The size of the model to load. */
    @JavaPassthrough(annotation="@android.annotation.IntRange(from = NpuModelSize.LESS_THAN_1GB, to = NpuModelSize.GREATER_THAN_2G)")
    @JavaPassthrough(annotation="@NpuModelSize")
    NpuModelSize size;

    /** The priority of the model to load. */
    @JavaPassthrough(annotation="@android.annotation.IntRange(from = PRIORITY_NORMAL, to = PRIORITY_BACKGROUND)")
    int priority;

    const int PRIORITY_NORMAL = 0;
    const int PRIORITY_BACKGROUND = 1000;
}