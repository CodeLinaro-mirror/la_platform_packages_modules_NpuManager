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

//! Implements the INpuAllocatorCallback AIDL interface. This is just a proxy
//! wrapper around NpuAllocatorClient.

use crate::npu_allocator_client::NpuAllocatorClient;
use framework_npumanager_aidl::aidl::android::npumanager::INpuAllocatorCallback::INpuAllocatorCallback;
use std::sync::Arc;

pub struct NpuAllocatorCallback {
    client: Arc<NpuAllocatorClient>,
}

impl NpuAllocatorCallback {
    pub fn new(client: Arc<NpuAllocatorClient>) -> Self {
        Self { client }
    }
}

impl binder::Interface for NpuAllocatorCallback {}

impl INpuAllocatorCallback for NpuAllocatorCallback {
    fn onGetBuffer(
        &self,
        appReqId: i64,
        bufFd: Option<&binder::ParcelFileDescriptor>,
        errorNum: i32,
        errMsg: Option<&str>,
    ) -> binder::Result<()> {
        self.client.onGetBuffer(appReqId, bufFd, errorNum, errMsg)
    }

    fn onNotifyPreempted(&self, appReqId: i64) -> binder::Result<()> {
        self.client.onNotifyPreempted(appReqId)
    }

    fn onLoad(&self, appReqId: i64, errorNum: i32, errMsg: Option<&str>) -> binder::Result<()> {
        self.client.onLoad(appReqId, errorNum, errMsg)
    }
}
