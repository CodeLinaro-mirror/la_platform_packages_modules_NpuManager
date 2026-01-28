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

namespace {

TEST(CtsNpuManagerBufferTest, CreateRequest) {
    auto req = ANpuManager_AllocRequest_create();
    ASSERT_NE(nullptr, req);
    ANpuManager_AllocRequest_free(req);
}

// TODO: b/466107663 - Add more tests after implementing the APIs.

}  // namespace
