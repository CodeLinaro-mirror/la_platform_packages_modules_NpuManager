/*
 * Copyright (C) 2025 The Android Open Source Project
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

#include <android/log.h>
#include <jni.h>

#include <string>

#include "nnapi_inference.h"

#define LOG_TAG "NnapiDelegateAppJNI"
extern "C" JNIEXPORT jstring JNICALL

#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

Java_com_android_npumanager_delegateapp_MainActivity_runInference(
        JNIEnv* env, jobject /* this */, jbyteArray model_buffer,
        jbyteArray input_buffer) {

    if (model_buffer == nullptr) {
        ALOGE("Model buffer is null");
        return env->NewStringUTF("Error: Model buffer is null");
    }

    jbyte* model_data = env->GetByteArrayElements(model_buffer, nullptr);
    jsize model_size = env->GetArrayLength(model_buffer);

    if (model_data == nullptr) {
        ALOGE("Failed to get model data from byte array");
        return env->NewStringUTF("Error: Failed to get model data");
    }


    jbyte* input_data = env->GetByteArrayElements(input_buffer, nullptr);
    jsize input_size = env->GetArrayLength(input_buffer);
    if (input_data == nullptr) {
        env->ReleaseByteArrayElements(model_buffer, model_data, JNI_ABORT);
        ALOGE("Failed to get input data from byte array");
        return env->NewStringUTF("Error: Failed to get input data");
    }

    std::string result =
            RunNnapiInference(reinterpret_cast<const char*>(model_data), model_size,
                              reinterpret_cast<const char*>(input_data), input_size);

    env->ReleaseByteArrayElements(model_buffer, model_data, JNI_ABORT);
    env->ReleaseByteArrayElements(input_buffer, input_data, JNI_ABORT);


    return env->NewStringUTF(result.c_str());
}