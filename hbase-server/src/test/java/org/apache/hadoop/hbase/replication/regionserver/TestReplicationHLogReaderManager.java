/**
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

package org.apache.hadoop.hbase.replication.regionserver;


import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.KeyValue;
import org.apache.hadoop.hbase.MediumTests;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.regionserver.wal.HLog;
import org.apache.hadoop.hbase.regionserver.wal.HLogFactory;
import org.apache.hadoop.hbase.regionserver.wal.HLogKey;
import org.apache.hadoop.hbase.regionserver.wal.WALActionsListener;
import org.apache.hadoop.hbase.regionserver.wal.WALEdit;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.hdfs.MiniDFSCluster;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.experimental.categories.Category;

import static org.junit.Assert.*;

import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Category(MediumTests.class)
public class TestReplicationHLogReaderManager {

  private final static HBaseTestingUtility TEST_UTIL = new HBaseTestingUtility();
  private static Configuration conf;
  private static Path hbaseDir;
  private static FileSystem fs;
  private static MiniDFSCluster cluster;
  private static final TableName tableName = TableName.valueOf("tablename");
  private static final byte [] family = Bytes.toBytes("column");
  private static final byte [] qualifier = Bytes.toBytes("qualifier");
  private static final HRegionInfo info = new HRegionInfo(tableName,
      HConstants.EMPTY_START_ROW, HConstants.LAST_ROW, false);
  private static final HTableDescriptor htd = new HTableDescriptor(tableName);

  private HLog log;
  private ReplicationHLogReaderManager logManager;
  private PathWatcher pathWatcher;


  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    TEST_UTIL.startMiniDFSCluster(3);

    conf = TEST_UTIL.getConfiguration();
    hbaseDir = TEST_UTIL.createRootDir();
    cluster = TEST_UTIL.getDFSCluster();
    fs = cluster.getFileSystem();
  }

  @AfterClass
  public static void tearDownAfterClass() throws Exception {
    TEST_UTIL.shutdownMiniCluster();
  }

  @Before
  public void setUp() throws Exception {
    logManager = new ReplicationHLogReaderManager(fs, conf);
    List<WALActionsListener> listeners = new ArrayList<WALActionsListener>();
    pathWatcher = new PathWatcher();
    listeners.add(pathWatcher);
    log = HLogFactory.createHLog(fs, hbaseDir, "test", conf, listeners, "some server");
  }

  @After
  public void tearDown() throws Exception {
    log.closeAndDelete();
  }

  @Test
  public void test() throws Exception {
    // Grab the path that was generated when the log rolled as part of its creation
    Path path = pathWatcher.currentPath;

    // open it, it's empty so it fails
    try {
      logManager.openReader(path);
      fail("Shouldn't be able to open an empty file");
    } catch (EOFException ex) {}

    assertEquals(0, logManager.getPosition());

    appendToLog();

    // There's one edit in the log, read it. Reading past it needs to return nulls
    assertNotNull(logManager.openReader(path));
    logManager.seek();
    HLog.Entry[] entriesArray = new HLog.Entry[1];
    HLog.Entry entry = logManager.readNextAndSetPosition(entriesArray, 0);
    assertNotNull(entry);
    entry = logManager.readNextAndSetPosition(entriesArray, 0);
    assertNull(entry);
    logManager.closeReader();
    long oldPos = logManager.getPosition();

    appendToLog();

    // Read the newly added entry, make sure we made progress
    assertNotNull(logManager.openReader(path));
    logManager.seek();
    entry = logManager.readNextAndSetPosition(entriesArray, 0);
    assertNotEquals(oldPos, logManager.getPosition());
    assertNotNull(entry);
    logManager.closeReader();
    oldPos = logManager.getPosition();

    log.rollWriter();

    // We rolled but we still should see the end of the first log and not get data
    assertNotNull(logManager.openReader(path));
    logManager.seek();
    entry = logManager.readNextAndSetPosition(entriesArray, 0);
    assertEquals(oldPos, logManager.getPosition());
    assertNull(entry);
    logManager.finishCurrentFile();

    path = pathWatcher.currentPath;

    // Finally we have a new empty log, which should still give us EOFs
    try {
      logManager.openReader(path);
      fail();
    } catch (EOFException ex) {}

  }

  private void appendToLog() throws IOException {
    log.append(info, tableName, getWALEdit(), System.currentTimeMillis(), htd);
  }

  private WALEdit getWALEdit() {
    WALEdit edit = new WALEdit();
    edit.add(new KeyValue(Bytes.toBytes(System.currentTimeMillis()), family, qualifier,
        System.currentTimeMillis(), qualifier));
    return edit;
  }

  class PathWatcher implements WALActionsListener {

    Path currentPath;

    @Override
    public void preLogRoll(Path oldPath, Path newPath) throws IOException {
      currentPath = newPath;
    }

    @Override
    public void postLogRoll(Path oldPath, Path newPath) throws IOException {}

    @Override
    public void preLogArchive(Path oldPath, Path newPath) throws IOException {}

    @Override
    public void postLogArchive(Path oldPath, Path newPath) throws IOException {}

    @Override
    public void logRollRequested() {}

    @Override
    public void logCloseRequested() {}

    @Override
    public void visitLogEntryBeforeWrite(HRegionInfo info, HLogKey logKey, WALEdit logEdit) {}

    @Override
    public void visitLogEntryBeforeWrite(HTableDescriptor htd, HLogKey logKey, WALEdit logEdit) {}
  }
}
