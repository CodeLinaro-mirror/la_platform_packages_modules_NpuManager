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

#include <android-base/file.h>
#include <android-base/strings.h>
#include <android-base/unique_fd.h>
#include <android/npumanager/buffer.h>
#include <gtest/gtest.h>

#include <sys/mman.h>
#include <condition_variable>
#include <functional>
#include <iostream>
#include <memory>
#include <mutex>
#include <optional>
#include <string>

using std::chrono_literals::operator""ms;

namespace {

using Request = std::unique_ptr<ANpuManager_AllocRequest, decltype(&ANpuManager_AllocRequest_free)>;

constexpr size_t MiB = 1024 * 1024;

std::string ProtToString(int32_t prot) {
    if (prot == PROT_NONE) {
        return "PROT_NONE";
    }
    std::vector<std::string> items;
    if (prot & PROT_READ) {
        items.push_back("PROT_READ");
    }
    if (prot & PROT_WRITE) {
        items.push_back("PROT_WRITE");
    }
    if (prot & PROT_EXEC) {
        items.push_back("PROT_EXEC");
    }
    auto ret = android::base::Join(items, "|");
    return ret.empty() ? "?" : ret;
}

std::string BufferTypeToString(ANpuBuffer_Type type) {
    switch (type) {
        case ANPUBUFFER_TYPE_UNKNOWN:
            return "ANPUBUFFER_TYPE_UNKNOWN";
        case ANPUBUFFER_TYPE_AUXILIARY:
            return "ANPUBUFFER_TYPE_AUXILIARY";
        case ANPUBUFFER_TYPE_CACHE:
            return "ANPUBUFFER_TYPE_CACHE";
        case ANPUBUFFER_TYPE_MODEL_EXECUTABLE:
            return "ANPUBUFFER_TYPE_MODEL_EXECUTABLE";
        case ANPUBUFFER_TYPE_MODEL_WEIGHTS:
            return "ANPUBUFFER_TYPE_MODEL_WEIGHTS";
    }
    return "?";
}

std::unique_ptr<TemporaryFile> NewRandomFile(size_t size) {
    android::base::unique_fd random(open("/dev/urandom", O_RDONLY));
    if (random.get() == -1) {
        ADD_FAILURE() << "Failed to open /dev/urandom: " << strerror(errno);
        return nullptr;
    }
    auto file = std::make_unique<TemporaryFile>();
    for (size_t written = 0; written < size;) {
        std::vector<char> buffer(std::min(size, 128 * MiB));
        if (!android::base::ReadFully(random, buffer.data(), buffer.size())) {
            ADD_FAILURE() << "Failed to read from /dev/urandom: " << strerror(errno);
            return nullptr;
        }
        if (!android::base::WriteFully(file->fd, buffer.data(), buffer.size())) {
            ADD_FAILURE() << "Failed to write to temporary file: " << strerror(errno);
            return nullptr;
        }
        written += buffer.size();
    }
    return file;
}

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
            thiz->alloc_result_ = AllocResult{error, buffer};
            thiz->cv_.notify_one();
        } else {
            ANpuBuffer* buffers[] = {thiz->alloc_result_->buffer};
            (void)ANpuBuffer_free(buffers, 1);
        }
    }
    static void OnLoad(void* cookie, int error, ANpuBuffer* buffer) {
        TestCookie* thiz = static_cast<TestCookie*>(cookie);
        std::lock_guard<std::mutex> lock(thiz->mutex_);
        if (!thiz->alloc_result_.has_value()) {
            ADD_FAILURE() << "Load callback is called before alloc callback";
            return;
        }
        EXPECT_EQ(buffer, thiz->alloc_result_->buffer)
                << "Load callback is called on a different buffer";
        thiz->load_result_ = error;
        thiz->cv_.notify_one();
    }
    static void Delete(void* cookie) { delete static_cast<TestCookie*>(cookie); }

    std::optional<AllocResult> WaitForAllocResult(std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cv_.wait_for(lock, timeout, [this] { return alloc_result_.has_value(); })) {
            expired_ = true;
            return std::nullopt;
        }
        return alloc_result_;
    }

    std::optional<int> WaitForLoadResult(std::chrono::milliseconds timeout) {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!cv_.wait_for(lock, timeout, [this] { return load_result_.has_value(); })) {
            return std::nullopt;
        }
        return load_result_;
    }

  private:
    std::mutex mutex_;
    std::condition_variable cv_;
    // If true, the caller no longer owns the buffer, so TestCookie needs to free it.
    bool expired_ = false;
    std::optional<AllocResult> alloc_result_;
    std::optional<int> load_result_;
};

struct TestFileSegmentToLoad {
    int64_t file_full_length;
    int64_t file_segment_offset;
    int64_t file_segment_length;
    int64_t buffer_offset;
};
std::ostream& operator<<(std::ostream& os, const TestFileSegmentToLoad& segment) {
    os << "{file_segment={offset=" << segment.file_segment_offset
       << ", len=" << segment.file_segment_length << "}, buf_offset=" << segment.buffer_offset
       << "}";
    return os;
}

struct TestParam {
    int32_t device_number;
    ANpuBuffer_Type buffer_type;
    int64_t size;
    int32_t prot = PROT_READ | PROT_WRITE;
    std::chrono::milliseconds timeout = 5000ms;

    TestFileSegmentToLoad load_on_alloc;
    TestFileSegmentToLoad load_later;
};
std::ostream& operator<<(std::ostream& os, const TestParam& param) {
    os << "{device_number=" << param.device_number
       << ", buffer_type=" << BufferTypeToString(param.buffer_type) << ", size=" << param.size
       << ", prot=" << ProtToString(param.prot) << ", timeout=" << param.timeout.count() << "ms"
       << ", load_on_alloc=" << param.load_on_alloc << ", load_later=" << param.load_later << "}";
    return os;
}

class CtsNpuManagerBufferTest : public ::testing::TestWithParam<TestParam> {
  public:
    static std::vector<TestParam> GetTestParams() {
        std::vector<TestParam> params;
        // Simple allocation
        params.emplace_back(TestParam{
                .device_number = 0, .buffer_type = ANPUBUFFER_TYPE_MODEL_WEIGHTS, .size = 4 * MiB});

        // Test loading a file on allocation and later.
        params.emplace_back(TestParam{
                .device_number = 0,
                .buffer_type = ANPUBUFFER_TYPE_MODEL_WEIGHTS,
                .size = 8 * MiB,
                .load_on_alloc = TestFileSegmentToLoad{.file_full_length = 7 * MiB,
                                                       .file_segment_offset = 1 * MiB,
                                                       .file_segment_length = 2 * MiB,
                                                       .buffer_offset = 4 * MiB},
                .load_later = TestFileSegmentToLoad{.file_full_length = 6 * MiB,
                                                    .file_segment_offset = 2 * MiB,
                                                    .file_segment_length = 1 * MiB,
                                                    .buffer_offset = 3 * MiB},
        });
        return params;
    }
    void SetUp() override {
        const auto& param = GetParam();
        if (param.load_on_alloc.file_full_length > 0) {
            load_on_alloc = NewRandomFile(param.load_on_alloc.file_full_length);
            EXPECT_NE(nullptr, load_on_alloc);
        }
        if (param.load_later.file_full_length > 0) {
            load_later = NewRandomFile(param.load_later.file_full_length);
            EXPECT_NE(nullptr, load_later);
        }
    }

    static void CheckFileSegment(const TestFileSegmentToLoad& segment,
                                 android::base::borrowed_fd file_fd, void* buffer) {
        auto orig_file_segment = std::unique_ptr<void, std::function<void(void*)>>(
                mmap(nullptr, segment.file_segment_length, PROT_READ, MAP_SHARED, file_fd.get(),
                     segment.file_segment_offset),
                [&](void* mapped) {
                    if (mapped != MAP_FAILED) {
                        EXPECT_EQ(0, munmap(mapped, segment.file_segment_length))
                                << "munmap failed with errno: " << strerror(errno);
                    }
                });
        EXPECT_NE(MAP_FAILED, orig_file_segment.get())
                << "mmap failed with errno: " << strerror(errno);

        EXPECT_EQ(0, memcmp(orig_file_segment.get(), (char*)buffer + segment.buffer_offset,
                            segment.file_segment_length));
    }

    std::unique_ptr<TemporaryFile> load_on_alloc;
    std::unique_ptr<TemporaryFile> load_later;
};

TEST_P(CtsNpuManagerBufferTest, SingleBuffer) {
    const auto& param = GetParam();

    auto req = Request(ANpuManager_AllocRequest_create(), &ANpuManager_AllocRequest_free);
    ASSERT_NE(nullptr, req);

    TestCookie* cookie = new TestCookie();
    ANpuManager_AllocRequest_setCookie(req.get(), cookie, &TestCookie::Delete);

    ANpuManager_AllocRequest_setDeviceNumber(req.get(), param.device_number);
    ANpuManager_AllocRequest_setBufferType(req.get(), param.buffer_type);
    ANpuManager_AllocRequest_setSize(req.get(), param.size);
    ANpuManager_AllocRequest_setProtectionFlags(req.get(), param.prot);
    ANpuManager_AllocRequest_setOnAlloc(req.get(), &TestCookie::OnAlloc);

    std::optional<off_t> load_on_alloc_orig_pos;
    if (load_on_alloc != nullptr) {
        load_on_alloc_orig_pos = lseek(load_on_alloc->fd, 0, SEEK_CUR);
        android::base::unique_fd duped(dup(load_on_alloc->fd));
        ASSERT_NE(-1, duped.get()) << strerror(errno);
        ANpuManager_AllocRequest_setFileSegmentToLoad(
                req.get(), duped.release(), param.load_on_alloc.file_segment_offset,
                param.load_on_alloc.file_segment_length, param.load_on_alloc.buffer_offset);
    }

    ANpuManager_AllocRequest* requests[] = {req.get()};
    bool isSupported = false;
    int ret = ANpuManager_isSupported(requests, 1, &isSupported);
    ASSERT_EQ(0, ret) << "ANpuManager_isSupported failed with errno: " << strerror(errno);
    if (!isSupported) {
        // TODO: b/479028987 - Use GTEST_SKIP().
        // GTEST_SKIP() << "Request " << param << " not supported";
        return;
    }

    ANpuManager_allocAsync(requests, 1);

    auto result = cookie->WaitForAllocResult(param.timeout);

    ASSERT_TRUE(result.has_value()) << "Timed out waiting for callback";
    EXPECT_EQ(0, result->error) << strerror(result->error);
    ASSERT_NE(nullptr, result->buffer);

    if (load_on_alloc_orig_pos) {
        EXPECT_EQ(load_on_alloc_orig_pos, lseek(load_on_alloc->fd, 0, SEEK_CUR))
                << "file fd pos should not change after allocation: " << strerror(errno);
    }

    auto buffer = std::unique_ptr<ANpuBuffer, std::function<void(ANpuBuffer*)>>(
            result->buffer, [](ANpuBuffer* buffer) {
                ANpuBuffer* buffers[] = {buffer};
                EXPECT_EQ(0, ANpuBuffer_free(buffers, 1));
            });

    auto mapped = std::unique_ptr<void, std::function<void(void*)>>(
            ANpuBuffer_map(result->buffer, nullptr, param.size, PROT_READ, MAP_SHARED, 0),
            [&](void* mapped) {
                if (mapped != MAP_FAILED) {
                    EXPECT_EQ(0, ANpuBuffer_unmap(buffer.get(), mapped, param.size))
                            << "ANpuBuffer_unmap failed with errno: " << strerror(errno);
                }
            });
    EXPECT_NE(MAP_FAILED, mapped.get()) << "ANpuBuffer_map failed with errno: " << strerror(errno);

    if (load_on_alloc != nullptr) {
        CheckFileSegment(param.load_on_alloc, load_on_alloc->fd, mapped.get());
    }

    if (load_later != nullptr) {
        auto load_later_orig_pos = lseek(load_later->fd, 0, SEEK_CUR);
        android::base::unique_fd duped(dup(load_later->fd));
        ASSERT_NE(-1, duped.get()) << strerror(errno);
        ANpuBuffer_loadAsync(buffer.get(), duped.release(), param.load_later.file_segment_offset,
                             param.load_later.file_segment_length, param.load_later.buffer_offset,
                             &TestCookie::OnLoad);
        auto load_result = cookie->WaitForLoadResult(param.timeout);
        ASSERT_TRUE(load_result.has_value()) << "Timed out waiting for load callback";
        EXPECT_EQ(0, *load_result)
                << "ANpuBuffer_loadAsync failed with errno: " << strerror(*load_result);
        CheckFileSegment(param.load_later, load_later->fd, mapped.get());
        EXPECT_EQ(load_later_orig_pos, lseek(load_later->fd, 0, SEEK_CUR))
                << "file fd pos should not change after load: " << strerror(errno);
    }

    // Calls ANpuBuffer_unmap and see if that succeeds.
    mapped.reset();

    // Calls ANpuBuffer_free and see if that succeeds.
    buffer.reset();
}

INSTANTIATE_TEST_SUITE_P(CtsNpuManagerBufferTest, CtsNpuManagerBufferTest,
                         testing::ValuesIn(CtsNpuManagerBufferTest::GetTestParams()));

}  // namespace
