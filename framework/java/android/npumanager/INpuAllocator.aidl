/*
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

import android.npumanager.FileSegment;
import android.npumanager.INpuAllocatorCallback;
import android.npumanager.NpuBufferGetRequest;

/**
 * Allocator interface for NPU buffers.
 *
 * Each instance corresponds to one client with the one INpuAllocatorCallback.
 *
 * @hide
 */
interface INpuAllocator {
    /**
     * Gets a buffer. NpuManagerService may allocate a new buffer or reuse
     * an existing idle one.
     *
     * Throws an exception immediately if there is a serious issue and none
     * of the requests can be replied (e.g. a transaction error). Otherwise,
     * if this returns normally, it means the NpuManagerService acknowledges
     * that it has received the requests, and will reply to the client
     * asynchronously.
     */
    void getBuffers(in NpuBufferGetRequest[] requests);

    /**
     * Checks if the given requests are supported.
     */
    boolean[] isSupported(in NpuBufferGetRequest[] requests);

    /**
     * Puts a buffer back to the pool. This indicates that the app is done with
     * the buffers, and the given appReqIds will be invalid.
     * NpuManagerService may put the buffers back to the pool (for reuse) and/or
     * deallocate it some time later.
     */
    void putBuffers(in long[] appReqIds);

    /**
     * Adjust the priority of a buffer.
     */
    void setPriority(in long appReqId, in int newBufferPriority);

    /**
     * Loads a file segment into a buffer.
     */
    void loadFileSegmentToBuffer(in long appReqId, in FileSegment fileSegment);
}
