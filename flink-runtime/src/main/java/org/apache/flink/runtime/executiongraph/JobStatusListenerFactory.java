package org.apache.flink.runtime.executiongraph;

import org.apache.flink.configuration.Configuration;

/**
 * {@link JobStatusListener} factory.
 *
 * <p>Listeners that can be instantiated with a factory automatically qualify for being loaded as a
 * plugin, so long as the listener jar is self-contained (excluding Flink dependencies) and contains
 * a {@code META-INF/services/org.apache.flink.runtime.executiongraph.JobStatusListenerFactory} file
 * containing the qualified class name of the factory.
 */
public interface JobStatusListenerFactory {

    /**
     * Creates a new job status listener.
     *
     * @param configuration configuration for the listener
     * @return created job status listener
     */
    JobStatusListener createJobStatusListener(final Configuration configuration);
}
