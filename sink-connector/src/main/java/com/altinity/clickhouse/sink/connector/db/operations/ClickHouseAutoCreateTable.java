package com.altinity.clickhouse.sink.connector.db.operations;

import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfigVariables;
import com.clickhouse.data.ClickHouseDataType;
import com.clickhouse.jdbc.ClickHouseConnection;
import com.google.common.annotations.VisibleForTesting;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.connect.data.Field;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

import static com.altinity.clickhouse.sink.connector.db.ClickHouseDbConstants.*;

/**
 * Class that wraps all functionality
 * related to creating tables
 * from kafka sink record.
 */
public class ClickHouseAutoCreateTable extends ClickHouseTableOperationsBase{


    private static final Logger log = LoggerFactory.getLogger(ClickHouseAutoCreateTable.class.getName());

    public void createNewTable(
            ArrayList<String> primaryKey,
            String tableName, Field[] fields,
            ClickHouseConnection connection,
            ClickHouseSinkConnectorConfig config,
            Boolean isNewReplacingMergeTreeEngine) throws SQLException {
        Map<String, String> colNameToDataTypeMap = this.getColumnNameToCHDataTypeMapping(fields);
        String createTableQuery = this.createTableSyntax(primaryKey, tableName, fields, colNameToDataTypeMap, config, isNewReplacingMergeTreeEngine);
        log.info("**** AUTO CREATE TABLE " + createTableQuery);
        // ToDO: need to run it before a session is created.
        this.runQuery(createTableQuery, connection);
    }

    /**
     * Function to generate CREATE TABLE for ClickHouse.
     *
     * @param primaryKey
     * @param columnToDataTypesMap
     * @return CREATE TABLE query
     */
    public java.lang.String createTableSyntax(
            ArrayList<String> primaryKey,
            String tableName,
            Field[] fields,
            Map<String, String> columnToDataTypesMap,
            ClickHouseSinkConnectorConfig config,
            Boolean isNewReplacingMergeTreeEngine) {

        Boolean isClusterEnabled = config.getBoolean(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_CLUSTER_ENABLED.toString());
        final String clusterName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_CLUSTER.toString());
        final String databaseName = config.getString(ClickHouseSinkConnectorConfigVariables.CLICKHOUSE_DATABASE.toString());

        if (isClusterEnabled && StringUtils.isBlank(clusterName)) {
            isClusterEnabled = Boolean.FALSE;
        }

        String innerTableName = "`" + INNER_TABLE_PREFIX + tableName.replace("`", "") + "`";

        StringBuilder createTableSyntax = new StringBuilder();

        createTableSyntax.append(CREATE_TABLE).append(" ");

        if (isClusterEnabled) {
            createTableSyntax.append(databaseName).append(".").append(innerTableName).append(" ").append(ON_CLUSTER).append(" ").append(clusterName);
        } else {
            createTableSyntax.append(databaseName).append(".").append(tableName);
        }

        createTableSyntax.append("(");

        String isDeletedColumn = IS_DELETED_COLUMN;
        if(Arrays.stream(fields).anyMatch((field -> field.name().equals(IS_DELETED_COLUMN)))) {
            isDeletedColumn = "__" + IS_DELETED_COLUMN;
        }

        for(Field f: fields) {
            String colName = f.name();
            String dataType = columnToDataTypesMap.get(colName);
            boolean isNull = false;
            if(f.schema().isOptional() == true) {
                isNull = true;
            }
            createTableSyntax.append("`").append(colName).append("`").append(" ").append(dataType);

            // Ignore setting NULL OR not NULL for JSON and Array
            if(dataType != null &&
                    (dataType.equalsIgnoreCase(ClickHouseDataType.JSON.name()) ||
                            dataType.contains(ClickHouseDataType.Array.name()))) {
                // ignore adding nulls;
            } else {
                if (isNull) {
                    createTableSyntax.append(" ").append(NULL);
                } else {
                    createTableSyntax.append(" ").append(NOT_NULL);
                }
            }
            createTableSyntax.append(",");

        }
//        for(Map.Entry<String, String>  entry: columnToDataTypesMap.entrySet()) {
//            createTableSyntax.append("`").append(entry.getKey()).append("`").append(" ").append(entry.getValue()).append(",");
//        }
        //createTableSyntax.deleteCharAt(createTableSyntax.lastIndexOf(","));

        if(isNewReplacingMergeTreeEngine == true) {
            createTableSyntax.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE).append(",");
            createTableSyntax.append("`").append(isDeletedColumn).append("` ").append(IS_DELETED_COLUMN_DATA_TYPE);
        } else {
            createTableSyntax.append("`").append(SIGN_COLUMN).append("` ").append(SIGN_COLUMN_DATA_TYPE).append(",");
            createTableSyntax.append("`").append(VERSION_COLUMN).append("` ").append(VERSION_COLUMN_DATA_TYPE);
        }

        createTableSyntax.append(")").append(" ");
        if(isNewReplacingMergeTreeEngine == true) {
            createTableSyntax.append("Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(",").append(isDeletedColumn).append(")");
        } else {
            createTableSyntax.append("Engine=ReplacingMergeTree(").append(VERSION_COLUMN).append(")");
        }


        createTableSyntax.append(" ");

        if(primaryKey != null && isPrimaryKeyColumnPresent(primaryKey, columnToDataTypesMap)) {
            createTableSyntax.append(PRIMARY_KEY).append("(");
            createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
            createTableSyntax.append(") ");

            createTableSyntax.append(ORDER_BY).append("(");
            createTableSyntax.append(primaryKey.stream().map(Object::toString).collect(Collectors.joining(",")));
            createTableSyntax.append(")");
        } else {
            // ToDO:
            createTableSyntax.append(ORDER_BY_TUPLE);
        }
        createTableSyntax.append(";");
        if (isClusterEnabled) {
            createTableSyntax.append(CREATE_TABLE).append(" ").append(databaseName).append(".").append(tableName);
            createTableSyntax.append(" AS ").append(databaseName).append(".").append(innerTableName);
            createTableSyntax.append(" Engine=Distributed(").append(clusterName).append(",").append(databaseName).append(",").append(innerTableName);
            createTableSyntax.append(")").append(";");
        }
       return createTableSyntax.toString();
    }

    @VisibleForTesting
    boolean isPrimaryKeyColumnPresent(ArrayList<String> primaryKeys, Map<String, String> columnToDataTypesMap) {

        for(String primaryKey: primaryKeys) {
            if(!columnToDataTypesMap.containsKey(primaryKey)) {
                return false;
            }
        }
        return true;
    }
}
