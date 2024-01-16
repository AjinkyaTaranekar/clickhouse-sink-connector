package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.jdbc.ClickHouseConnection;
import org.apache.commons.lang3.StringUtils;

import java.sql.SQLException;

public class ClickHouseCreateDatabase extends ClickHouseTableOperationsBase {
    public void createNewDatabase(ClickHouseConnection conn, String dbName, ClickHouseSinkConnectorConfig config) throws SQLException {
        Boolean isClusterEnabled = config.getBoolean(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_CLUSTER_ENABLED.toString());
        final String clusterName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_CLUSTER.toString());

        if (isClusterEnabled && StringUtils.isBlank(clusterName)) {
            isClusterEnabled = Boolean.FALSE;
        }

        String createDatabaseQuery = String.format("CREATE DATABASE IF NOT EXISTS %s;", dbName, dbName);

        if (isClusterEnabled) {
            createDatabaseQuery = String.format("CREATE DATABASE IF NOT EXISTS %s ON CLUSTER %s;", dbName, clusterName);
        }

        String query = String.format("USE system; %s; USE %s", createDatabaseQuery, dbName);
        this.runQuery(query, conn);
    }
}
