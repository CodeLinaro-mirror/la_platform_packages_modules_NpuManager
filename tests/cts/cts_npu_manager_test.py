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

_BACKGROUND_APP_PACKAGE_NAME = (
    'com.android.npumanager.delegateapp'
)
_FOREGROUND_APP_PACKAGE_NAME = (
    'com.android.npumanager.delegateapp.foreground'
)

_NAMESPACE = 'machine_learning'

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
        self.flags = device_flags.DeviceFlags(self.dut)
        flag_val = self.flags.get_value(_NAMESPACE,
                            'com.android.npumanager.npumanager_enabled')

        self.npu_manager_enabled = flag_val == 'true'

    def teardown_test(self):
        self.dut.background_delegate_snippet.closeActivity()
        self.dut.foreground_delegate_snippet.closeActivity()

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