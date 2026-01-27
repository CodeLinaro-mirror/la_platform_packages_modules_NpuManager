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
#For end to end AI core testing
_NPU_MANAGER_ENABLED_FLAG = 'com.android.npumanager.npumanager_enabled'
_SAPI_APP_PACKAGE_NAME = 'com.android.npumanager.sapiapp'
_AICORE_ISOLATED_PROCESS_FLAG = 'AicOnDeviceIntelligence__enabled'
_AICORE_ENABLE_NPU_INTEGRATION_FLAG ='AicCommon__enable_npu_manager_integration'
_MACHINE_LEARNING_NAMESPACE = 'machine_learning'
_AICORE_NAMESPACE = 'aicore'

class CtsNpuManagerTest(base_test.BaseTestClass):

    def setup_class(self):
        logging.info('Setup class')
        self.dut = self.register_controller(android_device)[0]
        logging.info("installing apks")
        for apk in self.user_params['files'].values():
            logging.info(f"installing apk: {apk}")
            apk_utils.install(self.dut, apk[0])
        logging.info("loading snippets")
        self.dut.load_snippet(
            'background_delegate_snippet',
            _BACKGROUND_APP_PACKAGE_NAME,
        )
        self.dut.load_snippet(
            'foreground_delegate_snippet',
            _FOREGROUND_APP_PACKAGE_NAME,
        )
        self.dut.load_snippet(
            'sapi_snippet',
            _SAPI_APP_PACKAGE_NAME
        )

    def teardown_test(self):
        self.dut.background_delegate_snippet.closeActivity()
        self.dut.foreground_delegate_snippet.closeActivity()

    def _override_device_config(
            self,
            module: str,
            flag: str,
            value: str | bool | int,
    ) -> None:
        """Overrides the given flag via device config."""
        if isinstance(value, bool):
            value = str(value).lower()
        self.dut.adb.shell(f"device_config override {module} {flag} {value}")


    def _get_device_config(
            self,
            namespace: str,
            flag: str,
    ) -> str | bool:
        """Gets the given flag via device config."""
        output = (
            self.dut.adb.shell(f"device_config get {namespace} {flag}")
            .decode("utf-8")
            .strip()
        )
        if output == "true":
            return True
        if output == "false":
            return False
        return output

    def test_aicore_rewrite(self):
        """
        Tests AICore integrations with NPU Manager by running a simple rewrite inference
        using the Solutions API.

        1. Disable isolated process, and enable NPU manager related flags.
        2. Kill AICore
        3. Verify flag values
        4. Start activity and run rewrite text inference.
        5. Assert inference success.

        :return:
        """
        self._override_device_config(_AICORE_NAMESPACE, _AICORE_ISOLATED_PROCESS_FLAG,
                                     False)
        self._override_device_config(_AICORE_NAMESPACE, _AICORE_ENABLE_NPU_INTEGRATION_FLAG,
                                     True)
        self._override_device_config(_MACHINE_LEARNING_NAMESPACE,
                                     _NPU_MANAGER_ENABLED_FLAG, True)
        try:
            pid = (self.dut.adb.shell(["pidof", "com.google.android.aicore"])
                  .decode("utf-8"))
            if pid:
                logging.info(f"Found pid of AICore: {pid}. killing now")
                self.dut.adb.shell(["kill", "-9", pid])
            else:
                logging.info("Did not find pid of AICore to destroy")
        except adb.AdbError as e:
            logging.error(f"Could not destroy AICore process: {e}")
        asserts.skip_if(self._get_device_config(
                                             _AICORE_NAMESPACE,
                                          _AICORE_ISOLATED_PROCESS_FLAG),
            f"{_AICORE_ISOLATED_PROCESS_FLAG} must be disabled for this test."
        )
        asserts.skip_if(not self._get_device_config(_AICORE_NAMESPACE,
                                                    _AICORE_ENABLE_NPU_INTEGRATION_FLAG),
                        f"{_AICORE_ENABLE_NPU_INTEGRATION_FLAG} must be enabled for this test.")
        asserts.skip_if(not self._get_device_config(_MACHINE_LEARNING_NAMESPACE, _NPU_MANAGER_ENABLED_FLAG),
                        f"{_NPU_MANAGER_ENABLED_FLAG} must be enabled for this test.")
        self.dut.sapi_snippet.turnScreenOn()
        self.dut.sapi_snippet.pressMenu()

        inference_handler = self.dut.sapi_snippet.asyncWaitForInferenceComplete('inference')
        self.dut.sapi_snippet.startActivity()
        self.dut.sapi_snippet.rewriteText()
        #Wait for inference.
        inference_handler.waitAndGet('inference', 10)

    def test_foreground_app_finishes_first(self):
        """
        Runs NPU inferences on two apps (one in background, one in foreground) - the background
        inference is triggered first. Tests that the foreground app finishes its inference first.

        Test Steps:
        1. Start first app (background) and run an NPU inference on it.
        2. Start second app (foreground) and run an NPU inference on it.
        3. Ensure that both apps finish their inferences.
        4. Ensure that the foreground app finishes its inference first.
        :return:
        """
        asserts.skip("Disabling this test until b/476377913 is complete.")
        asserts.skip_if(not self.npu_manager_enabled,
                        'NpuManager flag must be enabled for this test.')

        inference_handler_background =  self.dut.background_delegate_snippet.asyncWaitForInferenceComplete(
            'background'
        )
        inference_handler_foreground =  self.dut.foreground_delegate_snippet.asyncWaitForInferenceComplete(
            'foreground'
        )

        self.dut.background_delegate_snippet.startActivity()
        self.dut.background_delegate_snippet.runNpuInference()

        self.dut.foreground_delegate_snippet.startActivity()
        self.dut.foreground_delegate_snippet.runNpuInference()

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