/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.nifi.provenance.store.iterator;

import org.apache.nifi.provenance.ProvenanceEventRecord;

import java.io.Closeable;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Optional;
import java.util.function.Predicate;

public interface EventIterator extends Closeable {

    Optional<ProvenanceEventRecord> nextEvent() throws IOException;


    EventIterator EMPTY = new EventIterator() {
        @Override
        public void close() throws IOException {
        }

        @Override
        public Optional<ProvenanceEventRecord> nextEvent() {
            return Optional.empty();
        }
    };

    static EventIterator of(final ProvenanceEventRecord... events) {
        final Iterator<ProvenanceEventRecord> itr = Arrays.asList(events).iterator();
        return new EventIterator() {
            @Override
            public void close() throws IOException {
            }

            @Override
            public Optional<ProvenanceEventRecord> nextEvent() {
                return itr.hasNext() ? Optional.empty() : Optional.of(itr.next());
            }
        };
    }


    default EventIterator filter(Predicate<ProvenanceEventRecord> predicate) {
        final EventIterator self = this;

        return new EventIterator() {
            @Override
            public void close() throws IOException {
                self.close();
            }

            @Override
            public Optional<ProvenanceEventRecord> nextEvent() throws IOException {
                while (true) {
                    Optional<ProvenanceEventRecord> next = self.nextEvent();
                    if (!next.isPresent()) {
                        return next;
                    }

                    final ProvenanceEventRecord event = next.get();
                    if (predicate.test(event)) {
                        return next;
                    }
                }
            }
        };
    }
}
