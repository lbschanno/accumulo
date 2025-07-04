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
package org.apache.accumulo.cluster.standalone;

import static com.google.common.base.Preconditions.checkArgument;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.apache.accumulo.cluster.AccumuloCluster;
import org.apache.accumulo.cluster.ClusterUser;
import org.apache.accumulo.core.client.Accumulo;
import org.apache.accumulo.core.client.AccumuloClient;
import org.apache.accumulo.core.client.security.tokens.AuthenticationToken;
import org.apache.accumulo.core.clientImpl.ClientInfo;
import org.apache.accumulo.core.conf.AccumuloConfiguration;
import org.apache.accumulo.core.conf.ConfigurationCopy;
import org.apache.accumulo.core.conf.SiteConfiguration;
import org.apache.accumulo.core.manager.thrift.ManagerGoalState;
import org.apache.accumulo.minicluster.ServerType;
import org.apache.accumulo.server.ServerContext;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;

import com.google.common.base.Preconditions;
import com.google.common.base.Suppliers;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * AccumuloCluster implementation to connect to an existing deployment of Accumulo
 */
public class StandaloneAccumuloCluster implements AccumuloCluster {

  static final List<ServerType> ALL_SERVER_TYPES =
      Collections.unmodifiableList(Arrays.asList(ServerType.MANAGER, ServerType.TABLET_SERVER,
          ServerType.GARBAGE_COLLECTOR, ServerType.MONITOR));

  private final ClientInfo info;
  private String accumuloHome;
  private String clientAccumuloConfDir;
  private String serverAccumuloConfDir;
  private String hadoopConfDir;
  private final Path tmp;
  private final List<ClusterUser> users;
  private String clientCmdPrefix;
  private String serverCmdPrefix;
  private final SiteConfiguration siteConfig;
  private final Supplier<ServerContext> contextSupplier;
  private volatile State clusterState = State.STOPPED;
  private final AtomicBoolean serverContextCreated = new AtomicBoolean(false);

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN",
      justification = "code runs in same security context as user who provided input file name")
  public StandaloneAccumuloCluster(ClientInfo info, Path tmp, List<ClusterUser> users,
      String serverAccumuloConfDir) {
    this.info = info;
    this.tmp = tmp;
    this.users = users;
    this.serverAccumuloConfDir = serverAccumuloConfDir;
    siteConfig = SiteConfiguration
        .fromFile(
            java.nio.file.Path.of(serverAccumuloConfDir).resolve("accumulo.properties").toFile())
        .build();
    this.contextSupplier = Suppliers.memoize(() -> ServerContext.withClientInfo(siteConfig, info));
  }

  public String getAccumuloHome() {
    return accumuloHome;
  }

  public void setAccumuloHome(String accumuloHome) {
    this.accumuloHome = accumuloHome;
  }

  public String getClientAccumuloConfDir() {
    return clientAccumuloConfDir;
  }

  public void setClientAccumuloConfDir(String accumuloConfDir) {
    this.clientAccumuloConfDir = accumuloConfDir;
  }

  public String getServerAccumuloConfDir() {
    return serverAccumuloConfDir;
  }

  public void setServerCmdPrefix(String serverCmdPrefix) {
    this.serverCmdPrefix = serverCmdPrefix;
  }

  public void setClientCmdPrefix(String clientCmdPrefix) {
    this.clientCmdPrefix = clientCmdPrefix;
  }

  public String getHadoopConfDir() {
    if (hadoopConfDir == null) {
      hadoopConfDir = System.getenv("HADOOP_CONF_DIR");
    }
    if (hadoopConfDir == null) {
      throw new IllegalArgumentException("Cannot determine HADOOP_CONF_DIR for standalone cluster");
    }
    return hadoopConfDir;
  }

  public void setHadoopConfDir(String hadoopConfDir) {
    this.hadoopConfDir = hadoopConfDir;
  }

  @Override
  public String getInstanceName() {
    return info.getInstanceName();
  }

  @Override
  public String getZooKeepers() {
    return info.getZooKeepers();
  }

  @Override
  public ServerContext getServerContext() {
    serverContextCreated.set(true);
    return contextSupplier.get();
  }

  @Override
  public AccumuloClient createAccumuloClient(String user, AuthenticationToken token) {
    return Accumulo.newClient().to(getInstanceName(), getZooKeepers()).as(user, token).build();
  }

  @Override
  public Properties getClientProperties() {
    return info.getClientProperties();
  }

  @Override
  public StandaloneClusterControl getClusterControl() {
    return new StandaloneClusterControl(accumuloHome, clientAccumuloConfDir, serverAccumuloConfDir,
        clientCmdPrefix, serverCmdPrefix);
  }

  @Override
  public void start() throws IOException {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot start a cluster that is terminated.");
    StandaloneClusterControl control = getClusterControl();

    // TODO We can check the hosts files, but that requires us to be on a host with the
    // installation. Limitation at the moment.

    control.setGoalState(ManagerGoalState.NORMAL.toString());

    for (ServerType type : ALL_SERVER_TYPES) {
      control.startAllServers(type);
    }
    clusterState = State.STARTED;
  }

  @Override
  public void stop() throws IOException {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot stop a cluster that is terminated.");
    StandaloneClusterControl control = getClusterControl();

    // TODO We can check the hosts files, but that requires us to be on a host with the
    // installation. Limitation at the moment.

    for (ServerType type : ALL_SERVER_TYPES) {
      control.stopAllServers(type);
    }
    clusterState = State.STOPPED;
  }

  @Override
  public void terminate() throws Exception {
    Preconditions.checkState(clusterState != State.TERMINATED,
        "Cannot stop a cluster that is terminated.");

    if (clusterState != State.STOPPED) {
      stop();
    }

    if (serverContextCreated.get()) {
      getServerContext().close();
    }
    clusterState = State.TERMINATED;
  }

  public Configuration getHadoopConfiguration() {
    String confDir = getHadoopConfDir();
    // Using CachedConfiguration will make repeatedly calling this method much faster
    final Configuration conf = getServerContext().getHadoopConf();
    conf.addResource(new Path(confDir, "core-site.xml"));
    // Need hdfs-site.xml for NN HA
    conf.addResource(new Path(confDir, "hdfs-site.xml"));
    return conf;
  }

  @Override
  public FileSystem getFileSystem() {
    Configuration conf = getHadoopConfiguration();
    try {
      return FileSystem.get(conf);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public Path getTemporaryPath() {
    return getFileSystem().makeQualified(tmp);
  }

  public ClusterUser getUser(int offset) {
    checkArgument(offset >= 0 && offset < users.size(),
        "Invalid offset, should be non-negative and less than " + users.size());
    return users.get(offset);
  }

  @Override
  public AccumuloConfiguration getSiteConfiguration() {
    return new ConfigurationCopy(siteConfig);
  }

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN",
      justification = "code runs in same security context as user who provided input file name")
  @Override
  public String getAccumuloPropertiesPath() {
    return java.nio.file.Path.of(serverAccumuloConfDir).resolve("accumulo.properties").toString();
  }

  @SuppressFBWarnings(value = "PATH_TRAVERSAL_IN",
      justification = "code runs in same security context as user who provided input file name")
  @Override
  public String getClientPropsPath() {
    return java.nio.file.Path.of(clientAccumuloConfDir).resolve("accumulo-client.properties")
        .toString();
  }
}
