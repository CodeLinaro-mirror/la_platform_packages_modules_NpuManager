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

package android.npumanager.cts;

import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_STATUS_QUO;
import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_NORMAL;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import com.android.compatibility.common.util.RequiredFeatureRule;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

@RunWith(AndroidJUnit4.class)
public class CtsNpuModelManagerTest {
    private static final String TAG = "CtsNpuModelManagerTest";

    UiAutomation mUiAutomation;

    @Before
    public void setUp() {
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    }

    @Rule
    public RequiredFeatureRule REQUIRES_NPU_RULE =
            new RequiredFeatureRule(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_setPolicy_noPermission()
            throws RemoteException, InterruptedException {
        Context context = InstrumentationRegistry.getContext();
        NpuManager npuManager = context.getSystemService(NpuManager.class);
        Assert.assertNotNull(npuManager);

        Assert.assertThrows(
                SecurityException.class,
                () -> npuManager.setPolicy(NPU_MODEL_POLICY_STATUS_QUO, null));
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_StatusQuoPolicy() throws RemoteException, InterruptedException {
        try {
            mUiAutomation.adoptShellPermissionIdentity(
                    android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API);

            Context context = InstrumentationRegistry.getContext();
            Assert.assertEquals(
                    context.checkSelfPermission(
                            android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API),
                    PackageManager.PERMISSION_GRANTED);
            NpuManager npuModelManager = context.getSystemService(NpuManager.class);
            Assert.assertNotNull(npuModelManager);

            npuModelManager.setPolicy(NPU_MODEL_POLICY_STATUS_QUO, null);
            CountDownLatch latch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(1);
            ModelLoadRequest request =
                    new ModelLoadRequest.Builder(54)
                            .setSize(NPU_MODEL_SIZE_LESS_THAN_1GB)
                            .setPriority(NPU_MODEL_PRIORITY_NORMAL)
                            .build();
            ModelLoadRequestCallback callback =
                    new ModelLoadRequestCallback() {
                        public void onCanLoadModel(
                                ModelLoadRequest req,
                                int status,
                                NpuManager.ModelLoadStatusListener listener) {
                            Assert.assertEquals(req, request);
                            Assert.assertEquals(status, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                            latch.countDown();
                        }

                        public void onRequestUnloadModel(ModelLoadRequest request) {}

                        public void onModelLoadRequestComplete(ModelLoadRequest req, int status) {
                            Assert.assertEquals(req, request);

                            Assert.assertEquals(status, NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED);
                            completeLatch.countDown();
                        }
                    };

            npuModelManager.requestLoadModel(request, callback);

            Assert.assertTrue(latch.await(2, TimeUnit.SECONDS));
            npuModelManager.cancelModelLoad(request);
            Assert.assertTrue(completeLatch.await(1, TimeUnit.SECONDS));

        } finally {
            InstrumentationRegistry.getInstrumentation()
                    .getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }
}
