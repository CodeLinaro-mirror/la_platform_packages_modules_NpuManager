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

import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_BUDGET;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_STATUS_QUO;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_TURN_TAKING;
import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_NORMAL;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.UiAutomation;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.npumanager.testing.ITestModelLoadRequestCallback;
import android.npumanager.testing.ITestModelLoadStatusListener;
import android.npumanager.testing.TestModelLoadRequest;
import android.npumanager.testing.TestNpuManagerClient;
import android.os.RemoteException;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.RequiredFeatureRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CtsNpuModelManagerTest {
    private static final String TAG = "CtsNpuModelManagerTest";

    private static final String FOREGROUND_PACKAGE_NAME = "android.npumanager.testapp.A";
    private static final String BACKGROUND_PACKAGE_NAME = "android.npumanager.testapp.B";
    private static final String TEST_APP_MAIN_ACTIVITY_NAME =
            "android.npumanager.testapp.MainActivity";

    UiAutomation mUiAutomation;
    Context mContext;
    TestNpuManagerClient mForegroundNpuManager;
    TestNpuManagerClient mBackgroundNpuManager;

    @Before
    public void setUp() {
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        mForegroundNpuManager = new TestNpuManagerClient(mContext, FOREGROUND_PACKAGE_NAME);
        mBackgroundNpuManager = new TestNpuManagerClient(mContext, BACKGROUND_PACKAGE_NAME);
    }

    @After
    public void tearDown() throws RemoteException {
        mForegroundNpuManager.reset();
        mBackgroundNpuManager.reset();
    }

    @Rule(order = 1)
    public final CheckFlagsRule checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 0)
    public RequiredFeatureRule REQUIRES_NPU_RULE =
            new RequiredFeatureRule(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_setPolicy_noPermission()
            throws RemoteException, InterruptedException {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        NpuManager npuManager = context.getSystemService(NpuManager.class);
        assertNotNull(npuManager);

        assertThrows(
                SecurityException.class,
                () -> npuManager.setPolicy(NPU_MODEL_POLICY_STATUS_QUO, null));
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_statusQuoPolicy() throws RemoteException, InterruptedException {
        try {
            mUiAutomation.adoptShellPermissionIdentity(
                    android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API);

            Context context = InstrumentationRegistry.getInstrumentation().getContext();
            assertEquals(
                    context.checkSelfPermission(
                            android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API),
                    PackageManager.PERMISSION_GRANTED);
            NpuManager npuModelManager = context.getSystemService(NpuManager.class);
            assertNotNull(npuModelManager);

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
                            assertEquals(status, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
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
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_turnTakingPolicy() throws Exception {
        try {
            mUiAutomation.adoptShellPermissionIdentity(
                    android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API);
            mContext.getSystemService(NpuManager.class)
                    .setPolicy(NPU_MODEL_POLICY_TURN_TAKING, null);

            // Start foreground activity
            mContext.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(FOREGROUND_PACKAGE_NAME, TEST_APP_MAIN_ACTIVITY_NAME)
                            .addFlags(FLAG_ACTIVITY_NEW_TASK));

            // First load background model
            CountDownLatch backgroundLatch = new CountDownLatch(1);
            CountDownLatch unloadLatch = new CountDownLatch(1);
            TestModelLoadRequest backgroundRequest = new TestModelLoadRequest(1, 100, 100);
            ITestModelLoadRequestCallback backgroundCallback =
                    new ITestModelLoadRequestCallback.Stub() {
                        private ITestModelLoadStatusListener mListener;

                        public void onCanLoadModel(
                                TestModelLoadRequest request,
                                int status,
                                ITestModelLoadStatusListener listener)
                                throws RemoteException {
                            assertEquals(status, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                            mListener = listener;
                            mListener.notifyModelLoaded(request);
                            backgroundLatch.countDown();
                        }

                        public void onRequestUnloadModel(TestModelLoadRequest request) {
                            try {
                                unloadLatch.countDown();
                                mListener.notifyModelUnloaded(request);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }

                        public void onModelLoadRequestComplete(
                                TestModelLoadRequest request, int status) {}
                    };
            mBackgroundNpuManager.requestLoadModel(backgroundRequest, backgroundCallback);
            assertTrue(backgroundLatch.await(5, TimeUnit.SECONDS));

            // Then, load foreground model. It should preempt the background one
            CountDownLatch waitLatch = new CountDownLatch(1);
            CountDownLatch canLoadLatch = new CountDownLatch(1);
            TestModelLoadRequest foregroundRequest = new TestModelLoadRequest(2, 100, 100);
            ITestModelLoadRequestCallback foregroundCallback =
                    new ITestModelLoadRequestCallback.Stub() {
                        public void onCanLoadModel(
                                TestModelLoadRequest request,
                                int status,
                                ITestModelLoadStatusListener listener)
                                throws RemoteException {
                            if (status == NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD) {
                                waitLatch.countDown();
                            } else if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                                canLoadLatch.countDown();
                                listener.notifyModelLoaded(request);
                            }
                        }

                        public void onRequestUnloadModel(TestModelLoadRequest request) {}

                        public void onModelLoadRequestComplete(
                                TestModelLoadRequest request, int status) {}
                    };
            mForegroundNpuManager.requestLoadModel(foregroundRequest, foregroundCallback);
            assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
            assertTrue(unloadLatch.await(5, TimeUnit.SECONDS));
            assertTrue(canLoadLatch.await(5, TimeUnit.SECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_budgetPolicy() throws Exception {
        try {
            mUiAutomation.adoptShellPermissionIdentity(
                    android.Manifest.permission.ACCESS_NPU_MODEL_MANAGER_API);
            mContext.getSystemService(NpuManager.class).setPolicy(NPU_MODEL_POLICY_BUDGET, null);

            // Start foreground activity
            mContext.startActivity(
                    new Intent(Intent.ACTION_MAIN)
                            .setClassName(FOREGROUND_PACKAGE_NAME, TEST_APP_MAIN_ACTIVITY_NAME)
                            .addFlags(FLAG_ACTIVITY_NEW_TASK));

            // First load background model
            CountDownLatch backgroundLatch = new CountDownLatch(1);
            CountDownLatch unloadLatch = new CountDownLatch(1);
            TestModelLoadRequest backgroundRequest =
                    new TestModelLoadRequest(1, NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
            ITestModelLoadRequestCallback backgroundCallback =
                    new ITestModelLoadRequestCallback.Stub() {
                        private ITestModelLoadStatusListener mListener;

                        public void onCanLoadModel(
                                TestModelLoadRequest request,
                                int status,
                                ITestModelLoadStatusListener listener)
                                throws RemoteException {
                            assertEquals(status, NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW);
                            mListener = listener;
                            mListener.notifyModelLoaded(request);
                            backgroundLatch.countDown();
                        }

                        public void onRequestUnloadModel(TestModelLoadRequest request) {
                            try {
                                unloadLatch.countDown();
                                mListener.notifyModelUnloaded(request);
                            } catch (Exception e) {
                                throw new RuntimeException(e);
                            }
                        }

                        public void onModelLoadRequestComplete(
                                TestModelLoadRequest request, int status) {}
                    };
            mBackgroundNpuManager.requestLoadModel(backgroundRequest, backgroundCallback);
            assertTrue(backgroundLatch.await(5, TimeUnit.SECONDS));

            // Then, load foreground model. It should preempt the background one
            CountDownLatch waitLatch = new CountDownLatch(1);
            CountDownLatch canLoadLatch = new CountDownLatch(1);
            TestModelLoadRequest foregroundRequest =
                    new TestModelLoadRequest(2, NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
            ITestModelLoadRequestCallback foregroundCallback =
                    new ITestModelLoadRequestCallback.Stub() {
                        public void onCanLoadModel(
                                TestModelLoadRequest request,
                                int status,
                                ITestModelLoadStatusListener listener)
                                throws RemoteException {
                            if (status == NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD) {
                                waitLatch.countDown();
                            } else if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                                canLoadLatch.countDown();
                                listener.notifyModelLoaded(request);
                            }
                        }

                        public void onRequestUnloadModel(TestModelLoadRequest request) {}

                        public void onModelLoadRequestComplete(
                                TestModelLoadRequest request, int status) {}
                    };
            mForegroundNpuManager.requestLoadModel(foregroundRequest, foregroundCallback);
            assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
            assertTrue(unloadLatch.await(5, TimeUnit.SECONDS));
            assertTrue(canLoadLatch.await(5, TimeUnit.SECONDS));
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }
}
