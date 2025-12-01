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

package android.npumanager.testing;

import android.os.Parcel;
import android.os.Parcelable;

public class TestModelLoadRequest implements Parcelable {
    public final int id;
    public final int size;
    public final int priority;

    public TestModelLoadRequest(int id, int size, int priority) {
        this.id = id;
        this.size = size;
        this.priority = priority;
    }

    private TestModelLoadRequest(Parcel in) {
        id = in.readInt();
        size = in.readInt();
        priority = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(id);
        dest.writeInt(size);
        dest.writeInt(priority);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<TestModelLoadRequest> CREATOR =
            new Creator<TestModelLoadRequest>() {
                @Override
                public TestModelLoadRequest createFromParcel(Parcel in) {
                    return new TestModelLoadRequest(in);
                }

                @Override
                public TestModelLoadRequest[] newArray(int size) {
                    return new TestModelLoadRequest[size];
                }
            };
}
