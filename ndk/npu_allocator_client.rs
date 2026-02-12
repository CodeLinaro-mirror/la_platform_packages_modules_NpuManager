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

//! Holds data for the client side. Implements business logic from the NDK API
//! to the AIDL call.

use crate::alloc_request::ANpuManager_AllocRequest;
use crate::deferred_alloc_error::DeferredAllocError;
use crate::npu_buffer_impl::ANpuBufferImpl;
use crate::translations::status_to_errno;
use crate::try_clone::try_clone_with_id;
use crate::unpack_option::LoadCallbackFn;
use binder::Strong;
use framework_npumanager_aidl::aidl::android::npumanager::{
    FileSegment::FileSegment, INpuAllocator::INpuAllocator,
    INpuAllocatorCallback::INpuAllocatorCallback, NpuBufferGetRequest::NpuBufferGetRequest,
};
use itertools::{Either, Itertools};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

pub struct NpuAllocatorClient {
    buffers: Mutex<HashMap<i64, Arc<ANpuBufferImpl>>>,
}

impl NpuAllocatorClient {
    pub fn new() -> Self {
        Self { buffers: Mutex::new(HashMap::new()) }
    }

    /// Returns a list of errors that needs to be immediately sent to the client.
    pub fn alloc_async(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        start_app_req_id: i64,
        requests: &[&ANpuManager_AllocRequest],
    ) -> Vec<DeferredAllocError> {
        // Try cloning the AIDL requests into a vector so we can send it out.
        let (aidl_requests, mut errors): (Vec<_>, Vec<_>) = {
            let mut buffers =
                self.buffers.lock().expect("Cannot lock to register map of allocated buffers.");

            requests.iter().enumerate().partition_map(|(i, request)| {
                let app_req_id = start_app_req_id + i as i64;

                match try_clone_with_id(request.aidl_request(), app_req_id) {
                    Ok(aidl_request) => {
                        let buffer = Arc::new(ANpuBufferImpl::new(app_req_id, request));
                        buffers.insert(app_req_id, buffer);
                        Either::Left(aidl_request)
                    }
                    Err(e) => Either::Right(DeferredAllocError::new(request, e)),
                }
            })
        };
        // Unlocks before making the binder call to avoid deadlock.

        // Call into NpuManagerService.
        if let Err(e) = allocator.getBuffers(&aidl_requests) {
            // If there an error is immediately thrown, all requests are rejected. Drop entries.
            log::error!("getBuffers failed: {}", e);
            {
                let mut buffers = self
                    .buffers
                    .lock()
                    .expect("Cannot lock to remove entries from map of allocated buffers.");
                for aidl_request in &aidl_requests {
                    buffers.remove(&aidl_request.appReqId);
                }
            }
            // Prepare for the errors to be sent to the app.
            let error_num = status_to_errno(&e);
            for aidl_request in &aidl_requests {
                let index = (aidl_request.appReqId - start_app_req_id) as usize;
                if let Some(request) = requests.get(index) {
                    errors.push(DeferredAllocError::new(request, error_num));
                }
            }
        }
        errors
    }

    /// Returns 0 on success, -1 & set errno on failure.
    pub fn is_supported<'a, RequestsIter>(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        start_app_req_id: i64,
        requests: RequestsIter,
        results: &mut [bool],
    ) -> std::ffi::c_int
    where
        RequestsIter: Iterator<Item = &'a ANpuManager_AllocRequest>,
    {
        let aidl_requests: Vec<NpuBufferGetRequest> = requests
            .enumerate()
            .map(|(i, request)| NpuBufferGetRequest {
                appReqId: start_app_req_id + i as i64,
                // Clear fileSegmentToLoad; isSupported doesn't need it. This avoids the dup().
                fileSegmentToLoad: None,
                ..*request.aidl_request()
            })
            .collect();
        match allocator.isSupported(&aidl_requests) {
            Ok(aidl_results) => {
                aidl_results.into_iter().zip(results.iter_mut()).for_each(
                    |(aidl_result, result)| {
                        *result = aidl_result;
                    },
                );
                0
            }
            Err(e) => {
                log::error!("isSupported failed: {}", e);
                errno::set_errno(errno::Errno(status_to_errno(&e)));
                -1
            }
        }
    }

    /// Returns 0 on success, -1 & set errno on failure.
    pub fn free<'a, BuffersIter>(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        buffers: BuffersIter,
    ) -> std::ffi::c_int
    where
        BuffersIter: Iterator<Item = &'a ANpuBufferImpl>,
    {
        let app_req_ids: Vec<i64> = buffers.map(|buffer| buffer.app_req_id()).collect();
        let status = allocator.putBuffers(&app_req_ids);

        // Regardless of the status, remove the buffers from the map in the app's process.
        {
            let mut buffers_map = self
                .buffers
                .lock()
                .expect("Cannot lock to remove entries from map of allocated buffers.");
            for app_req_id in app_req_ids {
                buffers_map.remove(&app_req_id);
            }
        }

        match status {
            Ok(()) => 0,
            Err(e) => {
                log::error!("putBuffers failed: {}", e);
                errno::set_errno(errno::Errno(status_to_errno(&e)));
                -1
            }
        }
    }

    pub fn load_async(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        buf: &ANpuBufferImpl,
        file_segment: &FileSegment,
        on_load: LoadCallbackFn,
    ) {
        buf.load_async(allocator, file_segment, on_load);
    }

    /// Find the entry in buffers map.
    ///
    /// # Returns
    /// The corresponding ANpuBufferImpl if found, otherwise None.
    pub fn find_entry(&self, appReqId: i64) -> Option<Arc<ANpuBufferImpl>> {
        let buffers =
            self.buffers.lock().expect("Cannot lock to look up map of allocated buffers.");
        buffers.get(&appReqId).cloned()
    }

    /// Removes the entry in buffers map if found.
    pub fn remove_entry(&self, appReqId: i64) {
        let mut buffers = self
            .buffers
            .lock()
            .expect("Cannot lock to remove entries from map of allocated buffers.");
        buffers.remove(&appReqId);
    }

    /// If `bufFd` is Some, sets the buffer FD in the ANpuBufferImpl if found.
    /// If `bufFd` is None, removes the entry.
    ///
    /// # Returns
    /// A tuple of the corresponding ANpuBufferImpl if found, the duplicated FD, and the updated
    /// errno (which may contain the dup() error even when `errorNum` is 0).
    pub fn set_buf_fd_or_drop_entry(
        &self,
        appReqId: i64,
        bufFd: Option<&binder::ParcelFileDescriptor>,
        errorNum: i32,
    ) -> (Option<Arc<ANpuBufferImpl>>, Option<binder::ParcelFileDescriptor>, i32) {
        let mut buffers =
            self.buffers.lock().expect("Cannot lock to look up map of allocated buffers.");
        let buf = match buffers.get(&appReqId) {
            Some(buf) => buf.clone(),
            None => {
                log::error!("Invalid buffer ID from NpuManager service: {}", appReqId);
                return (None, None, libc::ENOENT);
            }
        };

        let (duped, ret_errno) = Self::dup_buf_fd(bufFd, errorNum);
        // If we don't have a valid buffer, remove the entry since the user won't call free() later.
        if duped.is_none() {
            buffers.remove(&appReqId);
        }

        (Some(buf), duped, ret_errno)

        // Unlock before calling the user-provided callback to avoid accidental deadlock.
        // If we don't unlock, the user may try to grab a lock that is held by another thread
        // that waits on self.buffers, causing deadlock.
    }

    /// dup() the replied fd so we can store it in ANpuBufferImpl.
    ///
    /// # Returns
    /// A tuple of the duplicated FD, and the updated errno.
    fn dup_buf_fd(
        buf_fd: Option<&binder::ParcelFileDescriptor>,
        mut ret_errno: i32,
    ) -> (Option<binder::ParcelFileDescriptor>, i32) {
        let expects_fd = ret_errno == 0;

        let duped = match (buf_fd, expects_fd) {
            (Some(fd), true) => {
                // The fd is owned by the callback, so we must clone it before passing it on.
                match fd.try_clone() {
                    Ok(cloned_fd) => Some(cloned_fd),
                    Err(e) => {
                        ret_errno = e.raw_os_error().unwrap_or(libc::EBADF);
                        log::error!("Failed to clone ParcelFileDescriptor: {}", e);
                        None
                    }
                }
            }
            (Some(_), false) => {
                // This should not happen. We don't expect NpuManagerService to return an FD, but
                // it does. To ensure the NDK API's correctness, just ignore the fd.
                log::error!(
                    "BUG in NpuManagerService: error = {}, but an fd is replied",
                    std::io::Error::from_raw_os_error(ret_errno)
                );
                None
            }
            (None, true) => {
                // This should not happen. We expect NpuManagerService to return an FD, but
                // it doesn't, and no errorNum is provided. So we have to set the error number.
                ret_errno = libc::ENOSYS;
                log::error!("BUG in NpuManagerService: No error, but replied fd is none");
                None
            }
            (None, false) => None,
        };
        (duped, ret_errno)
    }
}

impl binder::Interface for NpuAllocatorClient {}
impl INpuAllocatorCallback for NpuAllocatorClient {
    fn onGetBuffer(
        &self,
        appReqId: i64,
        bufFd: Option<&binder::ParcelFileDescriptor>,
        errorNum: i32,
        errMsg: Option<&str>,
    ) -> binder::Result<()> {
        if let Some(errMsg) = errMsg {
            log::error!(
                "Received callback for request {} with error: {:?}: {}",
                appReqId,
                errno::Errno(errorNum),
                errMsg
            );
        }

        let (buf, duped, ret_errno) = self.set_buf_fd_or_drop_entry(appReqId, bufFd, errorNum);

        if let Some(buf) = buf {
            buf.on_allocated(duped, ret_errno);
        }

        Ok(())
    }

    fn onNotifyPreempted(&self, appReqId: i64) -> binder::Result<()> {
        let Some(buf) = self.find_entry(appReqId) else {
            log::error!("Invalid buffer ID from NpuManager service: {}", appReqId);
            return Ok(());
        };

        // Advance the state of ANpuBufferImpl and call on_preempt.
        let from_allocating = buf.on_preempt();

        // If we advanced from Allocating, the user won't call free() later, so remove the entry.
        if from_allocating {
            self.remove_entry(appReqId);
        }

        Ok(())
    }

    fn onLoad(&self, appReqId: i64, errorNum: i32, errMsg: Option<&str>) -> binder::Result<()> {
        if let Some(errMsg) = errMsg {
            log::error!(
                "Received callback for load request with error: {:?}: {}",
                errno::Errno(errorNum),
                errMsg
            );
        }

        let Some(buf) = self.find_entry(appReqId) else {
            log::error!("Invalid buffer ID from NpuManager service: {}", appReqId);
            return Ok(());
        };

        // Advance the state of ANpuBufferImpl and call on_load.
        buf.on_load(errorNum);

        Ok(())
    }
}
