/*
 * Copyright (c) 2011-2014 The original author or authors
 * ------------------------------------------------------
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *     The Eclipse Public License is available at
 *     http://www.eclipse.org/legal/epl-v10.html
 *
 *     The Apache License v2.0 is available at
 *     http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.ext.jdbc.impl.actions;

import io.vertx.core.Vertx;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.math.BigDecimal;
import java.sql.*;
import java.sql.Date;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;

/**
 * @author <a href="mailto:nscavell@redhat.com">Nick Scavelli</a>
 */
public abstract class AbstractJDBCStatement<T> extends AbstractJDBCAction<T> {
  private final String sql;
  private final JsonArray parameters;

  private List<String> namedParameters = null;

  protected AbstractJDBCStatement(Vertx vertx, Connection connection, String sql, JsonArray parameters) {
    super(vertx, connection);
    this.sql = sql;
    this.parameters = parameters;
  }

  @Override
  protected final T execute(Connection conn) throws SQLException {
    try (PreparedStatement statement = preparedStatement(conn, sql)) {
      fillStatement(statement, parameters);

      return executeStatement(statement);
    }
  }

  protected PreparedStatement preparedStatement(Connection conn, String sql) throws SQLException {
    if ( hasNamedParameters() ) {
      String sqlReplaced = parseForNamedParameters();
      return conn.prepareStatement(sqlReplaced);
    } else {
      return conn.prepareStatement(sql);
    }
  }

  protected abstract T executeStatement(PreparedStatement statement) throws SQLException;

  protected String parseForNamedParameters() {
    String npRegex = "((:[a-zA-Z][a-zA-Z0-9_]+))";
    Pattern p = Pattern.compile(npRegex);
    Matcher m = p.matcher(this.sql);
    // extract parameters to list
    namedParameters = new LinkedList<String>();
    for ( int idx = 1; m.find(); idx++) {
      String result = m.group(1);
      result = result.replace(":", "");
      namedParameters.add(result);
    }
    // replace occurrences of regex with ? to satisfy conn.prepareStatement
    String sqlReplaced = m.replaceAll("?");
    return sqlReplaced;
  }

  protected boolean hasNamedParameters() {
    String npRegex = "((:[a-z][a-z]+))";
    Pattern p = Pattern.compile(npRegex);
    Matcher m = p.matcher(this.sql);
    boolean result = m.find();
    return result;
  }
  protected void fillStatement(PreparedStatement statement, JsonArray parameters) throws SQLException {
    if (parameters == null || parameters.size() == 0) {
      return;
    }
    if ( hasNamedParameters() ) {
      fillStatementNamedParameters(statement, parameters);  
    } else { 
      for (int i = 0; i < parameters.size(); i++) {
        statement.setObject(i + 1, parameters.getValue(i));
      }
    }
  }

  protected void fillStatementNamedParameters(PreparedStatement statement, JsonArray parameters) throws SQLException {
    if (parameters == null || parameters.size() == 0) {
      return;
    }
    for ( Object obj : parameters ) {
      if ( obj instanceof JsonObject) {
        JsonObject jo = (JsonObject)obj;
        Map<String, Object> paramMap = jo.getMap();
        for ( String key : paramMap.keySet() ) {
          for ( int i = 0; i < namedParameters.size(); i++ ) {
            String param = namedParameters.get(i);
            if ( param.equalsIgnoreCase(key)) {
              statement.setObject(i+1, paramMap.get(key) );
            }
          }
        }
      }
    }
  }

  protected io.vertx.ext.sql.ResultSet asList(ResultSet rs) throws SQLException {

    List<String> columnNames = new ArrayList<>();
    ResultSetMetaData metaData = rs.getMetaData();
    int cols = metaData.getColumnCount();
    for (int i = 1; i <= cols; i++) {
      columnNames.add(metaData.getColumnLabel(i));
    }

    List<JsonArray> results = new ArrayList<>();

    while (rs.next()) {
      JsonArray result = new JsonArray();
      for (int i = 1; i <= cols; i++) {
        Object res = convertSqlValue(rs.getObject(i));
        if (res != null) {
          result.add(res);
        } else {
          result.addNull();
        }
      }
      results.add(result);
    }

    return new io.vertx.ext.sql.ResultSet(columnNames, results);
  }

  protected Object convertSqlValue(Object value) {
    if (value == null) {
      return null;
    }

    // valid json types are just returned as is
    if (value instanceof Boolean || value instanceof String || value instanceof byte[]) {
      return value;
    }

    // numeric values
    if (value instanceof Number) {
      if (value instanceof BigDecimal) {
        BigDecimal d = (BigDecimal) value;
        if (d.scale() == 0) {
          return ((BigDecimal) value).toBigInteger();
        } else {
          // we might loose precision here
          return ((BigDecimal) value).doubleValue();
        }
      }

      return value;
    }

    // temporal values
    if (value instanceof Date || value instanceof Time || value instanceof Timestamp) {
      return OffsetDateTime.ofInstant(Instant.ofEpochMilli(((java.util.Date) value).getTime()), ZoneOffset.UTC).format(ISO_OFFSET_DATE_TIME);
    }

    // large objects
    if (value instanceof Clob) {
      Clob c = (Clob) value;
      try {
        // result might be truncated due to downcasting to int
        String tmp = c.getSubString(1, (int) c.length());
        c.free();

        return tmp;
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    if (value instanceof Blob) {
      Blob b = (Blob) value;
      try {
        // result might be truncated due to downcasting to int
        byte[] tmp = b.getBytes(1, (int) b.length());
        b.free();
        return tmp;
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    // arrays
    if (value instanceof Array) {
      Array a = (Array) value;
      try {
        Object[] arr = (Object[]) a.getArray();
        if (arr != null) {
          JsonArray jsonArray = new JsonArray();
          for (Object o : arr) {
            jsonArray.add(convertSqlValue(o));
          }

          a.free();

          return jsonArray;
        }
      } catch (SQLException e) {
        throw new RuntimeException(e);
      }
    }

    // fallback to String
    return value.toString();
  }
}
