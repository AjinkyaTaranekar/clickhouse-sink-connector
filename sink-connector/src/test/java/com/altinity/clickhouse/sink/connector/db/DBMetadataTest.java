package com.altinity.clickhouse.sink.connector.db;

import org.apache.commons.lang3.tuple.Pair;
import org.junit.Assert;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import com.altinity.clickhouse.sink.connector.ClickHouseSinkConnectorConfig;
import org.apache.commons.lang3.tuple.MutablePair;
import org.testcontainers.utility.MountableFile;

import java.sql.SQLException;
import java.time.ZoneId;
import java.util.HashMap;

import static org.mockito.Mockito.when;

@Testcontainers

public class DBMetadataTest {

    @Container
    private ClickHouseContainer clickHouseContainer = new ClickHouseContainer("clickhouse/clickhouse-server:latest")
            .withInitScript("./init_clickhouse.sql").withCopyFileToContainer(MountableFile.forClasspathResource("config.xml"), "/etc/clickhouse-server/config.d/config.xml");

    @Test
    public void testGetSignColumnForCollapsingMergeTree() {

        DBMetadata metadata = new DBMetadata();

        String createTableDML = "CollapsingMergeTree(signNumberCol) PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getSignColumnForCollapsingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("signNumberCol"));
    }

    @Test
    public void testDefaultGetSignColumnForCollapsingMergeTree() {

        DBMetadata metadata = new DBMetadata();

        String createTableDML = "ReplacingMergeTree() PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getSignColumnForCollapsingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("sign"));
    }

    @Test
    public void testGetUnderlyingDatabaseAndTableOfDistributed() {
        DBMetadata metadata = new DBMetadata();

        String createTableDML = "Distributed(cluster1, `db`, `test`) SETTINGS index_granularity = 8192";
        Pair<String, String > databaseAndTableOfDistributed = metadata.getUnderlyingDatabaseAndTableOfDistributed(createTableDML);

        final Pair<String, String > expectedDatabaseAndTableOfDistributed = new MutablePair<>("db", "test");
        Assert.assertTrue(databaseAndTableOfDistributed.getLeft().equals(expectedDatabaseAndTableOfDistributed.getLeft()));
        Assert.assertTrue(databaseAndTableOfDistributed.getRight().equals(expectedDatabaseAndTableOfDistributed.getRight()));
    }

    @Test
    public void testGetVersionColumnForReplacingMergeTree() {
        DBMetadata metadata = new DBMetadata();

        String createTableDML = "ReplacingMergeTree(versionNo) PRIMARY KEY productCode ORDER BY productCode SETTINGS index_granularity = 8192";
        String signColumn = metadata.getVersionColumnForReplacingMergeTree(createTableDML);

        Assert.assertTrue(signColumn.equalsIgnoreCase("versionNo"));

    }

    @Test
    @Tag("IntegrationTest")
    public void testCheckIfDatabaseExists() throws SQLException {

        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        // Default database exists.
        boolean result = new DBMetadata().checkIfDatabaseExists(writer.getConnection(), "default");
        Assert.assertTrue(result);

        boolean result2 = new DBMetadata().checkIfDatabaseExists(writer.getConnection(), "newdb");
        Assert.assertFalse(result2);

    }

    @Test
    public void testGetEngineFromResponse() throws SQLException {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);

        // Default database exists.
        boolean result = new DBMetadata().checkIfDatabaseExists(writer.getConnection(), "default");
        Assert.assertTrue(result);

        String replacingMergeTree = "ReplacingMergeTree(ver) PRIMARY KEY dept_no ORDER BY dept_no SETTINGS index_granularity = 8192";
        MutablePair<DBMetadata.TABLE_ENGINE, String> replacingMergeTreeResult = new DBMetadata().getEngineFromResponse(writer.getConnection(), replacingMergeTree);

        Assert.assertTrue(replacingMergeTreeResult.getRight().equalsIgnoreCase("ver"));
        Assert.assertTrue(replacingMergeTreeResult.getLeft().getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()));

        String distributedEngine = "Distributed(cluster1, db, test) SETTINGS index_granularity = 8192";

        when(new DBMetadata().getTableEngineUsingSystemTables(writer.getConnection(), "db", "test")).thenReturn(new MutablePair<>(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE, "ver"));
        MutablePair<DBMetadata.TABLE_ENGINE, String> distributedResult = new DBMetadata().getEngineFromResponse(writer.getConnection(), distributedEngine);

        Assert.assertTrue(distributedResult.getRight().equalsIgnoreCase("ver"));
        Assert.assertTrue(distributedResult.getLeft().getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()));

        String replicatedReplacingMergeTree = "ReplicatedReplacingMergeTree('/clickhouse/{cluster}/tables/dashboard_mysql_replication/favourite_products', '{replica}', ver) ORDER BY id SETTINGS allow_nullable_key = 1, index_granularity = 8192";

        MutablePair<DBMetadata.TABLE_ENGINE, String> replicatedReplacingMergeTreeResult = new DBMetadata().getEngineFromResponse(writer.getConnection(), replicatedReplacingMergeTree);

        Assert.assertTrue(replicatedReplacingMergeTreeResult.getRight().equalsIgnoreCase("ver"));
        Assert.assertTrue(replicatedReplacingMergeTreeResult.getLeft().getEngine().equalsIgnoreCase(DBMetadata.TABLE_ENGINE.REPLACING_MERGE_TREE.getEngine()));


    }

    @ParameterizedTest
    @CsvSource({
            "23.2, true",
            "23.1, false",
            "23.2.1, true",
            "23.1.1, false",
            "23.0.1, false",
            "23.3, true",
            "33.1, true",
            "23.10.1, true",
            "23.9.2.47442, true"
    })
    public void testIsRMTVersionSupported(String clickhouseVersion, boolean result) throws SQLException {
        Assert.assertTrue(new DBMetadata().checkIfNewReplacingMergeTree(clickhouseVersion) == result);
    }

    @Test
    public void getTestGetServerTimeZone() {
        String dbHostName = clickHouseContainer.getHost();
        Integer port = clickHouseContainer.getFirstMappedPort();
        String database = "default";
        String userName = clickHouseContainer.getUsername();
        String password = clickHouseContainer.getPassword();
        String tableName = "employees";

        DbWriter writer = new DbWriter(dbHostName, port, database, tableName, userName, password,
                new ClickHouseSinkConnectorConfig(new HashMap<>()), null);
        ZoneId serverTimeZone = new DBMetadata().getServerTimeZone(writer.getConnection());

        Assert.assertTrue(serverTimeZone.toString().equalsIgnoreCase("America/Chicago"));

    }
}
