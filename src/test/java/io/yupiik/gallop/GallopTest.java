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
package io.yupiik.gallop;

import io.yupiik.gallop.command.Start;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import static java.util.concurrent.TimeUnit.MINUTES;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GallopTest {
    @Test
    void cinematic(@TempDir final Path work) throws IOException, InterruptedException {
        final var started = new CountDownLatch(1);
        final var start = new Thread(() -> {
            try {
                new Start() {
                    @Override
                    protected void onStart(final Path path) {
                        super.onStart(path);
                        started.countDown();
                    }
                }.exec(List.of("1", "2", "120"));
            } catch (final IOException e) {
                throw new IllegalStateException(e);
            }
        }, "gallop-GallopTest-cinematic_start");
        start.start();
        assertTrue(started.await(1, MINUTES));

        try {
            Gallop.main(
                    "exec", "1",
                    Path.of(System.getProperty("java.home")).resolve("bin/java").toString(),
                    "-cp",
                    GallopTest.class.getProtectionDomain().getCodeSource().getLocation().getFile(),
                    CreateFile.class.getName(),
                    work.toString(),
                    "Hello\nFrom\nTest");
        } finally {
            Gallop.main("await", "1");
            start.join();
        }

        assertEquals("""
                Hello
                From
                Test""", Files.readString(work.resolve("test.txt")));
    }

    public static class CreateFile {
        private CreateFile() {
            // no-op
        }

        public static void main(final String... args) throws IOException {
            Files.writeString(Files.createDirectories(Path.of(args[0])).resolve("test.txt"), args[1]);
        }
    }
}
