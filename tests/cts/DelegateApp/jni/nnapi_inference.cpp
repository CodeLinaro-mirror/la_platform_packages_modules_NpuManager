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

#include "nnapi_inference.h"

#include <android/log.h>

#include <algorithm>
#include <cstdint>
#include <memory>
#include <numeric>
#include <sstream>
#include <vector>

#include "tensorflow/lite/c/c_api_types.h"
#include "tensorflow/lite/c/common.h"
#include "tensorflow/lite/delegates/nnapi/nnapi_delegate.h"
#include "tensorflow/lite/interpreter.h"
#include "tensorflow/lite/kernels/register.h"
#include "tensorflow/lite/model_builder.h"

#define LOG_TAG "NnapiInferenceCc"
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

std::string RunNnapiInference(const char* model_data, size_t model_size,
                              const char* input_bin_data,
                              size_t input_bin_size, int* status) {
    *status = -1;
    std::string model_path =  "assets/mobilenet_v2_224_100.tflite";
    std::string input_path = "/data/local/tmp/panda.ndarray";

    ALOGI("Starting NNAPI inference from buffer.");

    auto model = tflite::FlatBufferModel::BuildFromBuffer(model_data, model_size);
    if (!model) {
        return "Error: Failed to load model from buffer";
    }
    ALOGI("Model loaded successfully.");

    tflite::ops::builtin::BuiltinOpResolver resolver;
    std::unique_ptr<tflite::Interpreter> interpreter;
    if (tflite::InterpreterBuilder(*model, resolver)(&interpreter) != kTfLiteOk) {
        return "Error: Failed to build interpreter";
    }

    tflite::StatefulNnApiDelegate::Options delegate_options;
    delegate_options.accelerator_name = "google-edgetpu";
    delegate_options.model_token = "test";
    delegate_options.cache_dir = "/data/local/tmp";

    tflite::StatefulNnApiDelegate nnapi_delegate(delegate_options);

    TfLiteStatus delegate_status =
            interpreter->ModifyGraphWithDelegate(&(nnapi_delegate));
    if (delegate_status != kTfLiteOk) {
        std::stringstream ss;
        ss << "Error: Failed to apply NNAPI delegate (Status: " << delegate_status << ")";
        ALOGE("%s", ss.str().c_str());
        return ss.str();
    } else {
        ALOGI("Successfully applied NNAPI delegate for google-edgetpu.");
    }

    if (interpreter->AllocateTensors() != kTfLiteOk) {
        return "Error: Failed to allocate tensors";
    }
    ALOGI("Tensors allocated.");

    size_t input_offset = 0;
    if (interpreter->inputs().empty()) {
        return "Error: Model has no inputs.";
    }

    for (int i = 0; i < interpreter->inputs().size(); ++i) {
        int tensor_index = interpreter->inputs()[i];
        TfLiteTensor* input_tensor = interpreter->tensor(tensor_index);
        size_t tensor_bytes = input_tensor->bytes;

        if (input_offset + tensor_bytes > input_bin_size) {
            std::stringstream ss;
            ss << "Error: Input data buffer too small. Needed "
               << (input_offset + tensor_bytes) << " but only have "
               << input_bin_size << " at input index " << i;
            ALOGE("%s", ss.str().c_str());
            return ss.str();
        }

        memcpy(input_tensor->data.raw, input_bin_data + input_offset,
               input_tensor->bytes);
        input_offset += tensor_bytes;
        ALOGI("Filled input tensor %d (%s) with %zu bytes", i, input_tensor->name, tensor_bytes);
    }

    if (input_offset != input_bin_size) {
        ALOGW("Input buffer size (%zu) does not match total size of input tensors (%zu)."
              , input_bin_size, input_offset);
    }
    ALOGI("All input tensors filled from asset data.");


    for (int i = 0; i < 1000; ++i) {
        if (interpreter->Invoke() != kTfLiteOk) {
            return "Error: Failed to invoke interpreter";
        }
        ALOGI("Inference invoked.");
    }

    int output_tensor_index = interpreter->outputs()[0];
    const TfLiteTensor* output_tensor = interpreter->tensor(output_tensor_index);
    std::stringstream result_ss;
    result_ss << "Inference success.\nOutput tensor shape: ";
    for (int i = 0; i < output_tensor->dims->size; ++i) {
        result_ss << output_tensor->dims->data[i] << " ";
    }
    result_ss << "\n";

    if (output_tensor->type == kTfLiteFloat32) {
        size_t output_size = output_tensor->bytes / sizeof(float);
        const float* output_data = interpreter->typed_tensor<float>(output_tensor_index);
        if (!output_data) return "Error: Failed to get output tensor data";
        result_ss << "First few float output values: ";
        for (size_t i = 0; i < std::min(static_cast<size_t>(10), output_size); ++i) {
            result_ss << output_data[i] << " ";
        }
    } else if (output_tensor->type == kTfLiteUInt8) {
        size_t output_size = output_tensor->bytes;
        const uint8_t* output_data = interpreter->typed_tensor<uint8_t>(output_tensor_index);
        if (!output_data) return "Error: Failed to get output tensor data";
        result_ss << "First few uint8 output values: ";
        for (size_t i = 0; i < std::min(static_cast<size_t>(10), output_size); ++i) {
            result_ss << static_cast<int>(output_data[i]) << " ";
        }
    } else {
        result_ss << "Output tensor type (" << output_tensor->type << ") not float or uint8, "
                                                                      << "skipping data display.";
    }

    ALOGI("Inference finished successfully.");
    *status = 0;
    return result_ss.str();
}
