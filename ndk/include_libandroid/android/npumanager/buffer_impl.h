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

#ifndef ANDROID_NPUMANAGER_BUFFER_IMPL_H
#define ANDROID_NPUMANAGER_BUFFER_IMPL_H

#include <stdbool.h>
#include <stdint.h>
#include <sys/cdefs.h>
#include <sys/types.h>

__BEGIN_DECLS

ANpuManager_AllocRequest* _Nonnull ANpuManagerImpl_ANpuManager_AllocRequest_create()
        __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_free(ANpuManager_AllocRequest* _Nonnull request)
        __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setCookie(
        ANpuManager_AllocRequest* _Nonnull request, void* _Nullable cookieToOwn,
        ANpuManager_CookieDeleter _Nullable cookieDeleter) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setDeviceNumber(
        ANpuManager_AllocRequest* _Nonnull request, int32_t deviceNumber) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setBufferType(
        ANpuManager_AllocRequest* _Nonnull request, ANpuBuffer_Type bufferType) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setSize(ANpuManager_AllocRequest* _Nonnull request,
                                                      int64_t size) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setBufferPriority(
        ANpuManager_AllocRequest* _Nonnull request, int32_t bufferPriority) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setFileSegmentToLoad(
        ANpuManager_AllocRequest* _Nonnull request, int fdToOwn, int64_t fileOffset,
        int64_t segmentLength, int64_t bufferOffset) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setProtectionFlags(
        ANpuManager_AllocRequest* _Nonnull request, int32_t prot) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc(ANpuManager_AllocRequest* _Nonnull request,
                                                         ANpuManager_AllocCallback _Nonnull onAlloc)
        __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_AllocRequest_setOnPreempt(
        ANpuManager_AllocRequest* _Nonnull request, ANpuManager_PreemptCallback _Nullable onPreempt)
        __INTRODUCED_IN(37);

int ANpuManagerImpl_ANpuManager_isSupported(
        ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests, size_t requestsLen,
        bool* _Nonnull outIsSupported) __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuManager_allocAsync(
        ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests, size_t requestsLen)
        __INTRODUCED_IN(37);

int ANpuManagerImpl_ANpuBuffer_free(ANpuBuffer* _Nonnull const* _Nonnull buffers, size_t buffersLen)
        __INTRODUCED_IN(37);

void* _Nonnull ANpuManagerImpl_ANpuBuffer_map(ANpuBuffer* _Nonnull buf, void* _Nullable addr,
                                              size_t length, int prot, int flags, off_t offset)
        __INTRODUCED_IN(37);

int ANpuManagerImpl_ANpuBuffer_unmap(ANpuBuffer* _Nonnull buf, void* _Nonnull addr, size_t length)
        __INTRODUCED_IN(37);

int ANpuManagerImpl_ANpuBuffer_setPriority(ANpuBuffer* _Nonnull buf, int32_t newBufferPriority)
        __INTRODUCED_IN(37);

void ANpuManagerImpl_ANpuBuffer_loadAsync(ANpuBuffer* _Nonnull buf, int fdToOwn, int64_t fileOffset,
                                          int64_t segmentLength, int64_t bufferOffset,
                                          ANpuManager_LoadCallback _Nonnull onLoad)
        __INTRODUCED_IN(37);

__END_DECLS

#endif  // ANDROID_NPUMANAGER_BUFFER_IMPL_H
