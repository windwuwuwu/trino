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
package io.trino.tests.product.hive;

import io.trino.tempto.ProductTest;
import org.testng.annotations.Test;

import java.util.List;

import static io.trino.tempto.assertions.QueryAssert.Row;
import static io.trino.tempto.assertions.QueryAssert.Row.row;
import static io.trino.tempto.assertions.QueryAssert.assertQueryFailure;
import static io.trino.tempto.assertions.QueryAssert.assertThat;
import static io.trino.tests.product.TestGroups.HIVE_SPARK_BUCKETING;
import static io.trino.tests.product.TestGroups.PROFILE_SPECIFIC_TESTS;
import static io.trino.tests.product.hive.util.TemporaryHiveTable.randomTableSuffix;
import static io.trino.tests.product.utils.QueryExecutors.onSpark;
import static io.trino.tests.product.utils.QueryExecutors.onTrino;
import static java.lang.String.format;

public class TestHiveSparkCompatibility
        extends ProductTest
{
    // see spark-defaults.conf
    private static final String TRINO_CATALOG = "hive";

    @Test(groups = {HIVE_SPARK_BUCKETING, PROFILE_SPECIFIC_TESTS})
    public void testTrinoReadingTableCreatedByNativeSpark()
    {
        // Spark tables can be created using native Spark code or by going through Hive code
        // This tests the native Spark path.
        String sparkTableName = "test_trino_reading_spark_native_buckets_" + randomTableSuffix();
        String trinoTableName = format("%s.default.%s", TRINO_CATALOG, sparkTableName);

        onSpark().executeQuery(format(
                "CREATE TABLE `default`.`%s` (\n" +
                        "  `a_string` STRING,\n" +
                        "  `a_bigint` BIGINT,\n" +
                        "  `an_integer` INT,\n" +
                        "  `a_real` FLOAT,\n" +
                        "  `a_double` DOUBLE,\n" +
                        "  `a_boolean` BOOLEAN)\n" +
                        "USING ORC\n" +
                        "CLUSTERED BY (a_string)\n" +
                        "INTO 4 BUCKETS\n" +
                        // Hive requires "original" files of transactional tables to conform to the bucketed tables naming pattern
                        // We can disable transactions or add another pattern to BackgroundHiveSplitLoader
                        "TBLPROPERTIES ('transactional'='false')",
                sparkTableName));

        onSpark().executeQuery(format(
                "INSERT INTO %s VALUES " +
                        "('one', 1000000000000000, 1000000000, 10000000.123, 100000000000.123, true)" +
                        ", ('two', -1000000000000000, -1000000000, -10000000.123, -100000000000.123, false)" +
                        ", ('three', 2000000000000000, 2000000000, 20000000.123, 200000000000.123, true)" +
                        ", ('four', -2000000000000000, -2000000000, -20000000.123, -200000000000.123, false)",
                sparkTableName));

        List<Row> expected = List.of(
                row("one", 1000000000000000L, 1000000000, 10000000.123F, 100000000000.123, true),
                row("two", -1000000000000000L, -1000000000, -10000000.123F, -100000000000.123, false),
                row("three", 2000000000000000L, 2000000000, 20000000.123F, 200000000000.123, true),
                row("four", -2000000000000000L, -2000000000, -20000000.123F, -200000000000.123, false));
        assertThat(onSpark().executeQuery("SELECT a_string, a_bigint, an_integer, a_real, a_double, a_boolean FROM " + sparkTableName))
                .containsOnly(expected);
        assertThat(onTrino().executeQuery("SELECT a_string, a_bigint, an_integer, a_real, a_double, a_boolean FROM " + trinoTableName))
                .containsOnly(expected);

        assertThat(onTrino().executeQuery("SHOW CREATE TABLE " + trinoTableName))
                .containsOnly(row(format(
                        "CREATE TABLE %s (\n" +
                                "   a_string varchar,\n" +
                                "   a_bigint bigint,\n" +
                                "   an_integer integer,\n" +
                                "   a_real real,\n" +
                                "   a_double double,\n" +
                                "   a_boolean boolean\n" +
                                ")\n" +
                                "WITH (\n" +
                                "   format = 'ORC'\n" +
                                ")",
                        trinoTableName)));

        assertQueryFailure(() -> onTrino().executeQuery("SELECT a_string, a_bigint, an_integer, a_real, a_double, a_boolean, \"$bucket\" FROM " + trinoTableName))
                .hasMessageContaining("Column '$bucket' cannot be resolved");

        onSpark().executeQuery("DROP TABLE " + sparkTableName);
    }
}
