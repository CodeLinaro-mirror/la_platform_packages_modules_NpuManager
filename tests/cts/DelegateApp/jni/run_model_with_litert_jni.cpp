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

#include <jni.h>

#include <string>

#include "run_model_with_litert.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_android_npumanager_delegateapp_MainActivity_runLiteRtInference(JNIEnv* env,
                                                                        jobject /* this */) {
    int status = 0;
    std::string result = RunModelWithLitert(&status);

    if (status != 0) {
        env->ThrowNew(env->FindClass("java/lang/RuntimeException"), result.c_str());
    }

    return env->NewStringUTF(result.c_str());
}
