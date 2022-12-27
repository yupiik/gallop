/*
 * Copyright (c) 2022 - Yupiik SAS - https://www.yupiik.com
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package io.yupiik.gallop.command;

import io.yupiik.gallop.client.OneCommandClient;
import io.yupiik.gallop.protocol.Command;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StartTest {
    @Test
    void start() throws IOException, InterruptedException {
        final var received = new CountDownLatch(1);
        final var serverStopped = new CountDownLatch(1);
        final var serverStarted = new CountDownLatch(1);
        final var unknown = new ArrayList<Command>();
        final var start = new Start() {
            @Override
            protected void onUnknownCommand(final Command cmd) {
                super.onUnknownCommand(cmd);
                synchronized (unknown) {
                    unknown.add(cmd);
                }
                received.countDown();
            }

            @Override
            protected void onStart(final Path path) {
                super.onStart(path);
                serverStarted.countDown();
            }
        };
        new Thread(() -> { // async since it is actually an event loop (blocking)
            try {
                start.exec(List.of("0", "2", "60"));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            } finally {
                serverStopped.countDown();
            }
        }, "gallop-server-starttest_start").start();

        assertTrue(serverStarted.await(1, MINUTES));

        final var client = new OneCommandClient("0");
        final var command = new Command("hello", List.of("test", "a\"'@\"é&value", "1"));

        client.send(command);
        assertTrue(received.await(1, MINUTES));
        synchronized (unknown) {
            assertEquals(List.of(command), unknown);
        }

        client.send(new Command("await", List.of()));
        assertTrue(serverStopped.await(1, MINUTES));
    }
}
