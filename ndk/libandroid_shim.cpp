/*
 * Copyright 2026 The Android Open Source Project
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

/* libandroid_npumanager: calls into libnpumanager. */

#include <android/npumanager/buffer.h>
#include <android/npumanager/buffer_impl.h>

ANpuManager_AllocRequest* _Nonnull ANpuManager_AllocRequest_create() {
    return ANpuManagerImpl_ANpuManager_AllocRequest_create();
}

void ANpuManager_AllocRequest_free(ANpuManager_AllocRequest* _Nonnull request) {
    ANpuManagerImpl_ANpuManager_AllocRequest_free(request);
}

void ANpuManager_AllocRequest_setCookie(ANpuManager_AllocRequest* _Nonnull request,
                                        void* _Nullable cookieToOwn,
                                        ANpuManager_CookieDeleter _Nullable cookieDeleter) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setCookie(request, cookieToOwn, cookieDeleter);
}

void ANpuManager_AllocRequest_setDeviceNumber(ANpuManager_AllocRequest* _Nonnull request,
                                              int32_t deviceNumber) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setDeviceNumber(request, deviceNumber);
}

void ANpuManager_AllocRequest_setBufferType(ANpuManager_AllocRequest* _Nonnull request,
                                            ANpuBuffer_Type bufferType) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setBufferType(request, bufferType);
}

void ANpuManager_AllocRequest_setSize(ANpuManager_AllocRequest* _Nonnull request, int64_t size) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setSize(request, size);
}

void ANpuManager_AllocRequest_setBufferPriority(ANpuManager_AllocRequest* _Nonnull request,
                                                int32_t bufferPriority) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setBufferPriority(request, bufferPriority);
}

void ANpuManager_AllocRequest_setFileSegmentToLoad(ANpuManager_AllocRequest* _Nonnull request,
                                                   int fdToOwn, int64_t fileOffset,
                                                   int64_t segmentLength, int64_t bufferOffset) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setFileSegmentToLoad(request, fdToOwn, fileOffset,
                                                                  segmentLength, bufferOffset);
}

void ANpuManager_AllocRequest_setProtectionFlags(ANpuManager_AllocRequest* _Nonnull request,
                                                 int32_t prot) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setProtectionFlags(request, prot);
}

void ANpuManager_AllocRequest_setOnAlloc(ANpuManager_AllocRequest* _Nonnull request,
                                         ANpuManager_AllocCallback _Nonnull onAlloc) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc(request, onAlloc);
}

void ANpuManager_AllocRequest_setOnPreempt(ANpuManager_AllocRequest* _Nonnull request,
                                           ANpuManager_PreemptCallback _Nullable onPreempt) {
    ANpuManagerImpl_ANpuManager_AllocRequest_setOnPreempt(request, onPreempt);
}

int ANpuManager_isSupported(ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests,
                            size_t requestsLen, bool* _Nonnull outIsSupported) {
    return ANpuManagerImpl_ANpuManager_isSupported(requests, requestsLen, outIsSupported);
}

void ANpuManager_allocAsync(ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests,
                            size_t requestsLen) {
    ANpuManagerImpl_ANpuManager_allocAsync(requests, requestsLen);
}

int ANpuBuffer_free(ANpuBuffer* _Nonnull const* _Nonnull buffers, size_t buffersLen) {
    return ANpuManagerImpl_ANpuBuffer_free(buffers, buffersLen);
}

void* _Nonnull ANpuBuffer_map(ANpuBuffer* _Nonnull buf, void* _Nullable addr, size_t length,
                              int prot, int flags, off_t offset) {
    return ANpuManagerImpl_ANpuBuffer_map(buf, addr, length, prot, flags, offset);
}

int ANpuBuffer_unmap(ANpuBuffer* _Nonnull buf, void* _Nonnull addr, size_t length) {
    return ANpuManagerImpl_ANpuBuffer_unmap(buf, addr, length);
}

int ANpuBuffer_setPriority(ANpuBuffer* _Nonnull buf, int32_t newBufferPriority) {
    return ANpuManagerImpl_ANpuBuffer_setPriority(buf, newBufferPriority);
}
