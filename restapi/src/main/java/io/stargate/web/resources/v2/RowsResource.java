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
package io.stargate.web.resources.v2;

import com.codahale.metrics.annotation.Timed;
import com.datastax.oss.driver.shaded.guava.common.base.Strings;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.stargate.auth.UnauthorizedException;
import io.stargate.db.datastore.DataStore;
import io.stargate.db.datastore.ResultSet;
import io.stargate.db.datastore.query.ColumnOrder;
import io.stargate.db.datastore.query.ImmutableColumnOrder;
import io.stargate.db.datastore.query.Value;
import io.stargate.db.datastore.query.Where;
import io.stargate.db.schema.Column;
import io.stargate.db.schema.Table;
import io.stargate.web.models.Error;
import io.stargate.web.models.GetResponseWrapper;
import io.stargate.web.models.ResponseWrapper;
import io.stargate.web.resources.Converters;
import io.stargate.web.resources.Db;
import io.stargate.web.resources.RequestHandler;
import io.stargate.web.service.WhereParser;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javax.inject.Inject;
import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.PATCH;
import javax.ws.rs.POST;
import javax.ws.rs.PUT;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Response;
import org.apache.cassandra.stargate.db.ConsistencyLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Path("/v2/keyspaces/{keyspaceName}/{tableName}")
@Produces(MediaType.APPLICATION_JSON)
public class RowsResource {
  private static final Logger logger = LoggerFactory.getLogger(RowsResource.class);

  @Inject private Db db;
  private static final ObjectMapper mapper = new ObjectMapper();
  private final int DEFAULT_PAGE_SIZE = 100;

  @Timed
  @GET
  public Response getWithWhere(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      @QueryParam("where") final String where,
      @QueryParam("fields") final String fields,
      @QueryParam("page-size") final int pageSizeParam,
      @QueryParam("page-state") final String pageStateParam,
      @QueryParam("raw") final boolean raw,
      @QueryParam("sort") final String sort) {
    return RequestHandler.handle(
        () -> {
          ByteBuffer pageState = null;
          if (pageStateParam != null) {
            byte[] decodedBytes = Base64.getDecoder().decode(pageStateParam);
            pageState = ByteBuffer.wrap(decodedBytes);
          }

          int pageSize = DEFAULT_PAGE_SIZE;
          if (pageSizeParam > 0) {
            pageSize = pageSizeParam;
          }

          DataStore localDB = db.getDataStoreForToken(token, pageSize, pageState);
          final Table tableMetadata = db.getTable(localDB, keyspaceName, tableName);

          Object response =
              getRows(
                  fields,
                  raw,
                  sort,
                  localDB,
                  tableMetadata,
                  WhereParser.parseWhere(where, tableMetadata));
          return Response.status(Response.Status.OK)
              .entity(Converters.writeResponse(response))
              .build();
        });
  }

  @Timed
  @GET
  @Path("/{path: .*}")
  public Response get(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      @PathParam("path") List<PathSegment> path,
      @QueryParam("fields") final String fields,
      @QueryParam("page-size") final int pageSizeParam,
      @QueryParam("page-state") final String pageStateParam,
      @QueryParam("raw") final boolean raw,
      @QueryParam("sort") final String sort) {
    return RequestHandler.handle(
        () -> {
          ByteBuffer pageState = null;
          if (pageStateParam != null) {
            byte[] decodedBytes = Base64.getDecoder().decode(pageStateParam);
            pageState = ByteBuffer.wrap(decodedBytes);
          }

          int pageSize = DEFAULT_PAGE_SIZE;
          if (pageSizeParam > 0) {
            pageSize = pageSizeParam;
          }

          DataStore localDB = db.getDataStoreForToken(token, pageSize, pageState);
          final Table tableMetadata = db.getTable(localDB, keyspaceName, tableName);

          List<Where<?>> where;
          try {
            where = buildWhereForPath(tableMetadata, path);
          } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(
                    new Error(
                        "not enough partition keys provided",
                        Response.Status.BAD_REQUEST.getStatusCode()))
                .build();
          }

          Object response = getRows(fields, raw, sort, localDB, tableMetadata, where);
          return Response.status(Response.Status.OK)
              .entity(Converters.writeResponse(response))
              .build();
        });
  }

  @Timed
  @POST
  public Response add(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      String payload) {
    return RequestHandler.handle(
        () -> {
          DataStore localDB = db.getDataStoreForToken(token);

          Map<String, String> requestBody = mapper.readValue(payload, Map.class);

          Table table = db.getTable(localDB, keyspaceName, tableName);

          List<Value<?>> values =
              requestBody.entrySet().stream()
                  .map((e) -> Converters.colToValue(e, table))
                  .collect(Collectors.toList());

          localDB
              .query()
              .insertInto(keyspaceName, tableName)
              .value(values)
              .consistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
              .execute();

          Map<String, Object> keys = new HashMap<>();
          for (Column col : table.primaryKeyColumns()) {
            keys.put(col.name(), requestBody.get(col.name()));
          }

          return Response.status(Response.Status.CREATED)
              .entity(Converters.writeResponse(keys))
              .build();
        });
  }

  @Timed
  @PUT
  @Path("/{path: .*}")
  public Response update(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      @PathParam("path") List<PathSegment> path,
      @QueryParam("raw") final boolean raw,
      String payload) {
    return RequestHandler.handle(
        () -> modifyRow(token, keyspaceName, tableName, path, raw, payload));
  }

  @Timed
  @DELETE
  @Path("/{path: .*}")
  public Response delete(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      @PathParam("path") List<PathSegment> path) {
    return RequestHandler.handle(
        () -> {
          DataStore localDB = db.getDataStoreForToken(token);

          final Table tableMetadata = db.getTable(localDB, keyspaceName, tableName);

          List<Where<?>> where;
          try {
            where = buildWhereForPath(tableMetadata, path);
          } catch (IllegalArgumentException iae) {
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(
                    new Error(
                        "not enough partition keys provided",
                        Response.Status.BAD_REQUEST.getStatusCode()))
                .build();
          }

          localDB
              .query()
              .delete()
              .from(keyspaceName, tableName)
              .where(where)
              .consistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
              .execute();

          return Response.status(Response.Status.NO_CONTENT).build();
        });
  }

  @Timed
  @PATCH
  @Path("/{path: .*}")
  public Response patch(
      @HeaderParam("X-Cassandra-Token") String token,
      @PathParam("keyspaceName") final String keyspaceName,
      @PathParam("tableName") final String tableName,
      @PathParam("path") List<PathSegment> path,
      @QueryParam("raw") final boolean raw,
      String payload) {
    return RequestHandler.handle(
        () -> modifyRow(token, keyspaceName, tableName, path, raw, payload));
  }

  private Response modifyRow(
      String token,
      String keyspaceName,
      String tableName,
      List<PathSegment> path,
      boolean raw,
      String payload)
      throws UnauthorizedException, com.fasterxml.jackson.core.JsonProcessingException,
          ExecutionException, InterruptedException {
    DataStore localDB = db.getDataStoreForToken(token);

    final Table tableMetadata = db.getTable(localDB, keyspaceName, tableName);

    List<Where<?>> where;
    try {
      where = buildWhereForPath(tableMetadata, path);
    } catch (IllegalArgumentException iae) {
      return Response.status(Response.Status.BAD_REQUEST)
          .entity(
              new Error(
                  "not enough partition keys provided",
                  Response.Status.BAD_REQUEST.getStatusCode()))
          .build();
    }

    Map<String, String> requestBody = mapper.readValue(payload, Map.class);
    List<Value<?>> changes =
        requestBody.entrySet().stream()
            .map((e) -> Converters.colToValue(e, tableMetadata))
            .collect(Collectors.toList());

    final ResultSet r =
        localDB
            .query()
            .update(keyspaceName, tableName)
            .value(changes)
            .where(where)
            .consistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
            .execute();

    Object response = raw ? requestBody : new ResponseWrapper(requestBody);
    return Response.status(Response.Status.OK).entity(Converters.writeResponse(response)).build();
  }

  private Object getRows(
      String fields,
      boolean raw,
      String sort,
      DataStore localDB,
      Table tableMetadata,
      List<Where<?>> where)
      throws Exception {
    List<Column> columns;
    if (Strings.isNullOrEmpty(fields)) {
      columns = tableMetadata.columns();
    } else {
      columns =
          Arrays.stream(fields.split(",")).map(Column::reference).collect(Collectors.toList());
    }

    final ResultSet r =
        localDB
            .query()
            .select()
            .column(columns)
            .from(tableMetadata.keyspace(), tableMetadata.name())
            .where(where)
            .orderBy(buildSortOrder(sort))
            .consistencyLevel(ConsistencyLevel.LOCAL_QUORUM)
            .execute();

    List<Map<String, Object>> rows =
        r.rows().stream().map(Converters::row2Map).collect(Collectors.toList());
    String newPagingState =
        r.getPagingState() != null
            ? Base64.getEncoder().encodeToString(r.getPagingState().array())
            : null;
    return raw ? rows : new GetResponseWrapper(rows.size(), newPagingState, rows);
  }

  private List<ColumnOrder> buildSortOrder(String sort)
      throws com.fasterxml.jackson.core.JsonProcessingException {
    if (Strings.isNullOrEmpty(sort)) {
      return new ArrayList<>();
    }

    List<ColumnOrder> order = new ArrayList<>();
    Map<String, String> sortOrder = mapper.readValue(sort, Map.class);

    for (Map.Entry<String, String> entry : sortOrder.entrySet()) {
      Column.Order colOrder =
          "asc".equals(entry.getValue().toLowerCase()) ? Column.Order.Asc : Column.Order.Desc;
      order.add(ImmutableColumnOrder.of(entry.getKey(), colOrder));
    }
    return order;
  }

  private List<Where<?>> buildWhereForPath(Table tableMetadata, List<PathSegment> path) {
    if (tableMetadata.partitionKeyColumns().size() > path.size()) {
      throw new IllegalArgumentException(
          String.format(
              "Invalid number of key values required (%s). All partition key columns values are required plus 0..all clustering columns values in proper order.",
              tableMetadata.partitionKeyColumns().size()));
    }

    List<Column> keys = tableMetadata.primaryKeyColumns();
    return IntStream.range(0, path.size())
        .mapToObj(
            i -> Converters.idToWhere(path.get(i).getPath(), keys.get(i).name(), tableMetadata))
        .collect(Collectors.toList());
  }
}
