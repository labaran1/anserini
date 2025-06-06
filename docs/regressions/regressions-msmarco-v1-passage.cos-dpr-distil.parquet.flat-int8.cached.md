# Anserini Regressions: MS MARCO Passage Ranking

**Model**: cosDPR-distil with quantized flat indexes (using cached queries)

This page describes regression experiments, integrated into Anserini's regression testing framework, using the cosDPR-distil model on the [MS MARCO passage ranking task](https://github.com/microsoft/MSMARCO-Passage-Ranking), as described in the following paper:

> Xueguang Ma, Tommaso Teofili, and Jimmy Lin. [Anserini Gets Dense Retrieval: Integration of Lucene's HNSW Indexes.](https://dl.acm.org/doi/10.1145/3583780.3615112) _Proceedings of the 32nd International Conference on Information and Knowledge Management (CIKM 2023)_, October 2023, pages 5366–5370, Birmingham, the United Kingdom.

In these experiments, we are using cached queries (i.e., cached results of query encoding).

The exact configurations for these regressions are stored in [this YAML file](../../src/main/resources/regression/msmarco-v1-passage.cos-dpr-distil.parquet.flat-int8.cached.yaml).
Note that this page is automatically generated from [this template](../../src/main/resources/docgen/templates/msmarco-v1-passage.cos-dpr-distil.parquet.flat-int8.cached.template) as part of Anserini's regression pipeline, so do not modify this page directly; modify the template instead and then run `bin/build.sh` to rebuild the documentation.

From one of our Waterloo servers (e.g., `orca`), the following command will perform the complete regression, end to end:

```bash
python src/main/python/run_regression.py --index --verify --search --regression msmarco-v1-passage.cos-dpr-distil.parquet.flat-int8.cached
```

We make available a version of the MS MARCO Passage Corpus that has already been encoded with cosDPR-distil.

From any machine, the following command will download the corpus and perform the complete regression, end to end:

```bash
python src/main/python/run_regression.py --download --index --verify --search --regression msmarco-v1-passage.cos-dpr-distil.parquet.flat-int8.cached
```

The `run_regression.py` script automates the following steps, but if you want to perform each step manually, simply copy/paste from the commands below and you'll obtain the same regression results.

## Corpus Download

Download the corpus and unpack into `collections/`:

```bash
wget https://rgw.cs.uwaterloo.ca/pyserini/data/msmarco-passage-cos-dpr-distil.parquet.tar -P collections/
tar xvf collections/msmarco-passage-cos-dpr-distil.parquet.tar -C collections/
```

To confirm, `msmarco-passage-cos-dpr-distil.parquet.tar` is 26 GB and has MD5 checksum `b9183de205fbd5c799211c21187179e7`.
With the corpus downloaded, the following command will perform the remaining steps below:

```bash
python src/main/python/run_regression.py --index --verify --search --regression msmarco-v1-passage.cos-dpr-distil.parquet.flat-int8.cached \
  --corpus-path collections/msmarco-passage-cos-dpr-distil.parquet
```

## Indexing

Sample indexing command, building quantized flat indexes:

```bash
bin/run.sh io.anserini.index.IndexFlatDenseVectors \
  -threads 16 \
  -collection ParquetDenseVectorCollection \
  -input /path/to/msmarco-passage-cos-dpr-distil.parquet \
  -generator DenseVectorDocumentGenerator \
  -index indexes/lucene-flat-int8.msmarco-v1-passage.cos-dpr-distil/ \
  -quantize.int8 \
  >& logs/log.msmarco-passage-cos-dpr-distil.parquet &
```

The path `/path/to/msmarco-passage-cos-dpr-distil.parquet/` should point to the corpus downloaded above.
Upon completion, we should have an index with 8,841,823 documents.

## Retrieval

Topics and qrels are stored [here](https://github.com/castorini/anserini-tools/tree/master/topics-and-qrels), which is linked to the Anserini repo as a submodule.
The regression experiments here evaluate on the 6980 dev set questions; see [this page](../../docs/experiments-msmarco-passage.md) for more details.

After indexing has completed, you should be able to perform retrieval as follows using HNSW indexes:

```bash
bin/run.sh io.anserini.search.SearchFlatDenseVectors \
  -index indexes/lucene-flat-int8.msmarco-v1-passage.cos-dpr-distil/ \
  -topics tools/topics-and-qrels/topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.gz \
  -topicReader JsonIntVector \
  -output runs/run.msmarco-passage-cos-dpr-distil.parquet.cos-dpr-distil-flat-int8-cached.topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.txt \
  -hits 1000 -threads 16 &
```

Evaluation can be performed using `trec_eval`:

```bash
bin/trec_eval -c -m map tools/topics-and-qrels/qrels.msmarco-passage.dev-subset.txt runs/run.msmarco-passage-cos-dpr-distil.parquet.cos-dpr-distil-flat-int8-cached.topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.txt
bin/trec_eval -c -M 10 -m recip_rank tools/topics-and-qrels/qrels.msmarco-passage.dev-subset.txt runs/run.msmarco-passage-cos-dpr-distil.parquet.cos-dpr-distil-flat-int8-cached.topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.txt
bin/trec_eval -c -m recall.100 tools/topics-and-qrels/qrels.msmarco-passage.dev-subset.txt runs/run.msmarco-passage-cos-dpr-distil.parquet.cos-dpr-distil-flat-int8-cached.topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.txt
bin/trec_eval -c -m recall.1000 tools/topics-and-qrels/qrels.msmarco-passage.dev-subset.txt runs/run.msmarco-passage-cos-dpr-distil.parquet.cos-dpr-distil-flat-int8-cached.topics.msmarco-passage.dev-subset.cos-dpr-distil.jsonl.txt
```

## Effectiveness

With the above commands, you should be able to reproduce the following results:

| **AP@1000**                                                                                                  | **cosDPR-distil**|
|:-------------------------------------------------------------------------------------------------------------|-----------|
| [MS MARCO Passage: Dev](https://github.com/microsoft/MSMARCO-Passage-Ranking)                                | 0.3942    |
| **RR@10**                                                                                                    | **cosDPR-distil**|
| [MS MARCO Passage: Dev](https://github.com/microsoft/MSMARCO-Passage-Ranking)                                | 0.3896    |
| **R@100**                                                                                                    | **cosDPR-distil**|
| [MS MARCO Passage: Dev](https://github.com/microsoft/MSMARCO-Passage-Ranking)                                | 0.9075    |
| **R@1000**                                                                                                   | **cosDPR-distil**|
| [MS MARCO Passage: Dev](https://github.com/microsoft/MSMARCO-Passage-Ranking)                                | 0.9796    |

The above figures are from running brute-force search with cached queries on non-quantized indexes.
With cached queries on quantized indexes, results may differ slightly.
