/*
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
package org.apache.orc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Random;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.exec.vector.BytesColumnVector;
import org.apache.hadoop.hive.ql.exec.vector.VectorizedRowBatch;

import org.apache.hadoop.hive.ql.io.sarg.PredicateLeaf;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgument;
import org.apache.hadoop.hive.ql.io.sarg.SearchArgumentFactory;
import org.apache.orc.impl.OrcIndex;
import org.apache.orc.impl.OutStream;
import org.apache.orc.impl.RecordReaderImpl;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;

public class TestStringDictionary {

  private Path workDir = new Path(System.getProperty("test.tmp.dir", "target" + File.separator + "test"
      + File.separator + "tmp"));

  private Configuration conf;
  private FileSystem fs;
  private Path testFilePath;

  @Rule
  public TestName testCaseName = new TestName();

  @Before
  public void openFileSystem() throws Exception {
    conf = new Configuration();
    fs = FileSystem.getLocal(conf);
    testFilePath = new Path(workDir, "TestStringDictionary." + testCaseName.getMethodName() + ".orc");
    fs.delete(testFilePath, false);
  }

  @Test
  public void testTooManyDistinct() throws Exception {
    TypeDescription schema = TypeDescription.createString();

    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema)
                                   .compress(CompressionKind.NONE)
                                   .bufferSize(10000));
    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector col = (BytesColumnVector) batch.cols[0];
    for (int i = 0; i < 20000; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      col.setVal(batch.size++, String.valueOf(i).getBytes());
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    RecordReader rows = reader.rows();
    batch = reader.getSchema().createRowBatch();
    col = (BytesColumnVector) batch.cols[0];
    int idx = 0;
    while (rows.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        assertEquals(String.valueOf(idx++), col.toString(r));
      }
    }

    // make sure the encoding type is correct
    for (StripeInformation stripe : reader.getStripes()) {
      // hacky but does the job, this casting will work as long this test resides
      // within the same package as ORC reader
      OrcProto.StripeFooter footer = ((RecordReaderImpl) rows).readStripeFooter(stripe);
      for (int i = 0; i < footer.getColumnsCount(); ++i) {
        OrcProto.ColumnEncoding encoding = footer.getColumns(i);
        assertEquals(OrcProto.ColumnEncoding.Kind.DIRECT_V2, encoding.getKind());
      }
    }
  }

  @Test
  public void testHalfDistinct() throws Exception {
    TypeDescription schema = TypeDescription.createString();

    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema).compress(CompressionKind.NONE)
            .bufferSize(10000));
    Random rand = new Random(123);
    int[] input = new int[20000];
    for (int i = 0; i < 20000; i++) {
      input[i] = rand.nextInt(10000);
    }

    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector col = (BytesColumnVector) batch.cols[0];
    for (int i = 0; i < 20000; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      col.setVal(batch.size++, String.valueOf(input[i]).getBytes());
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    RecordReader rows = reader.rows();
    batch = reader.getSchema().createRowBatch();
    col = (BytesColumnVector) batch.cols[0];
    int idx = 0;
    while (rows.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        assertEquals(String.valueOf(input[idx++]), col.toString(r));
      }
    }

    // make sure the encoding type is correct
    for (StripeInformation stripe : reader.getStripes()) {
      // hacky but does the job, this casting will work as long this test resides
      // within the same package as ORC reader
      OrcProto.StripeFooter footer = ((RecordReaderImpl) rows).readStripeFooter(stripe);
      for (int i = 0; i < footer.getColumnsCount(); ++i) {
        OrcProto.ColumnEncoding encoding = footer.getColumns(i);
        assertEquals(OrcProto.ColumnEncoding.Kind.DICTIONARY_V2, encoding.getKind());
      }
    }
  }

  @Test
  public void testTooManyDistinctCheckDisabled() throws Exception {
    TypeDescription schema = TypeDescription.createString();

    conf.setBoolean(OrcConf.ROW_INDEX_STRIDE_DICTIONARY_CHECK.getAttribute(), false);
    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema).compress(CompressionKind.NONE)
            .bufferSize(10000));
    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector string = (BytesColumnVector) batch.cols[0];
    for (int i = 0; i < 20000; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      string.setVal(batch.size++, String.valueOf(i).getBytes());
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    RecordReader rows = reader.rows();
    batch = reader.getSchema().createRowBatch();
    string = (BytesColumnVector) batch.cols[0];
    int idx = 0;
    while (rows.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        assertEquals(String.valueOf(idx++), string.toString(r));
      }
    }

    // make sure the encoding type is correct
    for (StripeInformation stripe : reader.getStripes()) {
      // hacky but does the job, this casting will work as long this test resides
      // within the same package as ORC reader
      OrcProto.StripeFooter footer = ((RecordReaderImpl) rows).readStripeFooter(stripe);
      for (int i = 0; i < footer.getColumnsCount(); ++i) {
        OrcProto.ColumnEncoding encoding = footer.getColumns(i);
        assertEquals(OrcProto.ColumnEncoding.Kind.DIRECT_V2, encoding.getKind());
      }
    }
  }

  @Test
  public void testHalfDistinctCheckDisabled() throws Exception {
    TypeDescription schema = TypeDescription.createString();

    conf.setBoolean(OrcConf.ROW_INDEX_STRIDE_DICTIONARY_CHECK.getAttribute(),
        false);
    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema)
            .compress(CompressionKind.NONE)
            .bufferSize(10000));
    Random rand = new Random(123);
    int[] input = new int[20000];
    for (int i = 0; i < 20000; i++) {
      input[i] = rand.nextInt(10000);
    }
    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector string = (BytesColumnVector) batch.cols[0];
    for (int i = 0; i < 20000; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      string.setVal(batch.size++, String.valueOf(input[i]).getBytes());
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    RecordReader rows = reader.rows();
    batch = reader.getSchema().createRowBatch();
    string = (BytesColumnVector) batch.cols[0];
    int idx = 0;
    while (rows.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        assertEquals(String.valueOf(input[idx++]), string.toString(r));
      }
    }

    // make sure the encoding type is correct
    for (StripeInformation stripe : reader.getStripes()) {
      // hacky but does the job, this casting will work as long this test resides
      // within the same package as ORC reader
      OrcProto.StripeFooter footer = ((RecordReaderImpl) rows).readStripeFooter(stripe);
      for (int i = 0; i < footer.getColumnsCount(); ++i) {
        OrcProto.ColumnEncoding encoding = footer.getColumns(i);
        assertEquals(OrcProto.ColumnEncoding.Kind.DICTIONARY_V2, encoding.getKind());
      }
    }
  }

  @Test
  public void testTooManyDistinctV11AlwaysDictionary() throws Exception {
    TypeDescription schema = TypeDescription.createString();

    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema)
            .compress(CompressionKind.NONE)
            .version(OrcFile.Version.V_0_11).bufferSize(10000));
    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector string = (BytesColumnVector) batch.cols[0];
    for (int i = 0; i < 20000; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      string.setVal(batch.size++, String.valueOf(i).getBytes());
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    batch = reader.getSchema().createRowBatch();
    string = (BytesColumnVector) batch.cols[0];
    RecordReader rows = reader.rows();
    int idx = 0;
    while (rows.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        assertEquals(String.valueOf(idx++), string.toString(r));
      }
    }

    // make sure the encoding type is correct
    for (StripeInformation stripe : reader.getStripes()) {
      // hacky but does the job, this casting will work as long this test resides
      // within the same package as ORC reader
      OrcProto.StripeFooter footer = ((RecordReaderImpl) rows).readStripeFooter(stripe);
      for (int i = 0; i < footer.getColumnsCount(); ++i) {
        OrcProto.ColumnEncoding encoding = footer.getColumns(i);
        assertEquals(OrcProto.ColumnEncoding.Kind.DICTIONARY, encoding.getKind());
      }
    }

  }

  @Test
  public void testForcedNonDictionary() throws Exception {
    // Set the row stride to 16k so that it is a multiple of the batch size
    final int INDEX_STRIDE = 16 * 1024;
    final int NUM_BATCHES = 50;
    // Explicitly turn off dictionary encoding.
    OrcConf.DICTIONARY_KEY_SIZE_THRESHOLD.setDouble(conf, 0);
    TypeDescription schema = TypeDescription.fromString("struct<str:string>");
    try (Writer writer = OrcFile.createWriter(testFilePath,
           OrcFile.writerOptions(conf)
               .setSchema(schema)
               .rowIndexStride(INDEX_STRIDE))) {
      // Write 50 batches where each batch has a single value for str.
      VectorizedRowBatch batch = schema.createRowBatch();
      BytesColumnVector col = (BytesColumnVector) batch.cols[0];
      for(int b=0; b < NUM_BATCHES; ++b) {
        batch.reset();
        batch.size = 1024;
        col.setVal(0, ("Value for " + b).getBytes(StandardCharsets.UTF_8));
        col.isRepeating = true;
        writer.addRowBatch(batch);
      }
    }

    try (Reader reader = OrcFile.createReader(testFilePath,
                                              OrcFile.readerOptions(conf));
         RecordReaderImpl rows = (RecordReaderImpl) reader.rows()) {
      VectorizedRowBatch batch = reader.getSchema().createRowBatch();
      BytesColumnVector col = (BytesColumnVector) batch.cols[0];

      // Get the index for the str column
      OrcProto.RowIndex index = rows.readRowIndex(0, null, null)
                                      .getRowGroupIndex()[1];
      // We assume that it fits in a single stripe
      assertEquals(1, reader.getStripes().size());
      // There are 4 entries, because ceil(NUM_BATCHES * 1024 / INDEX_STRIDE) = 4.
      assertEquals(4, index.getEntryCount());
      for(int e=0; e < index.getEntryCount(); ++e) {
        OrcProto.RowIndexEntry entry = index.getEntry(e);
        // For a string column with direct encoding, compression & no nulls, we
        // should have 5 positions in each entry.
        assertEquals("position count entry " + e, 5, entry.getPositionsCount());
        // make sure we can seek and get the right data
        int row = e * INDEX_STRIDE;
        rows.seekToRow(row);
        assertTrue("entry " + e, rows.nextBatch(batch));
        assertEquals("entry " + e, 1024, batch.size);
        assertEquals("entry " + e, true, col.noNulls);
        assertEquals("entry " + e, "Value for " + (row / 1024), col.toString(0));
      }
    }
  }

  /**
   * That when we disable dictionaries, we don't get broken row indexes.
   */
  @Test
  public void testRowIndex() throws Exception {
    TypeDescription schema =
        TypeDescription.fromString("struct<str:string>");
    // turn off the dictionaries
    OrcConf.DICTIONARY_KEY_SIZE_THRESHOLD.setDouble(conf, 0);
    Writer writer = OrcFile.createWriter(
        testFilePath,
        OrcFile.writerOptions(conf).setSchema(schema).rowIndexStride(4 * 1024));

    VectorizedRowBatch batch = schema.createRowBatch();
    BytesColumnVector strVector = (BytesColumnVector) batch.cols[0];

    for (int i = 0; i < 32 * 1024; i++) {
      if (batch.size == batch.getMaxSize()) {
        writer.addRowBatch(batch);
        batch.reset();
      }
      byte[] value = String.format("row %06d", i).getBytes(StandardCharsets.UTF_8);
      strVector.setRef(batch.size, value, 0, value.length);
      ++batch.size;
    }
    writer.addRowBatch(batch);
    writer.close();

    Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs));
    SearchArgument sarg = SearchArgumentFactory.newBuilder()
        .lessThan("str", PredicateLeaf.Type.STRING, "row 001000")
        .build();
    RecordReader recordReader = reader.rows(reader.options().searchArgument(sarg, null));
    batch = reader.getSchema().createRowBatch();
    strVector = (BytesColumnVector) batch.cols[0];
    long base = 0;
    while (recordReader.nextBatch(batch)) {
      for(int r=0; r < batch.size; ++r) {
        String value = String.format("row %06d", r + base);
        assertEquals("row " + (r + base), value, strVector.toString(r));
      }
      base += batch.size;
    }
    // We should only read the first row group.
    assertEquals(4 * 1024, base);
  }

  /**
   * Test that files written before ORC-569 are read correctly.
   */
  @Test
  public void testRowIndexPreORC569() throws Exception {
    testFilePath = new Path(System.getProperty("example.dir"), "TestStringDictionary.testRowIndex.orc");
    SearchArgument sarg = SearchArgumentFactory.newBuilder()
        .lessThan("str", PredicateLeaf.Type.STRING, "row 001000")
        .build();
    try (Reader reader = OrcFile.createReader(testFilePath, OrcFile.readerOptions(conf).filesystem(fs))) {
      try (RecordReader recordReader = reader.rows(reader.options().searchArgument(sarg, null))) {
        VectorizedRowBatch batch = reader.getSchema().createRowBatch();
        BytesColumnVector strVector = (BytesColumnVector) batch.cols[0];
        long base = 0;
        while (recordReader.nextBatch(batch)) {
          for (int r = 0; r < batch.size; ++r) {
            String value = String.format("row %06d", r + base);
            assertEquals("row " + (r + base), value, strVector.toString(r));
          }
          base += batch.size;
        }
        // We should only read the first row group.
        assertEquals(4 * 1024, base);
      }

      try (RecordReader recordReader = reader.rows()) {
        VectorizedRowBatch batch = reader.getSchema().createRowBatch();
        recordReader.seekToRow(4 * 1024);
        assertTrue(recordReader.nextBatch(batch));
        recordReader.seekToRow(0);
        assertTrue(recordReader.nextBatch(batch));
      }
    }
  }
}
