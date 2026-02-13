/*
 * Copyright (C) 2026 The Android Open Source Project
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
 *
 */

//! JNI of NpuManagerServiceImpl

use dma_heap_config_proto::schema::BufferType as ProtoBufferType;
use dmabufheap::BufferAllocator;
use errno::Errno;
use framework_npumanager_aidl::aidl::android::npumanager::BufferType::BufferType as AidlBufferType;
use jni::objects::{JObject, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use libc::{EINVAL, ENOSYS};
use protobuf::Enum;
use std::os::fd::{AsFd, BorrowedFd, IntoRawFd, OwnedFd};
use std::sync::LazyLock;
use wrapfd::{WrapfdDriver, WrapfdOwnershipGuard};

// Static assertions to ensure BufferType consistency between AIDL and Proto.
const _: () = {
    assert!(ProtoBufferType::UNKNOWN as i32 == AidlBufferType::UNKNOWN.0 as i32);
    assert!(ProtoBufferType::MODEL_EXECUTABLE as i32 == AidlBufferType::MODEL_EXECUTABLE.0 as i32);
    assert!(ProtoBufferType::MODEL_WEIGHTS as i32 == AidlBufferType::MODEL_WEIGHTS.0 as i32);
    assert!(ProtoBufferType::CACHE as i32 == AidlBufferType::CACHE.0 as i32);
    assert!(ProtoBufferType::AUXILIARY as i32 == AidlBufferType::AUXILIARY.0 as i32);
};

static WRAPFD_DRIVER: LazyLock<nix::Result<WrapfdDriver>> = LazyLock::new(WrapfdDriver::new);

/// My wrapper of an arbitrary Java exception.
struct JavaException {
    exception_class: &'static str,
    message: String,
    /// The errno to use if the exception needs to be cast to an errno.
    errno: Errno,
}

impl JavaException {
    fn throw(&self, env: &mut JNIEnv) -> jni::errors::Result<()> {
        env.throw_new(self.exception_class, &self.message)
    }
}

/// My wrapper of android.system.ErrnoException.
struct ErrnoException {
    message: String,
    errno: Errno,
}

impl ErrnoException {
    fn new(message: &str, errno: Errno) -> Self {
        Self { message: message.to_string(), errno }
    }

    fn throw(&self, env: &mut JNIEnv) -> jni::errors::Result<()> {
        // TODO do this statically
        let exception_class = env.find_class("android/system/ErrnoException")?;
        let message = env.new_string(self.message.as_str())?;
        let exception_obj = env.new_object(
            &exception_class,
            "(Ljava/lang/String;I)V",
            &[(&message).into(), self.errno.0.into()],
        )?;
        env.throw(jni::objects::JThrowable::from(exception_obj))
    }
}

impl From<JavaException> for ErrnoException {
    // We lose the exception class, but it is okay because the message is still there.
    fn from(e: JavaException) -> Self {
        Self::new(e.message.as_str(), e.errno)
    }
}

/// Converts an i64 to a u64, returning an ErrnoException if the input is negative.
fn non_negative_i64(value: i64, name: &str) -> Result<u64, ErrnoException> {
    value.try_into().map_err(|_| ErrnoException::new(&format!("{name} is negative"), Errno(EINVAL)))
}

/// Calls dmabuf_heap_alloc2() then wrapfd_wrap(), then load the file.
///
/// # Safety
///
/// Caller ensures heap_name and buf_name are valid Java strings from the `env`.
///
/// Caller ensures `borrowed_file_fd` is a valid FD throughout the call, or is -1.
///
/// If return value is non-negative, caller takes ownership of the FD.
///
/// # Returns
/// On success, the file descriptor.
///
/// On error, -1 is returned, and an exception is thrown via `env`.
#[no_mangle]
pub unsafe extern "system" fn Java_com_android_server_npumanager_NpuAllocator_nativeAllocWrapLoad(
    mut env: JNIEnv,
    _this: JObject,
    device_number: jint,
    buffer_type: jint,
    size: jlong,
    buf_name: JString,
    wrap_prot: jint,
    borrowed_file_fd: jint,
    file_segment_offset: jlong,
    file_segment_length: jlong,
    buffer_offset: jlong,
) -> jint {
    let borrowed_file_fd = (borrowed_file_fd >= 0).then(|| {
        // SAFETY: Caller ensures `borrowed_file_fd` is a valid FD throughout the call.
        unsafe { BorrowedFd::borrow_raw(borrowed_file_fd) }
    });
    let result = (|| {
        let buf_name = env.get_string(&buf_name).map_err(|_| {
            ErrnoException::new("Can't get buffer name. JNIEnv::get_string", Errno(EINVAL))
        })?;
        alloc_wrap_load(
            device_number,
            buffer_type,
            size as usize,
            buf_name.to_str().map_err(|_| {
                ErrnoException::new(
                    "Can't cast buffer name to &str. JavaStr::to_str",
                    Errno(EINVAL),
                )
            })?,
            wrap_prot,
            borrowed_file_fd,
            file_segment_offset,
            file_segment_length,
            buffer_offset,
        )
    })();

    result.map_or_else(
        |e| {
            let _ = e.throw(&mut env);
            -1
        },
        |buf| buf.into_raw_fd(),
    )
}

#[allow(clippy::too_many_arguments)]
fn alloc_wrap_load(
    device_number: i32,
    buffer_type: i32,
    size: usize,
    buf_name: &str,
    wrap_prot: i32,
    borrowed_file_fd: Option<BorrowedFd>,
    file_segment_offset: i64,
    file_segment_length: i64,
    buffer_offset: i64,
) -> Result<OwnedFd, ErrnoException> {
    // Allocate
    let heap_name = get_heap_name(device_number, buffer_type).map_err(ErrnoException::from)?;
    let allocator = BufferAllocator::new();
    let buf = allocator.alloc(&heap_name, size).map_err(|e| {
        ErrnoException::new("Cannot allocate on DMA-buf heap. BufferAllocator::alloc", e)
    })?;
    allocator.set_name(&buf, buf_name).map_err(|e| {
        ErrnoException::new("Cannot set name for buffer. BufferAllocator::set_name", e)
    })?;

    // Wrap
    let wrapfd_driver = WRAPFD_DRIVER.as_ref().map_err(|e| {
        ErrnoException::new("Failed to open wrapfd driver. WrapfdDriver::new", Errno(*e as i32))
    })?;

    let wrapped_buf = wrapfd_driver.wrap(buf, wrap_prot as u64).map_err(|e| {
        ErrnoException::new("Can't wrap buffer. WrapfdDriver::wrap", Errno(e as i32))
    })?;

    // Load if necessary
    if let Some(borrowed_file_fd) = borrowed_file_fd {
        load_file_segment(
            wrapped_buf.as_fd(),
            borrowed_file_fd,
            file_segment_offset,
            buffer_offset,
            file_segment_length,
        )?;
    }
    Ok(wrapped_buf)
}

/// Loads a file segment into the buffer.
fn load_file_segment(
    buf: BorrowedFd,
    file: BorrowedFd,
    file_segment_offset: i64,
    buffer_offset: i64,
    file_segment_length: i64,
) -> Result<(), ErrnoException> {
    let mut guard = WrapfdOwnershipGuard::new(buf).map_err(|e| {
        ErrnoException::new(
            "Can't take ownership of buffer. WrapfdOwnershipGuard::new",
            Errno(e as i32),
        )
    })?;
    wrapfd::load(
        buf,
        file,
        non_negative_i64(file_segment_offset, "file_segment_offset")?,
        non_negative_i64(buffer_offset, "buffer_offset")?,
        non_negative_i64(file_segment_length, "file_segment_length")?,
    )
    .map_err(|e| ErrnoException::new("Can't load file segment. wrapfd::load", Errno(e as i32)))?;
    guard.put().map_err(|e| {
        ErrnoException::new(
            "Can't release ownership of buffer. WrapfdOwnershipGuard::put",
            Errno(e as i32),
        )
    })?;
    Ok(())
}

#[no_mangle]
pub unsafe extern "system" fn Java_com_android_server_npumanager_NpuAllocator_nativeLoadFileSegmentToBuffer(
    mut env: JNIEnv,
    _this: JObject,
    buffer_fd: jint,
    borrowed_file_fd: jint,
    file_segment_offset: jlong,
    file_segment_length: jlong,
    buffer_offset: jlong,
) -> jboolean {
    // SAFETY: Caller ensures `buffer_fd` is a valid FD throughout the call.
    let buf = unsafe { BorrowedFd::borrow_raw(buffer_fd) };
    // SAFETY: Caller ensures `borrowed_file_fd` is a valid FD throughout the call.
    let borrowed_file_fd = unsafe { BorrowedFd::borrow_raw(borrowed_file_fd) };

    load_file_segment(
        buf,
        borrowed_file_fd,
        file_segment_offset,
        buffer_offset,
        file_segment_length,
    )
    .inspect_err(|e| {
        let _ = e.throw(&mut env);
    })
    .is_ok() as jboolean
}

/// # Returns
/// * `false` if DMA-buf heap config can't be parsed.
/// * `true` otherwise (the config does not exist or it exists and can be parsed.)
#[no_mangle]
pub extern "system" fn Java_com_android_server_npumanager_NpuAllocator_nativeInitDmaHeapConfig(
) -> jboolean {
    dma_heap_config::get().is_some() as jboolean
}

#[no_mangle]
pub extern "system" fn Java_com_android_server_npumanager_NpuAllocator_nativeInitWrapfdDriver(
) -> jboolean {
    WRAPFD_DRIVER
        .as_ref()
        .inspect_err(|e| {
            log::error!("Failed to open wrapfd driver: {e:?}");
        })
        .is_ok() as jboolean
}

/// # Returns
/// * Throws exception if any error
/// * Otherwise, the heap name without the /dev/dma_heap prefix
#[no_mangle]
pub extern "system" fn Java_com_android_server_npumanager_NpuAllocator_nativeGetHeapName(
    mut env: JNIEnv,
    _this: JObject,
    device_number: jint,
    buffer_type: jint, // android.npumanager.BufferType
) -> jstring {
    let Ok(heap_name) = get_heap_name(device_number, buffer_type).inspect_err(|e| {
        let _ = e.throw(&mut env);
    }) else {
        return std::ptr::null_mut();
    };

    let Ok(heap_name) = env.new_string(heap_name).inspect_err(|e| {
        let _ = JavaException {
            exception_class: "java/lang/IllegalStateException",
            message: format!("Can't create Java string: {e:?}. JNIEnv::new_string"),
            errno: Errno(EINVAL),
        }
        .throw(&mut env);
    }) else {
        return std::ptr::null_mut();
    };

    heap_name.into_raw()
}

/// # Returns
/// * JavaException if any error occurs
/// * Otherwise, the heap name without the /dev/dma_heap prefix
fn get_heap_name(device_number: i32, buffer_type: i32) -> Result<String, JavaException> {
    let Some(config) = dma_heap_config::get() else {
        return Err(JavaException {
            exception_class: "java/lang/IllegalStateException",
            message: "DMA-buf heap config is invalid. get_heap_name".to_string(),
            errno: Errno(EINVAL),
        });
    };

    let Some(target_buffer_type) = ProtoBufferType::from_i32(buffer_type) else {
        return Err(JavaException {
            exception_class: "java/lang/IllegalArgumentException",
            message: "Invalid buffer type. get_heap_name".to_string(),
            errno: Errno(EINVAL),
        });
    };

    for (heap_path, heap_info) in &config.devices {
        let Some(heap_name) = heap_path.strip_prefix("/dev/dma_heap/") else {
            log::error!(
                "Invalid heap path in DMA-buf heap config: {}, ignored. get_heap_name",
                heap_path
            );
            continue;
        };
        for npu_compat in &heap_info.npu {
            if npu_compat.device_numbers.contains(&device_number)
                && npu_compat.buffer_types.contains(&target_buffer_type.into())
            {
                return Ok(heap_name.to_string());
            }
        }
    }

    Err(JavaException {
        exception_class: "java/lang/UnsupportedOperationException",
        message: format!(
            "device_number={device_number}, buffer_type={buffer_type} is not supported. get_heap_name"
        ),
        errno: Errno(ENOSYS),
    })
}
