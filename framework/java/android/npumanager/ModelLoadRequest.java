/*
 * Copyright 2025 The Android Open Source Project
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
package android.npumanager;

import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_BACKGROUND;
import static android.npumanager.NpuManager.NPU_MODEL_PRIORITY_NORMAL;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_GREATER_THAN_2G;
import static android.npumanager.NpuManager.NPU_MODEL_SIZE_LESS_THAN_1GB;

import android.annotation.FlaggedApi;
import android.annotation.IntRange;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.npumanager.NpuManager.NpuModelPriority;
import android.npumanager.NpuManager.NpuModelSize;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import java.util.Objects;

/**
 * An object representing a request to load or unload a model.
 *
 * @hide
 */
@SystemApi
@FlaggedApi(com.android.npumanager.Flags.FLAG_NPUMANAGER_ENABLED)
public class ModelLoadRequest implements Parcelable {
    private final int mId;
    private final int mSize;
    private final int mPriority;
    private final int mUid;

    private ModelLoadRequest(int id, int size, int priority) {
        mId = id;
        mSize = size;
        mPriority = priority;
        mUid = Process.myUid();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        ModelLoadRequest that = (ModelLoadRequest) o;
        return mId == that.mId
                && mSize == that.mSize
                && mPriority == that.mPriority
                && mUid == that.mUid;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mSize, mPriority, mUid);
    }

    @Override
    public String toString() {
        return "ModelLoadRequest{"
                + "mId="
                + mId
                + ", mSize="
                + mSize
                + ", mPriority="
                + mPriority
                + ", mUid="
                + mUid
                + '}';
    }

    /**
     * Returns the id of the model load request.
     *
     * @return The id of the model load request.
     */
    @IntRange(from = Integer.MIN_VALUE, to = Integer.MAX_VALUE)
    public int getId() {
        return mId;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeInt(mSize);
        dest.writeInt(mPriority);
    }

    public static final @NonNull Creator<ModelLoadRequest> CREATOR =
            new Creator<ModelLoadRequest>() {
                @Override
                public ModelLoadRequest createFromParcel(Parcel in) {
                    return new ModelLoadRequest(in.readInt(), in.readInt(), in.readInt());
                }

                @Override
                public ModelLoadRequest[] newArray(int size) {
                    return new ModelLoadRequest[size];
                }
            };

    /** Builder for {@link ModelLoadRequest}. */
    public static class Builder {
        private final int mId;
        private int mSize = NPU_MODEL_SIZE_LESS_THAN_1GB;
        private int mPriority = NPU_MODEL_PRIORITY_NORMAL;

        /**
         * Sets the size of the model to load.
         *
         * @param size The size of the model to load. Must be one of {@link NpuModelSize}.
         * @return The builder.
         */
        public Builder setSize(@NpuModelSize int size) {
            mSize = size;
            return this;
        }

        /**
         * Sets the priority of the model to load.
         *
         * @param priority The priority of the model to load. Must be one of {@link NpuModelPriority}.
         * @return The builder.
         */
        public Builder setPriority(int priority) {
            mPriority = priority;
            return this;
        }

        /**
         * Constructor for the builder which sets the id for the request. Ids should be unique for
         * each request.
         *
         * @param id The id of the model to load.
         */
        public Builder(int id) {
            mId = id;
        }

        /**
         * Builds the {@link ModelLoadRequest}.
         *
         * @return The {@link ModelLoadRequest}.
         */
        public ModelLoadRequest build() {
            return new ModelLoadRequest(mId, mSize, mPriority);
        }
    }

    /**
     * Returns the size of the model to load.
     *
     * @return The size of the model to load.
     */
    @IntRange(from = NPU_MODEL_SIZE_LESS_THAN_1GB, to = NPU_MODEL_SIZE_GREATER_THAN_2G)
    public @NpuModelSize int getSize() {
        return mSize;
    }

    /**
     * Returns the priority of the model to load.
     *
     * @return The priority of the model to load.
     */
    @IntRange(from = NPU_MODEL_PRIORITY_NORMAL, to = NPU_MODEL_PRIORITY_BACKGROUND)
    public @NpuModelPriority int getPriority() {
        return mPriority;
    }
}
