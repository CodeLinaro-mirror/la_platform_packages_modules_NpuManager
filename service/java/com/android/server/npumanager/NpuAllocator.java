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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresNoPermission;
import android.npumanager.FileSegment;
import android.npumanager.INpuAllocator;
import android.npumanager.INpuAllocatorCallback;
import android.npumanager.NpuBufferGetRequest;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.system.ErrnoException;
import android.system.OsConstants;
import android.util.Slog;
import com.android.internal.util.ConcurrentUtils;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

final class NpuAllocator extends INpuAllocator.Stub {
    static {
        System.loadLibrary("npumanager_service_jni");
    }

    static final String TAG = "NpuAllocator";
    private final INpuAllocatorCallback mCallback;
    // Executor for heavy I/O work.
    private final ExecutorService mExecutor;
    private final BufferRegistry mBufferRegistry = new BufferRegistry();
    private final int mDebugClientPid;

    private static final int MAX_THREADS = 1;

    NpuAllocator(@NonNull INpuAllocatorCallback callback) {
        if (!nativeInitDmaHeapConfig()) {
            throw new IllegalStateException("Failed to parse DMA-buf heaps configuration.");
        }
        if (!nativeInitWrapfdDriver()) {
            throw new UnsupportedOperationException(
                    "Device does not support wrapfd driver to NPU manager.");
        }
        mCallback = callback;
        mExecutor =
                ConcurrentUtils.newFixedThreadPool(
                        MAX_THREADS,
                        "NpuManagerService-Allocator-Heavy",
                        android.os.Process.THREAD_PRIORITY_BACKGROUND);
        mDebugClientPid = getCallingPid();
    }

    /**
     * Gets the client's PID for this transaction, falling back to the original PID when
     * createAllocator() was called.
     */
    private int getDebugCallingPid() {
        int thisTxPid = getCallingPid();
        if (thisTxPid == 0) {
            return mDebugClientPid;
        }
        return thisTxPid;
    }

    /** Client's entry point to allocate & load buffers. */
    @Override
    @RequiresNoPermission
    public void getBuffers(NpuBufferGetRequest[] requests) {
        for (NpuBufferGetRequest request : requests) {
            mExecutor.execute(() -> backgroundHandleGetRequestAndReply(request));
        }
    }

    /** Handles a single request to allocate & load a buffer. */
    private void backgroundHandleGetRequestAndReply(NpuBufferGetRequest request) {
        ParcelFileDescriptor fd = null;
        ErrnoException exception = null;

        try {
            fd = backgroundGetBuffer(request);
        } catch (ErrnoException e) {
            exception = e;
        }

        try {
            if (exception != null) {
                mCallback.onGetBuffer(
                        request.appReqId, fd, exception.errno, exception.getMessage());
            } else {
                mCallback.onGetBuffer(request.appReqId, fd, 0, null);
            }
        } catch (RemoteException e) {
            Slog.e(
                    TAG,
                    "Failed to respond to buffer request "
                            + request.appReqId
                            + " to client "
                            + getDebugCallingPid(),
                    e);
            // Ignore the error about unable to send oneway calls.
        }
    }

    /**
     * Allocates and loads a single buffer.
     *
     * @throws ErrnoException if the buffer allocation fails.
     */
    private ParcelFileDescriptor backgroundGetBuffer(NpuBufferGetRequest request)
            throws ErrnoException {
        BufferEntry pendingEntry = new BufferEntry();
        if (mBufferRegistry.putIfAbsent(request.appReqId, pendingEntry) != null) {
            throw new ErrnoException(
                    "Duplicate request. NpuAllocator.getBuffer", OsConstants.EINVAL);
        }
        ParcelFileDescriptor fd;
        try {
            fd =
                    allocWrapLoad(
                            request.deviceNumber,
                            request.bufferType,
                            request.size,
                            getBufName(request),
                            request.protectionFlags,
                            request.fileSegmentToLoad);
            pendingEntry.onAllocated(fd);
        } catch (ErrnoException e) {
            mBufferRegistry.removeAndClose(request.appReqId);
            throw e;
        }
        return fd;
    }

    /**
     * Client's entry point to check if requests are supported.
     *
     * @throws IllegalStateException if any error
     * @throws InvalidArgumentException if requests are not valid
     */
    @Override
    @RequiresNoPermission
    public boolean[] isSupported(NpuBufferGetRequest[] requests) {
        boolean[] results = new boolean[requests.length];
        for (int i = 0; i < requests.length; i++) {
            results[i] = !getHeapName(requests[i]).isEmpty();
        }
        return results;
    }

    /**
     * Determines the DMA-buf heap name for the given request.
     *
     * @return Empty string if not supported. Otherwise, the heap name.
     * @throws IllegalStateException if any internal state error
     * @throws InvalidArgumentException if requests are not valid
     */
    private @NonNull String getHeapName(NpuBufferGetRequest request) {
        String ret;
        try {
            ret = nativeGetHeapName(request.deviceNumber, request.bufferType);
        } catch (UnsupportedOperationException e) {
            return "";
        }
        if (ret == null) {
            throw new IllegalStateException("nativeGetHeapName returns null but no exception seen");
        }
        return ret;
    }

    /** Returns a name for the buffer for debugging purposes. */
    private String getBufName(NpuBufferGetRequest request) {
        return "npubuf-" + getDebugCallingPid() + "-" + request.appReqId;
    }

    /** Client's entry point to put buffers back to the pool. */
    @Override
    @RequiresNoPermission
    public void putBuffers(long[] appReqIds) {
        List<String> missing = mBufferRegistry.removeAllAndDeferClose(appReqIds, mExecutor);
        if (!missing.isEmpty()) {
            throw new ServiceSpecificException(
                    OsConstants.EINVAL,
                    "Some buffer IDs are invalid: " + String.join(",", missing));
        }
    }

    /** Client's entry point to change the priority of a buffer. */
    @Override
    @RequiresNoPermission
    public void setPriority(long appReqId, int priority) {
        if (mBufferRegistry.get(appReqId) == null) {
            throw new ServiceSpecificException(OsConstants.EINVAL, "Buffer ID is invalid.");
        }
        // TODO: b/466107663 - Implement preempt and priority.
    }

    /**
     * Client's entry point to load a file segment into a buffer AFTER it has been allocated and
     * returned to the client.
     */
    @Override
    @RequiresNoPermission
    public void loadFileSegmentToBuffer(long appReqId, FileSegment fileSegment) {
        mExecutor.execute(() -> backgroundHandleLoadAndReply(appReqId, fileSegment));
    }

    /** Handles a single request to load a file segment into a buffer and replies to the client. */
    private void backgroundHandleLoadAndReply(long appReqId, FileSegment fileSegment) {
        ErrnoException exception = null;
        try {
            backgroundHandleLoad(appReqId, fileSegment);
        } catch (ErrnoException e) {
            exception = e;
        }
        try {
            if (exception != null) {
                mCallback.onLoad(appReqId, exception.errno, exception.getMessage());
            } else {
                mCallback.onLoad(appReqId, 0, null);
            }
        } catch (RemoteException e) {
            Slog.e(
                    TAG,
                    "Failed to respond to load request "
                            + appReqId
                            + " to client "
                            + getDebugCallingPid(),
                    e);
            // Ignore the error about unable to send oneway calls.
        }
    }

    /**
     * Handles a single request to load a file segment into a buffer.
     *
     * @throws ErrnoException for any error that needs to be replied to the client.
     */
    private void backgroundHandleLoad(long appReqId, FileSegment fileSegment)
            throws ErrnoException {
        BufferEntry buffer = mBufferRegistry.get(appReqId);
        if (buffer == null) {
            throw new ErrnoException("Buffer ID is invalid.", OsConstants.EINVAL);
        }
        ParcelFileDescriptor bufferFd = buffer.onLoading();
        if (!nativeLoadFileSegmentToBuffer(
                bufferFd.getFd(),
                fileSegment.fileFd.getFd(),
                fileSegment.fileOffset,
                fileSegment.segmentLength,
                fileSegment.bufferOffset)) {
            throw new ErrnoException(
                    "Failed to load file segment with unknown error", OsConstants.EBUSY);
        }
        buffer.onLoaded();
    }

    /**
     * Calls dmabuf_heap_alloc2(), then wrapfd_wrap(), then wrapfd_load().
     *
     * @param deviceNumber device number of the NPU device to allocate the buffer for
     * @param bufferType buffer type of the request
     * @param size size of buffer in bytes
     * @param bufName Buffer name for identification / debugging
     * @param wrapProt The protection flags to call wrapfd_wrap() with
     * @return The file descriptor of the allocated buffer.
     * @throws ErrnoException if the buffer allocation fails.
     */
    private @NonNull ParcelFileDescriptor allocWrapLoad(
            int deviceNumber,
            int bufferType,
            long size,
            String bufName,
            int wrapProt,
            FileSegment fileSegmentToLoad)
            throws ErrnoException {
        int borrowedFileFd = -1;
        long fileSegmentOffset = 0;
        long fileSegmentLength = 0;
        long bufferOffset = 0;
        if (fileSegmentToLoad != null && fileSegmentToLoad.fileFd != null) {
            borrowedFileFd = fileSegmentToLoad.fileFd.getFd();
            fileSegmentOffset = fileSegmentToLoad.fileOffset;
            fileSegmentLength = fileSegmentToLoad.segmentLength;
            bufferOffset = fileSegmentToLoad.bufferOffset;
        }

        int fd =
                nativeAllocWrapLoad(
                        deviceNumber,
                        bufferType,
                        size,
                        bufName,
                        wrapProt,
                        borrowedFileFd,
                        fileSegmentOffset,
                        fileSegmentLength,
                        bufferOffset);
        if (fd < 0) {
            throw new ErrnoException("Unknown error. nativeWrapLoad", OsConstants.EINVAL);
        }
        return ParcelFileDescriptor.adoptFd(fd);
    }

    /**
     * @return null if exception can't be thrown. Otherwise, the heap name.
     * @throws IllegalStateException if any internal state error
     * @throws InvalidArgumentException if requests are not valid
     * @throws UnsupportedOperationException if the request is not supported
     */
    private native @Nullable String nativeGetHeapName(int deviceNumber, int bufferType);

    /**
     * @return True if the config is present and valid, or not present. False if the config is
     *     present and invalid.
     */
    private static native boolean nativeInitDmaHeapConfig();

    private static native boolean nativeInitWrapfdDriver();

    /**
     * @return -1 if exception can't be thrown, or the file descriptor on success.
     * @throws ErrnoException if the buffer allocation fails.
     */
    private native int nativeAllocWrapLoad(
            int deviceNumber,
            int bufferType,
            long size,
            String bufName,
            int wrapProt,
            int borrowedFileFd,
            long fileSegmentOffset,
            long fileSegmentLength,
            long bufferOffset)
            throws ErrnoException;

    /**
     * Calls wrapfd_load() to load a file segment into a buffer.
     *
     * @return True if the load is successful, or false if there is an error and the exception can't
     *     be thrown.
     * @throws ErrnoException if the buffer allocation fails.
     */
    private native boolean nativeLoadFileSegmentToBuffer(
            int bufferFd,
            int borrowedFileFd,
            long fileSegmentOffset,
            long fileSegmentLength,
            long bufferOffset)
            throws ErrnoException;
}
