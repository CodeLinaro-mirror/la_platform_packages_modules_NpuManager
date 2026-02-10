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

//! Struct that can record an error for a single request, such that the error can be replied
//! later.

use crate::alloc_request::ANpuManager_AllocRequest;
use crate::cookie::Cookie;
use crate::unpack_option::AllocCallbackFn;
use std::sync::Arc;

pub struct DeferredAllocError {
    cookie: Arc<Cookie>,
    error_num: i32,
    on_alloc: AllocCallbackFn,
}
impl DeferredAllocError {
    /// # Panics
    /// Panics if request.on_alloc is null.
    pub fn new(request: &ANpuManager_AllocRequest, error_num: i32) -> Self {
        // The doc of ANpuManager_allocAsync() mentions that if onAlloc is null then it'll crash.
        Self { cookie: request.cookie(), error_num, on_alloc: request.on_alloc_fn_or_panic() }
    }
    pub fn call(self) {
        // SAFETY: see ANpuManagerImpl_ANpuManager_AllocRequest_setOnAlloc.
        unsafe { (self.on_alloc)(self.cookie.raw(), self.error_num, std::ptr::null_mut()) }
    }
}
