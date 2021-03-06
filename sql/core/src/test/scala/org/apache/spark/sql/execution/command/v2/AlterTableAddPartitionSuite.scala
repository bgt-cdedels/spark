/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.execution.command.v2

import org.apache.spark.SparkConf
import org.apache.spark.sql.AnalysisException
import org.apache.spark.sql.catalyst.analysis.{PartitionsAlreadyExistException, ResolvePartitionSpec}
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.connector.{InMemoryPartitionTable, InMemoryPartitionTableCatalog, InMemoryTableCatalog}
import org.apache.spark.sql.connector.catalog.{CatalogV2Implicits, Identifier}
import org.apache.spark.sql.execution.command
import org.apache.spark.sql.test.SharedSparkSession

class AlterTableAddPartitionSuite
  extends command.AlterTableAddPartitionSuiteBase
  with SharedSparkSession {

  import CatalogV2Implicits._

  override def version: String = "V2"
  override def catalog: String = "test_catalog"
  override def defaultUsing: String = "USING _"

  override def sparkConf: SparkConf = super.sparkConf
    .set(s"spark.sql.catalog.$catalog", classOf[InMemoryPartitionTableCatalog].getName)
    .set(s"spark.sql.catalog.non_part_$catalog", classOf[InMemoryTableCatalog].getName)

  override protected def checkLocation(
      t: String,
      spec: TablePartitionSpec,
      expected: String): Unit = {
    val tablePath = t.split('.')
    val catalogName = tablePath.head
    val namespaceWithTable = tablePath.tail
    val namespaces = namespaceWithTable.init
    val tableName = namespaceWithTable.last
    val catalogPlugin = spark.sessionState.catalogManager.catalog(catalogName)
    val partTable = catalogPlugin.asTableCatalog
      .loadTable(Identifier.of(namespaces, tableName))
      .asInstanceOf[InMemoryPartitionTable]
    val ident = ResolvePartitionSpec.convertToPartIdent(spec, partTable.partitionSchema.fields)
    val partMetadata = partTable.loadPartitionMetadata(ident)

    assert(partMetadata.containsKey("location"))
    assert(partMetadata.get("location") === expected)
  }

  test("partition already exists") {
    withNsTable("ns", "tbl") { t =>
      sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing PARTITIONED BY (id)")
      sql(s"ALTER TABLE $t ADD PARTITION (id=2) LOCATION 'loc1'")

      val errMsg = intercept[PartitionsAlreadyExistException] {
        sql(s"ALTER TABLE $t ADD PARTITION (id=1) LOCATION 'loc'" +
          " PARTITION (id=2) LOCATION 'loc1'")
      }.getMessage
      assert(errMsg.contains("The following partitions already exists"))

      sql(s"ALTER TABLE $t ADD IF NOT EXISTS PARTITION (id=1) LOCATION 'loc'" +
        " PARTITION (id=2) LOCATION 'loc1'")
      checkPartitions(t, Map("id" -> "1"), Map("id" -> "2"))
    }
  }

  test("SPARK-33650: add partition into a table which doesn't support partition management") {
    withNsTable("ns", "tbl", s"non_part_$catalog") { t =>
      sql(s"CREATE TABLE $t (id bigint, data string) $defaultUsing")
      val errMsg = intercept[AnalysisException] {
        sql(s"ALTER TABLE $t ADD PARTITION (id=1)")
      }.getMessage
      assert(errMsg.contains(s"Table $t can not alter partitions"))
    }
  }
}
