/*
 * Copyright 2013-2015 the original author or authors.
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
package org.glowroot.ui;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.List;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Ordering;
import com.google.common.io.CharStreams;
import org.immutables.value.Value;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.glowroot.common.live.LiveJvmService;
import org.glowroot.common.live.LiveJvmService.MBeanTreeRequest;
import org.glowroot.common.live.LiveThreadDumpService;
import org.glowroot.common.util.Clock;
import org.glowroot.common.util.ObjectMappers;
import org.glowroot.storage.repo.ConfigRepository;
import org.glowroot.storage.repo.GaugeValueRepository;
import org.glowroot.storage.repo.GaugeValueRepository.Gauge;
import org.glowroot.wire.api.model.GaugeValueOuterClass.GaugeValue;

@JsonService
class JvmJsonService {

    private static final Logger logger = LoggerFactory.getLogger(JvmJsonService.class);
    private static final ObjectMapper mapper = ObjectMappers.create();

    private final GaugeValueRepository gaugeValueRepository;
    private final ConfigRepository configRepository;
    private final LiveJvmService liveJvmService;
    private final LiveThreadDumpService liveThreadDumpService;

    private final Clock clock;

    JvmJsonService(GaugeValueRepository gaugeValueDao, ConfigRepository configRepository,
            LiveJvmService liveJvmService, LiveThreadDumpService liveThreadDumpService,
            Clock clock) {
        this.gaugeValueRepository = gaugeValueDao;
        this.configRepository = configRepository;
        this.liveJvmService = liveJvmService;
        this.liveThreadDumpService = liveThreadDumpService;
        this.clock = clock;
    }

    @GET("/backend/jvm/gauge-values")
    String getGaugeValues(String queryString) throws Exception {
        GaugeValueRequest request = QueryStrings.decode(queryString, GaugeValueRequest.class);
        int rollupLevel = gaugeValueRepository.getRollupLevelForView(request.serverGroup(),
                request.from(), request.to());
        long intervalMillis;
        if (rollupLevel == 0) {
            intervalMillis = configRepository.getGaugeCollectionIntervalMillis();
        } else {
            intervalMillis =
                    configRepository.getRollupConfigs().get(rollupLevel - 1).intervalMillis();
        }
        double gapMillis = intervalMillis * 1.5;
        // 2x in order to deal with displaying deltas
        long revisedFrom = request.from() - 2 * intervalMillis;
        long revisedTo = request.to() + intervalMillis;

        long liveCaptureTime = clock.currentTimeMillis();
        List<DataSeries> dataSeriesList = Lists.newArrayList();
        for (String gaugeName : request.gaugeNames()) {
            List<GaugeValue> gaugeValues = getGaugeValues(request.serverGroup(), revisedFrom,
                    revisedTo, gaugeName, rollupLevel, liveCaptureTime);
            if (!gaugeValues.isEmpty()) {
                dataSeriesList.add(convertToDataSeriesWithGaps(gaugeName, gaugeValues, gapMillis));
            }
        }

        StringBuilder sb = new StringBuilder();
        JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
        jg.writeStartObject();
        jg.writeObjectField("dataSeries", dataSeriesList);
        jg.writeEndObject();
        jg.close();
        return sb.toString();
    }

    @GET("/backend/jvm/all-gauges")
    String getAllGaugeNames(String queryString) throws Exception {
        String server = getServer(queryString);
        List<Gauge> gauges = gaugeValueRepository.getGauges(server);
        ImmutableList<Gauge> sortedGauges = new GaugeOrdering().immutableSortedCopy(gauges);
        return mapper.writeValueAsString(sortedGauges);
    }

    @GET("/backend/jvm/mbean-tree")
    String getMBeanTree(String queryString) throws Exception {
        MBeanTreeRequest request = QueryStrings.decode(queryString, MBeanTreeRequest.class);
        return mapper.writeValueAsString(liveJvmService.getMBeanTree(request));
    }

    @GET("/backend/jvm/mbean-attribute-map")
    String getMBeanAttributeMap(String queryString) throws Exception {
        MBeanAttributeMapRequest request =
                QueryStrings.decode(queryString, MBeanAttributeMapRequest.class);
        return mapper.writeValueAsString(liveJvmService
                .getMBeanSortedAttributeMap(request.server(), request.objectName()));
    }

    @POST("/backend/jvm/perform-gc")
    void performGC() throws IOException {
        // using MemoryMXBean.gc() instead of System.gc() in hope that it will someday bypass
        // -XX:+DisableExplicitGC (see https://bugs.openjdk.java.net/browse/JDK-6396411)
        ManagementFactory.getMemoryMXBean().gc();
    }

    @GET("/backend/jvm/thread-dump")
    String getThreadDump() throws IOException {
        return mapper.writeValueAsString(liveThreadDumpService.getAllThreads());
    }

    @GET("/backend/jvm/heap-dump-default-dir")
    String getHeapDumpDefaultDir(String queryString) throws Exception {
        String server = getServer(queryString);
        return mapper.writeValueAsString(liveJvmService.getHeapDumpDefaultDirectory(server));
    }

    @POST("/backend/jvm/available-disk-space")
    String getAvailableDiskSpace(String content) throws IOException {
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        try {
            return Long.toString(
                    liveJvmService.getAvailableDiskSpace(request.server(),
                            request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @POST("/backend/jvm/dump-heap")
    String dumpHeap(String content) throws Exception {
        HeapDumpRequest request = mapper.readValue(content, ImmutableHeapDumpRequest.class);
        try {
            return mapper.writeValueAsString(
                    liveJvmService.dumpHeap(request.server(), request.directory()));
        } catch (IOException e) {
            logger.debug(e.getMessage(), e);
            // this is for specific common errors, e.g. "Directory doesn't exist"
            StringBuilder sb = new StringBuilder();
            JsonGenerator jg = mapper.getFactory().createGenerator(CharStreams.asWriter(sb));
            jg.writeStartObject();
            jg.writeStringField("error", e.getMessage());
            jg.writeEndObject();
            jg.close();
            return sb.toString();
        }
    }

    @GET("/backend/jvm/process-info")
    String getProcessInfo(String queryString) throws Exception {
        String server = getServer(queryString);
        return mapper.writeValueAsString(liveJvmService.getProcessInfo(server));
    }

    @GET("/backend/jvm/capabilities")
    String getCapabilities(String queryString) throws Exception {
        String server = getServer(queryString);
        return mapper.writeValueAsString(liveJvmService.getCapabilities(server));
    }

    private List<GaugeValue> getGaugeValues(String serverGroup, long from, long to,
            String gaugeName, int rollupLevel, long liveCaptureTime) throws Exception {
        List<GaugeValue> gaugeValues =
                gaugeValueRepository.readGaugeValues(serverGroup, gaugeName, from, to, rollupLevel);
        if (rollupLevel == 0) {
            return gaugeValues;
        }
        long nonRolledUpFrom = from;
        if (!gaugeValues.isEmpty()) {
            long lastRolledUpTime = gaugeValues.get(gaugeValues.size() - 1).getCaptureTime();
            nonRolledUpFrom = Math.max(nonRolledUpFrom, lastRolledUpTime + 1);
        }
        List<GaugeValue> allGaugeValues = Lists.newArrayList(gaugeValues);
        allGaugeValues.addAll(gaugeValueRepository.readManuallyRolledUpGaugeValues(serverGroup,
                nonRolledUpFrom, to, gaugeName, rollupLevel, liveCaptureTime));
        return allGaugeValues;
    }

    private static DataSeries convertToDataSeriesWithGaps(String dataSeriesName,
            List<GaugeValue> gaugeValues, double gapMillis) {
        DataSeries dataSeries = new DataSeries(dataSeriesName);
        GaugeValue lastGaugeValue = null;
        for (GaugeValue gaugeValue : gaugeValues) {
            if (lastGaugeValue != null
                    && gaugeValue.getCaptureTime() - lastGaugeValue.getCaptureTime() > gapMillis) {
                dataSeries.addNull();
            }
            dataSeries.add(gaugeValue.getCaptureTime(), gaugeValue.getValue());
            lastGaugeValue = gaugeValue;
        }
        return dataSeries;
    }

    private static String getServer(String queryString) throws Exception {
        return queryString.substring("server".length() + 1);
    }

    @Value.Immutable
    interface GaugeValueRequest {
        String serverGroup();
        long from();
        long to();
        ImmutableList<String> gaugeNames();
    }

    @Value.Immutable
    interface MBeanAttributeMapRequest {
        String server();
        String objectName();
    }

    @Value.Immutable
    interface HeapDumpRequest {
        String server();
        String directory();
    }

    private static class GaugeOrdering extends Ordering<Gauge> {
        @Override
        public int compare(Gauge left, Gauge right) {
            return left.display().compareToIgnoreCase(right.display());
        }
    }
}