/*
 * SonarLint Daemon
 * Copyright (C) 2009-2016 SonarSource SA
 * mailto:contact AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonarlint.daemon.services;

import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sonarlint.daemon.services.AbstractSonarLint.DefaultClientInputFile;
import org.sonarlint.daemon.services.AbstractSonarLint.ProxyIssueListener;
import org.sonarlint.daemon.services.AbstractSonarLint.ProxyLogOutput;
import org.sonarsource.sonarlint.core.ConnectedSonarLintEngineImpl;
import org.sonarsource.sonarlint.core.client.api.common.analysis.ClientInputFile;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedAnalysisConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedGlobalConfiguration;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedGlobalConfiguration.Builder;
import org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine;
import org.sonarsource.sonarlint.core.client.api.connected.ServerConfiguration;
import org.sonarsource.sonarlint.daemon.proto.ConnectedSonarLintGrpc.ConnectedSonarLint;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ConnectedAnalysisReq;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ConnectedConfiguration;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.LogEvent;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ModuleUpdateReq;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.ServerConfig;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.StorageState;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Void;
import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.StorageState.State;

import org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.InputFile;

import java.nio.charset.Charset;
import java.nio.file.Paths;
import java.util.LinkedList;
import java.util.List;

public class ConnectedSonarLintImpl implements ConnectedSonarLint {
  private static final Logger LOGGER = LoggerFactory.getLogger(ConnectedSonarLintImpl.class);
  private ConnectedSonarLintEngine engine;
  private ProxyLogOutput logOutput = new ProxyLogOutput();

  @Override
  public void start(ConnectedConfiguration requestConfig, StreamObserver<Void> response) {
    if (engine != null) {
      engine.stop(false);
      engine = null;
    }

    try {
      Builder builder = ConnectedGlobalConfiguration.builder();
      if (requestConfig.getHomePath() != null) {
        builder.setSonarLintUserHome(Paths.get(requestConfig.getHomePath()));
      }
      builder.setLogOutput(logOutput)
        .setServerId(requestConfig.getStorageId());

      engine = new ConnectedSonarLintEngineImpl(builder.build());
      response.onNext(Void.newBuilder().build());
      response.onCompleted();
    } catch (Exception e) {
      LOGGER.error("Error registering", e);
      response.onError(e);
    }
  }

  @Override
  public void analyze(ConnectedAnalysisReq requestConfig, StreamObserver<org.sonarsource.sonarlint.daemon.proto.SonarlintDaemon.Issue> response) {
    try {
      List<ClientInputFile> files = new LinkedList<>();
      List<InputFile> requestFiles = requestConfig.getFileList();

      for (InputFile f : requestFiles) {
        files.add(new DefaultClientInputFile(Paths.get(f.getPath()), f.getIsTest(), Charset.forName(f.getCharset())));
      }

      ConnectedAnalysisConfiguration config = new ConnectedAnalysisConfiguration(
        requestConfig.getModuleKey(),
        Paths.get(requestConfig.getBaseDir()),
        Paths.get(requestConfig.getWorkDir()),
        files,
        requestConfig.getProperties());

      engine.analyze(config, new ProxyIssueListener(response), logOutput);
      response.onCompleted();
    } catch (Exception e) {
      LOGGER.error("Error analyzing", e);
      response.onError(e);
    }
  }

  @Override
  public void streamLogs(Void request, StreamObserver<LogEvent> response) {
    logOutput.setObserver(response);
  }

  @Override
  public void update(ServerConfig request, StreamObserver<Void> response) {
    try {
      ServerConfiguration config = transformServerConfig(request);
      engine.update(config);
      response.onNext(Void.newBuilder().build());
      response.onCompleted();
    } catch (Exception e) {
      LOGGER.error("update", e);
      response.onError(e);
    }
  }

  private static ServerConfiguration transformServerConfig(ServerConfig config) {
    ServerConfiguration.Builder builder = ServerConfiguration.builder()
      .url(config.getHostUrl())
      .userAgent("SonarLint Daemon");

    switch (config.getAuthCase()) {
      case CREDENTIALS:
        builder.credentials(config.getCredentials().getLogin(), config.getCredentials().getPassword());
        break;
      case TOKEN:
        builder.token(config.getToken());
        break;
      case AUTH_NOT_SET:
      default:
        // do nothing
        break;
    }

    return builder.build();
  }

  @Override
  public void getState(Void request, StreamObserver<StorageState> response) {
    try {
      org.sonarsource.sonarlint.core.client.api.connected.ConnectedSonarLintEngine.State state = engine.getState();
      State transformed;

      switch (state) {
        case NEED_UPDATE:
          transformed = State.NEED_UPDATE;
          break;
        case NEVER_UPDATED:
          transformed = State.NEVER_UPDATED;
          break;
        case UPDATED:
          transformed = State.UPDATED;
          break;
        case UPDATING:
          transformed = State.UPDATING;
          break;
        case UNKNOW:
        default:
          transformed = State.UNKNOW;
      }
      response.onNext(StorageState.newBuilder().setState(transformed).build());
      response.onCompleted();
    } catch (Exception e) {
      LOGGER.error("status", e);
      response.onError(e);
    }
  }

  @Override
  public void updateModule(ModuleUpdateReq request, StreamObserver<Void> response) {
    try {
      ServerConfiguration serverConfig = transformServerConfig(request.getServerConfig());
      engine.updateModule(serverConfig, request.getModuleKey());
      response.onNext(Void.newBuilder().build());
      response.onCompleted();
    } catch (Exception e) {
      LOGGER.error("updateModule", e);
      response.onError(e);
    }
  }
}
