/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.hudi.table.format.cow.vector;

import org.apache.flink.table.data.ArrayData;
import org.apache.flink.table.data.DecimalData;
import org.apache.flink.table.data.MapData;
import org.apache.flink.table.data.RowData;
import org.apache.flink.table.data.TimestampData;
import org.apache.flink.table.data.vector.ArrayColumnVector;
import org.apache.flink.table.data.vector.BooleanColumnVector;
import org.apache.flink.table.data.vector.ByteColumnVector;
import org.apache.flink.table.data.vector.BytesColumnVector;
import org.apache.flink.table.data.vector.ColumnVector;
import org.apache.flink.table.data.vector.DecimalColumnVector;
import org.apache.flink.table.data.vector.DoubleColumnVector;
import org.apache.flink.table.data.vector.FloatColumnVector;
import org.apache.flink.table.data.vector.IntColumnVector;
import org.apache.flink.table.data.vector.LongColumnVector;
import org.apache.flink.table.data.vector.ShortColumnVector;
import org.apache.flink.table.data.vector.TimestampColumnVector;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;

/**
 * A VectorizedColumnBatch is a set of rows, organized with each column as a vector. It is the unit
 * of query execution, organized to minimize the cost per row.
 *
 * <p>{@code VectorizedColumnBatch}s are influenced by Apache Hive VectorizedRowBatch.
 *
 * <p>References {@code org.apache.flink.table.data.vector.VectorizedColumnBatch} to include FLINK-15390.
 */
public class VectorizedColumnBatch implements Serializable {
  private static final long serialVersionUID = 1L;

  /**
   * This number is carefully chosen to minimize overhead and typically allows one
   * VectorizedColumnBatch to fit in cache.
   */
  public static final int DEFAULT_SIZE = 2048;

  private int numRows;
  public final ColumnVector[] columns;

  public VectorizedColumnBatch(ColumnVector[] vectors) {
    this.columns = vectors;
  }

  public void setNumRows(int numRows) {
    this.numRows = numRows;
  }

  public int getNumRows() {
    return numRows;
  }

  public int getArity() {
    return columns.length;
  }

  public boolean isNullAt(int rowId, int colId) {
    return columns[colId].isNullAt(rowId);
  }

  public boolean getBoolean(int rowId, int colId) {
    return ((BooleanColumnVector) columns[colId]).getBoolean(rowId);
  }

  public byte getByte(int rowId, int colId) {
    return ((ByteColumnVector) columns[colId]).getByte(rowId);
  }

  public short getShort(int rowId, int colId) {
    return ((ShortColumnVector) columns[colId]).getShort(rowId);
  }

  public int getInt(int rowId, int colId) {
    return ((IntColumnVector) columns[colId]).getInt(rowId);
  }

  public long getLong(int rowId, int colId) {
    return ((LongColumnVector) columns[colId]).getLong(rowId);
  }

  public float getFloat(int rowId, int colId) {
    return ((FloatColumnVector) columns[colId]).getFloat(rowId);
  }

  public double getDouble(int rowId, int colId) {
    return ((DoubleColumnVector) columns[colId]).getDouble(rowId);
  }

  public BytesColumnVector.Bytes getByteArray(int rowId, int colId) {
    return ((BytesColumnVector) columns[colId]).getBytes(rowId);
  }

  private byte[] getBytes(int rowId, int colId) {
    BytesColumnVector.Bytes byteArray = getByteArray(rowId, colId);
    if (byteArray.len == byteArray.data.length) {
      return byteArray.data;
    } else {
      return byteArray.getBytes();
    }
  }

  public String getString(int rowId, int colId) {
    BytesColumnVector.Bytes byteArray = getByteArray(rowId, colId);
    return new String(byteArray.data, byteArray.offset, byteArray.len, StandardCharsets.UTF_8);
  }

  public DecimalData getDecimal(int rowId, int colId, int precision, int scale) {
    return ((DecimalColumnVector) (columns[colId])).getDecimal(rowId, precision, scale);
  }

  public TimestampData getTimestamp(int rowId, int colId, int precision) {
    return ((TimestampColumnVector) (columns[colId])).getTimestamp(rowId, precision);
  }

  public ArrayData getArray(int rowId, int colId) {
    return ((ArrayColumnVector) columns[colId]).getArray(rowId);
  }

  public RowData getRow(int rowId, int colId) {
    return ((RowColumnVector) columns[colId]).getRow(rowId);
  }

  public MapData getMap(int rowId, int colId) {
    return ((MapColumnVector) columns[colId]).getMap(rowId);
  }
}

