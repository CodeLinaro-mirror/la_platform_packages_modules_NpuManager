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

//! Delegate to NpuManagerService.

use crate::alloc_request::ANpuManager_AllocRequest;
use crate::deferred_alloc_error::DeferredAllocError;
use crate::npu_allocator_callback::NpuAllocatorCallback;
use crate::npu_allocator_client::NpuAllocatorClient;
use crate::npu_buffer_impl::ANpuBufferImpl;
use crate::translations::status_to_errno;
use crate::unpack_option::LoadCallbackFn;
use binder::{BinderFeatures, Strong};
use framework_npumanager_aidl::aidl::android::npumanager::{
    FileSegment::FileSegment, INpuAllocator::INpuAllocator,
    INpuAllocatorCallback::BnNpuAllocatorCallback, INpuManagerService::INpuManagerService,
};
use std::sync::{Arc, Mutex};

// app process:
//   NpuManagerDelegate ---------------------------------------------> NpuAllocatorClient
//                    \                                                 ^
//                     -> BpNpuAllocator              NpuAllocatorCallback
//                             |                              ^
// === PROCESS BOUNDARY =======|==============================|============================
//                             v                              /
// service process:         BnNpuAllocator -> BpNpuAllocatorCallback
//
pub struct NpuManagerDelegate {
    allocator: Strong<dyn INpuAllocator>,
    client: Arc<NpuAllocatorClient>,
    next_app_req_id: Mutex<i64>,
}

impl NpuManagerDelegate {
    pub fn get_instance() -> &'static Result<NpuManagerDelegate, i32> {
        static DELEGATE: std::sync::OnceLock<Result<NpuManagerDelegate, i32>> =
            std::sync::OnceLock::new();
        DELEGATE.get_or_init(|| {
            // Connect to NpuManagerService
            let service_binder =
                binder::get_interface::<dyn INpuManagerService>("npu").map_err(|e| {
                    log::error!("Failed to get NpuManager service: {:?}", e);
                    libc::ENOSYS
                })?;

            let client = Arc::new(NpuAllocatorClient::new());
            let callback = NpuAllocatorCallback::new(client.clone());
            let callback_binder =
                BnNpuAllocatorCallback::new_binder(callback, BinderFeatures::default());

            let allocator = service_binder.createAllocator(&callback_binder).map_err(|e| {
                log::error!("Failed to create allocator: {:?}", e);
                status_to_errno(&e)
            })?;

            Ok(NpuManagerDelegate { allocator, client, next_app_req_id: Mutex::new(0) })
        })
    }

    pub fn reserve_app_req_id(&self, num_requests: usize) -> i64 {
        let mut next_id =
            self.next_app_req_id.lock().expect("Cannot lock to reserve app request IDs.");
        let start_id = *next_id;
        *next_id += num_requests as i64;
        start_id
    }

    pub fn alloc_async<'a, RequestsIter>(&self, requests: RequestsIter) -> Vec<DeferredAllocError>
    where
        RequestsIter: Iterator<Item = &'a ANpuManager_AllocRequest>,
    {
        let requests: Vec<&ANpuManager_AllocRequest> = requests.collect();
        let start_app_req_id = self.reserve_app_req_id(requests.len());
        self.client.alloc_async(self.allocator.clone(), start_app_req_id, &requests)
    }

    pub fn is_supported<'a, RequestsIter>(
        &self,
        requests: RequestsIter,
        out_supported: &mut [bool],
    ) -> std::ffi::c_int
    where
        RequestsIter: Iterator<Item = &'a ANpuManager_AllocRequest>,
    {
        let start_app_req_id = self.reserve_app_req_id(out_supported.len());
        self.client.is_supported(self.allocator.clone(), start_app_req_id, requests, out_supported)
    }

    pub fn free<'a, BuffersIter>(&self, buffers: BuffersIter) -> std::ffi::c_int
    where
        BuffersIter: Iterator<Item = &'a ANpuBufferImpl>,
    {
        self.client.free(self.allocator.clone(), buffers)
    }

    pub fn set_priority(&self, buf: &ANpuBufferImpl, new_priority: i32) -> std::ffi::c_int {
        let status = self.allocator.setPriority(buf.app_req_id(), new_priority);
        match status {
            Ok(_) => 0,
            Err(e) => {
                log::error!("Failed to set priority: {}", e);
                errno::set_errno(errno::Errno(status_to_errno(&e)));
                -1
            }
        }
    }

    pub fn load_async(
        &self,
        buf: &ANpuBufferImpl,
        file_segment: &FileSegment,
        on_load: LoadCallbackFn,
    ) {
        self.client.load_async(self.allocator.clone(), buf, file_segment, on_load);
    }
}
