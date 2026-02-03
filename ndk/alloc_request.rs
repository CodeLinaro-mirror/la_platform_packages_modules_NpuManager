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

//! Implements the allocation request type.

use crate::cookie::Cookie;
use npumanager_bindings::{
    ANpuBuffer_Type, ANpuManager_AllocCallback, ANpuManager_CookieDeleter,
    ANpuManager_PreemptCallback,
};
use std::sync::Arc;

/// Allocation request.
pub struct ANpuManager_AllocRequest {
    cookie: Arc<Cookie>,
    on_alloc: ANpuManager_AllocCallback,
}

impl ANpuManager_AllocRequest {
    // Reports a failure. error_num must be non-zero.
    pub fn on_failure(&self, error_num: i32) {
        let error_num = if error_num == 0 { libc::EINVAL } else { error_num };
        // Doc of ANpuManager_allocAsync requires onAlloc to be set.
        let on_alloc = self.on_alloc.expect("onAlloc is null");
        // SAFETY: see ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc.
        unsafe {
            on_alloc(self.cookie.raw(), error_num, std::ptr::null_mut());
        }
    }
}

/// Creates a new allocation request. Fields are initialized to a default state.
///
/// # Returns
/// A new allocation request, or NULL on error.
#[no_mangle]
pub extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_create() -> *mut ANpuManager_AllocRequest
{
    let req = Box::new(ANpuManager_AllocRequest {
        // SAFETY: Cookie::new allows deleter to be None.
        cookie: unsafe { Cookie::new(std::ptr::null_mut(), None) },
        on_alloc: None,
    });
    // This leaks the raw pointer, which the user takes ownership of.
    Box::into_raw(req)
}

/// Destroys an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request to destroy.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_free(
    request: *mut ANpuManager_AllocRequest,
) {
    // SAFETY: the caller is responsible for providing an object created by
    // `ANpuManager_AllocRequest_create`.
    drop(unsafe { Box::from_raw(request) });
}

/// Sets the user cookie for an allocation request.
///
/// # Safety
/// * request: The caller is responsible for providing an object created by
///   `ANpuManager_AllocRequest_create`.
/// * cookie: This can be any value, including None or an arbitrary value.
///   The user must ensure that existing onAlloc and onPreempt callbacks must be
///   able to handle the new cookie value after this function is called, and the
///   deleter must be able to handle the cookie value.
/// * cookie_deleter: The caller is responsible for providing a valid deleter,
///   or None. The deleter must be able to handle the cookie value, which may
///   be None, gracefully. The deleter must be able to be called from any
///   thread. The deleter will be called at most once.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `cookie` - The user cookie.
/// * `cookie_deleter` - The deleter function to be called when libnpumanager
///   is done with the cookie.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setCookie(
    request: &mut ANpuManager_AllocRequest,
    cookie: *mut std::ffi::c_void,
    cookie_deleter: ANpuManager_CookieDeleter,
) {
    // INVARIANT: The caller asserted that the new cookie is compatible with the last registered
    //   on_alloc/on_preempt callback, if any
    // SAFETY: The caller asserted that the deleter is either None or a function that can be called
    //   exactly once from any thread to delete the cookie.
    request.cookie = unsafe { Cookie::new(cookie, cookie_deleter) };
}

/// Sets the device number for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `deviceNumber` - The device number.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setDeviceNumber(
    request: &mut ANpuManager_AllocRequest,
    deviceNumber: i32,
) {
}

/// Sets the purpose of the buffer for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `bufferType` - The buffer type.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setBufferType(
    request: &mut ANpuManager_AllocRequest,
    bufferType: ANpuBuffer_Type,
) {
}

/// Sets the size of the buffer in bytes for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `size` - The size of the buffer.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setSize(
    request: &mut ANpuManager_AllocRequest,
    size: i64,
) {
}

/// Sets the buffer priority for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `bufferPriority` - The buffer priority.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setBufferPriority(
    request: &mut ANpuManager_AllocRequest,
    bufferPriority: i32,
) {
}

/// Sets the file segment to load for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// The implementation takes ownership of `fdToOwn`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `fdToOwn` - The file descriptor of the file to load.
/// * `fileOffset` - The offset in the file to start loading from.
/// * `segmentLength` - The length of the file segment to load.
/// * `bufferOffset` - The offset in the buffer to start loading to.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setFileSegmentToLoad(
    request: &mut ANpuManager_AllocRequest,
    fdToOwn: std::ffi::c_int,
    fileOffset: i64,
    segmentLength: i64,
    bufferOffset: i64,
) {
}

/// Sets the protection flags for the buffer.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `prot` - The protection flags. Either PROT_NONE, or the bitwise OR of one or more of
///   PROT_READ, PROT_WRITE, PROT_EXEC.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setProtectionFlags(
    request: &mut ANpuManager_AllocRequest,
    prot: std::ffi::c_int,
) {
}

/// Sets the allocation callback for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// The caller is responsible for providing a valid callback, which must not
/// be null.
///
/// **Safety of arguments of the callback:**
///
/// * cookie:
///     * This is the last value set in
///       ANpuManagerImpl_ANpuManager_AllocRequest_setCookie() before the
///       ANpuManagerImpl_ANpuManager_allocAsync() call.
///     * The user must ensure that the cookie is valid during the onAlloc call.
/// * ret_buf:
///     * On error, this is NULL, and error_num is set to an Linux errno value. The
///       callback must be able to handle NULL.
///     * On success, this is a pointer to an ANpuBuffer, and error_num is 0. The user
///       may use the pointer to call ANpuBuffer_* functions on it, and is expected to eventually
///       call ANpuBuffer_free() on it.
///
/// # Arguments
/// * `request` - The allocation request.
/// * `onAlloc` - The allocation callback.
//
// Implementation note about safety:
// * ret_buf:
//     * ret_buf is in the buffers map until ANpuBuffer_free(), so ret_buf must be valid
//           at least until ANpuBuffer_free() is called.
//     * We are still holding buf: Arc<ANpuBufferImpl>, so ret_buf is valid during the
//           on_alloc call.
//     * We are casting it to ANpuBuffer, but this won't change the
//       value of it. We'll later receive the raw pointer as ANpuBuffer* in the
//       ANpuBuffer_* functions, where we'll cast it back to ANpuBufferImpl*.
// * cookie:
//     * The cookie is managed by ANpuBufferImpl:
//     * The cookie is in the buffers map until ANpuBuffer_free(), so cookie must be valid
//       at least until ANpuBuffer_free() is called.
//     * We are still holding buf: Arc<ANpuBufferImpl>, so cookie is valid during the on_alloc
//       call.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc(
    request: &mut ANpuManager_AllocRequest,
    onAlloc: ANpuManager_AllocCallback,
) {
    if onAlloc.is_none() {
        panic!("onAlloc is null");
    }
    request.on_alloc = onAlloc;
}

/// Sets the preemption callback for an allocation request.
///
/// # Safety
/// The caller is responsible for providing an object created by
/// `ANpuManager_AllocRequest_create`.
///
/// The caller is responsible for providing a valid callback, which may
/// be null.
///
/// **Safety of arguments of the callback:**
///
/// * cookie:
///     * This is the value previously provided in ANpuManager_AllocRequest_setCookie().
///
/// # Arguments
/// * `request` - The allocation request.
/// * `onPreempt` - The preemption callback.
//
// See ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc for more details about the callback
// safety.
#[no_mangle]
pub unsafe extern "C" fn ANpuManagerImpl_ANpuManager_AllocRequest_setOnPreempt(
    request: &mut ANpuManager_AllocRequest,
    onPreempt: ANpuManager_PreemptCallback,
) {
}
