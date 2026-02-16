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

//! Helper trait to unpack ANpuManager_AllocCallback from Option<T> to T,
//! ensuring that it is non-null.

use npumanager_bindings::{ANpuManager_AllocCallback, ANpuManager_LoadCallback};

pub trait UnpackOption {
    type Inner;
}
impl<T> UnpackOption for Option<T> {
    type Inner = T;
}
/// ANpuManager_AllocCallback, but guaranteed to be non-null.
pub type AllocCallbackFn = <ANpuManager_AllocCallback as UnpackOption>::Inner;

/// ANpuManager_LoadCallback, but guaranteed to be non-null.
pub type LoadCallbackFn = <ANpuManager_LoadCallback as UnpackOption>::Inner;
