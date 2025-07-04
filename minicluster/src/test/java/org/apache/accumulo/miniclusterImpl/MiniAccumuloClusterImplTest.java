/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.accumulo.miniclusterImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.Scanner;
import org.apache.accumulo.core.client.admin.TableOperations;
import org.apache.accumulo.core.client.security.tokens.PasswordToken;
import org.apache.accumulo.core.manager.thrift.ManagerGoalState;
import org.apache.accumulo.core.manager.thrift.ManagerMonitorInfo;
import org.apache.accumulo.core.manager.thrift.ManagerState;
import org.apache.accumulo.core.metadata.SystemTables;
import org.apache.accumulo.core.security.Authorizations;
import org.apache.accumulo.minicluster.ServerType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import com.google.common.collect.Iterators;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

@SuppressFBWarnings(value = "PATH_TRAVERSAL_IN", justification = "paths not set by user input")
public class MiniAccumuloClusterImplTest {

  @TempDir
  private static Path tempDir;

  private static MiniAccumuloClusterImpl accumulo;

  private static final int NUM_TSERVERS = 2;

  private static String TEST_TABLE = "test";
  private static String testTableID;

  @BeforeAll
  public static void setupMiniCluster() throws Exception {
    MiniAccumuloConfigImpl config =
        new MiniAccumuloConfigImpl(tempDir.toFile(), "superSecret").setJDWPEnabled(true);
    // expressly set number of tservers since we assert it later, in case the default changes
    config.getClusterServerConfiguration().setNumDefaultTabletServers(NUM_TSERVERS);
    accumulo = new MiniAccumuloClusterImpl(config);
    accumulo.start();
    // create a table to ensure there are some entries in the !0 table
    AccumuloClient client = accumulo.createAccumuloClient("root", new PasswordToken("superSecret"));
    TableOperations tableops = client.tableOperations();
    tableops.create(TEST_TABLE);
    testTableID = tableops.tableIdMap().get(TEST_TABLE);

    Scanner s = client.createScanner(TEST_TABLE, Authorizations.EMPTY);
    Iterators.size(s.iterator());
  }

  @Test
  @Timeout(10)
  public void testAccurateProcessListReturned() throws Exception {
    Map<ServerType,Collection<ProcessReference>> procs = accumulo.getProcesses();

    assertTrue(procs.containsKey(ServerType.GARBAGE_COLLECTOR));

    for (ServerType t : new ServerType[] {ServerType.MANAGER, ServerType.TABLET_SERVER,
        ServerType.ZOOKEEPER}) {
      assertTrue(procs.containsKey(t));
      Collection<ProcessReference> procRefs = procs.get(t);
      assertTrue(1 <= procRefs.size());

      for (ProcessReference procRef : procRefs) {
        assertNotNull(procRef);
      }
    }
  }

  @Test
  @Timeout(60)
  public void saneMonitorInfo() throws Exception {
    ManagerMonitorInfo stats;
    // Expecting default AccumuloTables + TEST_TABLE
    int expectedNumTables = SystemTables.values().length + 1;
    while (true) {
      stats = accumulo.getManagerMonitorInfo();
      if (stats.tableMap.size() < expectedNumTables) {
        continue;
      }

      if (null != stats.tServerInfo && stats.tServerInfo.size() == NUM_TSERVERS) {
        break;
      }
    }
    List<ManagerState> validStates = Arrays.asList(ManagerState.values());
    List<ManagerGoalState> validGoals = Arrays.asList(ManagerGoalState.values());
    assertTrue(validStates.contains(stats.state), "manager state should be valid.");
    assertTrue(validGoals.contains(stats.goalState),
        "manager goal state should be in " + validGoals + ". is " + stats.goalState);
    assertNotNull(stats.tableMap, "should have a table map.");
    assertTrue(stats.tableMap.containsKey(SystemTables.ROOT.tableId().canonical()),
        "root table should exist in " + stats.tableMap.keySet());
    assertTrue(stats.tableMap.containsKey(SystemTables.METADATA.tableId().canonical()),
        "meta table should exist in " + stats.tableMap.keySet());
    assertTrue(stats.tableMap.containsKey(SystemTables.FATE.tableId().canonical()),
        "fate table should exist in " + stats.tableMap.keySet());
    assertTrue(stats.tableMap.containsKey(SystemTables.SCAN_REF.tableId().canonical()),
        "scan ref table should exist in " + stats.tableMap.keySet());
    assertTrue(stats.tableMap.containsKey(testTableID),
        "our test table should exist in " + stats.tableMap.keySet());
    assertNotNull(stats.tServerInfo, "there should be tservers.");
    assertEquals(NUM_TSERVERS, stats.tServerInfo.size());
  }

  @AfterAll
  public static void tearDownMiniCluster() throws Exception {
    accumulo.stop();
  }

}
