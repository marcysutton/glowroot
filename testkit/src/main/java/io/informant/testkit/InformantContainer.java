/**
 * Copyright 2011-2013 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.testkit;

import io.informant.api.Logger;
import io.informant.api.LoggerFactory;
import io.informant.core.util.ThreadSafe;
import io.informant.testkit.internal.TempDirs;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableMap;

/**
 * {@link AppUnderTest}s are intended to be run serially within a given InformantContainer.
 * {@link AppUnderTest}s can be run in parallel using multiple InformantContainers.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
// even though this is thread safe, it is not useful for running tests in parallel since
// getLastTrace() and others are not scoped to a particular test
@ThreadSafe
public class InformantContainer {

    private static final Logger logger = LoggerFactory.getLogger(InformantContainer.class);

    private static final AtomicInteger threadNameCounter = new AtomicInteger();

    private final ExecutionAdapter executionAdapter;
    private final File dataDir;
    private final Informant informant;

    public static InformantContainer create() throws Exception {
        return create(0, false);
    }

    public static InformantContainer create(int uiPort, boolean useFileDb) throws Exception {
        File dataDir = TempDirs.createTempDir("informant-test-datadir");
        return create(uiPort, useFileDb, dataDir);
    }

    public static InformantContainer create(int uiPort, boolean useFileDb, File dataDir)
            throws Exception {
        // capture pre-existing threads before instantiating execution adapters
        ImmutableMap<String, String> properties = ImmutableMap.of(
                "ui.port", Integer.toString(uiPort),
                "data.dir", dataDir.getAbsolutePath(),
                "internal.h2.memdb", Boolean.toString(!useFileDb));
        ExecutionAdapter executionAdapter;
        if (useExternalJvmAppContainer()) {
            // this is the most realistic way to run tests because it launches an external JVM
            // process using -javaagent:informant-core.jar
            logger.debug("create(): using external JVM app container");
            executionAdapter = new ExternalJvmExecutionAdapter(properties);
        } else {
            // this is the easiest way to run/debug tests inside of Eclipse
            logger.debug("create(): using same JVM app container");
            executionAdapter = new SameJvmExecutionAdapter(properties);
        }
        return new InformantContainer(executionAdapter, dataDir);
    }

    private InformantContainer(ExecutionAdapter executionAdapter, File dataDir) throws Exception {
        this.executionAdapter = executionAdapter;
        this.dataDir = dataDir;
        informant = executionAdapter.getInformant();
    }

    public Informant getInformant() {
        return informant;
    }

    public void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass)
            throws Exception {
        String threadName = "AppUnderTest-" + threadNameCounter.getAndIncrement();
        String previousThreadName = Thread.currentThread().getName();
        try {
            executionAdapter.executeAppUnderTest(appUnderTestClass, threadName);
            // wait for all traces to be written to the embedded db
            Stopwatch stopwatch = new Stopwatch().start();
            while (informant.getNumPendingCompleteTraces() > 0
                    && stopwatch.elapsedMillis() < 5000) {
                Thread.sleep(10);
            }
        } finally {
            Thread.currentThread().setName(previousThreadName);
        }
    }

    public File getDataDir() {
        return dataDir;
    }

    public void close() throws Exception {
        closeWithoutDeletingDataDir();
        TempDirs.deleteRecursively(dataDir);
    }

    public void killExternalJvm() throws Exception {
        ((ExternalJvmExecutionAdapter) executionAdapter).kill();
    }

    // currently only reports number of bytes written to console for external jvm app container
    public long getNumConsoleBytes() {
        return ((ExternalJvmExecutionAdapter) executionAdapter).getNumConsoleBytes();
    }

    public void closeWithoutDeletingDataDir() throws Exception {
        executionAdapter.close();
    }

    private static boolean useExternalJvmAppContainer() {
        return Boolean.valueOf(System.getProperty("externalJvmAppContainer"));
    }

    @ThreadSafe
    interface ExecutionAdapter {
        Informant getInformant();
        void executeAppUnderTest(Class<? extends AppUnderTest> appUnderTestClass, String threadName)
                throws Exception;
        void close() throws Exception;
    }
}
