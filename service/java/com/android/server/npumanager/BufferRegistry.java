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

package com.android.server.npumanager;

import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.concurrent.Executor;
import android.annotation.Nullable;

/** A registry of buffer entries. */
final class BufferRegistry {
    private final HashMap<Long, BufferEntry> mAllocatedBuffers = new HashMap<>();

    @Nullable
    BufferEntry get(long appReqId) {
        synchronized (mAllocatedBuffers) {
            return mAllocatedBuffers.get(appReqId);
        }
    }

    @Nullable
    BufferEntry putIfAbsent(long appReqId, BufferEntry entry) {
        synchronized (mAllocatedBuffers) {
            return mAllocatedBuffers.putIfAbsent(appReqId, entry);
        }
    }

    /** Removes the entry for the given appReqId and closes the buffer in the current thread. */
    void removeAndClose(long appReqId) {
        BufferEntry entry;
        synchronized (mAllocatedBuffers) {
            entry = mAllocatedBuffers.remove(appReqId);
        }
        if (entry != null) {
            entry.close();
        }
    }

    /**
     * Removes the entries for the given appReqIds in the current thread, and closes the buffers in
     * the background thread.
     *
     * @return A list of appReqIds that are not found in the allocated buffers, in string.
     */
    List<String> removeAllAndDeferClose(long[] appReqIds, Executor executor) {
        ArrayList<String> missing = new ArrayList<>();
        ArrayList<BufferEntry> removed = new ArrayList<>();
        synchronized (mAllocatedBuffers) {
            for (long appReqId : appReqIds) {
                BufferEntry entry = mAllocatedBuffers.remove(appReqId);
                if (entry == null) {
                    missing.add(String.valueOf(appReqId));
                } else {
                    removed.add(entry);
                }
            }
        }
        executor.execute(
                () -> {
                    for (BufferEntry entry : removed) {
                        entry.close();
                    }
                });
        return missing;
    }
}
