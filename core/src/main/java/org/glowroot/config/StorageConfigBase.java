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
package org.glowroot.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.immutables.value.Value;

@Value.Immutable
// ignore these old properties as part of upgrade from 0.8.3 to 0.8.4
@JsonIgnoreProperties({"aggregateExpirationHours", "gaugeExpirationHours"})
public abstract class StorageConfigBase {

    // 2 days, 2 weeks, 2 months
    private static final ImmutableList<Integer> DEFAULT_ROLLUP_EXPIRATION_HOURS =
            ImmutableList.of(24 * 2, 24 * 7 * 2, 24 * 30 * 2);

    private static final ImmutableList<Integer> DEFAULT_CAPPED_DATABASE_SIZES_MB =
            ImmutableList.of(500, 500, 500);

    // TODO revisit this comment
    //
    // currently aggregate expiration should be at least as big as trace expiration
    // errors/messages page depends on this for calculating error percentage when using the filter
    @Value.Default
    public ImmutableList<Integer> rollupExpirationHours() {
        return DEFAULT_ROLLUP_EXPIRATION_HOURS;
    }

    @Value.Default
    public int traceExpirationHours() {
        return 24 * 7;
    }

    @Value.Default
    public ImmutableList<Integer> rollupCappedDatabaseSizesMb() {
        return DEFAULT_CAPPED_DATABASE_SIZES_MB;
    }

    @Value.Default
    public int traceCappedDatabaseSizeMb() {
        return 500;
    }

    @Value.Derived
    @JsonIgnore
    public String version() {
        return Versions.getVersion(this);
    }

    boolean hasListIssues() {
        return rollupExpirationHours().size() != DEFAULT_ROLLUP_EXPIRATION_HOURS.size()
                || rollupCappedDatabaseSizesMb().size() != DEFAULT_CAPPED_DATABASE_SIZES_MB.size();
    }

    StorageConfig withCorrectedLists() {
        StorageConfig thisConfig = (StorageConfig) this;
        StorageConfig defaultConfig = StorageConfig.builder().build();
        ImmutableList<Integer> rollupExpirationHours =
                fix(rollupExpirationHours(), defaultConfig.rollupExpirationHours());
        ImmutableList<Integer> rollupCappedDatabaseSizesMb =
                fix(rollupCappedDatabaseSizesMb(), defaultConfig.rollupCappedDatabaseSizesMb());
        return thisConfig.withRollupExpirationHours(rollupExpirationHours)
                .withRollupCappedDatabaseSizesMb(rollupCappedDatabaseSizesMb);
    }

    private ImmutableList<Integer> fix(ImmutableList<Integer> thisList, List<Integer> defaultList) {
        if (thisList.size() >= defaultList.size()) {
            return thisList.subList(0, defaultList.size());
        }
        List<Integer> correctedList = Lists.newArrayList(thisList);
        for (int i = thisList.size(); i < defaultList.size(); i++) {
            correctedList.add(defaultList.get(i));
        }
        return ImmutableList.copyOf(correctedList);
    }
}
