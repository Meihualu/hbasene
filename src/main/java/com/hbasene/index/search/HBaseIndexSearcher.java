/**
 * Copyright 2010 Karthik Kumar
 *
 * Based off the original code by Lucandra project, (C): Jake Luciani
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
package com.hbasene.index.search;

import java.io.IOException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.hbase.client.HTablePool;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.search.Filter;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopFieldDocs;
import org.apache.lucene.search.Weight;

import com.hbasene.index.HBaseIndexReader;
import com.hbasene.index.HBaseneConstants;

/**
 * IndexSearcher
 */
public class HBaseIndexSearcher extends IndexSearcher implements
    HBaseneConstants {

  private static final Log LOG = LogFactory.getLog(HBaseIndexSearcher.class);

  private final HTablePool tablePool;

  private final String indexName;

  public HBaseIndexSearcher(HBaseIndexReader indexReader)
      throws CorruptIndexException, IOException {
    super(indexReader);
    this.tablePool = indexReader.getTablePool();
    this.indexName = indexReader.getIndexName();
  }

  @Override
  public TopFieldDocs search(Weight weight, Filter filter, final int nDocs,
      Sort sort, boolean fillFields) throws IOException {
    SortField[] fields = sort.getSort();
    if (fields.length > 1) {
      throw new IllegalArgumentException(
          "Multiple Sort fields not supported at the moment");
    }
    if (fields[0] == SortField.FIELD_SCORE) {
      return super.search(weight, filter, nDocs, sort, fillFields);
    } else {
      return doSearch(weight, filter, nDocs, sort, fillFields);
    }
  }

  TopFieldDocs doSearch(final Weight weight, Filter filter, int nDocs,
      Sort sort, boolean fillFields) throws IOException {
    HBaseTopFieldCollector topFieldCollector = new HBaseTopFieldCollector(this.tablePool, this.indexName, nDocs, sort.getSort());
    search(weight, filter, topFieldCollector);
    return (TopFieldDocs) topFieldCollector.topDocs();
  }



}
