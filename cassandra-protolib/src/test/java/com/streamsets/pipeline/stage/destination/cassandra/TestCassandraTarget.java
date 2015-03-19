package com.streamsets.pipeline.stage.destination.cassandra;

import com.datastax.driver.core.Cluster;
import com.datastax.driver.core.ResultSet;
import com.datastax.driver.core.Row;
import com.datastax.driver.core.Session;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.streamsets.pipeline.api.Field;
import com.streamsets.pipeline.api.OnRecordError;
import com.streamsets.pipeline.api.Record;
import com.streamsets.pipeline.api.StageException;
import com.streamsets.pipeline.sdk.RecordCreator;
import com.streamsets.pipeline.sdk.TargetRunner;
import org.junit.Assert;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.thrift.transport.TTransportException;
import org.cassandraunit.utils.EmbeddedCassandraServerHelper;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class TestCassandraTarget {
  private static final Logger LOG = LoggerFactory.getLogger(TestCassandraTarget.class);

  private static final Double EPSILON = 1e-15;

  private static Cluster cluster = null;
  private static Session session = null;

  @BeforeClass
  public static void setUpClass() throws InterruptedException, TTransportException, ConfigurationException, IOException {
    EmbeddedCassandraServerHelper.startEmbeddedCassandra();

    cluster = Cluster.builder().addContactPoint("localhost").withPort(9142).build();
    session = cluster.connect();
  }

  @AfterClass
  public static void tearDownClass() {
    session.close();
    cluster.close();
    EmbeddedCassandraServerHelper.cleanEmbeddedCassandra();
  }

  @Before
  public void setUp() {
    session.execute("CREATE KEYSPACE IF NOT EXISTS test WITH REPLICATION = { 'class' : 'SimpleStrategy', 'replication_factor' : 1 };");

    session.execute(
        "CREATE TABLE IF NOT EXISTS test.trips (" +
            "driver_id int," +
            "trip_id int," +
            "time int," +
            "x double," +
            "y double," +
            "PRIMARY KEY (driver_id, trip_id)" +
            ");"
    );

    session.execute(
        "CREATE TABLE IF NOT EXISTS test.collections (id int, a_list list<int>, a_map map<text, int>, PRIMARY KEY(id));"
    );
  }

  @After
  public void tearDown() {
    session.execute("DROP TABLE IF EXISTS test.collections");
    session.execute("DROP TABLE IF EXISTS test.trips");
    session.execute("DROP KEYSPACE IF EXISTS test");
  }

  @Test
  public void testWriteEmptyBatch() throws InterruptedException, StageException {
    final String tableName = "test.trips";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "driver_id"),
        new CassandraFieldMappingConfig("[1]", "trip_id"),
        new CassandraFieldMappingConfig("[2]", "time"),
        new CassandraFieldMappingConfig("[3]", "x"),
        new CassandraFieldMappingConfig("[4]", "y")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .build();

    List<Record> emptyBatch = ImmutableList.of();
    targetRunner.runInit();
    targetRunner.runWrite(emptyBatch);
    targetRunner.runDestroy();
  }

  @Test
  public void testWriteSingleRecord() throws InterruptedException, StageException {
    final String tableName = "test.trips";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "driver_id"),
        new CassandraFieldMappingConfig("[1]", "trip_id"),
        new CassandraFieldMappingConfig("[2]", "time"),
        new CassandraFieldMappingConfig("[3]", "x"),
        new CassandraFieldMappingConfig("[4]", "y")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .build();

    Record record = RecordCreator.create();
    List<Field> fields = new ArrayList<>();
    fields.add(Field.create(1));
    fields.add(Field.create(2));
    fields.add(Field.create(3));
    fields.add(Field.create(4.0));
    fields.add(Field.create(5.0));
    record.set(Field.create(fields));

    List<Record> singleRecord = ImmutableList.of(record);
    targetRunner.runInit();
    targetRunner.runWrite(singleRecord);

    // Should not be any error records.
    Assert.assertTrue(targetRunner.getErrorRecords().isEmpty());
    Assert.assertTrue(targetRunner.getErrors().isEmpty());

    targetRunner.runDestroy();

    ResultSet resultSet = session.execute("SELECT * FROM test.trips");
    List<Row> allRows = resultSet.all();
    Assert.assertEquals(1, allRows.size());

    Row row = allRows.get(0);
    Assert.assertEquals(1, row.getInt("driver_id"));
    Assert.assertEquals(2, row.getInt("trip_id"));
    Assert.assertEquals(3, row.getInt("time"));
    Assert.assertEquals(4.0, row.getDouble("x"), EPSILON);
    Assert.assertEquals(5.0, row.getDouble("y"), EPSILON);
  }

  @Test
  public void testCollectionTypes() throws InterruptedException, StageException {
    final String tableName = "test.collections";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "id"),
        new CassandraFieldMappingConfig("[1]", "a_list"),
        new CassandraFieldMappingConfig("[2]", "a_map")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .build();

    Record record = RecordCreator.create();
    List<Field> fields = new ArrayList<>();
    fields.add(Field.create(1));
    fields.add(Field.create(ImmutableList.of(Field.create(2))));
    fields.add(Field.create(ImmutableMap.of("3", Field.create(4))));
    record.set(Field.create(fields));

    List<Record> singleRecord = ImmutableList.of(record);
    targetRunner.runInit();
    targetRunner.runWrite(singleRecord);

    // Should not be any error records.
    Assert.assertTrue(targetRunner.getErrorRecords().isEmpty());
    Assert.assertTrue(targetRunner.getErrors().isEmpty());

    targetRunner.runDestroy();

    ResultSet resultSet = session.execute("SELECT * FROM test.collections");
    List<Row> allRows = resultSet.all();
    assert (1 == allRows.size());

    Row row = allRows.get(0);
    assert (row.getInt("id") == 1);
    assert (row.getList("a_list", Integer.class).contains(2));
    assert (row.getMap("a_map", String.class, Integer.class).containsKey("3"));
  }

  @Test
  public void testWriteRecordsOnErrorDiscard() throws Exception {
    final String tableName = "test.trips";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "driver_id"),
        new CassandraFieldMappingConfig("[1]", "trip_id"),
        new CassandraFieldMappingConfig("[2]", "time"),
        new CassandraFieldMappingConfig("[3]", "x"),
        new CassandraFieldMappingConfig("[4]", "y")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .setOnRecordError(OnRecordError.DISCARD)
        .build();

    Record record = RecordCreator.create();
    List<Field> fields = new ArrayList<>();
    fields.add(Field.create(1.3));
    fields.add(Field.create(2));
    fields.add(Field.create(3));
    fields.add(Field.create(4.0));
    fields.add(Field.create(5.0));
    record.set(Field.create(fields));

    List<Record> singleRecord = ImmutableList.of(record);
    targetRunner.runInit();
    targetRunner.runWrite(singleRecord);

    // Should not be any error records if we are discarding.
    Assert.assertTrue(targetRunner.getErrorRecords().isEmpty());
    Assert.assertTrue(targetRunner.getErrors().isEmpty());

    targetRunner.runDestroy();

    ResultSet resultSet = session.execute("SELECT * FROM test.trips");
    List<Row> allRows = resultSet.all();
    Assert.assertEquals(0, allRows.size());
  }

  @Test
  public void testWriteRecordsOnErrorToError() throws Exception {
    final String tableName = "test.trips";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "driver_id"),
        new CassandraFieldMappingConfig("[1]", "trip_id"),
        new CassandraFieldMappingConfig("[2]", "time"),
        new CassandraFieldMappingConfig("[3]", "x"),
        new CassandraFieldMappingConfig("[4]", "y")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .setOnRecordError(OnRecordError.TO_ERROR)
        .build();

    Record record = RecordCreator.create();
    List<Field> fields = new ArrayList<>();
    fields.add(Field.create(1.3));
    fields.add(Field.create(2));
    fields.add(Field.create(3));
    fields.add(Field.create(4.0));
    fields.add(Field.create(5.0));
    record.set(Field.create(fields));

    List<Record> singleRecord = ImmutableList.of(record);
    targetRunner.runInit();
    targetRunner.runWrite(singleRecord);

    // Should have gone to error pipeline
    Assert.assertEquals(1, targetRunner.getErrorRecords().size());
    Assert.assertTrue(targetRunner.getErrors().isEmpty());

    targetRunner.runDestroy();

    ResultSet resultSet = session.execute("SELECT * FROM test.trips");
    List<Row> allRows = resultSet.all();
    Assert.assertEquals(0, allRows.size());
  }

  @Test(expected = StageException.class)
  public void testWriteRecordsOnErrorStopPipeline() throws Exception {
    final String tableName = "test.trips";
    List<CassandraFieldMappingConfig> fieldMappings = ImmutableList.of(
        new CassandraFieldMappingConfig("[0]", "driver_id"),
        new CassandraFieldMappingConfig("[1]", "trip_id"),
        new CassandraFieldMappingConfig("[2]", "time"),
        new CassandraFieldMappingConfig("[3]", "x"),
        new CassandraFieldMappingConfig("[4]", "y")
    );

    TargetRunner targetRunner = new TargetRunner.Builder(CassandraDTarget.class)
        .addConfiguration("contactNodes", ImmutableList.of("localhost"))
        .addConfiguration("useCredentials", false)
        .addConfiguration("qualifiedTableName", tableName)
        .addConfiguration("columnNames", fieldMappings)
        .addConfiguration("port", 9142)
        .setOnRecordError(OnRecordError.STOP_PIPELINE)
        .build();

    Record record = RecordCreator.create();
    List<Field> fields = new ArrayList<>();
    fields.add(Field.create(1.3));
    fields.add(Field.create(2));
    fields.add(Field.create(3));
    fields.add(Field.create(4.0));
    fields.add(Field.create(5.0));
    record.set(Field.create(fields));

    List<Record> singleRecord = ImmutableList.of(record);
    targetRunner.runInit();
    targetRunner.runWrite(singleRecord);

    // Should have gone to error pipeline
    Assert.assertEquals(1, targetRunner.getErrorRecords().size());
    Assert.assertTrue(targetRunner.getErrors().isEmpty());

    targetRunner.runDestroy();

    ResultSet resultSet = session.execute("SELECT * FROM test.trips");
    List<Row> allRows = resultSet.all();
    Assert.assertEquals(0, allRows.size());
  }
}
