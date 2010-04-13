/**
 * Copyright 2010 Karthik Kumar
 * 
 * 
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hbasene.index;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.util.Bytes;

/**
 * An index formed on-top of HBase. This requires a table with the following
 * column families, at the minimum.
 * 
 * <ul>
 * <li>termVector</li>
 * <li>documents</li>
 * </ul>
 * 
 * To create a HBase Table, specific to the index schema, refer to
 * {@link #createLuceneIndexTable(String, HBaseConfiguration, boolean)} .
 */
public class HBaseIndexTransactionLog extends AbstractIndexTransactionLog {

  /**
   * Column Family representing the term vector of a given term ( Field:Term, a
   * field-term combination). The columns are usually the doc-ids, with the
   * values containing the actual term positions in them, if they were
   * available.
   */
  static final byte[] FAMILY_TERMVECTOR = Bytes.toBytes("fm.termVector");

  /**
   * Column family that contains the document's stored content. The columns are
   * usually the field names with the values being the contents of the same.
   */
  static final byte[] FAMILY_DOCUMENTS = Bytes.toBytes("fm.documents");

  /**
   * Column family that contains the mapping from the docId to an integer
   */
  static final byte[] FAMILY_DOC_TO_INT = Bytes.toBytes("fm.doc2int");

  /**
   * Qualifier belonging to family, {@link #FAMILY_DOC_TO_INT} , representing an
   * uniquely increasing integer used by Lucene for internal purposes.
   */
  static final byte[] QUALIFIER_INT = Bytes.toBytes("qual.Int");

  /**
   * Column family that contains the mapping from an integer to a docId.
   */
  static final byte[] FAMILY_INT_TO_DOC = Bytes.toBytes("fm.int2doc");

  /**
   * Qualifier belonging to family {@link #FAMILY_INT_TO_DOC}, representing the
   * document Id as defined by the application.
   */
  static final byte[] QUALIFIER_DOC = Bytes.toBytes("qual.document");

  /**
   * Column family to store the sequence of the counter used by lucene.
   */
  static final byte[] FAMILY_SEQUENCE = Bytes.toBytes("fm.sequence");

  /**
   * Qualifier that represents a sequence.
   */
  static final byte[] QUALIFIER_SEQUENCE = Bytes.toBytes("qual.sequence");

  /**
   * Row Key for a special entry
   */
  static final byte[] ROW_SEQUENCE_ID = Bytes.toBytes("sequenceId");

  /**
   * List of puts to go in.
   */
  private List<Put> puts;

  private final Configuration configuration;

  /**
   * The name of the lucene index under consideration.
   */
  private final String indexName;

  /**
   * Table instance under consideration.
   */
  private HTable table;

  /**
   * Encoder of termPositions
   */
  private final AbstractTermPositionsEncoder termPositionEncoder = new AsciiTermPositionsEncoder();

  public HBaseIndexTransactionLog(final Configuration configuration,
      final String indexName) {
    this.puts = new ArrayList<Put>();
    this.indexName = indexName;
    this.configuration = configuration;
  }

  public String getIndexName() {
    return this.indexName;
  }

  @Override
  public void init() throws IOException {
    this.table = createLuceneIndexTable(indexName, configuration, false);
    this.table.setAutoFlush(false);

    Put put = new Put(ROW_SEQUENCE_ID);
    put.add(FAMILY_SEQUENCE, QUALIFIER_SEQUENCE, Bytes.toBytes(-1L));
    this.table.put(put);
    this.table.flushCommits();
  }

  @Override
  public void close() throws IOException {
    this.table.close();
  }

  @Override
  public void commit() throws IOException {
    this.table.put(puts);
    this.table.flushCommits();
    puts.clear();
  }

  @Override
  public void addTermVectors(String fieldTerm, byte[] docId,
      final List<Integer> termPositionVector) {
    Put put = new Put(Bytes.toBytes(fieldTerm));
    put.add(FAMILY_TERMVECTOR, docId, this.termPositionEncoder
        .encode(termPositionVector));
    this.puts.add(put);
  }

  @Override
  public void storeField(byte[] docId, String fieldName, byte[] value) {
    Put put = new Put(docId);
    put.add(FAMILY_DOCUMENTS, Bytes.toBytes(fieldName), value);
    this.puts.add(put);
  }

  @Override
  public long assignDocId(final byte[] primaryKey) throws IOException {
    HTable table = new HTable(this.configuration, this.indexName);
    long newId = -1;
    try {
      // TODO: What is the impact of a 'fail scenario' with writeToWAL set to
      // false.
      // High-performant, of course, but at what cost ?
      newId = table.incrementColumnValue(ROW_SEQUENCE_ID, FAMILY_SEQUENCE,
          QUALIFIER_SEQUENCE, 1, true);
      if (newId >= Integer.MAX_VALUE) {
        throw new IllegalStateException(
            "Lucene cannot store more than the integer count. Hold on until the limitation of the same is fixed in the Lucene API-s");
      }
      insertBiMap(primaryKey, Bytes.toBytes(newId));
    } finally {
      table.close();
    }

    return (int) newId;
  }

  void insertBiMap(final byte[] primaryKey, final byte[] docId)
      throws IOException {
    Put put = new Put(primaryKey);
    put.add(FAMILY_DOC_TO_INT, QUALIFIER_INT, docId);
    this.table.put(put);

    Put put2 = new Put(docId);
    put2.add(FAMILY_INT_TO_DOC, QUALIFIER_DOC, primaryKey);
    this.table.put(put2);

    this.table.flushCommits();
  }

  /**
   * Drop the given Lucene index table.
   * 
   * @param tableName
   * @param configuration
   * @throws IOException
   */
  public static void dropLuceneIndexTable(final String tableName,
      final Configuration configuration) throws IOException {
    HBaseAdmin admin = new HBaseAdmin(configuration);
    doDropTable(admin, tableName);
  }

  static void doDropTable(final HBaseAdmin admin, final String tableName)
      throws IOException {
    // TODO: The set of operations below are not atomic at all / Currently such
    // guarantee is not provided by HBase. Need to modify HBase RPC/ submit a
    // patch to incorporate the same.
    if (admin.tableExists(tableName)) {
      if (admin.isTableAvailable(tableName)) {
        admin.disableTable(tableName);
      }
      admin.deleteTable(tableName);
    }
  }

  /**
   * Create a table to store lucene indices, with the given name and the
   * configuration.
   * 
   * @param tableName
   *          Name of the table to hold lucene indices.
   * @param configuration
   *          Configuration to hold HBase schema.
   * @param forceRecreate
   *          Drop any old table if it exists by the same name.
   * @return a valid HTable reference to the table of the name, if created
   *         successfully. <br>
   *         null, if table was not created successfully.
   * @throws IOException
   *           in case of any error with regard to the same.
   */
  public static HTable createLuceneIndexTable(final String tableName,
      final Configuration configuration, boolean forceRecreate)
      throws IOException {
    HBaseAdmin admin = new HBaseAdmin(configuration);

    if (admin.tableExists(tableName)) {
      if (!forceRecreate) {
        throw new IllegalArgumentException(
            "Table already exists by the index name " + tableName);
      } else {
        doDropTable(admin, tableName);
      }
    }

    HTableDescriptor tableDescriptor = new HTableDescriptor(Bytes
        .toBytes(tableName));
    tableDescriptor.addFamily(createUniversionLZO(admin,
        HBaseIndexTransactionLog.FAMILY_DOCUMENTS));
    tableDescriptor.addFamily(createUniversionLZO(admin,
        HBaseIndexTransactionLog.FAMILY_TERMVECTOR));
    tableDescriptor.addFamily(createUniversionLZO(admin,
        HBaseIndexTransactionLog.FAMILY_DOC_TO_INT));
    tableDescriptor.addFamily(createUniversionLZO(admin,
        HBaseIndexTransactionLog.FAMILY_INT_TO_DOC));
    tableDescriptor.addFamily(createUniversionLZO(admin,
        HBaseIndexTransactionLog.FAMILY_SEQUENCE));

    admin.createTable(tableDescriptor);
    HTableDescriptor descriptor = admin.getTableDescriptor(Bytes
        .toBytes(tableName));
    return (descriptor != null) ? new HTable(configuration, tableName) : null;
  }

  static HColumnDescriptor createUniversionLZO(final HBaseAdmin admin,
      final byte[] columnFamilyName) {
    HColumnDescriptor desc = new HColumnDescriptor(columnFamilyName);
    // TODO: Is there anyway to check the algorithms supported by HBase in the
    // admin interface ?
    // if (admin.isSupported(Algorithm.LZO)) {
    // desc.setCompressionType(Algorithm.LZO);
    // }
    desc.setMaxVersions(1);
    return desc;
  }
}
