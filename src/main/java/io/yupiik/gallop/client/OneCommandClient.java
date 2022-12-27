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
package io.yupiik.gallop.client;

import io.yupiik.gallop.protocol.BaseSocket;
import io.yupiik.gallop.protocol.Command;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

import static java.net.StandardProtocolFamily.UNIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Clock.systemUTC;
import static java.util.Optional.ofNullable;

public class OneCommandClient extends BaseSocket {
    private final UnixDomainSocketAddress address;

    public OneCommandClient(final String pid) {
        this.address = addressOf(pid);
    }

    public void send(final Command command) {
        // support a light retry since the start will be async, the first exec can happen too early if we don't do that
        final var clock = systemUTC();
        final var end = clock.instant()
                .plusSeconds(ofNullable(System.getenv("GALLOP_CLIENT_RETRY_TIMEOUT"))
                        .map(Integer::parseInt)
                        .orElseGet(() -> Integer.getInteger("gallop.client.retry.timeout", 120)));

        do {
            try (final var channel = SocketChannel.open(UNIX)) {
                channel.connect(address);

                final var message = command.toJson().getBytes(UTF_8);
                final var buffer = ByteBuffer.allocate(message.length);
                buffer.clear();
                buffer.put(message);
                buffer.flip();

                channel.write(buffer);
                return;
            } catch (final IOException ioe) {
                // retry
                try {
                    Thread.sleep(1_000);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (clock.instant().isBefore(end));
        throw new IllegalStateException("Can't connect to gallop start process, ensure it is up and '" + address + "' exists.");
    }
}
