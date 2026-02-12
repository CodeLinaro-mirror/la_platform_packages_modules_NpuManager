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

//! Implementation of the opaque buffer handle.

use crate::alloc_request::ANpuManager_AllocRequest;
use crate::cookie::Cookie;
use crate::translations::status_to_errno;
use crate::unpack_option::{AllocCallbackFn, LoadCallbackFn};
use binder::Strong;
use errno::Errno;
use framework_npumanager_aidl::aidl::android::npumanager::{
    FileSegment::FileSegment, INpuAllocator::INpuAllocator,
};
use npumanager_bindings::{ANpuBuffer, ANpuManager_PreemptCallback};
use std::os::fd::AsRawFd;
use std::sync::{Arc, Mutex};

pub struct ANpuBufferImpl {
    app_req_id: i64,
    cookie: Arc<Cookie>,
    on_alloc: AllocCallbackFn,
    on_preempt: ANpuManager_PreemptCallback,
    state: Mutex<BufferState>,
}

/// The state machine of a buffer.
/// Allocating -(1)-> Allocated -(2)-> Loading
///     (6)|         (5)|  ^_____(3)____/ |
///        |____________|_________________| (4)
///                     |
///                     V
///                  Preempted
///      Entry point                           |   Mutation Fn                   | Callback
/// (1): ANpuManager_asyncAlloc()              | state_allocating_to_allocated() | on_alloc
/// (2): ANpuBuffer_loadAsync()                | state_allocated_to_loading()    | N/A
/// (3): INpuAllocatorCallback.onLoad()        | state_loading_to_allocated()    | on_load
/// (4): INpuManagerCallback.onNotifyPreempt() | state_to_preempted()            | on_load, on_preempt
/// (5): INpuManagerCallback.onNotifyPreempt() | state_to_preempted()            | on_preempt
/// (6): INpuManagerCallback.onNotifyPreempt() | state_to_preempted()            | on_alloc, on_preempt
#[derive(Debug)]
enum BufferState {
    Allocating,
    Allocated { buf_fd: Option<binder::ParcelFileDescriptor> },
    Preempted,
    Loading { buf_fd: Option<binder::ParcelFileDescriptor>, on_load: LoadCallbackFn },
}

impl ANpuBufferImpl {
    /// # Panics
    /// Panics if request.on_alloc is null.
    pub fn new(app_req_id: i64, request: &ANpuManager_AllocRequest) -> Self {
        Self {
            app_req_id,
            cookie: request.cookie(),
            on_alloc: request.on_alloc_fn_or_panic(),
            on_preempt: request.on_preempt_fn(),
            state: Mutex::new(BufferState::Allocating),
        }
    }

    pub fn app_req_id(&self) -> i64 {
        self.app_req_id
    }

    pub fn cookie(&self) -> Arc<Cookie> {
        self.cookie.clone()
    }

    /// Returns the opaque pointer that the callback wants.
    ///
    /// Casting to *mut ANpuBuffer is okay since the callbacks are FFI and the user
    /// agrees to just treats it as a opaque pointer, and only call ANpuBuffer_*()
    /// functions on it, where we treat the pointer as ANpuBufferImpl.
    pub fn opaque_handle(&self) -> *mut ANpuBuffer {
        self as *const _ as *mut _
    }

    /// Advance the state from Allocating to Allocated and call the on_alloc callback.
    ///
    /// If `buf_fd` is None, we have replied with buf=NULL. Otherwise, we have replied with
    /// a non-NULL buffer.
    pub fn on_allocated(&self, buf_fd: Option<binder::ParcelFileDescriptor>, error_num: i32) {
        let ret_buf = match buf_fd.as_ref() {
            // If we have a valid buffer (buf_fd.is_some()), keep it in our map so the user can
            // ANpuBuffer_free() it later, and reply with non-NULL
            Some(_) => self.opaque_handle(),
            // If we don't have a valid buffer, reply with NULL.
            None => std::ptr::null_mut(),
        };
        self.state_allocating_to_allocated(buf_fd);

        // SAFETY: see ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc.
        unsafe { (self.on_alloc)(self.cookie.raw(), error_num, ret_buf) }
    }

    /// Advance the state from Allocating to Allocated.
    fn state_allocating_to_allocated(&self, buf_fd: Option<binder::ParcelFileDescriptor>) {
        let mut state = self
            .state
            .lock()
            .expect("Cannot lock to change state of ANpuBuffer from Allocating to Allocated.");
        if !matches!(*state, BufferState::Allocating) {
            log::error!(
                "BUG in NpuManagerService: onGetBuffer called twice on req id {}",
                self.app_req_id
            );
            return;
        }
        *state = BufferState::Allocated { buf_fd };
    }

    /// Advance the state from Allocated or Loading to Preempted and call the on_preempt callback.
    ///
    /// # Returns
    /// `true` if the state was changed from Allocating. This tells NpuAllocatorClient to
    /// drop the entry from the buffers map because on_alloc was replied with null.
    pub fn on_preempt(&self) -> bool {
        let from_allocating = self.state_to_preempted();

        if let Some(on_preempt) = self.on_preempt {
            // SAFETY: see ANpuManagerImpl_ANpuManager_AllocRequest_setOnPreempt.
            unsafe { on_preempt(self.cookie.raw()) }
        }
        from_allocating
    }

    /// Advance the state from any state to Preempted, and calls pending on_alloc or on_load with
    /// errors if needed.
    ///
    /// # Returns
    /// `true` if the state was changed from Allocating. This tells NpuAllocatorClient to
    /// drop the entry from the buffers map because on_alloc was replied with null.
    fn state_to_preempted(&self) -> bool {
        let mut delayed_on_alloc: Option<AllocCallbackFn> = None;
        let mut delayed_on_load: Option<LoadCallbackFn> = None;

        let mut state =
            self.state.lock().expect("Cannot lock to change state of ANpuBuffer to Preempted.");
        match *state {
            BufferState::Allocating => {
                delayed_on_alloc = Some(self.on_alloc);
            }
            BufferState::Allocated { .. } => {}
            BufferState::Loading { on_load, .. } => {
                delayed_on_load = Some(on_load);
            }
            BufferState::Preempted => {
                log::error!(
                    "BUG in NpuManagerService: onNotifyPreempt called twice on req id {}",
                    self.app_req_id
                );
            }
        }
        *state = BufferState::Preempted;

        // Unlock before calling the user-provided callback to avoid accidental deadlock.
        drop(state);

        if let Some(on_alloc) = delayed_on_alloc {
            // SAFETY: see ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc.
            unsafe { on_alloc(self.cookie.raw(), libc::ECANCELED, std::ptr::null_mut()) }
        }
        if let Some(on_load) = delayed_on_load {
            // SAFETY: see ANpuManagerImpl_ANpuBuffer_loadAsync.
            unsafe { on_load(self.cookie.raw(), libc::ECANCELED, self.opaque_handle()) }
        }

        delayed_on_alloc.is_some() // from_allocating
    }

    /// # Safety
    /// * `addr` is the user-provided hint sent to mmap(). See mmap() for requirements.
    pub unsafe fn mmap(
        &self,
        addr: *mut std::ffi::c_void,
        length: usize,
        prot: i32,
        flags: i32,
        offset: libc::off_t,
    ) -> *mut std::ffi::c_void {
        let state = self.state.lock().expect("Cannot lock to retrieve FD of ANpuBuffer.");
        match *state {
            BufferState::Allocated { buf_fd: Some(ref fd) } => {
                // SAFETY:
                // - addr: user-provided.
                // - fd: ANpuBufferImpl owns the fd and ensuring it is valid throughout the mmap
                //   call.
                unsafe { libc::mmap(addr, length, prot, flags, fd.as_raw_fd(), offset) }
            }
            BufferState::Allocated { buf_fd: None } => {
                errno::set_errno(errno::Errno(libc::EBADF));
                libc::MAP_FAILED
            }
            _ => {
                errno::set_errno(errno::Errno(libc::ENOMEM));
                libc::MAP_FAILED
            }
        }
    }

    pub fn load_async(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        file_segment: &FileSegment,
        on_load: LoadCallbackFn,
    ) {
        match self.load_async_internal(allocator, file_segment, on_load) {
            Ok(()) => {}
            Err(e) => {
                errno::set_errno(e);
                // SAFETY: see ANpuManagerImpl_ANpuBuffer_loadAsync.
                unsafe { on_load(self.cookie.raw(), e.0, self.opaque_handle()) }
            }
        }
    }

    /// # Returns
    /// If request is sent successfully, returns Ok(()), and `on_load` will be called later.
    /// Otherwise, returns Err(errno) and `on_load` will not be called.
    fn load_async_internal(
        &self,
        allocator: Strong<dyn INpuAllocator>,
        file_segment: &FileSegment,
        on_load: LoadCallbackFn,
    ) -> Result<(), Errno> {
        self.state_allocated_to_loading(on_load)?;
        allocator
            .loadFileSegmentToBuffer(self.app_req_id, file_segment)
            .map_err(|e| Errno(status_to_errno(&e)))
    }

    /// Advance the state from Allocated to Loading.
    fn state_allocated_to_loading(&self, on_load: LoadCallbackFn) -> Result<(), Errno> {
        let mut state = self
            .state
            .lock()
            .expect("Cannot lock to change state of ANpuBuffer from Allocated to Loading.");
        let buf_fd = if let BufferState::Allocated { buf_fd } = &mut *state {
            buf_fd.take()
        } else {
            log::error!(
                "Invalid state of buffer to load: {:#?}. Dup load request? Buffer preempted?",
                state
            );
            return Err(Errno(libc::EINVAL));
        };
        *state = BufferState::Loading { buf_fd, on_load };
        Ok(())
    }

    pub fn on_load(&self, error_num: i32) {
        let Some(on_load) = self.state_loading_to_allocated() else {
            return;
        };

        // Unlock before calling the user-provided callback to avoid accidental deadlock.

        // SAFETY: see ANpuManagerImpl_ANpuBuffer_loadAsync.
        unsafe {
            on_load(self.cookie.raw(), error_num, self.opaque_handle());
        }
    }

    /// Advance the state from Loading to Allocated and returns the on_load callback.
    ///
    /// # Returns
    /// The on_load callback if the state was changed from Loading. Otherwise, returns `None`.
    fn state_loading_to_allocated(&self) -> Option<LoadCallbackFn> {
        let mut state = self
            .state
            .lock()
            .expect("Cannot lock to change state of ANpuBuffer from Loading to Allocated.");
        match &mut *state {
            BufferState::Allocating => {
                log::error!("BUG in NpuManagerService called onLoad on buffer in Allocating state");
                None
            }
            BufferState::Allocated { .. } => {
                log::error!("Buffer in Allocated state. Duplicate load request?");
                None
            }
            BufferState::Loading { buf_fd, on_load } => {
                let on_load = *on_load;
                *state = BufferState::Allocated { buf_fd: buf_fd.take() };
                Some(on_load)
            }
            BufferState::Preempted => {
                // This is possible. Callback already called in state_to_preempted().
                None
            }
        }
    }
}
