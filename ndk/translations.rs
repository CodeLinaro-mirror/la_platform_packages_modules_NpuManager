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

//! Provide a meaningful errno for the given binder status.

use binder::{Status, StatusCode};

pub fn status_to_errno(status: &Status) -> i32 {
    match status.exception_code() {
        binder::ExceptionCode::NONE => 0,
        binder::ExceptionCode::SECURITY => libc::EACCES,
        binder::ExceptionCode::BAD_PARCELABLE => libc::EBADMSG,
        binder::ExceptionCode::ILLEGAL_ARGUMENT => libc::EINVAL,
        binder::ExceptionCode::NULL_POINTER => libc::EINVAL,
        binder::ExceptionCode::ILLEGAL_STATE => libc::EINVAL,
        binder::ExceptionCode::NETWORK_MAIN_THREAD => libc::ECONNRESET,
        binder::ExceptionCode::UNSUPPORTED_OPERATION => libc::ENOSYS,
        binder::ExceptionCode::SERVICE_SPECIFIC => status.service_specific_error(),
        binder::ExceptionCode::TRANSACTION_FAILED => {
            // This is a subset of fromStatusT() in libbinder.
            match status.transaction_error() {
                StatusCode::OK => 0,
                // These are negative errnos in non-Windows. See utils/Errors.h.
                e @ (StatusCode::NO_MEMORY
                | StatusCode::INVALID_OPERATION
                | StatusCode::BAD_VALUE
                | StatusCode::NAME_NOT_FOUND
                | StatusCode::PERMISSION_DENIED
                | StatusCode::NO_INIT
                | StatusCode::ALREADY_EXISTS
                | StatusCode::DEAD_OBJECT
                | StatusCode::BAD_INDEX
                | StatusCode::NOT_ENOUGH_DATA
                | StatusCode::WOULD_BLOCK
                | StatusCode::TIMED_OUT
                | StatusCode::UNKNOWN_TRANSACTION) => -(e as i32),
                // The rest are not negative errnos, so we just pick an errno.
                StatusCode::BAD_TYPE => libc::EINVAL,
                StatusCode::FAILED_TRANSACTION => libc::ECONNRESET,
                StatusCode::FDS_NOT_ALLOWED => libc::EBADF,
                StatusCode::UNEXPECTED_NULL => libc::EINVAL,
                _ => libc::ECONNRESET,
            }
        }
        _ => libc::ECONNRESET,
    }
}
