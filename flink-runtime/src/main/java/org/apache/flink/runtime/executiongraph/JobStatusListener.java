/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.runtime.executiongraph;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;

/** Interface for observers that monitor the status of a job. */
public interface JobStatusListener {

    /**
     * Configures this listener.
     *
     * <p>If the listener was instantiated generically and hence parameter-less, this method is the
     * place where the listener sets it's basic fields based on configuration values. Otherwise,
     * this method will typically be a no-op since resources can be acquired in the constructor.
     *
     * <p>This method is always called first on a newly instantiated listener.
     *
     * @param config The configuration for this reporter.
     */
    void open(Configuration config);

    /** Closes this listener. Should be used to close channels, streams and release resources. */
    void close();

    /**
     * This method is called whenever the status of the job changes.
     *
     * @param jobId The ID of the job.
     * @param newJobStatus The status the job switched to.
     * @param timestamp The timestamp when the status transition occurred.
     * @param error In case the job status switches to a failure state, this is the exception that
     *     caused the failure.
     */
    void jobStatusChanges(JobID jobId, JobStatus newJobStatus, long timestamp, Throwable error);
}
