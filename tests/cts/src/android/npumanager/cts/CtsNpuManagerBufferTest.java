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
 */

package android.npumanager.cts;

import android.content.pm.PackageManager;
import android.platform.test.annotations.RequiresFlagsEnabled;
import androidx.test.runner.AndroidJUnit4;
import com.android.compatibility.common.util.RequiredFeatureRule;
import com.android.gtestrunner.GtestRunner;
import com.android.gtestrunner.TargetLibrary;
import org.junit.runner.RunWith;
import org.junit.Rule;
import org.junit.Ignore;

@Ignore("b/481533812")
// @RunWith(GtestRunner.class)
// @TargetLibrary("ctsnpumanager_jni")
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
public class CtsNpuManagerBufferTest {
    @Rule(order = 0)
    public RequiredFeatureRule REQUIRES_NPU_RULE =
            new RequiredFeatureRule(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);
}
