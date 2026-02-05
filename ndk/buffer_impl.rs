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
#![allow(unused_variables)]

mod alloc_request;
mod cookie;

use crate::alloc_request::ANpuManager_AllocRequest;
use errno::{set_errno, Errno};
use npumanager_bindings::ANpuBuffer;

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
    // SAFETY: The caller ensures `outIsSupported` is valid for `requestsLen` elements.
    let results_slice = unsafe { std::slice::from_raw_parts_mut(outIsSupported, requestsLen) };
    results_slice.fill(false);
    0
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

    requests_refs.for_each(|request| request.on_failure(libc::ENOSYS));
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
    buffers: *const *mut ANpuBuffer,
    buffersLen: usize,
) -> std::ffi::c_int {
    set_errno(Errno(libc::ENOSYS));
    -1
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
    buf: &mut ANpuBuffer,
    addr: *mut std::ffi::c_void,
    length: usize,
    prot: std::ffi::c_int,
    flags: std::ffi::c_int,
    offset: libc::off_t,
) -> *mut std::ffi::c_void {
    set_errno(Errno(libc::ENOSYS));
    libc::MAP_FAILED
}

/// Unmaps a previously mapped buffer.
///
/// # Safety
/// The caller is responsible for providing addr/length from a previous call to
/// `ANpuBuffer_map()`.
///
/// # Arguments
/// * `buf` - The buffer to unmap.
/// * `addr` - passed to munmap().
/// * `length` - passed to munmap().
/// # Returns
/// 0 on success, or -1 on error with errno set.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuBuffer_unmap(
    buf: &mut ANpuBuffer,
    addr: *mut std::ffi::c_void,
    length: usize,
) -> std::ffi::c_int {
    set_errno(Errno(libc::ENOSYS));
    -1
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
    buf: &mut ANpuBuffer,
    newBufferPriority: i32,
) -> std::ffi::c_int {
    set_errno(Errno(libc::ENOSYS));
    -1
}
