package com.akolb;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.hive.metastore.api.FieldSchema;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.thrift.TException;
import org.jetbrains.annotations.Nullable;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;

import static com.akolb.HMSClient.makeTable;
import static com.akolb.Main.ENV_SERVER;
import static com.akolb.Main.OPT_DATABASE;
import static com.akolb.Main.OPT_DROP;
import static com.akolb.Main.OPT_NUMBER;
import static com.akolb.Main.OPT_PARTITIONS;
import static com.akolb.Main.OPT_PATTERN;
import static com.akolb.Main.OPT_HOST;
import static com.akolb.Main.OPT_TABLE;
import static com.akolb.Main.OPT_VERBOSE;
import static com.akolb.Main.getServerUri;
import static com.akolb.Main.help;

@State(Scope.Thread)
public class Benchmark {
  private static final Logger LOG = LoggerFactory.getLogger(Benchmark.class);

  private static final String ENV_DB = "HMS_BENCH_DB";
  private static final String ENV_TABLE = "HMS_BENCH_TABLE";


  private HMSClient client;
  private String dbName;
  private String tableName;
  List<FieldSchema> tableSchema;
  List<FieldSchema> partitionSchema;
  private Table table;

  public static void main(String[] args) throws RunnerException, TException {

    org.apache.commons.cli.Options options = new org.apache.commons.cli.Options();
    options.addOption("s", OPT_HOST, true, "HMS Server")
        .addOption("P", OPT_PARTITIONS, true, "partitions list")
        .addOption("h", "help", false, "print this info")
        .addOption("d", OPT_DATABASE, true, "database name (can be regexp for list)")
        .addOption("t", OPT_TABLE, true, "table name (can be regexp for list)")
        .addOption("v", OPT_VERBOSE, false, "verbose mode")
        .addOption("N", OPT_NUMBER, true, "number of instances")
        .addOption("S", OPT_PATTERN, true, "table name pattern for bulk creation")
        .addOption("D", OPT_DROP, false, "drop table if exists");

    CommandLineParser parser = new DefaultParser();

    CommandLine cmd = null;
    try {
      cmd = parser.parse(options, args);
    } catch (ParseException e) {
      help(options);
      System.exit(1);
    }

    if (cmd.hasOption("help")) {
      help(options);
    }

    HMSClient client = null;
    try {
      client = new HMSClient(getServerUri(cmd.getOptionValue(OPT_HOST)));
    } catch (IOException e) {
      LOG.error("Failed to connect to HMS", e);
      System.exit(1);
    } catch (InterruptedException e) {
      LOG.error("Interrupted while connecting to HMS", e);
      System.exit(1);
    }
    String dbName = cmd.getOptionValue(OPT_DATABASE);
    String tableName = cmd.getOptionValue(OPT_TABLE);

    if (tableName != null && tableName.contains(".")) {
      String[] parts = tableName.split("\\.");
      dbName = parts[0];
      tableName = parts[1];
    }

    if (dbName == null || dbName.isEmpty()) {
      throw new RuntimeException("Missing DB name");
    }
    if (tableName == null || tableName.isEmpty()) {
      throw new RuntimeException("Missing Table name");
    }

    LOG.info("Using table '{}.{}'", dbName, tableName);

    if (!client.dbExists(dbName)) {
      client.createDatabase(dbName);
    }

    if (client.tableExists(dbName, tableName)) {
      client.dropTable(dbName, tableName);
    }

    Options opt = new OptionsBuilder()
        .include(Benchmark.class.getSimpleName())
        .forks(1)
        .verbosity(VerboseMode.NORMAL)
        .mode(Mode.AverageTime)
        .build();

    new Runner(opt).run();
  }

  @Setup
  public void setup() throws MetaException, IOException, InterruptedException {
    Map<String, String> env = System.getenv();
    tableName = env.get(ENV_TABLE);
    dbName = env.get(ENV_DB);
    String server = env.get(ENV_SERVER);
    System.out.println("Using server " + server + " table '" + dbName + "." + tableName + "'");
    client = new HMSClient(getServerUri(server));
    table = makeTable(dbName, tableName, null, null);
  }

  @TearDown
  public void teardown() throws Exception {
    if (client != null) {
      client.close();
    }
  }

  @org.openjdk.jmh.annotations.Benchmark
  public void createTable() throws TException {
    client.createTable(table);
    client.dropTable(dbName, tableName);
  }

  @org.openjdk.jmh.annotations.Benchmark
  public void getAllDatabases() {
    client.getAllDatabasesNoException();
  }

  @org.openjdk.jmh.annotations.Benchmark
  public void getAllTables() {
    client.getAllTablesNoException(dbName);
  }

}
