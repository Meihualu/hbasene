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
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import junit.framework.Assert;

import org.apache.log4j.Logger;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.CorruptIndexException;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.util.Version;
import org.testng.annotations.Test;

public class TestHBaseMetaIndexReader extends AbstractHBaseneTest {

  private static final Logger LOG = Logger
      .getLogger(TestHBaseMetaIndexReader.class.getName());

  private static final String[] AIRPORTS = { "NYC", "JFK", "EWR", "SEA", "SFO",
      "OAK", "SJC" };

  private final Map<String, List<Integer>> airportMap = new HashMap<String, List<Integer>>();

  private HBaseIndexMetaReader metaReader;

  @Override
  protected void doSetupDerived() {
    this.metaReader = new HBaseIndexMetaReader(this.indexReader);
  }

  @Override
  protected void doInitDocs() throws CorruptIndexException, IOException {
    for (int i = 100; i >= 0; --i) {
      Document doc = this.getDocument(i);
      indexWriter.addDocument(doc, new StandardAnalyzer(Version.LUCENE_30));
    }
  }

  private Document getDocument(int i) {
    Document doc = new Document();
    doc.add(new Field("id", "doc" + i, Field.Store.YES, Field.Index.NO));
    int randomIndex = (int) (Math.random() * 7.0f);
    doc.add(new Field("airport", AIRPORTS[randomIndex], Field.Store.NO,
        Field.Index.ANALYZED_NO_NORMS));
    doc.add(new Field("searchterm", Math.random() > 0.5f ? "always" : "never",
        Field.Store.NO, Field.Index.ANALYZED_NO_NORMS));
    recordRandomIndex(100 - i, randomIndex);
    return doc;
  }

  private void recordRandomIndex(final int docIndex, final int airportIndex) {
    List<Integer> docs = airportMap.get(AIRPORTS[airportIndex]);
    if (docs == null) {
      docs = new LinkedList<Integer>();
      airportMap.put(AIRPORTS[airportIndex], docs);
    }
    docs.add(docIndex);
  }

  @Test
  public void testSortField() throws IOException {
    LOG.info(this.airportMap.toString());
    IndexSearcher searcher = new IndexSearcher(this.indexReader);
    try {
      TopDocs docs = searcher.search(new TermQuery(new Term("searchterm",
          "always")), 90);
      LOG.info("Total results are " + docs.scoreDocs.length);
      this.printScoreDocs(docs.scoreDocs, "Original Order ");
      ScoreDoc[] result = this.metaReader.sort(docs.scoreDocs, "airport");
      this.printScoreDocs(result, "Sorted Order");

      assertSortOrder(result);
    } finally {
      searcher.close();
    }
  }

  @Test(enabled = false)
  public void testNonExistentSortField() throws IOException {
    LOG.info(this.airportMap.toString());
    IndexSearcher searcher = new IndexSearcher(this.indexReader);
    try {
      TopDocs docs = searcher.search(new TermQuery(new Term("searchterm",
          "always")), 90);
      LOG.info("Total results are " + docs.scoreDocs.length);
      this.printScoreDocs(docs.scoreDocs, "Original Order ");
      ScoreDoc[] result = this.metaReader.sort(docs.scoreDocs, "airport1");
      //TODO: This method should throw an exception for an invalid field to be sorted.

    } finally {
      searcher.close();
    }
  }

  
  void printScoreDocs(final ScoreDoc[] scoreDocs, final String prefix) {
    List<Integer> originalOrder = new ArrayList<Integer>();
    for (ScoreDoc scoreDoc : scoreDocs) {
      originalOrder.add(scoreDoc.doc);
    }
    LOG.info(prefix + " is " + originalOrder);
  }

  void assertSortOrder(final ScoreDoc[] result) {
    Map<Integer, String> reverseMap = new HashMap<Integer, String>();
    for (final Map.Entry<String, List<Integer>> entry : this.airportMap
        .entrySet()) {
      for (final Integer docId : entry.getValue()) {
        reverseMap.put(docId, entry.getKey());
      }
    }
    String previousAirport = "000";
    for (final ScoreDoc scoreDoc : result) {
      String currentAirport = reverseMap.get(scoreDoc.doc);
      Assert.assertTrue(currentAirport + " vs " + previousAirport,
          currentAirport.compareTo(previousAirport) >= 0);
      previousAirport = currentAirport;
    }
  }

}
