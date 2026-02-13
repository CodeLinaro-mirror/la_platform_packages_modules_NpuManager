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

import android.os.ParcelFileDescriptor;
import android.util.Slog;
import java.io.IOException;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * A buffer entry in {@link BufferRegistry}.
 *
 * <p>This class is used to track the state of a buffer allocation and loading request.
 */
final class BufferEntry {
    private static final String TAG = NpuAllocator.TAG;

    private static enum State {
        ALLOCATING,
        ALLOCATED,
        LOADING,
    }

    private State mState = State.ALLOCATING;
    private @Nullable ParcelFileDescriptor mFd;

    synchronized void onAllocated(@NonNull ParcelFileDescriptor fd) throws ErrnoException {
        if (mState != State.ALLOCATING) {
            String msg =
                    String.format(
                            "After allocation, the buffer's state has been changed by another"
                                    + " thread. current state: %s, expects ALLOCATING",
                            mState);
            Slog.e(TAG, msg);
            throw new ErrnoException(msg, OsConstants.EINVAL);
        }
        mFd = fd;
        mState = State.ALLOCATED;
    }

    synchronized @NonNull ParcelFileDescriptor onLoading() throws ErrnoException {
        if (mState != State.ALLOCATED || mFd == null) {
            throw new ErrnoException(
                    "Buffer is not in allocated state. Duplicate load request?",
                    OsConstants.EINVAL);
        }
        mState = State.LOADING;
        return mFd;
    }

    synchronized void onLoaded() throws ErrnoException {
        if (mState != State.LOADING) {
            String msg =
                    String.format(
                            "After loading, the buffer's state has been changed by another"
                                    + " thread. current state: %s, expects LOADING",
                            mState);
            Slog.e(TAG, msg);
            throw new ErrnoException(msg, OsConstants.EINVAL);
        }
        mState = State.ALLOCATED;
    }

    synchronized void close() {
        if (mFd == null) {
            return;
        }
        try {
            mFd.close();
        } catch (IOException e) {
            Slog.e(TAG, "Failed to close buffer", e);
            // suppress close error; there's nothing we can do at this point.
        }
    }
}
