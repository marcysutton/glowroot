/*
 * Copyright 2012-2013 the original author or authors.
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
package org.glowroot.trace;

import java.util.Random;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;

import com.google.common.base.Ticker;

import org.glowroot.config.ConfigService;
import org.glowroot.config.FineProfilingConfig;
import org.glowroot.config.UserOverridesConfig;
import org.glowroot.markers.Singleton;
import org.glowroot.trace.model.Trace;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;

/**
 * Captures fine-grained profile for a percentage of traces.
 * 
 * @author Trask Stalnaker
 * @since 0.5
 */
@Singleton
class FineProfileScheduler {

    private final ScheduledExecutorService scheduledExecutor;
    private final ConfigService configService;
    private final Ticker ticker;
    private final Random random;

    FineProfileScheduler(ScheduledExecutorService scheduledExecutor, ConfigService configService,
            Ticker ticker, Random random) {
        this.scheduledExecutor = scheduledExecutor;
        this.configService = configService;
        this.ticker = ticker;
        this.random = random;
    }

    void maybeScheduleFineProfilingUsingUserId(Trace trace, String userId) {
        UserOverridesConfig userOverridesConfig = configService.getUserOverridesConfig();
        String overrideUserId = userOverridesConfig.getUserId();
        if (userId.equals(overrideUserId) && userOverridesConfig.isFineProfiling()) {
            scheduleProfiling(trace);
        }
    }

    void maybeScheduleFineProfilingUsingPercentage(Trace trace) {
        FineProfilingConfig fineProfilingConfig = configService.getFineProfilingConfig();
        double tracePercentage = fineProfilingConfig.getTracePercentage();
        // just optimization to check tracePercentage != 0
        if (tracePercentage != 0 && random.nextDouble() * 100 < tracePercentage) {
            scheduleProfiling(trace);
        }
    }

    // schedules the first stack collection for configured interval after trace start (or
    // immediately, if trace duration already exceeds configured collection interval)
    private void scheduleProfiling(Trace trace) {
        FineProfilingConfig config = configService.getFineProfilingConfig();
        // extra half interval at the end to make sure the final stack trace is grabbed if it aligns
        // on total (e.g. 100ms interval, 1 second total should result in exactly 10 stack traces)
        long endTick = trace.getStartTick() + SECONDS.toNanos(config.getTotalSeconds())
                + MILLISECONDS.toNanos(config.getIntervalMillis()) / 2;
        CollectStackCommand command = new CollectStackCommand(trace, endTick, true, ticker);
        long initialDelay = Math.max(0,
                config.getIntervalMillis() - NANOSECONDS.toMillis(trace.getDuration()));
        ScheduledFuture<?> scheduledFuture = scheduledExecutor.scheduleAtFixedRate(command,
                initialDelay, config.getIntervalMillis(), MILLISECONDS);
        trace.setFineProfilingScheduledFuture(scheduledFuture);
    }
}