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

import android.content.Context;
import android.content.pm.PackageManager;
import android.npumanager.NpuManager;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

@RunWith(AndroidJUnit4.class)
public class CtsNpuManagerServiceTest {
    private static final String TAG = "CtsNpuManagerServiceTest";
    private Context mContext;
    private PackageManager mPackageManager;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        mPackageManager = mContext.getPackageManager();
    }

    @Test
    public void testNpuManagerService_getService_noFeature() throws RemoteException {
        Assume.assumeFalse(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Object service = mContext.getSystemService(Context.NPU_SERVICE);
        Assert.assertNull(service);
    }

    @Test
    public void testNpuManagerService_getService_flagDisabled() throws RemoteException {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeFalse(com.android.npumanager.Flags.npumanagerEnabled());
        Object service = mContext.getSystemService(Context.NPU_SERVICE);
        Assert.assertNull(service);
    }

    @Test
    public void testNpuManagerService_getService() throws RemoteException {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeTrue(com.android.npumanager.Flags.npumanagerEnabled());
        Object service = mContext.getSystemService(Context.NPU_SERVICE);
        Assert.assertNotNull(service);
        NpuManager npuManager = (NpuManager) service;
        Assert.assertNotNull(npuManager);
    }

    @Test
    public void testNpuManagerService_dump() {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeTrue(com.android.npumanager.Flags.npumanagerEnabled());

        // The Shell has DUMP permsision, so this will work
        String dumpOutput = SystemUtil.runShellCommand("dumpsys npu");
        Assert.assertTrue(dumpOutput.contains("Policy:"));
        Assert.assertTrue(dumpOutput.contains("Priorities:"));
        Assert.assertTrue(dumpOutput.contains("[android.npumanager.testapp.A]"));
        Assert.assertTrue(dumpOutput.contains("[android.npumanager.testapp.B]"));
    }

    private static String runCommand(String cmd) throws IOException, InterruptedException {
        final var process = Runtime.getRuntime().exec(cmd);
        final StringBuilder output = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        reader.lines().forEach(output::append);
        reader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
        reader.lines().forEach(output::append);
        process.waitFor();
        return output.toString();
    }

    @Test
    public void testNpuManagerService_dump_noPermission() throws IOException, InterruptedException {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeTrue(com.android.npumanager.Flags.npumanagerEnabled());

        // This will run in the current app, which does not have DUMP permission, so it should fail.
        String dumpOutput = runCommand("dumpsys npu");
        Assert.assertTrue(dumpOutput.contains("can't dump NpuManagerService"));
    }

    @Test
    public void testNpuManagerService_cmd_noArgument() {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeTrue(com.android.npumanager.Flags.npumanagerEnabled());

        String cmdOutput = SystemUtil.runShellCommand("cmd npu");
        Assert.assertTrue(cmdOutput.contains("usage: cmd npu"));
    }

    @Test
    public void testNpuManagerService_cmd_info() {
        // Same as dumpsys
        testNpuManagerService_dump();
    }

    @Test
    public void testNpuManagerService_cmd_setStatusQuo() {
        Assume.assumeTrue(
                mPackageManager.hasSystemFeature(PackageManager.FEATURE_NEURAL_PROCESSING_UNIT));
        Assume.assumeTrue(com.android.npumanager.Flags.npumanagerEnabled());

        SystemUtil.runShellCommand("cmd npu set-status-quo-policy");
        String infoOutput = SystemUtil.runShellCommand("cmd npu info");
        Assert.assertTrue(infoOutput.contains("Policy: statusquo"));
    }
}
