/*
 * Anserini: A Lucene toolkit for reproducible information retrieval research
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.anserini.search;

import ai.onnxruntime.OrtException;
import io.anserini.encoder.dense.DenseEncoder;
import io.anserini.index.Constants;
import io.anserini.index.IndexReaderUtils;
import io.anserini.search.query.VectorQueryGenerator;

import org.apache.commons.lang3.time.DurationFormatUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.kohsuke.args4j.Option;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

public class FlatDenseSearcher<K extends Comparable<K>> extends BaseSearcher<K> implements AutoCloseable {
  // These are the default tie-breaking rules for documents that end up with the same score with respect to a query.
  // For most collections, docids are strings, and we break ties by lexicographic sort order.
  public static final Sort BREAK_SCORE_TIES_BY_DOCID =
      new Sort(SortField.FIELD_SCORE, new SortField(Constants.ID, SortField.Type.STRING_VAL));

  private static final Logger LOG = LogManager.getLogger(FlatDenseSearcher.class);

  /**
   * This class holds arguments for configuring the flat searcher for dense vectors. Note that, explicitly, there are
   * no arguments that define queries and outputs, since this class is meant to be called interactively.
   */
  public static class Args extends BaseSearchArgs {
    @Option(name = "-generator", metaVar = "[class]", usage = "QueryGenerator to use.")
    public String queryGenerator = "VectorQueryGenerator";

    @Option(name ="-encoder", metaVar = "[encoder]", usage = "Dense encoder to use.")
    public String encoder = null;
  }

  private final IndexReader reader;
  private final VectorQueryGenerator generator;
  private final DenseEncoder encoder;
  // Dummy, but needed for KnnFloatVectorQuery
  private final int DUMMY_EF_SEARCH = 1000;

  public FlatDenseSearcher(Args args) {
    super(args);

    Path indexPath = IndexReaderUtils.getIndex(args.index);

    try {
      this.reader = DirectoryReader.open(FSDirectory.open(indexPath));
    } catch (IOException e) {
      throw new IllegalArgumentException(String.format("\"%s\" does not appear to be a valid index.", args.index));
    }

    setIndexSearcher(new IndexSearcher(this.reader));

    try {
      this.generator = (VectorQueryGenerator) Class
          .forName(String.format("io.anserini.search.query.%s", args.queryGenerator))
          .getConstructor().newInstance();
    } catch (Exception e) {
      throw new IllegalArgumentException(String.format("Unable to load QueryGenerator \"%s\".", args.queryGenerator));
    }

    if (args.encoder != null) {
      try {
        // If Encoder is part of the name, strip ".Encoder" suffix to normalize the name
        // This supports both implementations based on a large amount of older code: we can use both.
        String encoderName = args.encoder.endsWith("Encoder") ?
            args.encoder.substring(0, args.encoder.length() - "Encoder".length()) :
            args.encoder;

        encoder = (DenseEncoder) Class
            .forName(String.format("io.anserini.encoder.dense.%sEncoder", encoderName))
            .getConstructor().newInstance();
      } catch (Exception e) {
        throw new IllegalArgumentException(String.format("Unable to load Encoder \"%s\".", args.encoder));
      }
    } else {
      encoder = null;
    }
  }

  /**
   * Searches the collection in batch using multiple threads.
   *
   * @param queries list of queries
   * @param qids list of unique query ids
   * @param k number of hits
   * @param threads number of threads
   * @return a map of query id to search results
   */
  public SortedMap<K, ScoredDoc[]> batch_search(List<String> queries, List<K> qids, int k, int threads) {
    final SortedMap<K, ScoredDoc[]> results = new ConcurrentSkipListMap<>();
    final AtomicInteger cnt = new AtomicInteger();
    final long start = System.nanoTime();
    List<Callable<Void>> tasks = new ArrayList<>(qids.size());

    assert qids.size() == queries.size();
    for (int i = 0; i < qids.size(); i++) {
      K qid = qids.get(i);
      String queryString = queries.get(i);

      // This is the per-query execution, in parallel.
      tasks.add(() -> {
        try {
          results.put(qid, search(qid, queryString, k));
        } catch (IOException e) {
          throw new CompletionException(e);
        }

        int n = cnt.incrementAndGet();
        if (n % 100 == 0) {
          LOG.info(String.format("%d queries processed", n));
        }
        return null;
      });
    }

    try (ExecutorService executor = Executors.newWorkStealingPool(threads)) {
      // block until all tasks are completed
      executor.invokeAll(tasks);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    final long durationMillis = TimeUnit.MILLISECONDS.convert(System.nanoTime() - start, TimeUnit.NANOSECONDS);

    LOG.info("{} queries processed in {}{}", queries.size(),
        DurationFormatUtils.formatDuration(durationMillis, "HH:mm:ss"),
        String.format(" = ~%.2f q/s", queries.size() / (durationMillis / 1000.0)));

    return results;
  }

  /**
   * Searches the collection with a query vector.
   *
   * @param query query vector
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(float[] query, int k) throws IOException {
    return search(null, query, k);
  }

  /**
   * Searches the collection with a query vector.
   *
   * @param qid query id
   * @param query query vector
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(@Nullable K qid, float[] query, int k) throws IOException {
    KnnFloatVectorQuery vectorQuery = new KnnFloatVectorQuery(Constants.VECTOR, query, DUMMY_EF_SEARCH);
    TopDocs topDocs = getIndexSearcher().search(vectorQuery, k, BREAK_SCORE_TIES_BY_DOCID, true);

    return super.processLuceneTopDocs(qid, topDocs);
  }

  /**
   * Searches the collection with a string query that will be encoded by the underlying encoder.
   *
   * @param query query
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(String query, int k) throws IOException {
    return search(null, query, k);
  }

  /**
   * Searches the collection with a string query that will be encoded by the underlying encoder.
   *
   * @param qid query id
   * @param query query
   * @param k number of hits
   * @return array of search results
   * @throws IOException if error encountered during search
   */
  public ScoredDoc[] search(@Nullable K qid, String query, int k) throws IOException {
    if (encoder != null) {
      try {
        return search(qid, encoder.encode(query), k);
      } catch (OrtException e) {
        throw new RuntimeException("Error encoding query.");
      }
    }

    KnnFloatVectorQuery vectorQuery = generator.buildQuery(Constants.VECTOR, query, DUMMY_EF_SEARCH);
    TopDocs topDocs = getIndexSearcher().search(vectorQuery, k, BREAK_SCORE_TIES_BY_DOCID, true);

    return super.processLuceneTopDocs(qid, topDocs);
  }

  @Override
  public void close() throws IOException {
    reader.close();
    if (encoder != null) {
      encoder.close();
    }
  }
}
