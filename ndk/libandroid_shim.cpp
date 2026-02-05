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

/* libandroid_npumanager: Let libandroid lazily load and call into libcom.android.npumanager. */

#include <android-base/logging.h>
#include <android-base/result.h>
#include <android/npumanager/buffer.h>
#include <android/npumanager/buffer_impl.h>
#include <dlfcn.h>
#include <map>
#include <memory>
#include <mutex>
#include <string>

// b/479732472: Lazily load libcom.android.npumanager.so. libandroid.so might be loaded to processes
// before the APEX is ready, so don't let libandroid.so immediately dlopen()
// libcom.android.npumanager.so.
namespace {

using ::android::base::Error;
using ::android::base::Result;

constexpr const char kLibComAndroidNpuManager[] = "libcom.android.npumanager.so";

std::string GetDlError() {
    const char* e = dlerror();
    return e ? e : "";
}

Result<void*> LoadLibComAndroidNpuManager() {
    static void* library_handle = dlopen(kLibComAndroidNpuManager, RTLD_LAZY | RTLD_LOCAL);
    static std::string error = GetDlError();
    return library_handle ? Result<void*>(library_handle) : (Error() << error);
}
template <typename Fn>
Fn LoadSymbol(const char* name) {
    auto library_handle = LoadLibComAndroidNpuManager();
    if (!library_handle.ok()) {
        LOG(FATAL) << "Failed to load " << kLibComAndroidNpuManager << ": "
                   << library_handle.error();
        return nullptr;
    }
    void* symbol = dlsym(*library_handle, name);
    if (symbol == nullptr) {
        LOG(FATAL) << "Failed to load symbol " << name << " from " << kLibComAndroidNpuManager
                   << ": " << dlerror();
        return nullptr;
    }
    return reinterpret_cast<Fn>(symbol);
}
}  // namespace

#define LOAD_SYMBOL(name) LoadSymbol<decltype(&name)>(#name)

ANpuManager_AllocRequest* _Nonnull ANpuManager_AllocRequest_create() {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_create);
    return symbol();
}

void ANpuManager_AllocRequest_free(ANpuManager_AllocRequest* _Nonnull request) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_free);
    return symbol(request);
}

void ANpuManager_AllocRequest_setCookie(ANpuManager_AllocRequest* _Nonnull request,
                                        void* _Nullable cookieToOwn,
                                        ANpuManager_CookieDeleter _Nullable cookieDeleter) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setCookie);
    return symbol(request, cookieToOwn, cookieDeleter);
}

void ANpuManager_AllocRequest_setDeviceNumber(ANpuManager_AllocRequest* _Nonnull request,
                                              int32_t deviceNumber) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setDeviceNumber);
    return symbol(request, deviceNumber);
}

void ANpuManager_AllocRequest_setBufferType(ANpuManager_AllocRequest* _Nonnull request,
                                            ANpuBuffer_Type bufferType) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setBufferType);
    return symbol(request, bufferType);
}

void ANpuManager_AllocRequest_setSize(ANpuManager_AllocRequest* _Nonnull request, int64_t size) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setSize);
    return symbol(request, size);
}

void ANpuManager_AllocRequest_setBufferPriority(ANpuManager_AllocRequest* _Nonnull request,
                                                int32_t bufferPriority) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setBufferPriority);
    return symbol(request, bufferPriority);
}

void ANpuManager_AllocRequest_setFileSegmentToLoad(ANpuManager_AllocRequest* _Nonnull request,
                                                   int fdToOwn, int64_t fileOffset,
                                                   int64_t segmentLength, int64_t bufferOffset) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setFileSegmentToLoad);
    return symbol(request, fdToOwn, fileOffset, segmentLength, bufferOffset);
}

void ANpuManager_AllocRequest_setProtectionFlags(ANpuManager_AllocRequest* _Nonnull request,
                                                 int32_t prot) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setProtectionFlags);
    return symbol(request, prot);
}

void ANpuManager_AllocRequest_setOnAlloc(ANpuManager_AllocRequest* _Nonnull request,
                                         ANpuManager_AllocCallback _Nonnull onAlloc) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc);
    return symbol(request, onAlloc);
}

void ANpuManager_AllocRequest_setOnPreempt(ANpuManager_AllocRequest* _Nonnull request,
                                           ANpuManager_PreemptCallback _Nullable onPreempt) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_AllocRequest_setOnPreempt);
    return symbol(request, onPreempt);
}

int ANpuManager_isSupported(ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests,
                            size_t requestsLen, bool* _Nonnull outIsSupported) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_isSupported);
    return symbol(requests, requestsLen, outIsSupported);
}

void ANpuManager_allocAsync(ANpuManager_AllocRequest* _Nonnull const* _Nonnull requests,
                            size_t requestsLen) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuManager_allocAsync);
    return symbol(requests, requestsLen);
}

int ANpuBuffer_free(ANpuBuffer* _Nonnull const* _Nonnull buffers, size_t buffersLen) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuBuffer_free);
    return symbol(buffers, buffersLen);
}

void* _Nonnull ANpuBuffer_map(ANpuBuffer* _Nonnull buf, void* _Nullable addr, size_t length,
                              int prot, int flags, off_t offset) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuBuffer_map);
    return symbol(buf, addr, length, prot, flags, offset);
}

int ANpuBuffer_unmap(ANpuBuffer* _Nonnull buf, void* _Nonnull addr, size_t length) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuBuffer_unmap);
    return symbol(buf, addr, length);
}

int ANpuBuffer_setPriority(ANpuBuffer* _Nonnull buf, int32_t newBufferPriority) {
    static auto symbol = LOAD_SYMBOL(ANpuManagerImpl_ANpuBuffer_setPriority);
    return symbol(buf, newBufferPriority);
}

#undef LOAD_SYMBOL
