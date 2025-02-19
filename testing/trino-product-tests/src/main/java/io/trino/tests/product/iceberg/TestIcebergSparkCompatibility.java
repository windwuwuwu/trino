/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.tests.product.iceberg;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Streams;
import io.trino.tempto.ProductTest;
import io.trino.tempto.query.QueryResult;
import org.assertj.core.api.Assertions;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.math.BigDecimal;
import java.sql.Date;
import java.sql.Timestamp;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.getOnlyElement;
import static io.trino.tempto.assertions.QueryAssert.Row;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertQueryFailure;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tests.product.TestGroups.ICEBERG;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.iceberg.TestIcebergSparkCompatibility.CreateMode.CREATE_TABLE_AND_INSERT;
import static io.trino.tests.product.iceberg.TestIcebergSparkCompatibility.CreateMode.CREATE_TABLE_AS_SELECT;
import static io.trino.tests.product.iceberg.TestIcebergSparkCompatibility.CreateMode.CREATE_TABLE_WITH_NO_DATA_AND_INSERT;
import static io.trino.tests.product.utils.QueryExecutors.onSpark;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;
import static java.util.Arrays.asList;
import static org.testng.Assert.assertTrue;

public class TestIcebergSparkCompatibility
        extends ProductTest
{
    // see spark-defaults.conf
    private static final String SPARK_CATALOG = "iceberg_test";
    private static final String TRINO_CATALOG = "iceberg";
    private static final String TEST_SCHEMA_NAME = "default";

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "unsupportedStorageFormats")
    public void testTrinoWithUnsupportedFileFormat(StorageFormat storageFormat)
    {
        String tableName = "test_trino_unsupported_file_format_" + storageFormat;
        String trinoTableName = trinoTableName(tableName);
        String sparkTableName = sparkTableName(tableName);

        onSpark().executeQuery(format("CREATE TABLE %s (x bigint) USING ICEBERG TBLPROPERTIES ('write.format.default'='%s')", sparkTableName, storageFormat));
        onSpark().executeQuery(format("INSERT INTO %s VALUES (42)", sparkTableName));

        assertQueryFailure(() -> onTrino().executeQuery("SELECT * FROM " + trinoTableName))
                .hasMessageMatching("Query failed \\(#\\w+\\):\\Q File format not supported for Iceberg: " + storageFormat);
        assertQueryFailure(() -> onTrino().executeQuery(format("INSERT INTO %s VALUES (42)", trinoTableName)))
                .hasMessageMatching("Query failed \\(#\\w+\\):\\Q File format not supported for Iceberg: " + storageFormat);

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testTrinoReadingSparkData(StorageFormat storageFormat)
    {
        String baseTableName = "test_trino_reading_primitive_types_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onSpark().executeQuery(format(
                "CREATE TABLE %s (" +
                        "  _string STRING" +
                        ", _bigint BIGINT" +
                        ", _integer INTEGER" +
                        ", _real REAL" +
                        ", _double DOUBLE" +
                        ", _short_decimal decimal(8,2)" +
                        ", _long_decimal decimal(38,19)" +
                        ", _boolean BOOLEAN" +
                        ", _timestamp TIMESTAMP" +
                        ", _date DATE" +
                        ") USING ICEBERG " +
                        "TBLPROPERTIES ('write.format.default'='%s')",
                sparkTableName,
                storageFormat));

        // Validate queries on an empty table created by Spark
        assertThat(onTrino().executeQuery(format("SELECT * FROM %s", trinoTableName("\"" + baseTableName + "$snapshots\"")))).hasNoRows();
        assertThat(onTrino().executeQuery(format("SELECT * FROM %s", trinoTableName))).hasNoRows();

        onSpark().executeQuery(format(
                "INSERT INTO %s VALUES (" +
                        "'a_string'" +
                        ", 1000000000000000" +
                        ", 1000000000" +
                        ", 10000000.123" +
                        ", 100000000000.123" +
                        ", CAST('123456.78' AS decimal(8,2))" +
                        ", CAST('1234567890123456789.0123456789012345678' AS decimal(38,19))" +
                        ", true" +
                        ", TIMESTAMP '2020-06-28 14:16:00.456'" +
                        ", DATE '1950-06-28'" +
                        ")",
                sparkTableName));

        Row row = row(
                "a_string",
                1000000000000000L,
                1000000000,
                10000000.123F,
                100000000000.123,
                new BigDecimal("123456.78"),
                new BigDecimal("1234567890123456789.0123456789012345678"),
                true,
                Timestamp.valueOf("2020-06-28 14:16:00.456"),
                Date.valueOf("1950-06-28"));

        assertThat(onSpark().executeQuery(
                "SELECT " +
                        "  _string" +
                        ", _bigint" +
                        ", _integer" +
                        ", _real" +
                        ", _double" +
                        ", _short_decimal" +
                        ", _long_decimal" +
                        ", _boolean" +
                        ", _timestamp" +
                        ", _date" +
                        " FROM " + sparkTableName))
                .containsOnly(row);

        assertThat(onTrino().executeQuery(
                "SELECT " +
                        "  _string" +
                        ", _bigint" +
                        ", _integer" +
                        ", _real" +
                        ", _double" +
                        ", _short_decimal" +
                        ", _long_decimal" +
                        ", _boolean" +
                        ", CAST(_timestamp AS TIMESTAMP)" + // TODO test the value without a CAST from timestamp with time zone to timestamp
                        ", _date" +
                        " FROM " + trinoTableName))
                .containsOnly(row);

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "testSparkReadingTrinoDataDataProvider")
    public void testSparkReadingTrinoData(StorageFormat storageFormat, CreateMode createMode)
    {
        String baseTableName = "test_spark_reading_primitive_types_" + storageFormat + "_" + createMode;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        String namedValues = "SELECT " +
                "  VARCHAR 'a_string' _string " +
                ", 1000000000000000 _bigint " +
                ", 1000000000 _integer " +
                ", REAL '10000000.123' _real " +
                ", DOUBLE '100000000000.123' _double " +
                ", DECIMAL '123456.78' _short_decimal " +
                ", DECIMAL '1234567890123456789.0123456789012345678' _long_decimal " +
                ", true _boolean " +
                //", TIMESTAMP '2020-06-28 14:16:00.456' _timestamp " +
                ", TIMESTAMP '2021-08-03 08:32:21.123456 Europe/Warsaw' _timestamptz " +
                ", DATE '1950-06-28' _date " +
                //", TIME '01:23:45.123456' _time " +
                "";

        switch (createMode) {
            case CREATE_TABLE_AND_INSERT:
                onTrino().executeQuery(format(
                        "CREATE TABLE %s (" +
                                "  _string VARCHAR" +
                                ", _bigint BIGINT" +
                                ", _integer INTEGER" +
                                ", _real REAL" +
                                ", _double DOUBLE" +
                                ", _short_decimal decimal(8,2)" +
                                ", _long_decimal decimal(38,19)" +
                                ", _boolean BOOLEAN" +
                                //", _timestamp TIMESTAMP" -- per https://iceberg.apache.org/spark-writes/ Iceberg's timestamp is currently not supported with Spark
                                ", _timestamptz timestamp(6) with time zone" +
                                ", _date DATE" +
                                //", _time time(6)" + -- per https://iceberg.apache.org/spark-writes/ Iceberg's time is currently not supported with Spark
                                ") WITH (format = '%s')",
                        trinoTableName,
                        storageFormat));

                onTrino().executeQuery(format("INSERT INTO %s %s", trinoTableName, namedValues));
                break;

            case CREATE_TABLE_AS_SELECT:
                onTrino().executeQuery(format("CREATE TABLE %s AS %s", trinoTableName, namedValues));
                break;

            case CREATE_TABLE_WITH_NO_DATA_AND_INSERT:
                onTrino().executeQuery(format("CREATE TABLE %s AS %s WITH NO DATA", trinoTableName, namedValues));
                onTrino().executeQuery(format("INSERT INTO %s %s", trinoTableName, namedValues));
                break;

            default:
                throw new UnsupportedOperationException("Unsupported create mode: " + createMode);
        }

        Row row = row(
                "a_string",
                1000000000000000L,
                1000000000,
                10000000.123F,
                100000000000.123,
                new BigDecimal("123456.78"),
                new BigDecimal("1234567890123456789.0123456789012345678"),
                true,
                //"2020-06-28 14:16:00.456",
                "2021-08-03 06:32:21.123456 UTC", // Iceberg's timestamptz stores point in time, without zone
                "1950-06-28"
                // "01:23:45.123456"
        /**/);
        assertThat(onTrino().executeQuery(
                "SELECT " +
                        "  _string" +
                        ", _bigint" +
                        ", _integer" +
                        ", _real" +
                        ", _double" +
                        ", _short_decimal" +
                        ", _long_decimal" +
                        ", _boolean" +
                        // _timestamp OR CAST(_timestamp AS varchar)
                        ", CAST(_timestamptz AS varchar)" +
                        ", CAST(_date AS varchar)" +
                        //", CAST(_time AS varchar)" +
                        " FROM " + trinoTableName))
                .containsOnly(row);

        assertThat(onSpark().executeQuery(
                "SELECT " +
                        "  _string" +
                        ", _bigint" +
                        ", _integer" +
                        ", _real" +
                        ", _double" +
                        ", _short_decimal" +
                        ", _long_decimal" +
                        ", _boolean" +
                        // _timestamp OR CAST(_timestamp AS string)
                        ", CAST(_timestamptz AS string) || ' UTC'" + // Iceberg timestamptz is mapped to Spark timestamp and gets represented without time zone
                        ", CAST(_date AS string)" +
                        // ", CAST(_time AS string)" +
                        " FROM " + sparkTableName))
                .containsOnly(row);

        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @DataProvider
    public Object[][] testSparkReadingTrinoDataDataProvider()
    {
        return Stream.of(storageFormats())
                .map(array -> getOnlyElement(asList(array)))
                .flatMap(storageFormat -> Stream.of(
                        new Object[] {storageFormat, CREATE_TABLE_AND_INSERT},
                        new Object[] {storageFormat, CREATE_TABLE_AS_SELECT},
                        new Object[] {storageFormat, CREATE_TABLE_WITH_NO_DATA_AND_INSERT}))
                .toArray(Object[][]::new);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testSparkCreatesTrinoDrops()
    {
        String baseTableName = "test_spark_creates_trino_drops";
        onSpark().executeQuery(format("CREATE TABLE %s (_string STRING, _bigint BIGINT) USING ICEBERG", sparkTableName(baseTableName)));
        onTrino().executeQuery("DROP TABLE " + trinoTableName(baseTableName));
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testTrinoCreatesSparkDrops()
    {
        String baseTableName = "test_trino_creates_spark_drops";
        onTrino().executeQuery(format("CREATE TABLE %s (_string VARCHAR, _bigint BIGINT)", trinoTableName(baseTableName)));
        onSpark().executeQuery("DROP TABLE " + sparkTableName(baseTableName));
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testSparkReadsTrinoPartitionedTable(StorageFormat storageFormat)
    {
        String baseTableName = "test_spark_reads_trino_partitioned_table_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery(format("CREATE TABLE %s (_string VARCHAR, _bigint BIGINT) WITH (partitioning = ARRAY['_string'], format = '%s')", trinoTableName, storageFormat));
        onTrino().executeQuery(format("INSERT INTO %s VALUES ('a', 1001), ('b', 1002), ('c', 1003)", trinoTableName));

        Row row = row("b", 1002);
        String select = "SELECT * FROM %s WHERE _string = 'b'";
        assertThat(onTrino().executeQuery(format(select, trinoTableName)))
                .containsOnly(row);
        assertThat(onSpark().executeQuery(format(select, sparkTableName)))
                .containsOnly(row);
        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testTrinoReadsSparkPartitionedTable(StorageFormat storageFormat)
    {
        String baseTableName = "test_trino_reads_spark_partitioned_table_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onSpark().executeQuery(format(
                "CREATE TABLE %s (_string STRING, _bigint BIGINT) USING ICEBERG PARTITIONED BY (_string) TBLPROPERTIES ('write.format.default'='%s')",
                sparkTableName,
                storageFormat));
        onSpark().executeQuery(format("INSERT INTO %s VALUES ('a', 1001), ('b', 1002), ('c', 1003)", sparkTableName));

        Row row = row("b", 1002);
        String select = "SELECT * FROM %s WHERE _string = 'b'";
        assertThat(onSpark().executeQuery(format(select, sparkTableName)))
                .containsOnly(row);
        assertThat(onTrino().executeQuery(format(select, trinoTableName)))
                .containsOnly(row);

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testTrinoReadingCompositeSparkData(StorageFormat storageFormat)
    {
        String baseTableName = "test_trino_reading_spark_composites_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onSpark().executeQuery(format("" +
                        "CREATE TABLE %s (" +
                        "  doc_id string,\n" +
                        "  info MAP<STRING, INT>,\n" +
                        "  pets ARRAY<STRING>,\n" +
                        "  user_info STRUCT<name:STRING, surname:STRING, age:INT, gender:STRING>)" +
                        "  USING ICEBERG" +
                        " TBLPROPERTIES ('write.format.default'='%s')",
                sparkTableName, storageFormat));

        onSpark().executeQuery(format(
                "INSERT INTO TABLE %s SELECT 'Doc213', map('age', 28, 'children', 3), array('Dog', 'Cat', 'Pig'), \n" +
                        "named_struct('name', 'Santa', 'surname', 'Claus','age', 1000,'gender', 'MALE')",
                sparkTableName));

        assertThat(onTrino().executeQuery("SELECT doc_id, info['age'], pets[2], user_info.surname FROM " + trinoTableName))
                .containsOnly(row("Doc213", 28, "Cat", "Claus"));

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testSparkReadingCompositeTrinoData(StorageFormat storageFormat)
    {
        String baseTableName = "test_spark_reading_trino_composites_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery(format(
                "CREATE TABLE %s (" +
                        "  doc_id VARCHAR,\n" +
                        "  info MAP(VARCHAR, INTEGER),\n" +
                        "  pets ARRAY(VARCHAR),\n" +
                        "  user_info ROW(name VARCHAR, surname VARCHAR, age INTEGER, gender VARCHAR)) " +
                        "  WITH (format = '%s')",
                trinoTableName,
                storageFormat));

        onTrino().executeQuery(format(
                "INSERT INTO %s VALUES('Doc213', MAP(ARRAY['age', 'children'], ARRAY[28, 3]), ARRAY['Dog', 'Cat', 'Pig'], ROW('Santa', 'Claus', 1000, 'MALE'))",
                trinoTableName));

        assertThat(onSpark().executeQuery("SELECT doc_id, info['age'], pets[1], user_info.surname FROM " + sparkTableName))
                .containsOnly(row("Doc213", 28, "Cat", "Claus"));

        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testTrinoReadingNestedSparkData(StorageFormat storageFormat)
    {
        String baseTableName = "test_trino_reading_nested_spark_data_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onSpark().executeQuery(format(
                "CREATE TABLE %s (\n" +
                        "  doc_id STRING\n" +
                        ", nested_map MAP<STRING, ARRAY<STRUCT<sname: STRING, snumber: INT>>>\n" +
                        ", nested_array ARRAY<MAP<STRING, ARRAY<STRUCT<mname: STRING, mnumber: INT>>>>\n" +
                        ", nested_struct STRUCT<name:STRING, complicated: ARRAY<MAP<STRING, ARRAY<STRUCT<mname: STRING, mnumber: INT>>>>>)\n" +
                        " USING ICEBERG TBLPROPERTIES ('write.format.default'='%s')",
                sparkTableName,
                storageFormat));

        onSpark().executeQuery(format(
                "INSERT INTO TABLE %s SELECT" +
                        "  'Doc213'" +
                        ", map('s1', array(named_struct('sname', 'ASName1', 'snumber', 201), named_struct('sname', 'ASName2', 'snumber', 202)))" +
                        ", array(map('m1', array(named_struct('mname', 'MAS1Name1', 'mnumber', 301), named_struct('mname', 'MAS1Name2', 'mnumber', 302)))" +
                        "       ,map('m2', array(named_struct('mname', 'MAS2Name1', 'mnumber', 401), named_struct('mname', 'MAS2Name2', 'mnumber', 402))))" +
                        ", named_struct('name', 'S1'," +
                        "               'complicated', array(map('m1', array(named_struct('mname', 'SAMA1Name1', 'mnumber', 301), named_struct('mname', 'SAMA1Name2', 'mnumber', 302)))" +
                        "                                   ,map('m2', array(named_struct('mname', 'SAMA2Name1', 'mnumber', 401), named_struct('mname', 'SAMA2Name2', 'mnumber', 402)))))",
                sparkTableName));

        Row row = row("Doc213", "ASName2", 201, "MAS2Name1", 302, "SAMA1Name1", 402);

        assertThat(onSpark().executeQuery(
                "SELECT" +
                        "  doc_id" +
                        ", nested_map['s1'][1].sname" +
                        ", nested_map['s1'][0].snumber" +
                        ", nested_array[1]['m2'][0].mname" +
                        ", nested_array[0]['m1'][1].mnumber" +
                        ", nested_struct.complicated[0]['m1'][0].mname" +
                        ", nested_struct.complicated[1]['m2'][1].mnumber" +
                        "  FROM " + sparkTableName))
                .containsOnly(row);

        assertThat(onTrino().executeQuery("SELECT" +
                "  doc_id" +
                ", nested_map['s1'][2].sname" +
                ", nested_map['s1'][1].snumber" +
                ", nested_array[2]['m2'][1].mname" +
                ", nested_array[1]['m1'][2].mnumber" +
                ", nested_struct.complicated[1]['m1'][1].mname" +
                ", nested_struct.complicated[2]['m2'][2].mnumber" +
                "  FROM " + trinoTableName))
                .containsOnly(row);

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testSparkReadingNestedTrinoData(StorageFormat storageFormat)
    {
        String baseTableName = "test_spark_reading_nested_trino_data_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery(format(
                "CREATE TABLE %s (\n" +
                        "  doc_id VARCHAR\n" +
                        ", nested_map MAP(VARCHAR, ARRAY(ROW(sname VARCHAR, snumber INT)))\n" +
                        ", nested_array ARRAY(MAP(VARCHAR, ARRAY(ROW(mname VARCHAR, mnumber INT))))\n" +
                        ", nested_struct ROW(name VARCHAR, complicated ARRAY(MAP(VARCHAR, ARRAY(ROW(mname VARCHAR, mnumber INT))))))" +
                        "  WITH (format = '%s')",
                trinoTableName,
                storageFormat));

        onTrino().executeQuery(format(
                "INSERT INTO %s SELECT" +
                        "  'Doc213'" +
                        ", map(array['s1'], array[array[row('ASName1', 201), row('ASName2', 202)]])" +
                        ", array[map(array['m1'], array[array[row('MAS1Name1', 301), row('MAS1Name2', 302)]])" +
                        "       ,map(array['m2'], array[array[row('MAS2Name1', 401), row('MAS2Name2', 402)]])]" +
                        ", row('S1'" +
                        "      ,array[map(array['m1'], array[array[row('SAMA1Name1', 301), row('SAMA1Name2', 302)]])" +
                        "            ,map(array['m2'], array[array[row('SAMA2Name1', 401), row('SAMA2Name2', 402)]])])",
                trinoTableName));

        Row row = row("Doc213", "ASName2", 201, "MAS2Name1", 302, "SAMA1Name1", 402);

        assertThat(onTrino().executeQuery(
                "SELECT" +
                        "  doc_id" +
                        ", nested_map['s1'][2].sname" +
                        ", nested_map['s1'][1].snumber" +
                        ", nested_array[2]['m2'][1].mname" +
                        ", nested_array[1]['m1'][2].mnumber" +
                        ", nested_struct.complicated[1]['m1'][1].mname" +
                        ", nested_struct.complicated[2]['m2'][2].mnumber" +
                        "  FROM " + trinoTableName))
                .containsOnly(row);

        QueryResult sparkResult = onSpark().executeQuery(
                "SELECT" +
                        "  doc_id" +
                        ", nested_map['s1'][1].sname" +
                        ", nested_map['s1'][0].snumber" +
                        ", nested_array[1]['m2'][0].mname" +
                        ", nested_array[0]['m1'][1].mnumber" +
                        ", nested_struct.complicated[0]['m1'][0].mname" +
                        ", nested_struct.complicated[1]['m2'][1].mnumber" +
                        "  FROM " + sparkTableName);
        assertThat(sparkResult).containsOnly(row);

        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testIdBasedFieldMapping(StorageFormat storageFormat)
    {
        String baseTableName = "test_schema_evolution_for_nested_fields_" + storageFormat;
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onSpark().executeQuery(format(
                "CREATE TABLE %s (_struct STRUCT<rename:BIGINT, keep:BIGINT, drop_and_add:BIGINT, CaseSensitive:BIGINT>, _partition BIGINT)"
                        + " USING ICEBERG"
                        + " partitioned by (_partition)"
                        + " TBLPROPERTIES ('write.format.default' = '%s')",
                sparkTableName,
                storageFormat));

        onSpark().executeQuery(format(
                "INSERT INTO TABLE %s SELECT "
                        + "named_struct('rename', 1, 'keep', 2, 'drop_and_add', 3, 'CaseSensitive', 4), "
                        + "1001",
                sparkTableName));

        // Alter nested fields using Spark. Trino does not support this yet.
        onSpark().executeQuery(format("ALTER TABLE %s RENAME COLUMN _struct.rename TO renamed", sparkTableName));
        onSpark().executeQuery(format("ALTER TABLE %s DROP COLUMN _struct.drop_and_add", sparkTableName));
        onSpark().executeQuery(format("ALTER TABLE %s ADD COLUMN _struct.drop_and_add BIGINT", sparkTableName));

        if (storageFormat == StorageFormat.PARQUET) {
            // TODO (https://github.com/trinodb/trino/issues/8750) the results should be the same for all storage formats

            // TODO support Row (JAVA_OBJECT) in Tempto and switch to QueryAssert
            Assertions.assertThat(onTrino().executeQuery(format("SELECT * FROM %s", trinoTableName)).rows())
                    .containsOnly(List.of(
                            rowBuilder()
                                    // Rename does not change id
                                    .addField("renamed", null)
                                    .addField("keep", 2L)
                                    .addField("CaseSensitive", 4L)
                                    // Dropping and re-adding changes id
                                    .addField("drop_and_add", 3L)
                                    .build(),
                            1001L));
        }
        else {
            // TODO support Row (JAVA_OBJECT) in Tempto and switch to QueryAssert
            Assertions.assertThat(onTrino().executeQuery(format("SELECT * FROM %s", trinoTableName)).rows())
                    .containsOnly(List.of(
                            rowBuilder()
                                    // Rename does not change id
                                    .addField("renamed", 1L)
                                    .addField("keep", 2L)
                                    .addField("CaseSensitive", 4L)
                                    // Dropping and re-adding changes id
                                    .addField("drop_and_add", null)
                                    .build(),
                            1001L));
        }

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testTrinoShowingSparkCreatedTables()
    {
        String sparkTable = "test_table_listing_for_spark";
        String trinoTable = "test_table_listing_for_trino";

        onSpark().executeQuery(format("CREATE TABLE %s (_integer INTEGER ) USING ICEBERG", sparkTableName(sparkTable)));
        onTrino().executeQuery(format("CREATE TABLE %s (_integer INTEGER )", trinoTableName(trinoTable)));

        assertThat(onTrino().executeQuery(format("SHOW TABLES FROM %s LIKE '%s'", TEST_SCHEMA_NAME, "test_table_listing_for_%")))
                .containsOnly(row(sparkTable), row(trinoTable));

        onSpark().executeQuery("DROP TABLE " + sparkTableName(sparkTable));
        onTrino().executeQuery("DROP TABLE " + trinoTableName(trinoTable));
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS}, dataProvider = "storageFormats")
    public void testTrinoWritingDataWithObjectStorageLocationProvider(StorageFormat storageFormat)
    {
        String baseTableName = "test_object_storage_location_provider_" + storageFormat;
        String sparkTableName = sparkTableName(baseTableName);
        String trinoTableName = trinoTableName(baseTableName);
        String dataPath = "hdfs://hadoop-master:9000/user/hive/warehouse/test_object_storage_location_provider/obj-data";

        onSpark().executeQuery(format("CREATE TABLE %s (_string STRING, _bigint BIGINT) USING ICEBERG TBLPROPERTIES (" +
                        "'write.object-storage.enabled'=true," +
                        "'write.object-storage.path'='%s'," +
                        "'write.format.default' = '%s')",
                sparkTableName, dataPath, storageFormat));
        onTrino().executeQuery(format("INSERT INTO %s VALUES ('a_string', 1000000000000000)", trinoTableName));

        Row result = row("a_string", 1000000000000000L);
        assertThat(onSpark().executeQuery(format("SELECT _string, _bigint FROM %s", sparkTableName))).containsOnly(result);
        assertThat(onTrino().executeQuery(format("SELECT _string, _bigint FROM %s", trinoTableName))).containsOnly(result);

        QueryResult queryResult = onTrino().executeQuery(format("SELECT file_path FROM %s", trinoTableName("\"" + baseTableName + "$files\"")));
        assertThat(queryResult).hasRowsCount(1).hasColumnsCount(1);
        assertTrue(((String) queryResult.row(0).get(0)).contains(dataPath));

        // TODO: support path override in Iceberg table creation: https://github.com/trinodb/trino/issues/8861
        assertQueryFailure(() -> onTrino().executeQuery("DROP TABLE " + trinoTableName))
                .hasMessageContaining("contains Iceberg path override properties and cannot be dropped from Trino");
        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }

    private static final List<String> SPECIAL_CHARACTER_VALUES = ImmutableList.of(
            "with-hyphen",
            "with.dot",
            "with:colon",
            "with/slash",
            "with\\\\backslashes",
            "with\\backslash",
            "with=equal",
            "with?question",
            "with!exclamation",
            "with%percent",
            "with%%percents",
            "with$dollar",
            "with#hash",
            "with*star",
            "with=equals",
            "with\"quote",
            "with'apostrophe",
            "with space",
            " with space prefix",
            "with space suffix ",
            "with€euro",
            "with non-ascii ąęłóść Θ Φ Δ",
            "with👨‍🏭combining character",
            " 👨‍🏭",
            "👨‍🏭 ");

    private static final String TRINO_INSERTED_PARTITION_VALUES =
            Streams.mapWithIndex(SPECIAL_CHARACTER_VALUES.stream(), ((value, index) -> format("(%d, '%s')", index, escapeTrinoString(value))))
                    .collect(Collectors.joining(", "));

    private static final String SPARK_INSERTED_PARTITION_VALUES =
            Streams.mapWithIndex(SPECIAL_CHARACTER_VALUES.stream(), ((value, index) -> format("(%d, '%s')", index, escapeSparkString(value))))
                    .collect(Collectors.joining(", "));

    private static final List<Row> EXPECTED_PARTITION_VALUES =
            Streams.mapWithIndex(SPECIAL_CHARACTER_VALUES.stream(), ((value, index) -> row((int) index, value)))
                    .collect(toImmutableList());

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testStringPartitioningWithSpecialCharactersCtasInTrino()
    {
        String baseTableName = "test_string_partitioning_with_special_chars_ctas_in_trino";
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery("DROP TABLE IF EXISTS " + trinoTableName);
        onTrino().executeQuery(format(
                "CREATE TABLE %s (id, part_col) " +
                        "WITH (partitioning = ARRAY['part_col']) " +
                        "AS VALUES %s",
                trinoTableName,
                TRINO_INSERTED_PARTITION_VALUES));
        assertSelectsOnSpecialCharacters(trinoTableName, sparkTableName);
        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testStringPartitioningWithSpecialCharactersInsertInTrino()
    {
        String baseTableName = "test_string_partitioning_with_special_chars_ctas_in_trino";
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery("DROP TABLE IF EXISTS " + trinoTableName);
        onTrino().executeQuery(format(
                "CREATE TABLE %s (id BIGINT, part_col VARCHAR) WITH (partitioning = ARRAY['part_col'])",
                trinoTableName));
        onTrino().executeQuery(format("INSERT INTO %s VALUES %s", trinoTableName, TRINO_INSERTED_PARTITION_VALUES));
        assertSelectsOnSpecialCharacters(trinoTableName, sparkTableName);
        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    @Test(groups = {ICEBERG, PROFILE_SPECIFIC_TESTS})
    public void testStringPartitioningWithSpecialCharactersInsertInSpark()
    {
        String baseTableName = "test_string_partitioning_with_special_chars_ctas_in_spark";
        String trinoTableName = trinoTableName(baseTableName);
        String sparkTableName = sparkTableName(baseTableName);

        onTrino().executeQuery("DROP TABLE IF EXISTS " + trinoTableName);
        onTrino().executeQuery(format(
                "CREATE TABLE %s (id BIGINT, part_col VARCHAR) WITH (partitioning = ARRAY['part_col'])",
                trinoTableName));
        onSpark().executeQuery(format("INSERT INTO %s VALUES %s", sparkTableName, SPARK_INSERTED_PARTITION_VALUES));
        assertSelectsOnSpecialCharacters(trinoTableName, sparkTableName);
        onTrino().executeQuery("DROP TABLE " + trinoTableName);
    }

    private void assertSelectsOnSpecialCharacters(String trinoTableName, String sparkTableName)
    {
        assertThat(onSpark().executeQuery("SELECT * FROM " + sparkTableName)).containsOnly(EXPECTED_PARTITION_VALUES);
        assertThat(onTrino().executeQuery("SELECT * FROM " + trinoTableName)).containsOnly(EXPECTED_PARTITION_VALUES);
        for (String value : SPECIAL_CHARACTER_VALUES) {
            String trinoValue = escapeTrinoString(value);
            String sparkValue = escapeSparkString(value);
            // Ensure Trino written metadata is readable from Spark and vice versa
            assertThat(onSpark().executeQuery("SELECT count(*) FROM " + sparkTableName + " WHERE part_col = '" + sparkValue + "'"))
                    .withFailMessage("Spark query with predicate containing '" + value + "' contained no matches, expected one")
                    .containsOnly(row(1));
            assertThat(onTrino().executeQuery("SELECT count(*) FROM " + trinoTableName + " WHERE part_col = '" + trinoValue + "'"))
                    .withFailMessage("Trino query with predicate containing '" + value + "' contained no matches, expected one")
                    .containsOnly(row(1));
        }
    }

    private static String escapeSparkString(String value)
    {
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    private static String escapeTrinoString(String value)
    {
        return value.replace("'", "''");
    }

    private static String sparkTableName(String tableName)
    {
        return format("%s.%s.%s", SPARK_CATALOG, TEST_SCHEMA_NAME, tableName);
    }

    private static String trinoTableName(String tableName)
    {
        return format("%s.%s.%s", TRINO_CATALOG, TEST_SCHEMA_NAME, tableName);
    }

    private io.trino.jdbc.Row.Builder rowBuilder()
    {
        return io.trino.jdbc.Row.builder();
    }

    @DataProvider
    public static Object[][] storageFormats()
    {
        return Stream.of(StorageFormat.values())
                .filter(StorageFormat::isSupportedInTrino)
                .map(storageFormat -> new Object[] {storageFormat})
                .toArray(Object[][]::new);
    }

    @DataProvider
    public static Object[][] unsupportedStorageFormats()
    {
        return Stream.of(StorageFormat.values())
                .filter(storageFormat -> !storageFormat.isSupportedInTrino())
                .map(storageFormat -> new Object[] {storageFormat})
                .toArray(Object[][]::new);
    }

    public enum StorageFormat
    {
        PARQUET,
        ORC,
        AVRO,
        /**/;

        public boolean isSupportedInTrino()
        {
            // TODO (https://github.com/trinodb/trino/issues/1324) not supported in Trino yet
            //  - remove testTrinoWithUnsupportedFileFormat once all formats are supported
            return this != AVRO;
        }
    }

    public enum CreateMode
    {
        CREATE_TABLE_AND_INSERT,
        CREATE_TABLE_AS_SELECT,
        CREATE_TABLE_WITH_NO_DATA_AND_INSERT,
    }
}
