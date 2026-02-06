/**
 * Copyright (C) 2026 The Android Open Source Project
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

import android.npumanager.BufferType;
import android.npumanager.FileSegment;

/**
 * A Request to get one buffer from NpuManagerService.
 *
 * @see android.npumanager.INpuAllocator#getBuffers
 * @hide
 */
parcelable NpuBufferGetRequest {
    /** A unique request ID chosen by the client. The server rejects duplicate IDs. */
    long appReqId;
    int deviceNumber = -1;
    BufferType bufferType = BufferType.UNKNOWN;
    long size;
    int bufferPriority = BUFFER_PRIORITY_DEFAULT;
    @nullable FileSegment fileSegmentToLoad;
    int protectionFlags = PROT_READ;

    const int BUFFER_PRIORITY_DEFAULT = 500;
    const int PROT_READ = 0x1;
}
