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

import static android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED;
import static android.npumanager.NpuManager.NPU_MODEL_LOAD_STATUS_WAIT_FOR_UNLOAD;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_BUDGET;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_STATUS_QUO;
import static android.npumanager.NpuManager.NPU_MODEL_POLICY_TURN_TAKING;
import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_NORMAL;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.npumanager.ModelLoadRequest;
import android.npumanager.ModelLoadRequestCallback;
import android.npumanager.NpuManager;
import android.npumanager.testing.ITestModelLoadRequestCallback;
import android.npumanager.testing.ITestModelLoadStatusListener;
import android.npumanager.testing.TestModelLoadRequest;
import android.npumanager.testing.TestNpuManagerClient;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Log;

import androidx.core.content.ContextCompat;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.compatibility.common.util.RequiredFeatureRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class CtsNpuModelManagerTest {
    private static final String TAG = "CtsNpuModelManagerTest";

    private static final String FOREGROUND_PACKAGE_NAME = "android.npumanager.testapp.A";
    private static final String BACKGROUND_PACKAGE_NAME = "android.npumanager.testapp.B";
    private static final String TEST_APP_MAIN_ACTIVITY_NAME =
            "android.npumanager.testapp.MainActivity";
    private static final String DISABLE_AUTO_SLEEP_CMD = "svc power stayon true";
    private static final String ENABLE_AUTO_SLEEP_CMD = "svc power stayon false";
    private static final String DISMISS_KEYGUARD_CMD = "wm dismiss-keyguard";
    private static final String ACTION_APP_RESUMED = "android.npumanager.testapp.APP_RESUMED";
    private static final String EXTRA_PACKAGE_NAME = "packagename";
    private static final long APP_START_TIMEOUT_MS = 5000;

    UiDevice mDevice;
    UiAutomation mUiAutomation;
    Context mContext;
    ActivityManager mActivityManager;
    TestNpuManagerClient mForegroundNpuManager;
    TestNpuManagerClient mBackgroundNpuManager;
    CountDownLatch mBackgroundAppImportanceUpdated;
    CountDownLatch mForegroundedAppImportanceUpdated;
    final ActivityManager.OnUidImportanceListener bgListener =
            (uid, importance) -> {
                try {
                    int bkgUid = getUid(BACKGROUND_PACKAGE_NAME);

                    if (bkgUid == uid) {
                        Log.d(TAG, "Importance for bg uid is: " + importance);
                        if (importance > IMPORTANCE_FOREGROUND_SERVICE) {
                            mBackgroundAppImportanceUpdated.countDown();
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            };

    final ActivityManager.OnUidImportanceListener fgListener =
            (uid, importance) -> {
                try {
                    int fgUid = getUid(FOREGROUND_PACKAGE_NAME);

                    if (fgUid == uid) {
                        Log.d(TAG, "Importance for fg uid is: " + importance);
                        if (importance <= IMPORTANCE_FOREGROUND_SERVICE) {
                            mForegroundedAppImportanceUpdated.countDown();
                        }
                    }
                } catch (Exception e) {
                    // Ignore
                }
            };

    @Before
    public void setUp() {
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mUiAutomation.adoptShellPermissionIdentity();
        mBackgroundAppImportanceUpdated = new CountDownLatch(1);
        mForegroundedAppImportanceUpdated = new CountDownLatch(1);
        mActivityManager.addOnUidImportanceListener(fgListener, IMPORTANCE_FOREGROUND_SERVICE);
        mActivityManager.addOnUidImportanceListener(bgListener, IMPORTANCE_FOREGROUND_SERVICE);
        mForegroundNpuManager =
                new TestNpuManagerClient(
                        mContext, FOREGROUND_PACKAGE_NAME, Context.BIND_AUTO_CREATE);
        mBackgroundNpuManager =
                new TestNpuManagerClient(
                        mContext,
                        BACKGROUND_PACKAGE_NAME,
                        Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND);

        // Prevent auto-sleep to ensure activity manager updates correctly.
        try {
            mDevice.wakeUp();
            mDevice.executeShellCommand(DISABLE_AUTO_SLEEP_CMD);
            mDevice.executeShellCommand(DISMISS_KEYGUARD_CMD);
        } catch (Exception e) {
            Log.e(TAG, "Exception", e);
        }
    }

    @After
    public void tearDown() {
        try {
            mForegroundNpuManager.reset();
            mBackgroundNpuManager.reset();
            mDevice.executeShellCommand(ENABLE_AUTO_SLEEP_CMD);
            mActivityManager.removeOnUidImportanceListener(fgListener);
            mActivityManager.removeOnUidImportanceListener(bgListener);
            killApp(BACKGROUND_PACKAGE_NAME, 5000);
            killApp(FOREGROUND_PACKAGE_NAME, 5000);
        } catch (Exception e) {
            // Ignore
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
    }

    @Rule(order = 1)
    public final CheckFlagsRule checkFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    @Rule(order = 0)
    public RequiredFeatureRule REQUIRES_NPU_RULE =
            new RequiredFeatureRule(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT);

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_setPolicy_noPermission() {
        mUiAutomation.dropShellPermissionIdentity();

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
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_turnTakingPolicy() throws Exception {
        mContext.getSystemService(NpuManager.class).setPolicy(NPU_MODEL_POLICY_TURN_TAKING, null);

        // First load background model
        CountDownLatch backgroundLatch = new CountDownLatch(1);
        CountDownLatch unloadLatch = new CountDownLatch(1);
        CountDownLatch cancelLatch = new CountDownLatch(1);
        TestModelLoadRequest backgroundRequest =
                new TestModelLoadRequest(1, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback backgroundCallback =
                new ITestModelLoadRequestCallback.Stub() {
                    private ITestModelLoadStatusListener mListener;

                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener)
                            throws RemoteException {
                        if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                            mListener = listener;
                            mListener.notifyModelLoaded(request);
                            backgroundLatch.countDown();
                        }
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

        waitForAppResume(FOREGROUND_PACKAGE_NAME);
        assertTrue(mBackgroundAppImportanceUpdated.await(10, TimeUnit.SECONDS));
        assertTrue(mForegroundedAppImportanceUpdated.await(10, TimeUnit.SECONDS));
        CountDownLatch waitLatch = new CountDownLatch(1);
        CountDownLatch canLoadLatch = new CountDownLatch(1);
        TestModelLoadRequest foregroundRequest =
                new TestModelLoadRequest(2, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
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
                            TestModelLoadRequest request, int status) {
                        if (status == NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED) {
                            assertEquals(foregroundRequest.id, request.id);
                            cancelLatch.countDown();
                        }
                    }
                };
        mForegroundNpuManager.requestLoadModel(foregroundRequest, foregroundCallback);
        assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
        assertTrue(unloadLatch.await(5, TimeUnit.SECONDS));
        assertTrue(canLoadLatch.await(5, TimeUnit.SECONDS));

        mForegroundNpuManager.cancelLoadModel(foregroundRequest);
        assertTrue(cancelLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_budgetPolicy() throws Exception {
        mContext.getSystemService(NpuManager.class).setPolicy(NPU_MODEL_POLICY_BUDGET, null);

        // First load background model
        CountDownLatch backgroundLatch = new CountDownLatch(1);
        CountDownLatch unloadLatch = new CountDownLatch(1);
        TestModelLoadRequest backgroundRequest =
                new TestModelLoadRequest(1, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback backgroundCallback =
                new ITestModelLoadRequestCallback.Stub() {
                    private ITestModelLoadStatusListener mListener;

                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener)
                            throws RemoteException {
                        if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                            mListener = listener;
                            mListener.notifyModelLoaded(request);
                            backgroundLatch.countDown();
                        }
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

        // Lower importance of background app by unbinding the service.
        waitForAppResume(FOREGROUND_PACKAGE_NAME);

        assertTrue(mBackgroundAppImportanceUpdated.await(10, TimeUnit.SECONDS));
        assertTrue(mForegroundedAppImportanceUpdated.await(10, TimeUnit.SECONDS));

        CountDownLatch waitLatch = new CountDownLatch(1);
        CountDownLatch canLoadLatch = new CountDownLatch(1);

        CountDownLatch cancelLatch = new CountDownLatch(1);
        TestModelLoadRequest foregroundRequest =
                new TestModelLoadRequest(2, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
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
                            TestModelLoadRequest request, int status) {
                        if (status == NPU_MODEL_LOAD_REQUEST_STATUS_CANCELLED) {
                            cancelLatch.countDown();
                        }
                    }
                };
        mForegroundNpuManager.requestLoadModel(foregroundRequest, foregroundCallback);
        assertTrue(waitLatch.await(5, TimeUnit.SECONDS));
        assertTrue(unloadLatch.await(5, TimeUnit.SECONDS));
        assertTrue(canLoadLatch.await(5, TimeUnit.SECONDS));

        mForegroundNpuManager.cancelLoadModel(foregroundRequest);
        assertTrue(cancelLatch.await(5, TimeUnit.SECONDS));
    }

    /**
     * Requests to load model for a foreground apps, but it doesn't actually load the model when it
     * receives CAN_LOAD_NOW. Tests that the background app does not receive CAN_LOAD_NOW.
     *
     * @throws Exception Thrown from setting the policy.
     */
    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_budgetPolicy_multipleAppsRequesting() throws Exception {
        NpuManager npuManager = mContext.getSystemService(NpuManager.class);
        assertNotNull(npuManager);

        npuManager.setPolicy(NPU_MODEL_POLICY_BUDGET, null);

        CountDownLatch fgCanLoadLatch = new CountDownLatch(1);
        TestModelLoadRequest foregroundRequest =
                new TestModelLoadRequest(2, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback foregroundCallback =
                new ITestModelLoadRequestCallback.Stub() {
                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener) {
                        if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                            fgCanLoadLatch.countDown();
                            // Not calling listener.notifyModel so that this budget
                            // is still considered "requested" and not loaded

                        }
                    }

                    public void onRequestUnloadModel(TestModelLoadRequest request) {}

                    public void onModelLoadRequestComplete(
                            TestModelLoadRequest request, int status) {}
                };

        CountDownLatch bgNotPrioritizedLatch = new CountDownLatch(1);
        TestModelLoadRequest backgroundRequest =
                new TestModelLoadRequest(1, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback backgroundCallback =
                new ITestModelLoadRequestCallback.Stub() {
                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener) {
                        if (status == NPU_MODEL_LOAD_STATUS_NOT_PRIORITIZED) {
                            bgNotPrioritizedLatch.countDown();
                        }
                    }

                    public void onRequestUnloadModel(TestModelLoadRequest request) {}

                    public void onModelLoadRequestComplete(
                            TestModelLoadRequest request, int status) {}
                };

        waitForAppResume(FOREGROUND_PACKAGE_NAME);

        assertTrue(mBackgroundAppImportanceUpdated.await(10, TimeUnit.SECONDS));
        assertTrue(mForegroundedAppImportanceUpdated.await(10, TimeUnit.SECONDS));

        // Load fg model.
        mForegroundNpuManager.requestLoadModel(foregroundRequest, foregroundCallback);
        assertTrue(fgCanLoadLatch.await(5, TimeUnit.SECONDS));

        // Attempt to load bg model. It should return with NOT_PRIORITIZED.
        mBackgroundNpuManager.requestLoadModel(backgroundRequest, backgroundCallback);
        assertTrue(bgNotPrioritizedLatch.await(5, TimeUnit.SECONDS));
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_turnTakingPolicy_killForegroundApp() throws Exception {
        runKillForegroundAppTestWithPolicy(NPU_MODEL_POLICY_TURN_TAKING);
    }

    @Test
    @RequiresFlagsEnabled(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
    public void testNpuModelManager_budgetPolicy_killForegroundApp() throws Exception {
        runKillForegroundAppTestWithPolicy(NPU_MODEL_POLICY_BUDGET);
    }

    private void runKillForegroundAppTestWithPolicy(int policy) throws Exception {
        mContext.getSystemService(NpuManager.class).setPolicy(policy, null);

        // Launch and load model for app A.
        waitForAppResume(FOREGROUND_PACKAGE_NAME);
        CountDownLatch appACanLoadLatch = new CountDownLatch(1);
        TestModelLoadRequest appARequest =
                new TestModelLoadRequest(1, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback appACallback =
                new ITestModelLoadRequestCallback.Stub() {
                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener)
                            throws RemoteException {
                        if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                            appACanLoadLatch.countDown();
                            listener.notifyModelLoaded(request);
                        }
                    }

                    public void onRequestUnloadModel(TestModelLoadRequest request) {}

                    public void onModelLoadRequestComplete(
                            TestModelLoadRequest request, int status) {}
                };
        mForegroundNpuManager.requestLoadModel(appARequest, appACallback);
        assertTrue(
                "App A failed to get canLoadModel callback",
                appACanLoadLatch.await(5, TimeUnit.SECONDS));

        // Launch background app.
        waitForAppResume(BACKGROUND_PACKAGE_NAME);

        // Kill foreground app.
        assertTrue(
                "App A process still running after force-stop",
                killApp(FOREGROUND_PACKAGE_NAME, 10000));

        // Load model on App B
        CountDownLatch appBCanLoadLatch = new CountDownLatch(1);
        TestModelLoadRequest appBRequest =
                new TestModelLoadRequest(2, NPU_MODEL_SIZE_GREATER_THAN_2G, 100);
        ITestModelLoadRequestCallback appBCallback =
                new ITestModelLoadRequestCallback.Stub() {
                    public void onCanLoadModel(
                            TestModelLoadRequest request,
                            int status,
                            ITestModelLoadStatusListener listener)
                            throws RemoteException {
                        if (status == NPU_MODEL_LOAD_STATUS_CAN_LOAD_NOW) {
                            appBCanLoadLatch.countDown();
                            listener.notifyModelLoaded(request);
                        }
                    }

                    public void onRequestUnloadModel(TestModelLoadRequest request) {}

                    public void onModelLoadRequestComplete(
                            TestModelLoadRequest request, int status) {}
                };
        mBackgroundNpuManager.requestLoadModel(appBRequest, appBCallback);
        assertTrue(
                "App B failed to get canLoadModel callback",
                appBCanLoadLatch.await(5, TimeUnit.SECONDS));
    }

    private void launchApp(String packageName) {
        Intent intent =
                new Intent(Intent.ACTION_MAIN)
                        .setClassName(packageName, TEST_APP_MAIN_ACTIVITY_NAME)
                        .addFlags(FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(intent);
    }

    // Launches the test app with the given package name and blocks until the ACTION_APP_RESUME
    // broadcast is received from the app.
    private void waitForAppResume(String packageName) throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        BroadcastReceiver receiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        Log.d(TAG, "Received onReceive. Intent action: " + intent.getAction());
                        if (ACTION_APP_RESUMED.equals(intent.getAction())) {
                            String pkg = intent.getStringExtra(EXTRA_PACKAGE_NAME);
                            Log.d(TAG, "ACTION_APP_RESUMED received. Package name: " + pkg);
                            if (packageName.equals(pkg)) {
                                Log.i(TAG, "Received resume broadcast from: " + pkg);
                                latch.countDown();
                            }
                        }
                    }
                };
        ContextCompat.registerReceiver(
                mContext,
                receiver,
                new IntentFilter(ACTION_APP_RESUMED),
                ContextCompat.RECEIVER_EXPORTED);
        try {
            launchApp(packageName);
            assertTrue(
                    "Timeout waiting for " + packageName + " to resume",
                    latch.await(APP_START_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        } finally {
            mContext.unregisterReceiver(receiver);
        }
    }

    // Blocks until app is killed.
    private boolean waitForAppDeath(String packageName, long timeoutMs) throws IOException {
        long startTime = System.currentTimeMillis();
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isAppKilled(packageName)) {
                return true;
            }
            SystemClock.sleep(100);
        }
        return isAppKilled(packageName);
    }

    private String getStringFromPfd(ParcelFileDescriptor pfd) throws IOException {
        InputStream inputStream = new ParcelFileDescriptor.AutoCloseInputStream(pfd);

        InputStreamReader isr = new InputStreamReader(inputStream, "UTF-8");
        BufferedReader reader = new BufferedReader(isr);

        StringBuilder output = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        if (!output.isEmpty() && output.charAt(output.length() - 1) == '\n') {
            output.setLength(output.length() - 1);
        }
        return output.toString();
    }

    private boolean isAppKilled(String packageName) throws IOException {
        String cmd = "pidof " + packageName;
        String result = getStringFromPfd(mUiAutomation.executeShellCommand(cmd));
        return result == null || result.trim().isEmpty();
    }

    private boolean killApp(String packageName, long timeoutMs) throws IOException {
        mUiAutomation.executeShellCommand("am force-stop " + packageName);
        return waitForAppDeath(packageName, timeoutMs);
    }

    private int getUid(String packageName) throws PackageManager.NameNotFoundException {
        return mContext.getPackageManager().getPackageUid(packageName, 0);
    }
}
