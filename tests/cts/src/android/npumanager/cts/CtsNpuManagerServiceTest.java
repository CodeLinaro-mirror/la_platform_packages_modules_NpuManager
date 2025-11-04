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
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.testng.Assert;

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
}
