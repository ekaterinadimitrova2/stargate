/*
 * Copyright The Stargate Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.stargate.graphql.fetchers;

import com.datastax.oss.driver.api.querybuilder.SchemaBuilder;
import com.datastax.oss.driver.api.querybuilder.schema.AlterTableDropColumnEnd;
import com.datastax.oss.driver.api.querybuilder.schema.AlterTableStart;
import graphql.schema.DataFetcher;
import graphql.schema.DataFetchingEnvironment;
import io.stargate.auth.AuthenticationService;
import io.stargate.auth.StoredCredentials;
import io.stargate.db.ClientState;
import io.stargate.db.Persistence;
import io.stargate.db.QueryState;
import io.stargate.db.datastore.DataStore;
import io.stargate.db.datastore.ResultSet;
import io.stargate.graphql.graphqlservlet.HTTPAwareContextImpl;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AlterTableDropFetcher
    implements io.stargate.graphql.fetchers.SchemaFetcher, DataFetcher {
  private final Persistence persistence;
  private AuthenticationService authenticationService;

  public AlterTableDropFetcher(
      Persistence persistence, AuthenticationService authenticationService) {
    this.persistence = persistence;
    this.authenticationService = authenticationService;
  }

  @Override
  public Object get(DataFetchingEnvironment environment) throws Exception {
    HTTPAwareContextImpl httpAwareContext = environment.getContext();

    String token = httpAwareContext.getAuthToken();
    StoredCredentials storedCredentials = authenticationService.validateToken(token);
    ClientState clientState = persistence.newClientState(storedCredentials.getRoleName());
    QueryState queryState = persistence.newQueryState(clientState);
    DataStore dataStore = persistence.newDataStore(queryState, null);

    CompletableFuture<ResultSet> resultSetSingle = dataStore.query(getQuery(environment));
    resultSetSingle.get();
    return true;
  }

  @Override
  public String getQuery(DataFetchingEnvironment dataFetchingEnvironment) {
    AlterTableStart start =
        SchemaBuilder.alterTable(
            (String) dataFetchingEnvironment.getArgument("keyspaceName"),
            (String) dataFetchingEnvironment.getArgument("tableName"));

    List<String> toDrop = dataFetchingEnvironment.getArgument("toDrop");
    if (toDrop.isEmpty()) {
      // TODO see if we can enforce that through the schema instead
      throw new IllegalArgumentException("toDrop must contain at least one element");
    }
    AlterTableDropColumnEnd table = null;
    for (String column : toDrop) {
      if (table != null) {
        table = table.dropColumn(column);
      } else {
        table = start.dropColumn(column);
      }
    }
    return table.build().getQuery();
  }
}
