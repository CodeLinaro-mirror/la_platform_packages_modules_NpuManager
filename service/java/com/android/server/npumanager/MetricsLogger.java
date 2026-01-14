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

import android.hardware.npu.EndReason;
import android.hardware.npu.StartReason;
import android.hardware.npu.WorkInfo;

public class MetricsLogger {
    private static final String TAG = "MetricsLogger";

    public void logWorkRequested(WorkInfo workInfo) {
        logWorkEvent(
                workInfo,
                NpuStatsLog.NPU_WORK_EVENT_OCCURRED__TYPE__REQUESTED,
                NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__UNSPECIFIED);
    }

    public void logWorkStarted(WorkInfo workInfo, @StartReason byte reason) {
        logWorkEvent(
                workInfo,
                NpuStatsLog.NPU_WORK_EVENT_OCCURRED__TYPE__STARTED,
                convertStartReason(reason));
    }

    public void logWorkEnded(WorkInfo workInfo, @EndReason byte reason) {
        logWorkEvent(
                workInfo,
                NpuStatsLog.NPU_WORK_EVENT_OCCURRED__TYPE__ENDED,
                convertEndReason(reason));
    }

    public void logAppBlocked(int uid) {
        NpuStatsLog.write(NpuStatsLog.NPU_APP_BLOCKED, uid);
    }

    private int convertStartReason(@StartReason byte reason) {
        return switch (reason) {
            case StartReason.INITIAL -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__INITIAL;
            case StartReason.RESUMED -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__RESUMED;
            default -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__UNSPECIFIED;
        };
    }

    private int convertEndReason(@EndReason byte reason) {
        return switch (reason) {
            case EndReason.CANCELLED_USER ->
                    NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__CANCELLED_USER;
            case EndReason.CANCELLED_SYSTEM ->
                    NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__CANCELLED_SYSTEM;
            case EndReason.PAUSED -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__PAUSED;
            case EndReason.FAILED -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__FAILED;
            case EndReason.COMPLETED -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__COMPLETED;
            default -> NpuStatsLog.NPU_WORK_EVENT_OCCURRED__REASON__UNSPECIFIED;
        };
    }

    private void logWorkEvent(WorkInfo workInfo, int eventType, int reason) {
        NpuStatsLog.write(
                NpuStatsLog.NPU_WORK_EVENT_OCCURRED,
                workInfo.uid,
                workInfo.debugPid,
                workInfo.originalUid,
                workInfo.jobPriority,
                workInfo.effectivePriority,
                workInfo.timestampMs,
                workInfo.deviceNumber,
                eventType,
                reason);
    }
}
