/**
 * (c) Copyright 2013 WibiData, Inc.
 *
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
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

package org.kiji.schema.filter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.util.NavigableMap;

import com.google.common.base.Preconditions;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.SequenceFile;
import org.apache.hadoop.io.Text;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import org.kiji.mapreduce.KijiMapReduceJob;
import org.kiji.mapreduce.gather.GathererContext;
import org.kiji.mapreduce.gather.KijiGatherJobBuilder;
import org.kiji.mapreduce.gather.KijiGatherer;
import org.kiji.mapreduce.output.SequenceFileMapReduceJobOutput;
import org.kiji.mapreduce.platform.KijiMRPlatformBridge;
import org.kiji.schema.KijiClientTest;
import org.kiji.schema.KijiDataRequest;
import org.kiji.schema.KijiDataRequestBuilder;
import org.kiji.schema.KijiRowData;
import org.kiji.schema.KijiTable;
import org.kiji.schema.layout.KijiTableLayout;
import org.kiji.schema.layout.KijiTableLayouts;
import org.kiji.schema.util.InstanceBuilder;
import org.kiji.schema.util.ResourceUtils;

public class TestRegexQualifierColumnFilter extends KijiClientTest {
  /** Test table, owned by this test. */
  private KijiTable mTable;

  @Before
  public final void setupTestRegexQualifierColumnFilter() throws Exception {
    // Get the test table layouts.
    final KijiTableLayout layout =
        KijiTableLayout.newLayout(KijiTableLayouts.getLayout(KijiTableLayouts.REGEX));

    // Populate the environment.
    new InstanceBuilder(getKiji())
        .withTable("regex_test", layout)
            .withRow("1")
                .withFamily("family")
                    .withQualifier("apple").withValue("cell")
                    .withQualifier("banana").withValue("cell")
                    .withQualifier("carrot").withValue("cell")
                    .withQualifier("aardvark").withValue("cell")
        .build();

    // Fill local variables.
    mTable = getKiji().openTable("regex_test");
  }

  @After
  public final void teardownTestRegexQualifierColumnFilter() throws Exception {
    ResourceUtils.releaseOrLog(mTable);
  }

  /**
   * A test gatherer that outputs all qualifiers from the 'family' family that start with
   * the letter 'a'.
   */
  public static class MyGatherer extends KijiGatherer<Text, NullWritable> {
    @Override
    public KijiDataRequest getDataRequest() {
      KijiDataRequestBuilder builder = KijiDataRequest.builder();
      builder.newColumnsDef().withMaxVersions(10)
          .withFilter(new RegexQualifierColumnFilter("a.*"))
          .addFamily("family");
      return builder.build();
    }

    @Override
    public void gather(KijiRowData input, GathererContext<Text, NullWritable> context)
        throws IOException {
      NavigableMap<String, NavigableMap<Long, CharSequence>> qualifiers =
          input.getValues("family");
      for (String qualifier : qualifiers.keySet()) {
        context.write(new Text(qualifier), NullWritable.get());
      }
    }

    @Override
    public Class<?> getOutputKeyClass() {
      return Text.class;
    }

    @Override
    public Class<?> getOutputValueClass() {
      return NullWritable.class;
    }
  }

  @Test
  public void testRegexQualifierColumnFilter() throws Exception {
    final File outputDir = File.createTempFile("gatherer-output", ".dir", getLocalTempDir());
    Preconditions.checkState(outputDir.delete());
    final int numSplits = 1;

    // Run a gatherer over the test_table.
    final KijiMapReduceJob gatherJob = KijiGatherJobBuilder.create()
        .withConf(getConf())
        .withInputTable(mTable.getURI())
        .withGatherer(MyGatherer.class)
        .withOutput(new SequenceFileMapReduceJobOutput(new Path(outputDir.getPath()), numSplits))
        .build();
    assertTrue(gatherJob.run());

    // Check the output file: two things should be there (apple, aardvark).
    final SequenceFile.Reader reader = KijiMRPlatformBridge.get().newSeqFileReader(
        getConf(), new Path(outputDir.getPath(), "part-m-00000"));
    try {
      final Text key = new Text();
      assertTrue(reader.next(key));
      assertEquals("aardvark", key.toString());
      assertTrue(reader.next(key));
      assertEquals("apple", key.toString());
      assertFalse(reader.next(key));
    } finally {
      reader.close();
    }
  }
}
