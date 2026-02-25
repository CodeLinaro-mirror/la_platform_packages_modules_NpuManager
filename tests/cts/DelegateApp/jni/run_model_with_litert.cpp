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

#include "run_model_with_litert.h"

#include <android/log.h>

#define LOG_TAG "RunModelWithLitert"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

std::string RunModelWithLitert(int* status) {
    ALOGI("RunModelWithLitert is a placeholder and does not execute a model.");
    // TODO: Add model execution logic here.
    *status = 0;
    return "RunModelWithLitert executed successfully (placeholder).";
}
