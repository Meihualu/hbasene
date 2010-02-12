/**
 * Copyright 2007 The Apache Software Foundation
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
package org.apache.hbase.lucene.mapreduce;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.Random;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriter.MaxFieldLength;
import org.apache.lucene.search.Similarity;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

/**
 * Create a local index, unwrap Lucene documents created by reduce, add them to
 * the index, and copy the index to the destination.
 */
public class IndexOutputFormat extends
    FileOutputFormat<ImmutableBytesWritable, LuceneDocumentWrapper> {

  static final Log LOG = LogFactory.getLog(IndexOutputFormat.class);

  /** Random generator. */
  private Random random = new Random();

  /**
   * Returns the record writer.
   * 
   * @param context
   *          The current task context.
   * @return The record writer.
   * @throws IOException
   *           When there is an issue with the writer.
   * @see org.apache.hadoop.mapreduce.lib.output.FileOutputFormat#getRecordWriter(org.apache.hadoop.mapreduce.TaskAttemptContext)
   */
  @Override
  public RecordWriter<ImmutableBytesWritable, LuceneDocumentWrapper> getRecordWriter(
      TaskAttemptContext context) throws IOException {

    final Path perm = new Path(FileOutputFormat.getOutputPath(context),
        FileOutputFormat.getUniqueFile(context, "part", ""));
    // null for "dirsProp" means no predefined directories
    final Path temp = context.getConfiguration().getLocalPath(
        "mapred.local.dir", "index/_" + Integer.toString(random.nextInt()));

    LOG.info("To index into " + perm);
    FileSystem fs = FileSystem.get(context.getConfiguration());
    // delete old, if any
    fs.delete(perm, true);

    final IndexConfiguration indexConf = new IndexConfiguration();
    String content = context.getConfiguration().get("hbase.index.conf");
    if (content != null) {
      indexConf.addFromXML(content);
    }

    String analyzerName = indexConf.getAnalyzerName();
    Analyzer analyzer;
    try {
      Class<? extends Analyzer> analyzerClass = Class.forName(analyzerName)
          .asSubclass(Analyzer.class);
      Constructor<? extends Analyzer> analyzerCtor = analyzerClass
          .getConstructor(Version.class);

      analyzer = analyzerCtor.newInstance(Version.LUCENE_30);
    } catch (Exception e) {
      throw new IOException("Error in creating an analyzer object "
          + analyzerName);
    }

    // build locally first
    final IndexWriter writer = new IndexWriter(FSDirectory.open(new File(fs
        .startLocalOutput(perm, temp).toString())), analyzer, true,
        MaxFieldLength.LIMITED);

    // no delete, so no need for maxBufferedDeleteTerms
    writer.setMaxBufferedDocs(indexConf.getMaxBufferedDocs());
    writer.setMaxFieldLength(indexConf.getMaxFieldLength());
    writer.setMaxMergeDocs(indexConf.getMaxMergeDocs());
    writer.setMergeFactor(indexConf.getMergeFactor());
    String similarityName = indexConf.getSimilarityName();
    if (similarityName != null) {
      try {
        Class<? extends Similarity> similarityClass = Class.forName(
            similarityName).asSubclass(Similarity.class);
        Constructor<? extends Similarity> ctor = similarityClass
            .getConstructor(Version.class);
        Similarity similarity = ctor.newInstance(Version.LUCENE_30);
        writer.setSimilarity(similarity);
      } catch (Exception e) {
        throw new IOException("Error in creating a similarity object "
            + similarityName);
      }
    }
    writer.setUseCompoundFile(indexConf.isUseCompoundFile());
    return new IndexRecordWriter(context, fs, writer, indexConf, perm, temp);
  }

}
