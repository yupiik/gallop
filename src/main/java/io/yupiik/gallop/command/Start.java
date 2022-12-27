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
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.MINUTES;

// todo: add error handling
public class Start extends BaseSocket {
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
        final var poolSize = Math.max(1, concurrency);
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
            while (!failed.get()) {
                if (clock.instant().isAfter(end)) {
                    System.err.println("[GALLOP] Timeout occurred for id=" + args.get(0) + ", port=" + pid + ", quitting");
                    if (!pool.isShutdown()) { // more brutal stop
                        pool.shutdownNow();
                    }
                    stop(pool, socket, selector, hook);
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
                                key, pool, bufferPerClient, clock, end, throttler,
                                (process, ex) -> onExecProcess(checkExitCode, processes, failed, process, ex),
                                () -> awaitProcesses(processes, clock, end))) {
                            return;
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
        } finally {
            if (!processes.isEmpty()) {
                killAll(processes);
            }
            stop(pool, socket, selector, hook);
        }
    }

    private static void awaitProcesses(final List<Process> processes, final Clock clock, final Instant end) {
        synchronized (processes) {
            for (final var process : processes) {
                final var max = end.minusMillis(clock.instant().toEpochMilli()).toEpochMilli();
                if (max <= 0) {
                    return; // give up
                }
                try {
                    process.waitFor(max, MILLISECONDS);
                } catch (final InterruptedException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
    }

    private static void onExecProcess(final boolean checkExitCode, final ArrayList<Process> processes, final AtomicBoolean failed, final Process process, final Exception ex) {
        if (ex != null) {
            failed.set(true);
            return;
        }

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

    private boolean onRead(final SelectionKey key, final ExecutorService pool,
                           final Map<SocketChannel, ClientBuffer> bufferPerClient,
                           final Clock clock, final Instant end,
                           final Semaphore throttler,
                           final BiConsumer<Process, Exception> processes,
                           final Runnable awaiter) throws IOException {
        final var client = (SocketChannel) key.channel();

        final var bytes = ByteBuffer.allocate(1024);
        final int size = client.read(bytes);

        final var command = onMessage(bufferPerClient, client, bytes, size);
        if (command.isPresent()) {
            bufferPerClient.remove(client);
            final var cmd = command.orElseThrow();
            return switch (cmd.name()) {
                case "await" -> {
                    doAwait(pool, Math.max(0, end.minusMillis(clock.instant().toEpochMilli()).toEpochMilli()), awaiter);
                    yield true;
                }
                case "exec" -> {
                    doExec(pool, cmd.args(), processes, throttler);
                    yield false;
                }
                default -> {
                    onUnknownCommand(cmd);
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

    private void doExec(final ExecutorService pool, final List<String> args, final BiConsumer<Process, Exception> processes,
                        final Semaphore throttler) {
        // System.out.println("[GALLOP] Exec: " + args);
        pool.submit(() -> {
            try {
                throttler.acquire();
            } catch (final InterruptedException e) {
                System.err.println("[GALLOP][ERROR] Interrupted, will not execute " + args);
                throw new IllegalStateException(e);
            }
            try {
                final var processBuilder = new ProcessBuilder(args).inheritIO();
                processBuilder.environment().put("GALLOP", "true");
                final var process = processBuilder.start();
                process.onExit().whenComplete((ok, ko) -> throttler.release());
                processes.accept(process, null);
            } catch (final Exception e) {
                System.err.println("[GALLOP][ERROR] Can't execute " + args + ": " + e.getMessage());
                processes.accept(null, e);
                throttler.release();
                throw new IllegalStateException(e);
            }
        });
    }

    private void doAwait(final ExecutorService pool, final long timeout, final Runnable awaiter) {
        System.out.println("[GALLOP] Await: " + timeout);
        pool.shutdown();
        if (timeout > 0) {
            try {
                if (!pool.awaitTermination(timeout, MILLISECONDS)) {
                    System.err.println("[GALLOP][ERROR] tried awaiting " + timeout + "ms but pool didn't reach the end of execution, killing it");
                    pool.shutdownNow();
                } else {
                    awaiter.run();
                }
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } else {
            System.err.println("[GALLOP][ERROR] No timeout to await");
            if (!pool.isTerminated()) {
                pool.shutdownNow();
            }
        }
    }

    private void stop(final ExecutorService pool, final ServerSocketChannel socket, final Selector selector, final Thread hook) {
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
