package org.apache.flink.runtime.jobmaster;

import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.DelegatingConfiguration;
import org.apache.flink.configuration.JobManagerOptions;
import org.apache.flink.core.plugin.PluginManager;
import org.apache.flink.runtime.executiongraph.JobStatusListener;
import org.apache.flink.runtime.executiongraph.JobStatusListenerFactory;

import org.apache.flink.shaded.guava18.com.google.common.collect.Iterators;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ServiceConfigurationError;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class JobStatusListenerSetup {

    private static final Logger LOG = LoggerFactory.getLogger(JobStatusListenerSetup.class);

    // regex pattern to split the defined listener
    private static final Pattern listenerListPattern = Pattern.compile("\\s*,\\s*");

    // regex pattern to extract the name from listener configuration keys, e.g. "foo" from
    // "jobmanager.job-status-listener.foo.class"
    private static final Pattern listenerClassPattern =
            Pattern.compile(
                    Pattern.quote(ConfigConstants.JOB_STATUS_LISTENER_PREFIX)
                            +
                            // [\S&&[^.]] = intersection of non-whitespace and non-period character
                            // classes
                            "([\\S&&[^.]]*)\\."
                            + '('
                            + Pattern.quote(ConfigConstants.JOB_STATUS_LISTENER_CLASS_SUFFIX)
                            + '|'
                            + Pattern.quote(
                                    ConfigConstants.JOB_STATUS_LISTENER_FACTORY_CLASS_SUFFIX)
                            + ')');

    private final String name;
    private final Configuration configuration;
    private final JobStatusListener jobStatusListener;

    public JobStatusListenerSetup(
            final String name,
            final Configuration configuration,
            final JobStatusListener jobStatusListener) {
        this.name = name;
        this.configuration = configuration;
        this.jobStatusListener = jobStatusListener;
    }

    public String getName() {
        return name;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public JobStatusListener getJobStatusListener() {
        return jobStatusListener;
    }

    public static List<JobStatusListenerSetup> fromConfiguration(
            final Configuration configuration, final PluginManager pluginManager) {
        String customJobStatusListenersString =
                configuration.getString(JobManagerOptions.JOB_LISTENERS_LIST, "");

        Set<String> namedListeners =
                findEnabledListenersInConfiguration(configuration, customJobStatusListenersString);
        if (namedListeners.isEmpty()) {
            return Collections.emptyList();
        }

        final List<Tuple2<String, Configuration>> listenerConfigurations =
                loadListenerConfigurations(configuration, namedListeners);
        final Map<String, JobStatusListenerFactory> listenerFactories =
                loadAvailableListenerFactories(pluginManager);

        return setupListeners(listenerFactories, listenerConfigurations);
    }

    private static JobStatusListenerSetup createListenerSetup(
            String listenerName, Configuration listenerConfig, JobStatusListener listener) {
        listener.open(listenerConfig);
        return new JobStatusListenerSetup(listenerName, listenerConfig, listener);
    }

    private static Set<String> findEnabledListenersInConfiguration(
            final Configuration configuration, String customJobStatusListenersString) {
        Set<String> includedListeners =
                listenerListPattern
                        .splitAsStream(customJobStatusListenersString)
                        .filter(r -> !r.isEmpty()) // splitting an empty string results in
                        // an empty string on jdk9+
                        .collect(Collectors.toSet());
        Set<String> namedOrderedListeners = new TreeSet<>(String::compareTo);
        for (String key : configuration.keySet()) {
            if (key.startsWith(ConfigConstants.JOB_STATUS_LISTENER_PREFIX)) {
                Matcher matcher = listenerClassPattern.matcher(key);
                if (matcher.matches()) {
                    String listenerName = matcher.group(1);
                    if (includedListeners.isEmpty() || includedListeners.contains(listenerName)) {
                        if (namedOrderedListeners.contains(listenerName)) {
                            LOG.warn(
                                    "Duplicate class configuration detected for listener {}.",
                                    listenerName);
                        } else {
                            namedOrderedListeners.add(listenerName);
                        }
                    } else {
                        LOG.info(
                                "Excluding listener {}, not configured in listener list ({}).",
                                listenerName,
                                namedOrderedListeners);
                    }
                }
            }
        }
        return namedOrderedListeners;
    }

    private static List<Tuple2<String, Configuration>> loadListenerConfigurations(
            Configuration configuration, Set<String> namedListeners) {
        final List<Tuple2<String, Configuration>> listenerConfigurations =
                new ArrayList<>(namedListeners.size());

        for (String namedListener : namedListeners) {
            DelegatingConfiguration delegatingConfiguration =
                    new DelegatingConfiguration(
                            configuration,
                            ConfigConstants.JOB_STATUS_LISTENER_PREFIX + namedListener + '.');

            listenerConfigurations.add(Tuple2.of(namedListener, delegatingConfiguration));
        }
        return listenerConfigurations;
    }

    private static Map<String, JobStatusListenerFactory> loadAvailableListenerFactories(
            @Nullable PluginManager pluginManager) {
        final Map<String, JobStatusListenerFactory> listenerFactories = new HashMap<>(2);
        final Iterator<JobStatusListenerFactory> factoryIterator =
                getAllListenerFactories(pluginManager);

        while (factoryIterator.hasNext()) {
            try {
                JobStatusListenerFactory factory = factoryIterator.next();
                String factoryClassName = factory.getClass().getName();
                JobStatusListenerFactory existingFactory = listenerFactories.get(factoryClassName);
                if (existingFactory == null) {
                    listenerFactories.put(factoryClassName, factory);
                    LOG.debug(
                            "Found listener factory {} at {} ",
                            factoryClassName,
                            new File(
                                            factory.getClass()
                                                    .getProtectionDomain()
                                                    .getCodeSource()
                                                    .getLocation()
                                                    .toURI())
                                    .getCanonicalPath());
                } else {
                    LOG.warn(
                            "Multiple implementations of the same listener were found in 'lib' and/or 'plugins' directories for {}. It is recommended to remove redundant listener JARs to resolve used versions' ambiguity.",
                            factoryClassName);
                }
            } catch (Exception | ServiceConfigurationError e) {
                LOG.warn("Error while loading listener factory.", e);
            }
        }

        return Collections.unmodifiableMap(listenerFactories);
    }

    private static Iterator<JobStatusListenerFactory> getAllListenerFactories(
            @Nullable PluginManager pluginManager) {
        final Iterator<JobStatusListenerFactory> factoryIteratorSPI =
                ServiceLoader.load(JobStatusListenerFactory.class).iterator();
        final Iterator<JobStatusListenerFactory> factoryIteratorPlugins =
                pluginManager != null
                        ? pluginManager.load(JobStatusListenerFactory.class)
                        : Collections.emptyIterator();

        return Iterators.concat(factoryIteratorPlugins, factoryIteratorSPI);
    }

    private static List<JobStatusListenerSetup> setupListeners(
            Map<String, JobStatusListenerFactory> listenerFactories,
            List<Tuple2<String, Configuration>> listenerConfigurations) {
        List<JobStatusListenerSetup> listenerSetups =
                new ArrayList<>(listenerConfigurations.size());
        for (Tuple2<String, Configuration> listenerConfiguration : listenerConfigurations) {
            String listenerName = listenerConfiguration.f0;
            Configuration listenerConfig = listenerConfiguration.f1;

            try {
                Optional<JobStatusListener> listenerOptional =
                        loadListener(listenerName, listenerConfig, listenerFactories);
                listenerOptional.ifPresent(
                        listener -> {
                            Configuration config = new Configuration(listenerConfig);
                            listenerSetups.add(createListenerSetup(listenerName, config, listener));
                        });
            } catch (Throwable t) {
                LOG.error(
                        "Could not instantiate listener {}. Job status might not be notified.",
                        listenerName,
                        t);
            }
        }
        return listenerSetups;
    }

    private static Optional<JobStatusListener> loadListener(
            final String listenerName,
            final Configuration listenerConfig,
            final Map<String, JobStatusListenerFactory> listenerFactories)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {

        final String listenerClassName =
                listenerConfig.getString(ConfigConstants.JOB_STATUS_LISTENER_CLASS_SUFFIX, null);
        final String factoryClassName =
                listenerConfig.getString(
                        ConfigConstants.JOB_STATUS_LISTENER_FACTORY_CLASS_SUFFIX, null);

        if (factoryClassName != null) {
            return loadViaFactory(
                    factoryClassName, listenerName, listenerConfig, listenerFactories);
        }

        if (listenerClassName != null) {
            return loadViaReflection(listenerClassName);
        }

        LOG.warn(
                "No listener class nor factory set for listener {}. Job status might not be notified.",
                listenerName);
        return Optional.empty();
    }

    private static Optional<JobStatusListener> loadViaFactory(
            final String factoryClassName,
            final String listenerName,
            final Configuration listenerConfig,
            final Map<String, JobStatusListenerFactory> listenerFactories) {

        JobStatusListenerFactory factory = listenerFactories.get(factoryClassName);

        if (factory == null) {
            LOG.warn(
                    "The listener factory ({}) could not be found for listener {}. Available factories: {}.",
                    factoryClassName,
                    listenerName,
                    listenerFactories.keySet());
            return Optional.empty();
        } else {
            return loadViaFactory(listenerConfig, factory);
        }
    }

    private static Optional<JobStatusListener> loadViaFactory(
            final Configuration listenerConfig, final JobStatusListenerFactory factory) {
        return Optional.of(factory.createJobStatusListener(listenerConfig));
    }

    private static Optional<JobStatusListener> loadViaReflection(final String listenerClassName)
            throws ClassNotFoundException, IllegalAccessException, InstantiationException {
        final Class<?> listenerClass = Class.forName(listenerClassName);
        return Optional.of((JobStatusListener) listenerClass.newInstance());
    }
}
