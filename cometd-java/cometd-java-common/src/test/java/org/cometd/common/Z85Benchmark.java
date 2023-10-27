/*
 * Copyright (c) 2008-2022 the original author or authors.
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
package org.cometd.common;

import java.util.Random;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.TimeValue;

@State(Scope.Benchmark)
public class Z85Benchmark {
    @Param({"1024", "8192", "65536"})
    public String length;
    private byte[] bytes;
    private String text;

    @Setup
    public void setup() {
        bytes = new byte[Integer.parseInt(length)];
        new Random().nextBytes(bytes);
        text = Z85.encoder.encodeBytes(bytes);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public String testZ85Encode() {
        return Z85.encoder.encodeBytes(bytes);
    }

    @Benchmark
    @BenchmarkMode({Mode.Throughput})
    public byte[] testZ85Decode() {
        return Z85.decoder.decodeBytes(text);
    }

    public static void main(String[] args) throws Exception {
        Options opt = new OptionsBuilder()
                .include(Z85Benchmark.class.getSimpleName())
                .warmupIterations(2)
                .warmupTime(TimeValue.seconds(5))
                .measurementIterations(3)
                .measurementTime(TimeValue.seconds(5))
                .forks(1)
                .threads(1)
                .build();
        new Runner(opt).run();
    }
}
