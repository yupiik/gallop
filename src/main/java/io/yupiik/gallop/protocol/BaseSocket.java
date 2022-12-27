package io.yupiik.gallop.protocol;

import java.net.UnixDomainSocketAddress;
import java.nio.file.Path;

public abstract class BaseSocket {
    protected UnixDomainSocketAddress addressOf(final String id) {
        return UnixDomainSocketAddress.of(pathOf(id));
    }

    protected Path pathOf(final String id) {
        return Path.of(System.getProperty("java.io.tmpdir", "/tmp")).resolve("gallop_" + id);
    }
}
