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
 *
 */

#include <android/npumanager/buffer.h>
#include <gtest/gtest.h>

#include <sys/mman.h>
#include <condition_variable>
#include <functional>
#include <memory>
#include <mutex>
#include <optional>

using std::chrono_literals::operator""ms;

namespace {

using Request = std::unique_ptr<ANpuManager_AllocRequest, decltype(&ANpuManager_AllocRequest_free)>;

void FakeOnAlloc(void*, int, ANpuBuffer*) {
    ADD_FAILURE() << "FakeOnAlloc should not be called.";
}

void FakeOnPreempt(void*) {
    ADD_FAILURE() << "FakeOnPreempt should not be called.";
}

TEST(CtsNpuManagerBufferTest, CreateRequest) {
    auto req = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req);
    ANpuManager_AllocRequest_setCookie(req.get(), reinterpret_cast<void*>(0x12345678), nullptr);
    ANpuManager_AllocRequest_setDeviceNumber(req.get(), 0);
    ANpuManager_AllocRequest_setBufferType(req.get(), ANPUBUFFER_TYPE_MODEL_EXECUTABLE);
    ANpuManager_AllocRequest_setSize(req.get(), 1024);
    ANpuManager_AllocRequest_setBufferPriority(req.get(), 100);
    ANpuManager_AllocRequest_setFileSegmentToLoad(req.get(), -1, 0, 0, 0);
    ANpuManager_AllocRequest_setOnAlloc(req.get(), FakeOnAlloc);
    ANpuManager_AllocRequest_setOnPreempt(req.get(), FakeOnPreempt);
}

class FakeCookie {
  public:
    void IncStrong() { ref_count++; }
    void DecStrong() { ref_count--; }
    std::atomic<int> ref_count;
};
void FakeDeleter(void* cookie) {
    static_cast<FakeCookie*>(cookie)->DecStrong();
}

// Checks that the cookie's deleter is called properly when setCookie() is called
// twice and when the request is freed.
TEST(CtsNpuManagerBufferTest, RequestCookieReset) {
    auto req = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req);

    // This is a fake example. In real usage, the cookie would be heap-allocated, and freed when
    // refcount drops to 0.
    FakeCookie cookie1;
    cookie1.IncStrong();
    ANpuManager_AllocRequest_setCookie(req.get(), &cookie1, &FakeDeleter);
    ASSERT_EQ(1, cookie1.ref_count);

    FakeCookie cookie2;
    cookie2.IncStrong();
    ANpuManager_AllocRequest_setCookie(req.get(), &cookie2, &FakeDeleter);
    ASSERT_EQ(0, cookie1.ref_count)
            << "The cookie deleter was not called when the cookie was replaced.";
    ASSERT_EQ(1, cookie2.ref_count);

    req.reset();
    ASSERT_EQ(0, cookie2.ref_count)
            << "The cookie deleter was not called when the request was freed.";
    ASSERT_EQ(0, cookie1.ref_count) << "The old cookie should not be touched.";
}

// Checks that the cookie's deleter is called twice after setCookie() is called twice with the same
// cookie on two different requests and then the requests are freed.
TEST(CtsNpuManagerBufferTest, RequestCookieReuse) {
    auto req1 = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req1);
    auto req2 = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req2);

    // This is a fake example. In real usage, the cookie would be heap-allocated, and freed when
    // refcount drops to 0.
    FakeCookie cookie;

    cookie.IncStrong();
    ANpuManager_AllocRequest_setCookie(req1.get(), &cookie, &FakeDeleter);
    cookie.IncStrong();
    ANpuManager_AllocRequest_setCookie(req2.get(), &cookie, &FakeDeleter);

    ASSERT_EQ(2, cookie.ref_count);
    req1.reset();
    ASSERT_EQ(1, cookie.ref_count);
    req2.reset();
    ASSERT_EQ(0, cookie.ref_count);
}

struct AllocResult {
    int error;
    ANpuBuffer* buffer;
};

class TestCookie {
  public:
    static void OnAlloc(void* cookie, int error, ANpuBuffer* buffer) {
        TestCookie* thiz = static_cast<TestCookie*>(cookie);
        std::lock_guard<std::mutex> lock(thiz->mutex_);
        if (!thiz->expired_) {
            thiz->result_ = AllocResult{error, buffer};
            thiz->cv_.notify_one();
        } else {
            ANpuBuffer* buffers[] = {thiz->result_->buffer};
            (void)ANpuBuffer_free(buffers, 1);
        }
    }
    static void Delete(void* cookie) { delete static_cast<TestCookie*>(cookie); }

    std::optional<AllocResult> WaitForResult(std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cv_.wait_for(lock, timeout, [this] { return result_.has_value(); })) {
            expired_ = true;
            return std::nullopt;
        }
        return result_;
    }

  private:
    std::mutex mutex_;
    std::condition_variable cv_;
    bool expired_ = false;
    std::optional<AllocResult> result_;
};

TEST(CtsNpuManagerBufferTest, SingleBuffer) {
    constexpr size_t kBufferSize = 4 * 1024 * 1024;

    auto req = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req);

    TestCookie* cookie = new TestCookie();
    ANpuManager_AllocRequest_setCookie(req.get(), cookie, &TestCookie::Delete);

    ANpuManager_AllocRequest_setDeviceNumber(req.get(), 0);
    ANpuManager_AllocRequest_setBufferType(req.get(), ANPUBUFFER_TYPE_MODEL_WEIGHTS);
    ANpuManager_AllocRequest_setSize(req.get(), kBufferSize);
    ANpuManager_AllocRequest_setOnAlloc(req.get(), &TestCookie::OnAlloc);

    ANpuManager_AllocRequest* requests[] = {req.get()};
    bool isSupported = false;
    int ret = ANpuManager_isSupported(requests, 1, &isSupported);
    ASSERT_EQ(0, ret) << "ANpuManager_isSupported failed with errno: " << strerror(errno);
    if (!isSupported) {
        // TODO: b/479028987 - Use GTEST_SKIP().
        // GTEST_SKIP() << "Request (deviceNumber=0, bufferType=ANPUBUFFER_TYPE_MODEL_WEIGHTS) not "
        //                 "supported";
        return;
    }

    ANpuManager_allocAsync(requests, 1);

    auto result = cookie->WaitForResult(5000ms);

    ASSERT_TRUE(result.has_value()) << "Timed out waiting for callback";
    EXPECT_EQ(0, result->error);
    ASSERT_NE(nullptr, result->buffer);

    auto buffer = std::unique_ptr<ANpuBuffer, std::function<void(ANpuBuffer*)>>(
            result->buffer, [](ANpuBuffer* buffer) {
                ANpuBuffer* buffers[] = {buffer};
                EXPECT_EQ(0, ANpuBuffer_free(buffers, 1));
            });

    auto mapped = std::unique_ptr<void, std::function<void(void*)>>(
            ANpuBuffer_map(result->buffer, nullptr, kBufferSize, PROT_READ, MAP_SHARED, 0),
            [&](void* mapped) {
                if (mapped != MAP_FAILED) {
                    EXPECT_EQ(0, ANpuBuffer_unmap(buffer.get(), mapped, kBufferSize))
                            << "ANpuBuffer_unmap failed with errno: " << strerror(errno);
                }
            });
    EXPECT_NE(MAP_FAILED, mapped.get()) << "ANpuBuffer_map failed with errno: " << strerror(errno);

    // Calls ANpuBuffer_unmap and see if that succeeds.
    mapped.reset();

    // Calls ANpuBuffer_free and see if that succeeds.
    buffer.reset();
}

}  // namespace
