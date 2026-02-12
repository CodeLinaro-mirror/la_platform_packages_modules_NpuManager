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

//! trait for try_clone

use framework_npumanager_aidl::aidl::android::npumanager::{
    FileSegment::FileSegment, NpuBufferGetRequest::NpuBufferGetRequest,
};

trait TryClone {
    fn try_clone(&self) -> Result<Self, std::io::Error>
    where
        Self: Sized;
}

impl TryClone for binder::ParcelFileDescriptor {
    fn try_clone(&self) -> Result<Self, std::io::Error> {
        self.try_clone()
    }
}

impl<T: TryClone> TryClone for Option<T> {
    fn try_clone(&self) -> Result<Self, std::io::Error> {
        self.as_ref().map(|x| x.try_clone()).transpose()
    }
}

impl TryClone for FileSegment {
    fn try_clone(&self) -> Result<Self, std::io::Error> {
        Ok(FileSegment { fileFd: self.fileFd.try_clone()?, ..*self })
    }
}

impl TryClone for NpuBufferGetRequest {
    fn try_clone(&self) -> Result<Self, std::io::Error> {
        Ok(NpuBufferGetRequest { fileSegmentToLoad: self.fileSegmentToLoad.try_clone()?, ..*self })
    }
}

/// Clones the AIDL request, assigning the given app_req_id to the clone.
pub fn try_clone_with_id(
    req: &NpuBufferGetRequest,
    app_req_id: i64,
) -> Result<NpuBufferGetRequest, i32> {
    let mut cloned = req.try_clone().map_err(|e| e.raw_os_error().unwrap_or(libc::EBADF))?;
    cloned.appReqId = app_req_id;
    Ok(cloned)
}
