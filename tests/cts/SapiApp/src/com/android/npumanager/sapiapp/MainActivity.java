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

package com.android.npumanager.sapiapp;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.mlkit.genai.common.DownloadCallback;
import com.google.mlkit.genai.common.FeatureStatus;
import com.google.mlkit.genai.common.GenAiException;
import com.google.mlkit.genai.rewriting.Rewriter;
import com.google.mlkit.genai.rewriting.RewriterOptions;
import com.google.mlkit.genai.rewriting.RewriterOptions.Language;
import com.google.mlkit.genai.rewriting.RewriterOptions.OutputType;
import com.google.mlkit.genai.rewriting.Rewriting;
import com.google.mlkit.genai.rewriting.RewritingRequest;
import com.google.mlkit.genai.rewriting.RewritingResult;

import java.util.concurrent.ExecutionException;

public class MainActivity extends Activity {
    private static final String TAG = "AndroidSapiApp";
    private Rewriter rewriter;
    public static final String ACTION_INFERENCE_COMPLETE = "ACTION_INFERENCE_COMPLETE";
    private static final String REWRITE_TEXT =
            "Golden sunlight filtered through the autumn leaves, casting long shadows across"
                    + " the garden path. A gentle breeze carried the faint scent of woodsmoke from"
                    + " a distant chimney. Everything felt quiet and perfectly still in that"
                    + " fleeting afternoon moment.";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        RewriterOptions options =
                RewriterOptions.builder(this)
                        .setOutputType(OutputType.ELABORATE)
                        .setLanguage(Language.ENGLISH)
                        .build();
        rewriter = Rewriting.getClient(options);
    }

    public void prepareAndStartRewrite() {
        try {
            int featureStatus = rewriter.checkFeatureStatus().get();
            if (featureStatus == FeatureStatus.DOWNLOADABLE) {
                // Download feature if necessary.
                rewriter.downloadFeature(
                        new DownloadCallback() {
                            @Override
                            public void onDownloadCompleted() {
                                Log.w(TAG, "Downloading completed");
                                rewriteText();
                            }

                            @Override
                            public void onDownloadFailed(@NonNull GenAiException e) {
                                Log.e(TAG, "Model failed to download. Test will fail");
                            }

                            @Override
                            public void onDownloadProgress(long l) {
                                Log.w(TAG, "onDownloadProgress()");
                            }

                            @Override
                            public void onDownloadStarted(long l) {
                                Log.w(TAG, "onDownloadStarted()");
                            }
                        });
            } else if (featureStatus == FeatureStatus.DOWNLOADING) {
                Log.w(TAG, "Feature downloading");
                rewriteText();
            } else if (featureStatus == FeatureStatus.AVAILABLE) {
                Log.w(TAG, "Feature available");
                rewriteText();
            } else if (featureStatus == FeatureStatus.UNAVAILABLE) {
                Log.e(TAG, "Feature is unavailable. Test will fail");
            }
        } catch (ExecutionException | InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "onDestroy()");
        rewriter.close();
    }

    public void rewriteText() {
        RewritingRequest request = RewritingRequest.builder(REWRITE_TEXT).build();
        ListenableFuture<RewritingResult> future = rewriter.runInference(request);
        Futures.addCallback(
                future,
                new FutureCallback<>() {
                    @Override
                    public void onSuccess(RewritingResult result) {
                        Log.i(TAG, "Successful rewrite inference");
                        if (!result.getResults().isEmpty()) {
                            for (int i = 0; i < result.getResults().size(); ++i) {
                                Log.i(
                                        TAG,
                                        "Rewritten suggestion #"
                                                + i
                                                + ": "
                                                + result.getResults().get(i).getText());
                            }
                            Intent intent = new Intent(ACTION_INFERENCE_COMPLETE);
                            sendBroadcast(intent);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        throw new RuntimeException("Failed to rewrite", t);
                    }
                },
                MoreExecutors.directExecutor());
    }
}
