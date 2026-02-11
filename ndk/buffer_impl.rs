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

//! libnpumanager: library to talk to NpuManagerService.

#![allow(non_camel_case_types)]
#![allow(non_snake_case)]

mod alloc_request;
mod cookie;
mod deferred_alloc_error;
mod npu_allocator_callback;
mod npu_allocator_client;
mod npu_buffer_impl;
mod npu_manager_delegate;
mod translations;
mod try_clone;
mod unpack_option;

use crate::alloc_request::ANpuManager_AllocRequest;
use crate::deferred_alloc_error::DeferredAllocError;
use crate::npu_buffer_impl::ANpuBufferImpl;
use crate::npu_manager_delegate::NpuManagerDelegate;
use errno::{set_errno, Errno};
use framework_npumanager_aidl::aidl::android::npumanager::FileSegment::FileSegment;
use npumanager_bindings::ANpuManager_LoadCallback;
use std::os::fd::{FromRawFd, OwnedFd};

/// Tests if the provided requests are supported or not.
///
/// # Safety
/// The user is responsible for providing a non-null requests array with
/// valid pointers to `ANpuManager_AllocRequest`. The array must not be modified
/// during the call. The array elements must not be modified or freed during the call,
/// e.g. with `ANpuManagerImpl_ANpuManager_AllocRequest_setXXX` and
/// `ANpuManagerImpl_ANpuManager_AllocRequest_free()`.
///
/// The user is responsible for providing a non-null outIsSupported array that
/// has `requestsLen` elements and can be written to by this function.
///
/// # Arguments
/// * `requests` - A list of requests, each describing a buffer to allocate.
/// * `requestsLen` - The length of the requests list.
/// * `outIsSupported` - The output boolean array to store the support status
///   of each request.
/// # Returns
/// 0 on success, or -1 on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_isSupported(
    requests: *const *mut ANpuManager_AllocRequest,
    requestsLen: usize,
    outIsSupported: *mut bool,
) -> std::ffi::c_int {
    // SAFETY: The caller ensures `requests` is valid for `requestsLen` elements.
    let requests_slice = unsafe { std::slice::from_raw_parts(requests, requestsLen) };
    // SAFETY: The caller ensures that the pointers in `requests` are valid.
    let requests_refs = requests_slice.iter().map(|&ptr| unsafe { &*ptr });
    // SAFETY: The caller ensures `outIsSupported` is valid for `requestsLen` elements.
    let results_slice = unsafe { std::slice::from_raw_parts_mut(outIsSupported, requestsLen) };

    match NpuManagerDelegate::get_instance() {
        Ok(delegate) => delegate.is_supported(requests_refs, results_slice),
        // If there is any error (e.g. no NpuManagerService, NpuManagerService can't
        // provide an allocator because the DMA buf heap config cannot be parsed, etc.),
        // propagate the error.
        Err(e) => {
            set_errno(Errno(*e));
            -1
        }
    }
}

/// Asynchronously allocates multiple buffers.
///
/// # Safety
/// The user is responsible for providing a non-null requests array with
/// valid pointers to `ANpuManager_AllocRequest`. The array must not be modified
/// during the call. The array elements must not be modified or freed during the call,
/// e.g. with `ANpuManagerImpl_ANpuManager_AllocRequest_setXXX` and
/// `ANpuManagerImpl_ANpuManager_AllocRequest_free()`.
///
/// # Arguments
/// * `requests` - A list of requests, each describing a buffer to allocate.
/// * `requestsLen` - The length of the requests list.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_allocAsync(
    requests: *const *mut ANpuManager_AllocRequest,
    requestsLen: usize,
) {
    // SAFETY: The caller ensures that `requests` is a valid pointer and has
    // `requestsLen` elements.
    let requests_slice = unsafe { std::slice::from_raw_parts(requests, requestsLen) };

    // SAFETY: The caller ensures that each element of `requests` is a valid pointer.
    let requests_refs = requests_slice.iter().map(|&ptr| unsafe { &*ptr });

    match NpuManagerDelegate::get_instance() {
        Ok(delegate) => {
            delegate.alloc_async(requests_refs).into_iter().for_each(DeferredAllocError::call)
        }
        Err(e) => requests_refs.for_each(|req| req.on_failure(*e)),
    }
}

/// Notifies NpuManager that the app is done with these buffers. NpuManager may
/// reuse these buffers or free them afterwards.
///
/// # Safety
/// The user is responsible for providing a non-null buffers array with
/// valid pointers to `ANpuBuffer`. The array must not be modified
/// during the call. The array elements must not be provided to another
/// `ANpuManagerImpl_ANpuBuffer_free()` call, either after this call or
/// concurrently in another thread (i.e. no double-free).
///
/// # Arguments
/// * `buffers` - A list of buffers to free.
/// * `buffersLen` - The length of the buffers list.
/// # Returns
/// 0 on success, or -1 on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_free(
    buffers: *const *mut ANpuBufferImpl,
    buffersLen: usize,
) -> std::ffi::c_int {
    // SAFETY: The caller ensures `buffers` is valid for `buffersLen` elements.
    let buffers_slice = unsafe { std::slice::from_raw_parts(buffers, buffersLen) };

    // SAFETY: The caller ensures that the pointers in `buffers` are valid.
    let buffers_refs = buffers_slice.iter().map(|&ptr| unsafe { &*ptr });

    match NpuManagerDelegate::get_instance() {
        Ok(delegate) => delegate.free(buffers_refs),
        Err(e) => {
            set_errno(Errno(*e));
            -1
        }
    }
}

/// Maps a buffer into the application's address space.
///
/// # Safety
/// The caller is responsible for providing a valid buffer array received from
/// the `onAlloc` callback and not freed. The caller is also responsible
/// for ensuring that the array itself is valid throughout the function call.
///
/// # Arguments
/// * `buf` - The buffer to map.
/// * `addr` - passed to mmap().
/// * `length` - passed to mmap().
/// * `prot` - passed to mmap().
/// * `flags` - passed to mmap().
/// * `offset` - passed to mmap().
/// # Returns
/// The mapped address on success, or MAP_FAILED on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_map(
    buf: &mut ANpuBufferImpl,
    addr: *mut std::ffi::c_void,
    length: usize,
    prot: std::ffi::c_int,
    flags: std::ffi::c_int,
    offset: libc::off_t,
) -> *mut std::ffi::c_void {
    // SAFETY: The caller of ANpuManagerImpl_ANpuBuffer_map ensures that |addr|
    //   is safe to be passed to mmap().
    unsafe { buf.mmap(addr, length, prot, flags, offset) }
}

/// Unmaps a previously mapped buffer.
///
/// # Safety
/// The caller is responsible for providing addr/length from a previous call to
/// `ANpuBuffer_map()`.
///
/// # Arguments
/// * `_buf` - The buffer to unmap.
/// * `addr` - passed to munmap().
/// * `length` - passed to munmap().
/// # Returns
/// 0 on success, or -1 on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_unmap(
    _buf: &mut ANpuBufferImpl,
    addr: *mut std::ffi::c_void,
    length: usize,
) -> std::ffi::c_int {
    // SAFETY: The caller ensures that `addr` and `length` are valid.
    unsafe { libc::munmap(addr, length) }
}

/// Sets the priority of the buffer.
///
/// # Safety
/// The caller is responsible for providing a valid buffer received from
/// the `onAlloc` callback and not freed.
///
/// # Arguments
/// * `buf` - The buffer to set the priority of.
/// * `newBufferPriority` - The new priority of the buffer.
/// # Returns
/// 0 on success, or -1 on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_setPriority(
    buf: &mut ANpuBufferImpl,
    newBufferPriority: i32,
) -> std::ffi::c_int {
    match NpuManagerDelegate::get_instance() {
        Ok(delegate) => delegate.set_priority(buf, newBufferPriority),
        Err(e) => {
            set_errno(Errno(*e));
            -1
        }
    }
}

/// Loads a file into the buffer asynchronously.
///
/// # Safety
/// The user is responsible for providing a valid buffer received from
/// the `onAlloc` callback and not `ANpuManagerImpl_ANpuBuffer_free()`d.
///
/// The user is responsible for providing a valid file descriptor, which
/// ownership is transferred to NpuManager.
///
/// # Arguments
/// * `buf` - The buffer to load into.
/// * `fdToOwn` - The file descriptor of the file to load. Must be a valid file
///   descriptor. The ownership of the fd is transferred to ANpuManager.
/// * `fileOffset` - The offset of the file segment to load, starting from the beginning of the
///   file.
/// * `segmentLength` - The length of the file segment to load. Must be non-negative.
/// * `bufferOffset` - The offset in the buffer to start loading to. Must be non-negative.
/// * `onLoad` - The callback to be invoked when loading is finished or has encountered an error.
///   See documentation of ANpuManager_LoadCallback for details about the arguments
///   when the callback is invoked.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_loadAsync(
    buf: &mut ANpuBufferImpl,
    fdToOwn: i32,
    fileOffset: i64,
    segmentLength: i64,
    bufferOffset: i64,
    onLoad: ANpuManager_LoadCallback,
) {
    // SAFETY: The caller gives us the ownership of fdToOwn.
    let file_fd = unsafe { OwnedFd::from_raw_fd(fdToOwn) };
    let file_segment = FileSegment {
        fileFd: Some(binder::ParcelFileDescriptor::new(file_fd)),
        fileOffset,
        segmentLength,
        bufferOffset,
    };
    let on_load = onLoad.expect("onLoad is null");
    match NpuManagerDelegate::get_instance() {
        Ok(delegate) => delegate.load_async(buf, &file_segment, on_load),
        Err(e) => {
            let cookie = buf.cookie();
            // SAFETY: see ANpuManager_LoadCallback
            unsafe { on_load(cookie.raw(), *e, buf.opaque_handle()) }
        }
    }
}
