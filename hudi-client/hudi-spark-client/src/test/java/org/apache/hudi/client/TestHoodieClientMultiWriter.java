/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.client;

import org.apache.hadoop.fs.Path;
import org.apache.hudi.client.transaction.FileSystemBasedLockProviderTestClass;
import org.apache.hudi.common.config.LockConfiguration;
import org.apache.hudi.common.model.HoodieFailedWritesCleaningPolicy;
import org.apache.hudi.common.model.HoodieFileFormat;
import org.apache.hudi.common.model.HoodieRecord;
import org.apache.hudi.common.model.HoodieTableType;
import org.apache.hudi.common.model.TableServiceType;
import org.apache.hudi.common.model.WriteConcurrencyMode;
import org.apache.hudi.common.table.marker.MarkerType;
import org.apache.hudi.common.table.timeline.HoodieInstant;
import org.apache.hudi.common.table.view.FileSystemViewStorageConfig;
import org.apache.hudi.common.table.view.FileSystemViewStorageType;
import org.apache.hudi.common.testutils.HoodieTestUtils;
import org.apache.hudi.common.util.Option;
import org.apache.hudi.config.HoodieClusteringConfig;
import org.apache.hudi.config.HoodieCompactionConfig;
import org.apache.hudi.config.HoodieLockConfig;
import org.apache.hudi.config.HoodieWriteConfig;
import org.apache.hudi.exception.HoodieWriteConflictException;
import org.apache.hudi.testutils.HoodieClientTestBase;
import org.apache.spark.api.java.JavaRDD;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import static org.apache.hudi.common.config.LockConfiguration.FILESYSTEM_LOCK_PATH_PROP_KEY;
import static org.apache.hudi.testutils.Assertions.assertNoWriteErrors;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

public class TestHoodieClientMultiWriter extends HoodieClientTestBase {

  public void setUpMORTestTable() throws IOException {
    cleanupResources();
    initPath();
    initSparkContexts();
    initTestDataGenerator();
    initFileSystem();
    fs.mkdirs(new Path(basePath));
    metaClient = HoodieTestUtils.init(hadoopConf, basePath, HoodieTableType.MERGE_ON_READ, HoodieFileFormat.PARQUET);
    initTestDataGenerator();
  }

  @AfterEach
  public void clean() throws IOException {
    cleanupResources();
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class, names = {"COPY_ON_WRITE", "MERGE_ON_READ"})
  public void testHoodieClientBasicMultiWriter(HoodieTableType tableType) throws Exception {
    if (tableType == HoodieTableType.MERGE_ON_READ) {
      setUpMORTestTable();
    }
    Properties properties = new Properties();
    properties.setProperty(FILESYSTEM_LOCK_PATH_PROP_KEY, basePath + "/.hoodie/.locks");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_NUM_RETRIES_PROP_KEY, "10");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_RETRY_WAIT_TIME_IN_MILLIS_PROP_KEY, "250");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_RETRY_WAIT_TIME_IN_MILLIS_PROP_KEY,"250");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_NUM_RETRIES_PROP_KEY,"10");
    HoodieWriteConfig writeConfig = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withFailedWritesCleaningPolicy(HoodieFailedWritesCleaningPolicy.LAZY).withAutoClean(false).build())
        .withWriteConcurrencyMode(WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL)
        // Timeline-server-based markers are not used for multi-writer tests
        .withMarkersType(MarkerType.DIRECT.name())
        .withLockConfig(HoodieLockConfig.newBuilder().withLockProvider(FileSystemBasedLockProviderTestClass.class)
            .build()).withAutoCommit(false).withProperties(properties).build();

    // Create the first commit
    createCommitWithInserts(writeConfig, getHoodieWriteClient(writeConfig), "000", "001", 200);

    final int threadCount = 2;
    final ExecutorService executors = Executors.newFixedThreadPool(2);
    final SparkRDDWriteClient client1 = getHoodieWriteClient(writeConfig);
    final SparkRDDWriteClient client2 = getHoodieWriteClient(writeConfig);

    final CyclicBarrier cyclicBarrier = new CyclicBarrier(threadCount);
    final AtomicBoolean writer1Completed = new AtomicBoolean(false);
    final AtomicBoolean writer2Completed = new AtomicBoolean(false);

    Future future1 = executors.submit(() -> {
      try {
        final String nextCommitTime = "002";
        final JavaRDD<WriteStatus> writeStatusList = startCommitForUpdate(writeConfig, client1, nextCommitTime, 100);

        // Wait for the 2nd writer to start the commit
        cyclicBarrier.await(60, TimeUnit.SECONDS);

        // Commit the update before the 2nd writer
        assertDoesNotThrow(() -> {
          client1.commit(nextCommitTime, writeStatusList);
        });

        // Signal the 2nd writer to go ahead for his commit
        cyclicBarrier.await(60, TimeUnit.SECONDS);
        writer1Completed.set(true);
      } catch (Exception e) {
        writer1Completed.set(false);
      }
    });

    Future future2 = executors.submit(() -> {
      try {
        final String nextCommitTime = "003";

        // Wait for the 1st writer to make progress with the commit
        cyclicBarrier.await(60, TimeUnit.SECONDS);
        final JavaRDD<WriteStatus> writeStatusList = startCommitForUpdate(writeConfig, client2, nextCommitTime, 100);

        // Wait for the 1st writer to complete the commit
        cyclicBarrier.await(60, TimeUnit.SECONDS);
        assertThrows(HoodieWriteConflictException.class, () -> {
          client2.commit(nextCommitTime, writeStatusList);
        });
        writer2Completed.set(true);
      } catch (Exception e) {
        writer2Completed.set(false);
      }
    });

    future1.get();
    future2.get();

    // both should have been completed successfully. I mean, we already assert for conflict for writer2 at L155.
    assertTrue(writer1Completed.get() && writer2Completed.get());
  }

  @Test
  public void testMultiWriterWithAsyncTableServicesWithConflictCOW() throws Exception {
    testMultiWriterWithAsyncTableServicesWithConflict(HoodieTableType.COPY_ON_WRITE);
  }

  @Test
  public void testMultiWriterWithAsyncTableServicesWithConflictMOR() throws Exception {
    testMultiWriterWithAsyncTableServicesWithConflict(HoodieTableType.MERGE_ON_READ);
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class, names = {"COPY_ON_WRITE", "MERGE_ON_READ"})
  public void testMultiWriterWithInsertsToDistinctPartitions(HoodieTableType tableType) throws Exception {
    if (tableType == HoodieTableType.MERGE_ON_READ) {
      setUpMORTestTable();
    }

    Properties properties = new Properties();
    properties.setProperty(FILESYSTEM_LOCK_PATH_PROP_KEY, basePath + "/.hoodie/.locks");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_NUM_RETRIES_PROP_KEY, "10");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_RETRY_WAIT_TIME_IN_MILLIS_PROP_KEY, "3000");

    HoodieWriteConfig cfg = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder()
            .withInlineCompaction(false)
            .withFailedWritesCleaningPolicy(HoodieFailedWritesCleaningPolicy.LAZY)
            .withMaxNumDeltaCommitsBeforeCompaction(2)
            .build())
        .withWriteConcurrencyMode(WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL)
        .withLockConfig(HoodieLockConfig.newBuilder()
            .withLockProvider(FileSystemBasedLockProviderTestClass.class)
            .build())
        .withAutoCommit(false)
        // Timeline-server-based markers are not used for multi-writer tests
        .withMarkersType(MarkerType.DIRECT.name())
        .withProperties(properties)
        .withFileSystemViewConfig(FileSystemViewStorageConfig.newBuilder().withStorageType(FileSystemViewStorageType.REMOTE_FIRST)
            .withSecondaryStorageType(FileSystemViewStorageType.MEMORY).build())
        .build();

    // Create the first commit
    SparkRDDWriteClient<?> client = getHoodieWriteClient(cfg);
    createCommitWithInsertsForPartition(cfg, client, "000", "001", 100, "2016/03/01");

    int numConcurrentWriters = 5;
    ExecutorService executors = Executors.newFixedThreadPool(numConcurrentWriters);

    List<Future<?>> futures = new ArrayList<>(numConcurrentWriters);
    for (int loop = 0; loop < numConcurrentWriters; loop++) {
      String newCommitTime = "00" + (loop + 2);
      String partition = "2016/03/0" + (loop + 2);
      futures.add(executors.submit(() -> {
        try {
          SparkRDDWriteClient<?> writeClient = getHoodieWriteClient(cfg);
          createCommitWithInsertsForPartition(cfg, writeClient, "001", newCommitTime, 100, partition);
        } catch (Exception e) {
          throw new RuntimeException(e);
        }
      }));
    }

    futures.forEach(f -> {
      try {
        f.get();
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });
  }

  /**
   * Count down the latch and await for all the needed threads to join.
   *
   * @param latch          - Count down latch
   * @param waitTimeMillis - Max wait time in millis for waiting
   */
  private void latchCountDownAndWait(CountDownLatch latch, long waitTimeMillis) {
    latch.countDown();
    try {
      latch.await(waitTimeMillis, TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      //
    }
  }

  private void testMultiWriterWithAsyncTableServicesWithConflict(HoodieTableType tableType) throws Exception {
    // create inserts X 1
    if (tableType == HoodieTableType.MERGE_ON_READ) {
      setUpMORTestTable();
    }
    Properties properties = new Properties();
    properties.setProperty(FILESYSTEM_LOCK_PATH_PROP_KEY, basePath + "/.hoodie/.locks");
    properties.setProperty("hoodie.write.lock.provider", "org.apache.hudi.client.transaction.FileSystemBasedLockProviderTestClass");
    properties.setProperty(FILESYSTEM_LOCK_PATH_PROP_KEY, basePath);
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_NUM_RETRIES_PROP_KEY, "3");
    properties.setProperty(LockConfiguration.LOCK_ACQUIRE_CLIENT_RETRY_WAIT_TIME_IN_MILLIS_PROP_KEY, "5000");
    // Disabling embedded timeline server, it doesn't work with multiwriter
    HoodieWriteConfig.Builder writeConfigBuilder = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder().withAutoClean(false)
            .withInlineCompaction(false).withAsyncClean(true)
            .withFailedWritesCleaningPolicy(HoodieFailedWritesCleaningPolicy.LAZY)
            .withMaxNumDeltaCommitsBeforeCompaction(2).build())
        .withEmbeddedTimelineServerEnabled(false)
        // Timeline-server-based markers are not used for multi-writer tests
        .withMarkersType(MarkerType.DIRECT.name())
        .withFileSystemViewConfig(FileSystemViewStorageConfig.newBuilder().withStorageType(
            FileSystemViewStorageType.MEMORY).build())
        .withWriteConcurrencyMode(WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL)
        .withLockConfig(HoodieLockConfig.newBuilder().withLockProvider(FileSystemBasedLockProviderTestClass.class)
            .build()).withAutoCommit(false).withProperties(properties);
    Set<String> validInstants = new HashSet<>();
    // Create the first commit with inserts
    HoodieWriteConfig cfg = writeConfigBuilder.build();
    SparkRDDWriteClient client = getHoodieWriteClient(cfg);
    createCommitWithInserts(cfg, client, "000", "001", 200);
    validInstants.add("001");
    // Create 2 commits with upserts
    createCommitWithUpserts(cfg, client, "001", "000", "002", 100);
    createCommitWithUpserts(cfg, client, "002", "000", "003", 100);
    validInstants.add("002");
    validInstants.add("003");

    // Three clients running actions in parallel
    final int threadCount = 3;
    final CountDownLatch scheduleCountDownLatch = new CountDownLatch(threadCount);
    final ExecutorService executors = Executors.newFixedThreadPool(threadCount);

    // Write config with clustering enabled
    final HoodieWriteConfig cfg2 = writeConfigBuilder
        .withClusteringConfig(HoodieClusteringConfig.newBuilder()
            .withInlineClustering(true)
            .withInlineClusteringNumCommits(1)
            .build())
        .build();
    final SparkRDDWriteClient client1 = getHoodieWriteClient(cfg2);
    final SparkRDDWriteClient client2 = getHoodieWriteClient(cfg);
    final SparkRDDWriteClient client3 = getHoodieWriteClient(cfg);

    // Create upserts, schedule cleaning, schedule compaction in parallel
    Future future1 = executors.submit(() -> {
      final String newCommitTime = "004";
      final int numRecords = 100;
      final String commitTimeBetweenPrevAndNew = "002";

      // We want the upsert to go through only after the compaction
      // and cleaning schedule completion. So, waiting on latch here.
      latchCountDownAndWait(scheduleCountDownLatch, 30000);
      if (tableType == HoodieTableType.MERGE_ON_READ) {
        // Since the compaction already went in, this upsert has
        // to fail
        assertThrows(IllegalArgumentException.class, () -> {
          createCommitWithUpserts(cfg, client1, "003", commitTimeBetweenPrevAndNew, newCommitTime, numRecords);
        });
      } else {
        // We don't have the compaction for COW and so this upsert
        // has to pass
        assertDoesNotThrow(() -> {
          createCommitWithUpserts(cfg, client1, "003", commitTimeBetweenPrevAndNew, newCommitTime, numRecords);
        });
        validInstants.add(newCommitTime);
      }
    });

    Future future2 = executors.submit(() -> {
      if (tableType == HoodieTableType.MERGE_ON_READ) {
        assertDoesNotThrow(() -> {
          client2.scheduleTableService("005", Option.empty(), TableServiceType.COMPACT);
        });
      }
      latchCountDownAndWait(scheduleCountDownLatch, 30000);
    });

    Future future3 = executors.submit(() -> {
      assertDoesNotThrow(() -> {
        latchCountDownAndWait(scheduleCountDownLatch, 30000);
        client3.scheduleTableService("006", Option.empty(), TableServiceType.CLEAN);
      });
    });
    future1.get();
    future2.get();
    future3.get();

    CountDownLatch runCountDownLatch = new CountDownLatch(threadCount);
    // Create inserts, run cleaning, run compaction in parallel
    future1 = executors.submit(() -> {
      final String newCommitTime = "007";
      final int numRecords = 100;
      latchCountDownAndWait(runCountDownLatch, 30000);
      assertDoesNotThrow(() -> {
        createCommitWithInserts(cfg, client1, "003", newCommitTime, numRecords);
        validInstants.add("007");
      });
    });

    future2 = executors.submit(() -> {
      latchCountDownAndWait(runCountDownLatch, 30000);
      if (tableType == HoodieTableType.MERGE_ON_READ) {
        assertDoesNotThrow(() -> {
          JavaRDD<WriteStatus> writeStatusJavaRDD = (JavaRDD<WriteStatus>) client2.compact("005");
          client2.commitCompaction("005", writeStatusJavaRDD, Option.empty());
          validInstants.add("005");
        });
      }
    });

    future3 = executors.submit(() -> {
      latchCountDownAndWait(runCountDownLatch, 30000);
      assertDoesNotThrow(() -> {
        client3.clean("006", false);
        validInstants.add("006");
      });
    });
    future1.get();
    future2.get();
    future3.get();

    validInstants.addAll(
        metaClient.reloadActiveTimeline().getCompletedReplaceTimeline()
            .filterCompletedInstants().getInstants().map(HoodieInstant::getTimestamp).collect(Collectors.toSet()));
    Set<String> completedInstants = metaClient.reloadActiveTimeline().getCommitsTimeline()
        .filterCompletedInstants().getInstants().map(HoodieInstant::getTimestamp)
        .collect(Collectors.toSet());
    assertTrue(validInstants.containsAll(completedInstants));
  }

  @ParameterizedTest
  @EnumSource(value = HoodieTableType.class, names = {"COPY_ON_WRITE", "MERGE_ON_READ"})
  public void testHoodieClientMultiWriterWithClustering(HoodieTableType tableType) throws Exception {
    if (tableType == HoodieTableType.MERGE_ON_READ) {
      setUpMORTestTable();
    }
    Properties properties = new Properties();
    properties.setProperty(FILESYSTEM_LOCK_PATH_PROP_KEY, basePath + "/.hoodie/.locks");
    HoodieWriteConfig.Builder writeConfigBuilder = getConfigBuilder()
        .withCompactionConfig(HoodieCompactionConfig.newBuilder().withFailedWritesCleaningPolicy(HoodieFailedWritesCleaningPolicy.LAZY)
            .withAutoClean(false).build())
        .withWriteConcurrencyMode(WriteConcurrencyMode.OPTIMISTIC_CONCURRENCY_CONTROL)
        // Timeline-server-based markers are not used for multi-writer tests
        .withMarkersType(MarkerType.DIRECT.name())
        .withLockConfig(HoodieLockConfig.newBuilder().withLockProvider(FileSystemBasedLockProviderTestClass.class)
            .build()).withAutoCommit(false).withProperties(properties);
    HoodieWriteConfig cfg = writeConfigBuilder.build();
    HoodieWriteConfig cfg2 = writeConfigBuilder.build();
    HoodieWriteConfig cfg3 = writeConfigBuilder
        .withClusteringConfig(HoodieClusteringConfig.newBuilder().withInlineClustering(true).withInlineClusteringNumCommits(1).build())
        .build();

    // Create the first commit
    createCommitWithInserts(cfg, getHoodieWriteClient(cfg), "000", "001", 200);
    // Start another inflight commit
    String newCommitTime = "003";
    int numRecords = 100;
    SparkRDDWriteClient client1 = getHoodieWriteClient(cfg);
    String commitTimeBetweenPrevAndNew = "002";
    JavaRDD<WriteStatus> result1 = updateBatch(cfg, client1, newCommitTime, "001",
        Option.of(Arrays.asList(commitTimeBetweenPrevAndNew)), "000", numRecords, SparkRDDWriteClient::upsert, false, false,
        numRecords, 200, 2);
    // Start and finish another commit while the previous writer for commit 003 is running
    newCommitTime = "004";
    SparkRDDWriteClient client2 = getHoodieWriteClient(cfg);
    JavaRDD<WriteStatus> result2 = updateBatch(cfg2, client2, newCommitTime, "001",
        Option.of(Arrays.asList(commitTimeBetweenPrevAndNew)), "000", numRecords, SparkRDDWriteClient::upsert, false, false,
        numRecords, 200, 2);
    client2.commit(newCommitTime, result2);
    // Schedule and run clustering while previous writer for commit 003 is running
    SparkRDDWriteClient client3 = getHoodieWriteClient(cfg3);
    // schedule clustering
    Option<String> clusterInstant = client3.scheduleTableService(Option.empty(), TableServiceType.CLUSTER);
    assertTrue(clusterInstant.isPresent());
    // Attempt to commit the inflight commit 003
    try {
      client1.commit("003", result1);
      fail("Should have thrown a concurrent conflict exception");
    } catch (Exception e) {
      // Expected
    }
  }

  private void createCommitWithInsertsForPartition(HoodieWriteConfig cfg, SparkRDDWriteClient client,
                                                   String prevCommitTime, String newCommitTime, int numRecords,
                                                   String partition) throws Exception {
    JavaRDD<WriteStatus> result = insertBatch(cfg, client, newCommitTime, prevCommitTime, numRecords, SparkRDDWriteClient::insert,
        false, false, numRecords, numRecords, 1, Option.of(partition));
    assertTrue(client.commit(newCommitTime, result), "Commit should succeed");
  }

  private void createCommitWithInserts(HoodieWriteConfig cfg, SparkRDDWriteClient client,
                                       String prevCommitTime, String newCommitTime, int numRecords) throws Exception {
    // Finish first base commmit
    JavaRDD<WriteStatus> result = insertFirstBatch(cfg, client, newCommitTime, prevCommitTime, numRecords, SparkRDDWriteClient::bulkInsert,
        false, false, numRecords);
    assertTrue(client.commit(newCommitTime, result), "Commit should succeed");
  }

  private void createCommitWithUpserts(HoodieWriteConfig cfg, SparkRDDWriteClient client, String prevCommit,
                                       String commitTimeBetweenPrevAndNew, String newCommitTime, int numRecords)
      throws Exception {
    JavaRDD<WriteStatus> result = updateBatch(cfg, client, newCommitTime, prevCommit,
        Option.of(Arrays.asList(commitTimeBetweenPrevAndNew)), "000", numRecords, SparkRDDWriteClient::upsert, false, false,
        numRecords, 200, 2);
    client.commit(newCommitTime, result);
  }

  /**
   * Start the commit for an update operation with given number of records
   *
   * @param writeConfig   - Write config
   * @param writeClient   - Write client for starting the commit
   * @param newCommitTime - Commit time for the update
   * @param numRecords    - Number of records to update
   * @return RDD of write status from the update
   * @throws Exception
   */
  private JavaRDD<WriteStatus> startCommitForUpdate(HoodieWriteConfig writeConfig, SparkRDDWriteClient writeClient,
                                                    String newCommitTime, int numRecords) throws Exception {
    // Start the new commit
    writeClient.startCommitWithTime(newCommitTime);

    // Prepare update records
    final Function2<List<HoodieRecord>, String, Integer> recordGenFunction =
        generateWrapRecordsFn(false, writeConfig, dataGen::generateUniqueUpdates);
    final List<HoodieRecord> records = recordGenFunction.apply(newCommitTime, numRecords);
    final JavaRDD<HoodieRecord> writeRecords = jsc.parallelize(records, 1);

    // Write updates
    Function3<JavaRDD<WriteStatus>, SparkRDDWriteClient, JavaRDD<HoodieRecord>, String> writeFn = SparkRDDWriteClient::upsert;
    JavaRDD<WriteStatus> result = writeFn.apply(writeClient, writeRecords, newCommitTime);
    List<WriteStatus> statuses = result.collect();
    assertNoWriteErrors(statuses);
    return result;
  }
}
