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

import android.npumanager.ModelLoadRequest;
import android.npumanager.NpuModelSize;
import android.os.Parcel;
import androidx.test.runner.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CtsModelLoadRequestTest {
    private static final int TEST_ID = 12345;
    private static final int TEST_SIZE = NpuModelSize.LESS_THAN_1GB;
    private static final int TEST_PRIORITY = ModelLoadRequest.PRIORITY_NORMAL;

    @Test
    public void testParcelable() {
        ModelLoadRequest request = new ModelLoadRequest();
        request.id = TEST_ID;
        request.size = TEST_SIZE;
        request.priority = TEST_PRIORITY;

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
