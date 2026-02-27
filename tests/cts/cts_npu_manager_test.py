#  Copyright (C) 2025 The Android Open Source Project
#
#  Licensed under the Apache License, Version 2.0 (the "License");
#  you may not use this file except in compliance with the License.
#  You may obtain a copy of the License at
#
#       http://www.apache.org/licenses/LICENSE-2.0
#
#  Unless required by applicable law or agreed to in writing, software
#  distributed under the License is distributed on an "AS IS" BASIS,
#  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
#  See the License for the specific language governing permissions and
#  limitations under the License.

# Lint as: python3
import logging
import time

from mobly import asserts
from mobly import base_test
from mobly import test_runner
from mobly.controllers import android_device
from mobly.controllers.android_device_lib import apk_utils
from mobly.tools import device_flags
from mobly.controllers.android_device_lib import adb

_BACKGROUND_APP_PACKAGE_NAME = (
    'com.android.npumanager.delegateapp'
)
_FOREGROUND_APP_PACKAGE_NAME = (
    'com.android.npumanager.delegateapp.foreground'
)
_NO_FEATURE_APP_PACKAGE_NAME = 'com.android.npumanager.delegateapp.nofeature'

#For end to end AI core testing
_NPU_MANAGER_ENABLED_FLAG = 'com.android.npumanager.npumanager_enabled'
_SAPI_APP_PACKAGE_NAME = 'com.android.npumanager.sapiapp'
_AICORE_ENABLE_NPU_INTEGRATION_FLAG ='AicCommon__enable_npu_manager_integration'
_MACHINE_LEARNING_NAMESPACE = 'machine_learning'
_AICORE_NAMESPACE = 'aicore'


def _is_cuttlefish_device(ad: android_device.AndroidDevice) -> bool:
    product_name = ad.adb.getprop("ro.product.name")
    return "cf_x86" in product_name

class CtsNpuManagerTest(base_test.BaseTestClass):

    def setup_class(self):
        logging.info('Setup class')
        self.dut = self.register_controller(android_device)[0]

        self.dut.load_snippet(
            'background_delegate_snippet',
            _BACKGROUND_APP_PACKAGE_NAME,
        )
        self.dut.load_snippet(
            'foreground_delegate_snippet',
            _FOREGROUND_APP_PACKAGE_NAME,
        )
        self.dut.load_snippet(
            'no_feature_snippet',
            _NO_FEATURE_APP_PACKAGE_NAME,
        )
        self.dut.load_snippet(
            'sapi_snippet',
            _SAPI_APP_PACKAGE_NAME
        )
        try:
            self.dut.root_adb()
            self.dut.adb.shell("setenforce 0")
        except adb.AdbError as e:
            logging.error(f"Error setting root and running shell cmd: {e}")
            asserts.abort_class(f"Error setting root and running setenforce 0: {e}")

    def teardown_test(self):
        self.dut.background_delegate_snippet.closeActivity()
        self.dut.foreground_delegate_snippet.closeActivity()
        self.dut.no_feature_snippet.closeActivity()
        self.dut.sapi_snippet.closeActivity()

    def _get_device_config(
            self,
            namespace: str,
            flag: str,
    ) -> str | bool:
        """Gets the given flag via device config."""
        output = False
        try:
            output =  (self.dut.adb.shell(f"device_config get {namespace} {flag}")
                       .decode("utf-8").strip())
        except adb.AdbError as e:
            logging.error(f"Error executing shell cmd: {e}")

        if output == "true":
            return True
        if output == "false":
            return False
        return output

    def _run_inference(self, snippet, package_name, use_nnapi=False, load_model=False):
        if self.user_params.get('use_litert_for_inference', False):
            # TODO(b/487727614) Determine SOC and use LiteRT
            pass

        if load_model:
            model_loaded_handler = snippet.asyncWaitForModelLoaded(
                f'model_loaded_{package_name}', package_name
            )
            asserts.assert_true(snippet.checkRunInferenceExists(),
                                    "Run inference tool could not be found")
            snippet.requestCanLoadModel(use_nnapi)
            model_loaded_handler.waitAndGet(f'model_loaded_{package_name}', 30)

        if use_nnapi:
            snippet.runNnapiNpuInference()
        else:
            snippet.runNpuInference()

    def test_cant_access_npu_with_nnapi_without_hardware_feature(self):
        """
        Tests that an app without the NPU hardware feature cannot access the NPU using NNAPI.
        1. Start an app that does not have the <uses-feature android:name="android.hardware.npu">
        in its manifest.
        2. Verify that it cannot run an inference.
        3. Start an app that does have the feature.
        4. Verify that it can run an inference.
        """
        asserts.skip_if(not self._get_device_config(_MACHINE_LEARNING_NAMESPACE, _NPU_MANAGER_ENABLED_FLAG),
                        f"{_NPU_MANAGER_ENABLED_FLAG} must be enabled for this test.")

        # App with the feature
        self.dut.background_delegate_snippet.startActivity()
        inference_handler = (
            self.dut.background_delegate_snippet.asyncWaitForInferenceComplete(
                'inference_with_feature', _BACKGROUND_APP_PACKAGE_NAME
            )
        )
        self._run_inference(self.dut.background_delegate_snippet, _BACKGROUND_APP_PACKAGE_NAME, use_nnapi=True, load_model=True)
        try:
            inference_event = inference_handler.waitAndGet('inference_with_feature', 30)
            asserts.skip_if(
                inference_event is None,
                "Inference did not complete for app with feature.",
            )
        except Exception:
            asserts.skip("Inference did not complete for app with feature.")


        # App without the feature
        self.dut.no_feature_snippet.startActivity()
        inference_handler = (
            self.dut.no_feature_snippet.asyncWaitForInferenceFailed(
                'inference_no_feature', _NO_FEATURE_APP_PACKAGE_NAME
            )
        )
        self._run_inference(self.dut.no_feature_snippet, _NO_FEATURE_APP_PACKAGE_NAME, use_nnapi=True, load_model=True)
        inference_event = inference_handler.waitAndGet('inference_no_feature', 30)
        asserts.assert_is_not_none(
            inference_event, "Inference did not fail for app without feature."
        )


    def test_cant_access_npu_without_hardware_feature(self):
        """
        Tests that an app without the NPU hardware feature cannot access the NPU.
        1. Start an app that does not have the <uses-feature android:name="android.hardware.npu">
        in its manifest.
        2. Verify that it cannot run an inference.
        3. Start an app that does have the feature.
        4. Verify that it can run an inference.
        """
        asserts.skip_if(not self._get_device_config(_MACHINE_LEARNING_NAMESPACE, _NPU_MANAGER_ENABLED_FLAG),
                        f"{_NPU_MANAGER_ENABLED_FLAG} must be enabled for this test.")

        # App without the feature
        self.dut.no_feature_snippet.startActivity()
        inference_handler = (
            self.dut.no_feature_snippet.asyncWaitForInferenceFailed(
                'inference_no_feature', _NO_FEATURE_APP_PACKAGE_NAME
            )
        )
        self._run_inference(self.dut.no_feature_snippet, _NO_FEATURE_APP_PACKAGE_NAME, load_model=True)
        inference_event = inference_handler.waitAndGet('inference_no_feature', 30)
        asserts.assert_is_not_none(
            inference_event, "Inference did not fail for app without feature."
        )

        # App with the feature
        self.dut.background_delegate_snippet.startActivity()
        inference_handler = (
            self.dut.background_delegate_snippet.asyncWaitForInferenceComplete(
                'inference_with_feature', _BACKGROUND_APP_PACKAGE_NAME
            )
        )
        self._run_inference(self.dut.background_delegate_snippet, _BACKGROUND_APP_PACKAGE_NAME, load_model=True)
        inference_event = inference_handler.waitAndGet('inference_with_feature', 30)
        asserts.assert_is_not_none(
            inference_event, "Inference did not complete for app with feature."
        )

    def test_aicore_rewrite(self):
        """
        Tests AICore integrations with NPU Manager by running a simple rewrite inference
        using the Solutions API.

        1. Restart AICore
        2. Check NPU manager related flag values. Skip test if flags not enabled.
        3. Start Solutions API activity and check if rewrite feature is available. Skip test if
        feature is unavailable.
        4. Assert inference success.

        :return:
        """
        try:
            logging.info("Force stopping AICore to restart it.")
            self.dut.adb.shell("am force-stop com.google.android.aicore")
        except adb.AdbError as e:
            logging.error(f"Could not force-stop AICore process: {e}")

        asserts.skip_if(not self._get_device_config(_AICORE_NAMESPACE,
                                                    _AICORE_ENABLE_NPU_INTEGRATION_FLAG),
                        f"{_AICORE_ENABLE_NPU_INTEGRATION_FLAG} must be enabled for this test.")
        asserts.skip_if(not self._get_device_config(_MACHINE_LEARNING_NAMESPACE, _NPU_MANAGER_ENABLED_FLAG),
                        f"{_NPU_MANAGER_ENABLED_FLAG} must be enabled for this test.")

        # Set NPU budget policy
        shell_cmd_failed = False
        try:
            self.dut.adb.shell("cmd npu set-budget-policy")
        except adb.AdbError as e:
            logging.error(f"Could not set budget policy: {e}")
            # Defer failing the test until we verify the SAPI Rewrite feature is supported.
            # If it's unsupported, we prefer to gracefully skip the test rather than fail.
            shell_cmd_failed = True

        self.dut.sapi_snippet.turnScreenOn()
        self.dut.sapi_snippet.pressMenu()

        inference_handler = self.dut.sapi_snippet.asyncWaitForInferenceComplete(
            'inference'
        )
        self.dut.sapi_snippet.startActivity()
        asserts.skip_if(not self.dut.sapi_snippet.isFeatureAvailable(), 'SAPI Rewrite Feature is not '
                                                                        'available on this device.')
        if shell_cmd_failed:
            asserts.fail("Could not set NPU budget policy. Check logs for more info.")

        self.dut.sapi_snippet.rewriteText()
        #Wait for inference.
        inference_handler.waitAndGet('inference', 30)

    def test_foreground_app_finishes_first(self):
        """
        Runs NPU inferences on two apps (one in background, one in foreground) - the background
        inference is triggered first. Tests that the foreground app finishes its inference first.

        Test Steps:
        1. Set NPU Manager to use budget policy.
        2. Start background app, and wait for onResume().
        3. Start foreground app, and wait for onResume().
        4. Request model loads for both background and foreground apps, which will trigger
        test inferences on each app.
        5. Wait for both inferences to complete.
        6. Ensure both inferences complete, and that the foreground inference (with higher priority)
        finishes first.
        :return:
        """
        # TODO (b/479241012): remove this when run-test-inference is implemented for non-CF devices.
        asserts.skip_if(not _is_cuttlefish_device(ad=self.dut), 'Skipping the test for non-Cuttlefish devices.')
        asserts.skip_if(not self._get_device_config(_MACHINE_LEARNING_NAMESPACE, _NPU_MANAGER_ENABLED_FLAG),
                        f"{_NPU_MANAGER_ENABLED_FLAG} must be enabled for this test.")

        app_resume_background_handler = (
            self.dut.background_delegate_snippet.asyncWaitForAppResume(
                'resume_background', _BACKGROUND_APP_PACKAGE_NAME
            )
        )
        app_resume_foreground_handler = (
            self.dut.foreground_delegate_snippet.asyncWaitForAppResume(
                'resume_foreground', _FOREGROUND_APP_PACKAGE_NAME
            )
        )
        inference_handler_background = (
            self.dut.background_delegate_snippet.asyncWaitForInferenceComplete(
                'background', _BACKGROUND_APP_PACKAGE_NAME
            )
        )
        inference_handler_foreground = (
            self.dut.foreground_delegate_snippet.asyncWaitForInferenceComplete(
                'foreground', _FOREGROUND_APP_PACKAGE_NAME
            )
        )
        try:
            self.dut.adb.shell("cmd npu set-budget-policy")
        except adb.AdbError as e:
            logging.error(f"Could not set budget policy: {e}")
            asserts.fail(f"Could not set NPU budget policy: {e}")

        self.dut.background_delegate_snippet.startActivity()
        app_resume_background_handler.waitAndGet('resume_background', 15)
        self.dut.foreground_delegate_snippet.startActivity()
        app_resume_foreground_handler.waitAndGet('resume_foreground', 15)
        self._run_inference(self.dut.background_delegate_snippet, _BACKGROUND_APP_PACKAGE_NAME, load_model=True)
        self._run_inference(self.dut.foreground_delegate_snippet, _FOREGROUND_APP_PACKAGE_NAME, load_model=True)

        event_background = inference_handler_background.waitAndGet('background', 15)
        event_foreground = inference_handler_foreground.waitAndGet('foreground', 15)

        event_background_timestamp = event_background.data.get('timestamp')
        event_foreground_timestamp = event_foreground.data.get('timestamp')

        asserts.assert_is_not_none(event_background_timestamp)
        asserts.assert_is_not_none(event_foreground_timestamp)
        asserts.assert_true(
            event_foreground_timestamp < event_background_timestamp,
            'Foreground app should have finished its inference before background app.',
            )

if __name__ == '__main__':
    test_runner.main()