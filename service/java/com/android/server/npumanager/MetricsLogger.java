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
import android.npumanager.ModelLoadRequest;
import android.npumanager.NpuManager;

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

    public void logModelLoadRequested(ModelLoadRequestInfo modelLoadRequestInfo) {
        ModelLoadRequest request = modelLoadRequestInfo.getRequest();
        NpuStatsLog.write(
                NpuStatsLog.NPU_MODEL_LOAD_REQUESTED,
                modelLoadRequestInfo.getUid(),
                request.getId(),
                convertModelSize(request.getSize()),
                convertModelPriority(request.getPriority()));
    }

    public void logModelLoadPolicyResponded(ModelLoadRequestInfo modelLoadRequestInfo, int status) {
        ModelLoadRequest request = modelLoadRequestInfo.getRequest();
        NpuStatsLog.write(
                NpuStatsLog.NPU_MODEL_LOAD_POLICY_RESPONDED,
                modelLoadRequestInfo.getUid(),
                request.getId(),
                convertModelLoadStatus(status));
    }

    public void logModelLoadRequestCancelled(ModelLoadRequestInfo modelLoadRequestInfo) {
        ModelLoadRequest request = modelLoadRequestInfo.getRequest();
        NpuStatsLog.write(
                NpuStatsLog.NPU_MODEL_LOAD_REQUEST_CANCELLED,
                modelLoadRequestInfo.getUid(),
                request.getId());
    }

    public void logModelUnloaded(ModelLoadRequestInfo modelLoadRequestInfo) {
        ModelLoadRequest request = modelLoadRequestInfo.getRequest();
        NpuStatsLog.write(
                NpuStatsLog.NPU_MODEL_LOAD_STATE_CHANGED,
                modelLoadRequestInfo.getUid(),
                request.getId(),
                NpuStatsLog.NPU_MODEL_LOAD_STATE_CHANGED__STATE__STATE_UNLOADED);
    }

    public void logModelLoaded(ModelLoadRequestInfo modelLoadRequestInfo) {
        ModelLoadRequest request = modelLoadRequestInfo.getRequest();
        NpuStatsLog.write(
                NpuStatsLog.NPU_MODEL_LOAD_STATE_CHANGED,
                modelLoadRequestInfo.getUid(),
                request.getId(),
                NpuStatsLog.NPU_MODEL_LOAD_STATE_CHANGED__STATE__STATE_LOADED);
    }

    private int convertModelSize(int modelSize) {
        return switch (modelSize) {
            case NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB ->
                    NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_SIZE__SIZE_LESS_THAN_ONE_GB;
            case NpuManager.NPU_MODEL_SIZE_BETWEEN_1GB_AND_2GB ->
                    NpuStatsLog
                            .NPU_MODEL_LOAD_REQUESTED__MODEL_SIZE__SIZE_BETWEEN_ONE_GB_AND_TWO_GB;
            case NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G ->
                    NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_SIZE__SIZE_GREATER_THAN_TWO_G;
            default -> NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_SIZE__SIZE_UNKNOWN;
        };
    }

    private int convertModelPriority(int modelPriority) {
        return switch (modelPriority) {
            case NpuManager.NPU_MODEL_PRIORITY_NORMAL ->
                    NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_PRIORITY__PRIORITY_NORMAL;
            case NpuManager.NPU_MODEL_PRIORITY_BACKGROUND ->
                    NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_PRIORITY__PRIORITY_BACKGROUND;
            default -> NpuStatsLog.NPU_MODEL_LOAD_REQUESTED__MODEL_PRIORITY__PRIORITY_UNKNOWN;
        };
    }

    private int convertModelLoadStatus(int status) {
        return switch (status) {
            case NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW ->
                    NpuStatsLog.NPU_MODEL_LOAD_POLICY_RESPONDED__STATUS__STATUS_CAN_LOAD_NOW;
            case NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD ->
                    NpuStatsLog.NPU_MODEL_LOAD_POLICY_RESPONDED__STATUS__STATUS_WAIT_FOR_UNLOAD;
            case NpuManager.NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED ->
                    NpuStatsLog.NPU_MODEL_LOAD_POLICY_RESPONDED__STATUS__STATUS_NOT_PRIORITIZED;
            default -> NpuStatsLog.NPU_MODEL_LOAD_POLICY_RESPONDED__STATUS__STATUS_UNKNOWN;
        };
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
