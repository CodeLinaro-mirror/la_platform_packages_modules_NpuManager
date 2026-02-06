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

import static org.junit.Assert.assertEquals;

import android.content.pm.PackageManager;
import android.npumanager.ModelLoadRequest;
import android.npumanager.NpuManager;
import android.os.Parcel;
import android.platform.test.annotations.RequiresFlagsEnabled;
import androidx.test.runner.AndroidJUnit4;
import com.android.compatibility.common.util.RequiredFeatureRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CtsModelLoadRequestTest {
    private static final int TEST_ID = 12345;
    private static final int TEST_SIZE = NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;
    private static final int TEST_PRIORITY = NpuManager.NPU_MODEL_PRIORITY_NORMAL;

    @Rule(order = 0)
    public RequiredFeatureRule REQUIRES_NPU_RULE =
            new RequiredFeatureRule(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testGetters() {
        ModelLoadRequest request =
                new ModelLoadRequest.Builder(TEST_ID)
                        .setSize(TEST_SIZE)
                        .setPriority(TEST_PRIORITY)
                        .build();
        assertEquals(TEST_ID, request.getId());
        assertEquals(TEST_SIZE, request.getSize());
        assertEquals(TEST_PRIORITY, request.getPriority());
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testParcelable() {
        ModelLoadRequest request =
                new ModelLoadRequest.Builder(TEST_ID)
                        .setSize(TEST_SIZE)
                        .setPriority(TEST_PRIORITY)
                        .build();

        Parcel parcel = Parcel.obtain();
        try {
            request.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            ModelLoadRequest fromParcel = ModelLoadRequest.CREATOR.createFromParcel(parcel);
            assertEquals(request, fromParcel);
        } finally {
            parcel.recycle();
        }
    }
}
