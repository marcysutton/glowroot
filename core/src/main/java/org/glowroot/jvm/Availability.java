/*
 * Copyright 2013 the original author or authors.
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
package org.glowroot.jvm;

import checkers.igj.quals.Immutable;

import org.glowroot.markers.UsedByJsonBinding;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
@UsedByJsonBinding
@Immutable
public class Availability {

    private final boolean available;
    // reason only needed when available is false
    private final String reason;

    static Availability unavailable(String reason) {
        return new Availability(false, reason);
    }

    static Availability available() {
        return new Availability(true, "");
    }

    static Availability from(boolean available, String reason) {
        return new Availability(available, reason);
    }

    private Availability(boolean available, String reason) {
        this.available = available;
        this.reason = reason;
    }

    public boolean isAvailable() {
        return available;
    }

    public String getReason() {
        return reason;
    }
}