package org.apache.flink.runtime.jobmaster;

import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.scheduler.SchedulerNG;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class CustomJobStatusListeners {
    private static final Logger LOG = LoggerFactory.getLogger(CustomJobStatusListeners.class);

    private final Object lock = new Object();

    @GuardedBy("lock")
    private final List<SurefireJobStatusListener> listeners;

    public CustomJobStatusListeners(Collection<JobStatusListenerSetup> listenerSetups) {
        this.listeners = new ArrayList<>();

        if (listenerSetups.isEmpty()) {
            // no custom job status listeners defined
            LOG.info("No custom listeners configured.");
        } else {
            for (JobStatusListenerSetup listenerSetup : listenerSetups) {
                //
                String listenerName = listenerSetup.getName();
                JobStatusListener listener = listenerSetup.getJobStatusListener();
                SurefireJobStatusListener surefireJobStatusListener =
                        new SurefireJobStatusListener(listenerName, listener);
                listeners.add(surefireJobStatusListener);
            }
        }
    }

    public void register(SchedulerNG schedulerNG) {
        synchronized (lock) {
            for (SurefireJobStatusListener listener : listeners) {
                LOG.info(
                        "Registering custom listener {} of type {}",
                        listener.name,
                        listener.getClass().getName());
                schedulerNG.registerJobStatusListener(listener);
            }
        }
    }

    public void shutdown() {
        synchronized (lock) {
            for (SurefireJobStatusListener listener : listeners) {
                try {
                    listener.close();
                } catch (Throwable t) {
                    LOG.warn(
                            "Failed to close the listener {} of type{}",
                            listener.name,
                            listener.getClass().getName(),
                            t);
                }
            }
        }
    }

    private static class SurefireJobStatusListener implements JobStatusListener {

        private final String name;
        private final JobStatusListener listener;

        private SurefireJobStatusListener(String name, JobStatusListener listener) {
            this.name = name;
            this.listener = listener;
        }

        @Override
        public void open(Configuration config) {
            listener.open(config);
        }

        @Override
        public void close() {
            listener.close();
        }

        @Override
        public void jobStatusChanges(
                JobID jobId, JobStatus newJobStatus, long timestamp, Throwable error) {
            try {
                listener.jobStatusChanges(jobId, newJobStatus, timestamp, error);
            } catch (Throwable t) {
                LOG.warn("Error while notifying the job status change", t);
            }
        }
    }
}
