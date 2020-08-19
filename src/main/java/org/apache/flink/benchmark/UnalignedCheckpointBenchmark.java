/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.benchmark;

import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.configuration.ConfigConstants;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.TaskManagerOptions;
import org.apache.flink.runtime.state.CheckpointListener;
import org.apache.flink.streaming.api.datastream.DataStreamSource;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.sink.SinkFunction;
import org.apache.flink.streaming.api.functions.source.RichParallelSourceFunction;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;

import java.io.IOException;

@OperationsPerInvocation(value = UnalignedCheckpointBenchmark.RECORDS_PER_INVOCATION)
public class UnalignedCheckpointBenchmark extends BenchmarkBase {
    public static final int RECORDS_PER_INVOCATION = 10_000_000;
    private static final int NUM_VERTICES = 3;
    private static final int PARALLELISM = 4;
    private static final long CHECKPOINT_INTERVAL_MS = 100;

    public static void main(String[] args) throws RunnerException {
        Options options = new OptionsBuilder()
            .verbosity(VerboseMode.NORMAL)
            .include(UnalignedCheckpointBenchmark.class.getCanonicalName())
            .build();

        new Runner(options).run();
    }

    @Benchmark
    public void unalignedCheckpointWithRemoteChannel(UCRemoteEnvironmentContext context) throws Exception {
        unalignedCheckpoint(context);
    }

    @Benchmark
    public void unalignedCheckpointWithLocalChannel(UCLocalEnvironmentContext context) throws Exception {
        unalignedCheckpoint(context);
    }

    private void unalignedCheckpoint(FlinkEnvironmentContext context) throws Exception {
        StreamExecutionEnvironment env = context.env;
        DataStreamSource<byte[]> source = env.addSource(new FiniteCheckpointSource(5));
        source
            .slotSharingGroup("source").rebalance()
            .map((MapFunction<byte[], byte[]>) value -> value).slotSharingGroup("map").rebalance()
            .addSink(new SlowDiscardSink<>()).slotSharingGroup("sink");

        env.execute();
    }

    public static class UnalignedCheckpointEnvironmentContext extends FlinkEnvironmentContext {

        @Setup
        public void setUp() throws IOException {
            super.setUp();

            env.setParallelism(parallelism);
            env.enableCheckpointing(CHECKPOINT_INTERVAL_MS);
            env.getCheckpointConfig().enableUnalignedCheckpoints(true);
        }
    }

    public static class UCRemoteEnvironmentContext extends UnalignedCheckpointEnvironmentContext {

        protected Configuration createConfiguration() {
            Configuration conf = super.createConfiguration();
            conf.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, 1);
            conf.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, NUM_VERTICES * PARALLELISM);

            return conf;
        }
    }

    public static class UCLocalEnvironmentContext extends UnalignedCheckpointEnvironmentContext {

        protected Configuration createConfiguration() {
            Configuration conf = super.createConfiguration();
            conf.setInteger(TaskManagerOptions.NUM_TASK_SLOTS, NUM_VERTICES * PARALLELISM);
            conf.setInteger(ConfigConstants.LOCAL_NUMBER_TASK_MANAGER, 1);

            return conf;
        }
    }

    /**
     * The source for finishing the configured number of checkpoints before exiting.
     */
    public static class FiniteCheckpointSource extends RichParallelSourceFunction<byte[]> implements CheckpointListener {

        private final int numExpectedCheckpoints;
        private final byte[] bytes = new byte[1024];

        private volatile boolean running = true;
        private volatile int numFinishedCheckpoints;

        FiniteCheckpointSource(int numCheckpoints) {
            this.numExpectedCheckpoints = numCheckpoints;
        }

        @Override
        public void notifyCheckpointComplete(long checkpointId) {
            ++numFinishedCheckpoints;
        }


        @Override
        public void run(SourceContext<byte[]> ctx) {
            while (running) {
                synchronized (ctx.getCheckpointLock()) {
                    ctx.collect(bytes);

                    if (numFinishedCheckpoints >= numExpectedCheckpoints) {
                        cancel();
                    }
                }
            }
        }

        @Override
        public void cancel() {
            running = false;
        }
    }

    /**
     * The custom sink for processing records slowly to cause accumulate in-flight
     * buffers even back pressure.
     */
    public static class SlowDiscardSink<T> implements SinkFunction<T> {

        @Override
        public void invoke(T value, SinkFunction.Context context) throws Exception {
            Thread.sleep(3);
        }
    }
}