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

package com.android.server.npumanager;

import android.annotation.NonNull;
import android.content.Context;
import android.os.Process;

public class DumpUtils {

    /**
     * Returns a String representing an application uid
     *
     * @param context a Context
     * @param uid the uid
     * @return a String with the app name, may be empty
     */
    @NonNull
    public static String getPackageNameForUid(@NonNull Context context, int uid) {
        if (uid <= Process.FIRST_APPLICATION_UID) {
            return "system";
        }

        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        if (packages != null && packages.length > 0) {
            return packages[0];
        } else {
            return "";
        }
    }
}
