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

import io.yupiik.gallop.Gallop;
import io.yupiik.gallop.protocol.BaseSocket;
import io.yupiik.gallop.protocol.Command;

import java.io.IOException;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

import static java.net.StandardProtocolFamily.UNIX;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Clock.systemUTC;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static java.util.Optional.ofNullable;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

public class Start extends BaseSocket {
    private static final byte[] OK = new byte[]{1};
    private static final boolean DEBUG = Boolean.parseBoolean(ofNullable(System.getenv("GALLOP_DEBUG"))
            .orElseGet(() -> System.getProperty("gallop.debug", "false")));

    public void exec(final List<String> args) throws IOException {
        if (args.size() != 3 && args.size() != 4) {
            throw new IllegalArgumentException("Invalid start parameters, must be <id> <concurrency> <timeout in seconds> [<check exit code>]");
        }

        final var pid = args.get(0);
        final var concurrency = Integer.parseInt(args.get(1));
        final var timeout = Integer.parseInt(args.get(2));
        final var checkExitCode = args.size() == 3 || Boolean.parseBoolean(args.get(3));

        final var path = pathOf(pid);
        final var hook = new Thread(() -> {
            try {
                Files.deleteIfExists(path);
            } catch (final IOException e) {
                // no-op
            }
        }, "gallop-delete-unix-socket");
        Runtime.getRuntime().addShutdownHook(hook);

        final var address = UnixDomainSocketAddress.of(path);
        final var socket = ServerSocketChannel.open(UNIX);
        socket.bind(address);
        socket.configureBlocking(false);

        final var selector = Selector.open();

        final var clock = systemUTC();
        final var end = timeout <= 0 ? Instant.MAX : clock.instant().plusSeconds(timeout);
        final var poolSize = ofNullable(System.getenv("GALLOP_POOL_SIZE"))
                .map(Integer::parseInt)
                .orElseGet(() -> Math.max(1, concurrency));
        final var pool = new ThreadPoolExecutor(
                poolSize, poolSize, 1, MINUTES, new LinkedBlockingQueue<>(),
                new ThreadFactory() {
                    private final AtomicInteger counter = new AtomicInteger();

                    @Override
                    public Thread newThread(final Runnable worker) {
                        return new Thread(worker, Gallop.class.getName() + "-" + counter.incrementAndGet());
                    }
                },
                (r, executor) -> System.err.println("[GALLOP][ERROR] Can't accept new task"));

        final var throttler = new Semaphore(poolSize);
        final var processes = new ArrayList<Process>();
        try {
            socket.register(selector, socket.validOps(), null);
            onStart(path);

            final var failed = new AtomicBoolean(false);
            final var bufferPerClient = new ConcurrentHashMap<SocketChannel, ClientBuffer>();
            boolean await = false;
            while (!failed.get()) {
                if (clock.instant().isAfter(end)) {
                    System.err.println("[GALLOP] Timeout occurred for id=" + args.get(0) + ", port=" + pid + ", quitting");
                    if (!pool.isShutdown()) { // more brutal stop
                        pool.shutdownNow();
                    }
                    stop(pool, socket, selector, hook);
                    break;
                }

                if (await) {
                    if (DEBUG) {
                        System.out.println("[GALLOP][DEBUG] awaiting " + pool.getQueue().size() + " tasks");
                    }
                    doAwait(
                            pool,
                            Math.max(0, end.minusMillis(clock.instant().toEpochMilli()).toEpochMilli()),
                            clock, end,
                            throttler, concurrency);
                    break;
                }

                selector.select();
                final var keys = selector.selectedKeys().iterator();
                while (keys.hasNext()) {
                    final var key = keys.next();
                    try {
                        if (key.isAcceptable()) {
                            onAccept(socket, selector);
                        } else if (key.isReadable() && onRead(
                                key, pool, bufferPerClient, throttler,
                                (process, ex) -> onExecProcess(checkExitCode, processes, failed, process, ex),
                                clock, end)) {
                            await = true; // after other keys processing
                        }
                    } catch (final RuntimeException | IOException e) {
                        System.err.println("[GALLOP][ERROR] " + e.getMessage());
                        e.printStackTrace();
                        throw e;
                    } finally {
                        keys.remove();
                    }
                }
            }

            if (failed.get()) {
                throw new IllegalStateException("Some process execution failed");
            }
        } catch (final RuntimeException | IOException re) {
            System.err.println("[GALLOP][ERROR] " + re.getMessage());
            re.printStackTrace();
            throw re;
        } finally {
            if (!processes.isEmpty()) {
                killAll(processes);
            }
            stop(pool, socket, selector, hook);
        }
    }

    private static void onExecProcess(final boolean checkExitCode, final List<Process> processes, final AtomicBoolean failed,
                                      final Process process, final Exception ex) {
        synchronized (processes) {
            processes.add(process);
        }

        process.onExit().whenComplete((ok, ko) -> {
            synchronized (processes) {
                processes.remove(process);
            }

            if (checkExitCode && ok.exitValue() != 0) {
                System.err.println("[GALLOP][ERROR] A process failed: pid=" + ok.pid() + ", exitCode=" + ok.exitValue());
                failed.set(true);
            }
        });

        if (ex != null) {
            failed.set(true);
        }
    }

    private void killAll(final List<Process> processes) {
        processes.forEach(p -> {
            try {
                p.destroyForcibly();
            } catch (final RuntimeException re) {
                System.err.println("[GALLOP][ERROR] Error killing pid=" + p.pid() + " " + re.getMessage());
            }
        });
    }

    private boolean onRead(final SelectionKey key, final ThreadPoolExecutor pool,
                           final Map<SocketChannel, ClientBuffer> bufferPerClient,
                           final Semaphore throttler,
                           final BiConsumer<Process, Exception> processes,
                           final Clock clock, final Instant end) throws IOException {
        final var client = (SocketChannel) key.channel();

        final var bytes = ByteBuffer.allocate(1024);
        int size;

        while ((size = client.read(bytes)) > 0) {
            onMessage(bufferPerClient, client, bytes, size);
        }

        final var command = onMessage(bufferPerClient, client, bytes, -1);
        if (command.isPresent()) {
            bufferPerClient.remove(client);
            final var cmd = command.orElseThrow();
            return switch (cmd.name()) {
                case "await" -> {
                    client.write(ByteBuffer.wrap(OK));
                    yield true;
                }
                case "exec" -> {
                    doExec(pool, cmd.args(), processes, throttler, clock, end);
                    client.write(ByteBuffer.wrap(OK));
                    yield false;
                }
                default -> {
                    onUnknownCommand(cmd);
                    client.write(ByteBuffer.wrap(OK));
                    yield false;
                }
            };
        }
        return false;
    }

    private void onAccept(final ServerSocketChannel socket, final Selector selector) throws IOException {
        final var client = socket.accept();
        client.configureBlocking(false);
        client.register(selector, SelectionKey.OP_READ);
    }

    protected void onUnknownCommand(final Command cmd) {
        System.err.println("[GALLOP][ERROR] Unknown command " + cmd);
    }

    protected void onStart(final Path path) {
        System.out.println("[GALLOP] Using address '" + path + "'");
    }

    protected Optional<Command> onMessage(final Map<SocketChannel, ClientBuffer> bufferPerClient, final SocketChannel client,
                                          final ByteBuffer bytes, final int size) {
        if (size >= 0) {
            bufferPerClient.computeIfAbsent(client, l -> new ClientBuffer()).append(bytes, size);
            return empty();
        }

        synchronized (bufferPerClient) {
            final var clientBuffer = bufferPerClient.get(client);
            if (clientBuffer == null) {
                return empty();
            }

            final var json = clientBuffer.asString();
            return of(Command.fromJson(json));
        }
    }

    private void doExec(final ThreadPoolExecutor pool, final List<String> args,
                        final BiConsumer<Process, Exception> processes,
                        final Semaphore throttler,
                        final Clock clock, final Instant end) {
        if (DEBUG) {
            System.out.println("[GALLOP][DEBUG] Exec: " + args);
        }
        pool.submit(() -> {
            try {
                final var timeout = end.minusMillis(clock.instant().toEpochMilli()).toEpochMilli();
                if (timeout < 0) {
                    final var error = "Timeout for " + args;
                    System.err.println("[GALLOP][ERROR] " + error);
                    throw new IllegalStateException(error);
                }
                if (!throttler.tryAcquire(timeout, MILLISECONDS)) {
                    final var error = "Can't acquire a permission for " + args;
                    System.err.println("[GALLOP][ERROR] " + error);
                    throw new IllegalStateException(error);
                }
            } catch (final InterruptedException e) {
                System.err.println("[GALLOP][ERROR] Interrupted, will not execute " + args);
                throw new IllegalStateException(e);
            }
            if (DEBUG) {
                System.out.println("[GALLOP][DEBUG] Starting " + args);
            }
            try {
                final var processBuilder = new ProcessBuilder(args).inheritIO();
                processBuilder.environment().put("GALLOP", "true");
                final var process = processBuilder.start();
                processes.accept(process, null);
                process.onExit().whenComplete((ok, ko) -> {
                    throttler.release();
                    if (DEBUG) {
                        System.out.println("[GALLOP][DEBUG] Finished " + args +
                                ", exitCode=" + (ok == null ? ko.getMessage() : ok.exitValue()) +
                                ". Remaining tasks: " + pool.getQueue().size());
                    }
                });
            } catch (final Exception e) {
                System.err.println("[GALLOP][ERROR] Can't execute " + args + ": " + e.getMessage());
                processes.accept(null, e);
                throttler.release();
                if (DEBUG) {
                    System.out.println("[GALLOP][DEBUG] Failed " + args + ": " + e.getMessage());
                }
                throw new IllegalStateException(e);
            }
        });
    }

    private void doAwait(final ThreadPoolExecutor pool, final long timeout,
                         final Clock clock, final Instant end,
                         final Semaphore throttler, final int concurrency) {
        System.out.println("[GALLOP] Awaiting, max timeout=" + timeout + "ms");

        pool.shutdown();
        if (DEBUG) {
            System.out.println("[GALLOP][DEBUG] Pool shut down");
        }

        try {
            if (DEBUG) {
                System.out.println("[GALLOP][DEBUG] Awaiting pool " + timeout + "ms");
            }
            if (!pool.awaitTermination(Math.max(1, timeout), MILLISECONDS)) {
                System.err.println("[GALLOP][ERROR] tried awaiting " + timeout + "ms but pool didn't reach the end of execution, killing it");
                pool.shutdownNow();
            } else if (DEBUG) {
                System.out.println("[GALLOP][DEBUG] pool awaited");
            }

            while (!pool.getQueue().isEmpty() && clock.instant().isBefore(end)) {
                if (DEBUG) {
                    System.out.println("[GALLOP][DEBUG] Awaiting thread pool queue size is empty");
                }
                Thread.sleep(500);
            }

            if (DEBUG) {
                System.out.println("[GALLOP][DEBUG] Thread pool queue size is empty");
            }
            if (!throttler.tryAcquire(
                    concurrency,
                    Math.max(1, end.minusMillis(clock.instant().toEpochMilli()).toEpochMilli()), MILLISECONDS)) {
                final var error = "Can't acquire enough slots to exit properly in the remaining time, giving up";
                System.err.println("[GALLOP][DEBUG] " + error);
                throw new IllegalStateException(error);
            }
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void stop(final ExecutorService pool, final ServerSocketChannel socket, final Selector selector, final Thread hook) {
        if (DEBUG) {
            System.out.println("[GALLOP][DEBUG] stopping");
        }
        try {
            if (!pool.isShutdown()) {
                pool.shutdown();
            }
            if (!pool.isTerminated()) { // try to give some time before giving up
                try {
                    pool.awaitTermination(1, MINUTES);
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        } finally {
            try {
                if (socket.isOpen()) {
                    try {
                        socket.close();
                    } catch (final IOException e) {
                        System.err.println("[GALLOP][ERROR] Error closing the server socket: " + e.getMessage());
                    }
                }
                if (selector.isOpen()) {
                    try {
                        selector.close();
                    } catch (final IOException e) {
                        System.err.println("[GALLOP][ERROR] Error closing the selector: " + e.getMessage());
                    }
                }
            } finally {
                try {
                    hook.run();
                    Runtime.getRuntime().removeShutdownHook(hook);
                } catch (final IllegalStateException itse) {
                    // no-op: already doing it
                }
            }
        }
    }

    protected static class ClientBuffer {
        private final List<ByteBuffer> buffers = new ArrayList<>();

        protected void append(final ByteBuffer buffer, final int size) {
            if (size <= 0) {
                return;
            }
            synchronized (this) {
                buffers.add(buffer.slice(0, size));
            }
        }

        protected String asString() {
            final var res = new byte[buffers.stream().mapToInt(ByteBuffer::remaining).sum()];
            int start = 0;
            for (final var array : buffers) {
                final int remaining = array.remaining();
                array.get(res, start, remaining);
                start += remaining;
            }
            return new String(res, UTF_8);
        }
    }
}
