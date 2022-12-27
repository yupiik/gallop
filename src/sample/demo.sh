#! /bin/bash

# in dev we override this path - just for dev purposes, normally binary is in the PATH directly
GALLOP_BINARY="${GALLOP_BINARY:-/opt/$USER/dev/gallop/target/gallop}"

# optional, set the retry timeout for the clients (exec/await) since start is done async
GALLOP_CLIENT_RETRY_TIMEOUT=120

# 1. start the server - not setting "false" at the end forces gallop to check all processes return 0
#    Here we use a thread pool of 4 threads (actually a throttler of 4 more than threads) and the script pid as identifier.
$GALLOP_BINARY start $$ 4 60 &

# 2. submit a few processes in the pool of #4 threads
$GALLOP_BINARY exec $$ echo process 1
$GALLOP_BINARY exec $$ echo process 2
$GALLOP_BINARY exec $$ echo process 3
$GALLOP_BINARY exec $$ echo process 4
$GALLOP_BINARY exec $$ echo process 5
$GALLOP_BINARY exec $$ echo process 6

# await all executions
$GALLOP_BINARY await $$
