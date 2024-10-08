/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [https://neo4j.com]
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.neo4j.driver.internal.messaging.request;

import static java.util.Arrays.asList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.neo4j.driver.AccessMode.READ;
import static org.neo4j.driver.AccessMode.WRITE;
import static org.neo4j.driver.Values.value;
import static org.neo4j.driver.internal.DatabaseNameUtil.database;
import static org.neo4j.driver.internal.DatabaseNameUtil.defaultDatabase;
import static org.neo4j.driver.internal.messaging.request.TransactionMetadataBuilder.buildMetadata;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.neo4j.driver.AccessMode;
import org.neo4j.driver.Bookmark;
import org.neo4j.driver.Logger;
import org.neo4j.driver.Logging;
import org.neo4j.driver.NotificationClassification;
import org.neo4j.driver.NotificationSeverity;
import org.neo4j.driver.Value;
import org.neo4j.driver.internal.GqlNotificationConfig;
import org.neo4j.driver.internal.InternalBookmark;

public class TransactionMetadataBuilderTest {
    @ParameterizedTest
    @EnumSource(AccessMode.class)
    void shouldHaveCorrectMetadata(AccessMode mode) {
        var bookmarks = Collections.singleton(
                InternalBookmark.parse(new HashSet<>(asList("neo4j:bookmark:v1:tx11", "neo4j:bookmark:v1:tx52"))));

        Map<String, Value> txMetadata = new HashMap<>();
        txMetadata.put("foo", value("bar"));
        txMetadata.put("baz", value(111));
        txMetadata.put("time", value(LocalDateTime.now()));

        var txTimeout = Duration.ofSeconds(7);

        var metadata = buildMetadata(
                txTimeout, txMetadata, defaultDatabase(), mode, bookmarks, null, null, null, true, Logging.none());

        Map<String, Value> expectedMetadata = new HashMap<>();
        expectedMetadata.put(
                "bookmarks", value(bookmarks.stream().map(Bookmark::value).collect(Collectors.toSet())));
        expectedMetadata.put("tx_timeout", value(7000));
        expectedMetadata.put("tx_metadata", value(txMetadata));
        if (mode == READ) {
            expectedMetadata.put("mode", value("r"));
        }

        assertEquals(expectedMetadata, metadata);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "foo", "data"})
    void shouldHaveCorrectMetadataForDatabaseName(String databaseName) {
        var bookmarks = Collections.singleton(
                InternalBookmark.parse(new HashSet<>(asList("neo4j:bookmark:v1:tx11", "neo4j:bookmark:v1:tx52"))));

        Map<String, Value> txMetadata = new HashMap<>();
        txMetadata.put("foo", value("bar"));
        txMetadata.put("baz", value(111));
        txMetadata.put("time", value(LocalDateTime.now()));

        var txTimeout = Duration.ofSeconds(7);

        var metadata = buildMetadata(
                txTimeout,
                txMetadata,
                database(databaseName),
                WRITE,
                bookmarks,
                null,
                null,
                null,
                true,
                Logging.none());

        Map<String, Value> expectedMetadata = new HashMap<>();
        expectedMetadata.put(
                "bookmarks", value(bookmarks.stream().map(Bookmark::value).collect(Collectors.toSet())));
        expectedMetadata.put("tx_timeout", value(7000));
        expectedMetadata.put("tx_metadata", value(txMetadata));
        expectedMetadata.put("db", value(databaseName));

        assertEquals(expectedMetadata, metadata);
    }

    @Test
    void shouldNotHaveMetadataForDatabaseNameWhenIsNull() {
        var metadata = buildMetadata(
                null, null, defaultDatabase(), WRITE, Collections.emptySet(), null, null, null, true, Logging.none());
        assertTrue(metadata.isEmpty());
    }

    @Test
    void shouldIncludeGqlNotificationConfig() {
        var metadata = buildMetadata(
                null,
                null,
                defaultDatabase(),
                WRITE,
                Collections.emptySet(),
                null,
                null,
                new GqlNotificationConfig(NotificationSeverity.WARNING, Set.of(NotificationClassification.UNSUPPORTED)),
                true,
                Logging.none());

        var expectedMetadata = new HashMap<String, Value>();
        expectedMetadata.put("notifications_minimum_severity", value("WARNING"));
        expectedMetadata.put("notifications_disabled_categories", value(Set.of("UNSUPPORTED")));
        assertEquals(expectedMetadata, metadata);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 1_000_001, 100_500_000, 100_700_000, 1_000_000_001})
    void shouldRoundUpFractionalTimeoutAndLog(long nanosValue) {
        // given
        var logging = mock(Logging.class);
        var logger = mock(Logger.class);
        given(logging.getLog(TransactionMetadataBuilder.class)).willReturn(logger);

        // when
        var metadata = buildMetadata(
                Duration.ofNanos(nanosValue),
                null,
                defaultDatabase(),
                WRITE,
                Collections.emptySet(),
                null,
                null,
                null,
                true,
                logging);

        // then
        var expectedMetadata = new HashMap<String, Value>();
        var expectedMillis = nanosValue / 1_000_000 + 1;
        expectedMetadata.put("tx_timeout", value(expectedMillis));
        assertEquals(expectedMetadata, metadata);
        then(logging).should().getLog(TransactionMetadataBuilder.class);
        then(logger)
                .should()
                .info(
                        "The transaction timeout has been rounded up to next millisecond value since the config had a fractional millisecond value");
    }

    @Test
    void shouldNotLogWhenRoundingDoesNotHappen() {
        // given
        var logging = mock(Logging.class);
        var logger = mock(Logger.class);
        given(logging.getLog(TransactionMetadataBuilder.class)).willReturn(logger);
        var timeout = 1000;

        // when
        var metadata = buildMetadata(
                Duration.ofMillis(timeout),
                null,
                defaultDatabase(),
                WRITE,
                Collections.emptySet(),
                null,
                null,
                null,
                true,
                logging);

        // then
        var expectedMetadata = new HashMap<String, Value>();
        expectedMetadata.put("tx_timeout", value(timeout));
        assertEquals(expectedMetadata, metadata);
        then(logging).shouldHaveNoInteractions();
        then(logger).shouldHaveNoInteractions();
    }
}
