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

package org.apache.spark.sql.hudi.command

import org.apache.hudi.common.table.HoodieTableMetaClient

import org.apache.spark.sql.{Row, SparkSession}
import org.apache.spark.sql.catalyst.TableIdentifier
import org.apache.spark.sql.catalyst.catalog.CatalogTypes.TablePartitionSpec
import org.apache.spark.sql.catalyst.catalog.HoodieCatalogTable
import org.apache.spark.sql.execution.command.TruncateTableCommand

import scala.util.control.NonFatal

/**
 * Command for truncate hudi table.
 */
class TruncateHoodieTableCommand(
   tableIdentifier: TableIdentifier,
   partitionSpec: Option[TablePartitionSpec])
  extends TruncateTableCommand(tableIdentifier, partitionSpec) {

  override def run(sparkSession: SparkSession): Seq[Row] = {
    val hoodieCatalogTable = HoodieCatalogTable(sparkSession, tableIdentifier)
    val properties = hoodieCatalogTable.tableConfig.getProps

    try {
      // Delete all data in the table directory
      super.run(sparkSession)
    } catch {
      // TruncateTableCommand will delete the related directories first, and then refresh the table.
      // It will fail when refresh table, because the hudi meta directory(.hoodie) has been deleted at the first step.
      // So here ignore this failure, and refresh table later.
      case NonFatal(_) =>
    }

    // If we have not specified the partition, truncate will delete all the data in the table path
    // include the hoodi.properties. In this case we should reInit the table.
    if (partitionSpec.isEmpty) {
      val hadoopConf = sparkSession.sessionState.newHadoopConf()
      // ReInit hoodie.properties
      HoodieTableMetaClient.withPropertyBuilder()
        .fromProperties(properties)
        .initTable(hadoopConf, hoodieCatalogTable.tableLocation)
    }

    // After deleting the data, refresh the table to make sure we don't keep around a stale
    // file relation in the metastore cache and cached table data in the cache manager.
    sparkSession.catalog.refreshTable(hoodieCatalogTable.table.identifier.quotedString)
    Seq.empty[Row]
  }
}
