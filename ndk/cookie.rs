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

//! Implements ownership of the cookie.

use npumanager_bindings::ANpuManager_CookieDeleter;
use std::sync::Arc;

/// Owns a user cookie.
pub struct Cookie {
    raw: *mut std::ffi::c_void,
    deleter: ANpuManager_CookieDeleter,
}
impl Cookie {
    /// Creates a new cookie.
    ///
    /// # Safety
    /// The caller must ensure that `deleter` is either None, or a valid function pointer that
    /// can be called exactly once with `raw` as an argument, in an arbitrary thread.
    pub unsafe fn new(raw: *mut std::ffi::c_void, deleter: ANpuManager_CookieDeleter) -> Arc<Self> {
        Arc::new(Self { raw, deleter })
    }
    pub fn raw(&self) -> *mut std::ffi::c_void {
        self.raw
    }
}
impl Drop for Cookie {
    fn drop(&mut self) {
        if let Some(deleter) = self.deleter {
            // SAFETY: By the variants we maintain in `new()`, `deleter` is safe to be called
            // with `self.raw`.
            unsafe { deleter(self.raw) };
        }
    }
}

// SAFETY: The only thing another thread can do with Cookie is:
// 1. Drop it, which triggers `deleter`. The `new()` function assumes that the user is providing a
//    `deleter` that can be called in an arbitrary thread. The `Arc` wrapper that `new()` returns
//    ensures that the `deleter` is only called once.
// 2. Call `raw()` which returns the raw pointer. This is safe.
unsafe impl Send for Cookie {}

// SAFETY: See Send above.
unsafe impl Sync for Cookie {}
