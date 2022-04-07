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

package com.ververica.cdc.connectors.mysql.source;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.connector.source.Boundedness;
import org.apache.flink.api.connector.source.Source;
import org.apache.flink.api.connector.source.SourceReader;
import org.apache.flink.api.connector.source.SourceReaderContext;
import org.apache.flink.api.connector.source.SplitEnumerator;
import org.apache.flink.api.connector.source.SplitEnumeratorContext;
import org.apache.flink.api.java.typeutils.ResultTypeQueryable;
import org.apache.flink.connector.base.source.reader.RecordsWithSplitIds;
import org.apache.flink.connector.base.source.reader.synchronization.FutureCompletingBlockingQueue;
import org.apache.flink.core.io.SimpleVersionedSerializer;
import org.apache.flink.util.FlinkRuntimeException;

import com.ververica.cdc.connectors.mysql.MySqlValidator;
import com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlBinlogSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlHybridSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.assigners.MySqlSplitAssigner;
import com.ververica.cdc.connectors.mysql.source.assigners.state.BinlogPendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.assigners.state.HybridPendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.assigners.state.PendingSplitsState;
import com.ververica.cdc.connectors.mysql.source.assigners.state.PendingSplitsStateSerializer;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfig;
import com.ververica.cdc.connectors.mysql.source.config.MySqlSourceConfigFactory;
import com.ververica.cdc.connectors.mysql.source.enumerator.MySqlSourceEnumerator;
import com.ververica.cdc.connectors.mysql.source.metrics.MySqlSourceReaderMetrics;
import com.ververica.cdc.connectors.mysql.source.reader.MySqlRecordEmitter;
import com.ververica.cdc.connectors.mysql.source.reader.MySqlSourceReader;
import com.ververica.cdc.connectors.mysql.source.reader.MySqlSplitReader;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplit;
import com.ververica.cdc.connectors.mysql.source.split.MySqlSplitSerializer;
import com.ververica.cdc.connectors.mysql.table.StartupMode;
import com.ververica.cdc.debezium.DebeziumDeserializationSchema;
import io.debezium.jdbc.JdbcConnection;
import io.debezium.relational.TableId;
import org.apache.kafka.connect.source.SourceRecord;

import java.util.List;
import java.util.function.Supplier;

import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.discoverCapturedTables;
import static com.ververica.cdc.connectors.mysql.debezium.DebeziumUtils.openJdbcConnection;

/**
 * The MySQL CDC Source based on FLIP-27 and Watermark Signal Algorithm which supports parallel
 * reading snapshot of table and then continue to capture data change from binlog.
 *
 * <pre>
 *     1. The source supports parallel capturing table change.
 *     2. The source supports checkpoint in split level when read snapshot data.
 *     3. The source doesn't need apply any lock of MySQL.
 * </pre>
 *
 * <pre>{@code
 * MySqlSource
 *     .<String>builder()
 *     .hostname("localhost")
 *     .port(3306)
 *     .databaseList("mydb")
 *     .tableList("mydb.users")
 *     .username(username)
 *     .password(password)
 *     .serverId(5400)
 *     .deserializer(new JsonDebeziumDeserializationSchema())
 *     .build();
 * }</pre>
 *
 * <p>See {@link MySqlSourceBuilder} for more details.
 *
 * @param <T> the output type of the source.
 */
@Internal
public class MySqlSource<T>
        implements Source<T, MySqlSplit, PendingSplitsState>, ResultTypeQueryable<T> {

    private static final long serialVersionUID = 1L;

    private final MySqlSourceConfigFactory configFactory;
    private final DebeziumDeserializationSchema<T> deserializationSchema;

    /**
     * Get a MySqlParallelSourceBuilder to build a {@link MySqlSource}.
     *
     * @return a MySql parallel source builder.
     */
    @PublicEvolving
    public static <T> MySqlSourceBuilder<T> builder() {
        return new MySqlSourceBuilder<>();
    }

    MySqlSource(
            MySqlSourceConfigFactory configFactory,
            DebeziumDeserializationSchema<T> deserializationSchema) {
        this.configFactory = configFactory;
        this.deserializationSchema = deserializationSchema;
    }

    public MySqlSourceConfigFactory getConfigFactory() {
        return configFactory;
    }

    @Override
    public Boundedness getBoundedness() {
        return Boundedness.CONTINUOUS_UNBOUNDED;
    }

    @Override
    public SourceReader<T, MySqlSplit> createReader(SourceReaderContext readerContext)
            throws Exception {
        // create source config for the given subtask (e.g. unique server id)
        MySqlSourceConfig sourceConfig =
                configFactory.createConfig(readerContext.getIndexOfSubtask());
        FutureCompletingBlockingQueue<RecordsWithSplitIds<SourceRecord>> elementsQueue =
                new FutureCompletingBlockingQueue<>();//数据存储队列
        final MySqlSourceReaderMetrics sourceReaderMetrics =
                new MySqlSourceReaderMetrics(readerContext.metricGroup());
        sourceReaderMetrics.registerMetrics();
        Supplier<MySqlSplitReader> splitReaderSupplier =//工厂类
                () -> new MySqlSplitReader(sourceConfig, readerContext.getIndexOfSubtask());
        return new MySqlSourceReader<>(//MySqlSourceReader 启动后会向 MySqlSourceEnumerator 发送请求分片事件，从而收集分配的切片数据
                elementsQueue,//MySqlSourceReader 通过 SingleThreadFetcherManager 创建Fetcher拉取分片数据，数据以 MySqlRecords 格式写入到 elementsQueue
                splitReaderSupplier,
                new MySqlRecordEmitter<>(
                        deserializationSchema,//SourceOperator 初始化完毕后，调用 emitNext 由 SourceReaderBase 从 elementsQueue 获取数据集合并下发给 MySqlRecordEmitter
                        sourceReaderMetrics,
                        sourceConfig.isIncludeSchemaChanges()),
                readerContext.getConfiguration(),
                readerContext,
                sourceConfig);
    }

    @Override
    public SplitEnumerator<MySqlSplit, PendingSplitsState> createEnumerator( //在启动时调用
            SplitEnumeratorContext<MySqlSplit> enumContext) {
        MySqlSourceConfig sourceConfig = configFactory.createConfig(0);

        final MySqlValidator validator = new MySqlValidator(sourceConfig);
        validator.validate();//检查mysql版本,配置

        final MySqlSplitAssigner splitAssigner;
        if (sourceConfig.getStartupOptions().startupMode == StartupMode.INITIAL) {
            try (JdbcConnection jdbc = openJdbcConnection(sourceConfig)) {
                final List<TableId> remainingTables = discoverCapturedTables(jdbc, sourceConfig);
                boolean isTableIdCaseSensitive = DebeziumUtils.isTableIdCaseSensitive(jdbc);
                splitAssigner =
                        new MySqlHybridSplitAssigner(//对全量+增量数据进行切片
                                sourceConfig,
                                enumContext.currentParallelism(),
                                remainingTables,
                                isTableIdCaseSensitive);
            } catch (Exception e) {
                throw new FlinkRuntimeException(
                        "Failed to discover captured tables for enumerator", e);
            }
        } else {
            splitAssigner = new MySqlBinlogSplitAssigner(sourceConfig);
        }

        return new MySqlSourceEnumerator(enumContext, sourceConfig, splitAssigner);
    }

    @Override
    public SplitEnumerator<MySqlSplit, PendingSplitsState> restoreEnumerator(
            SplitEnumeratorContext<MySqlSplit> enumContext, PendingSplitsState checkpoint) {
        MySqlSourceConfig sourceConfig = configFactory.createConfig(0);

        final MySqlSplitAssigner splitAssigner;
        if (checkpoint instanceof HybridPendingSplitsState) {
            splitAssigner =
                    new MySqlHybridSplitAssigner(
                            sourceConfig,
                            enumContext.currentParallelism(),
                            (HybridPendingSplitsState) checkpoint);
        } else if (checkpoint instanceof BinlogPendingSplitsState) {
            splitAssigner =
                    new MySqlBinlogSplitAssigner(
                            sourceConfig, (BinlogPendingSplitsState) checkpoint);
        } else {
            throw new UnsupportedOperationException(
                    "Unsupported restored PendingSplitsState: " + checkpoint);
        }
        return new MySqlSourceEnumerator(enumContext, sourceConfig, splitAssigner);
    }

    @Override
    public SimpleVersionedSerializer<MySqlSplit> getSplitSerializer() {
        return MySqlSplitSerializer.INSTANCE;
    }

    @Override
    public SimpleVersionedSerializer<PendingSplitsState> getEnumeratorCheckpointSerializer() {
        return new PendingSplitsStateSerializer(getSplitSerializer());
    }

    @Override
    public TypeInformation<T> getProducedType() {
        return deserializationSchema.getProducedType();
    }
}
