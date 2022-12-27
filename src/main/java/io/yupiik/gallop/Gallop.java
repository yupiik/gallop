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

import io.yupiik.gallop.client.OneCommandClient;
import io.yupiik.gallop.command.Start;
import io.yupiik.gallop.protocol.Command;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

public final class Gallop {
    private Gallop() {
        // no-op
    }

    public static void main(final String... args) throws IOException {
        if (args.length < 1) {
            throw new IllegalArgumentException("Args are required.\n\n" + usage());
        }

        final var commandArgs = Stream.of(args).skip(1).toList();
        switch (args[0]) {
            case "start" -> new Start().exec(commandArgs);
            case "await", "exec" -> doRun(commandArgs, args[0]);
            default -> throw new IllegalArgumentException("Unknown command '" + args[0] + "'\n\n" + usage());
        }
    }

    private static void doRun(final List<String> args, final String cmd) {
        new OneCommandClient(args.get(0)).send(new Command(cmd, args.size() == 1 ? List.of() : args.subList(1, args.size())));
    }

    private static String usage() {
        return """
                gallop <args>

                Available commands:
                                
                - start <id> <concurrency> <timeout in seconds> <check exit code>: starts gallop background process for the enclosing script. It will stop when `gallop await` is called and all tasks are done or when the timeout is reached.
                   * id: identifier of the gallop process, you can use `$$` to get the script pid (recommended)
                   * concurrency: how many tasks can be executed concurrently (matches a number of threads internally)
                   * timeout in seconds: the timeout gallop can stay alive, negative or zero means infinite (not recommended)
                   * check exit code: `true` or `false`, if `true` it will check the exit code of `exec` command is `0`
                   ex: $ gallop start $$ 8 60 true &
                   NOTE: it is important to start gallop with `&` (as a background process) since it will start a socket and keep running tasks.
                - await <id>: synchronization point for all the submitted tasks, will force gallop to not accept any new task until it is started again.
                   * id: identifier of the gallop process, you can use `$$` to get the script pid (recommended)
                   ex: $ gallop await
                   NOTE: it is a blocking operation.
                - exec <id> <command>: force the execution of a command in gallop scheduler
                   * id: gallop scheduler identifier (must match start value)
                   * command: the command
                   ex: $ gallop exec $$ /opt/bin/myexec run "my task"
                """;
    }
}
