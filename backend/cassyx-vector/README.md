# cassyx-vector

`vector<float, N>` columns, SAI index lifecycle and ANN query generation. **Plain Java — no Spring.**

Entry point: `io.cassyx.vector.api.VectorFactory`. Everything works with nothing but a `CqlSession`
(plan §2.1).

## Usage

```java
import io.cassyx.vector.api.*;
import java.util.*;

VectorService vectors = VectorFactory.vectorService();
VectorColumn embedding = vectors.vectorColumn(session, "demo", "doc_embeddings", "embedding");
// embedding.cqlType()  -> vector<float, 1536>
// embedding.annCapable() -> true when an SAI index backs it

// --- SAI lifecycle: create / check / alter / drop, on vector AND scalar columns
SaiIndexManager indexes = VectorFactory.saiIndexManager();

String ddl = indexes.createIndexCql("demo", "doc_embeddings",
    SaiIndexDefinition.builder("doc_embeddings_ann", "embedding")
        .similarityFunction(SimilarityFunction.COSINE)
        .build());
// CREATE CUSTOM INDEX IF NOT EXISTS doc_embeddings_ann ON demo.doc_embeddings (embedding)
//   USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'cosine'}

SaiIndexStatus status = indexes.status(session, "demo", "doc_embeddings", "doc_embeddings_ann");
// per-node build state; SAI builds per replica, so a coordinator-only view lies

List<String> alter = indexes.alterIndexCql("demo", "doc_embeddings", newDefinition);
// Cassandra has no ALTER INDEX: a drop + create pair, returned together for preview

// --- ANN, including hybrid SAI + ANN in one statement
AnnQuery query = AnnQuery.builder(embedding, queryVector)
    .limit(3)
    .select(List.of("doc_id", "title"))
    .where(AnnPredicate.equalTo("category", "release-notes"))
    .score(SimilarityFunction.COSINE)
    .build();

AnnQueryPreview preview = VectorFactory.annQueryBuilder().preview(query);
preview.cql();             // full statement, vector inlined - copy-pasteable into cqlsh
preview.abbreviatedCql();  // SELECT … ORDER BY embedding ANN OF [… 1536 floats …] LIMIT 3
preview.warnings();        // e.g. "column has no SAI index", "predicate column has no SAI index"

session.execute(VectorFactory.annQueryBuilder().statement(query));  // binds the vector, not inlined
```

Generated CQL is always shown in the "Preview CQL" pane and is editable before execution — DDL is
never executed silently.

## Display and export helpers

`VectorEncoding` converts between `CqlVector`, `List<Float>`, primitive arrays and the export
encodings: **JSON arrays for CSV/JSON, a native list type for Parquet** (plan §6). `sparkline()`
down-samples to 64 points so a grid cell never ships 1536 floats to the browser.

`VectorService.compare()` returns magnitudes plus scores under each `similarity_*` function, using
Cassandra's own normalisation — `(1 + cos)/2`, `(1 + dot)/2`, `1/(1 + d²)`. `AnnQueryIT` asserts
parity against a live cluster, because a number the user cannot reproduce in cqlsh is worse than no
number.

## Version constraints

`java-driver-core` **4.19.0** is required:

| Reference | Why it matters |
| --- | --- |
| driver ≥ 4.16 | `CqlVector` exists (implements `Iterable` plus `List`-like methods) |
| **JAVA-3118** | vector support in Schema Builder / QueryBuilder |
| **CASSJAVA-2** | `describe` emitted invalid CQL for vector columns on older 4.x |
| **CASSANDRA-19333** | data-corruption bug in `VectorCodec` — guarded by `VectorCodecFidelityTest` (unit, no container) and `VectorRoundTripIT` (through a real cluster), both asserting **element-by-element raw-bit equality over a full 1536-dimension vector**. Do not weaken these to a size or magnitude check; the corrupt codec passed both. |

## Cluster constraints found by the integration suite

- **`source_model` is not portable.** Astra and DSE accept it; Apache Cassandra 5.0 rejects it with
  `Properties specified [source_model] are not understood by StorageAttachedIndex`. Offer the field
  only where the cluster supports it. Pinned by `SaiIndexLifecycleIT`.
- **`similarity_*` cannot infer the type of two bind markers.** `similarity_cosine(?, ?)` fails with
  *"use type casts to disambiguate"*; `similarity_cosine(<column>, ?)` binds fine. The generated
  projections always pass the column first, so this never bites in practice.

## Capability gating (plan §7.1)

Vector/ANN needs Cassandra 5.x or Astra; SAI additionally DSE 6.8+. **Neither exists on Amazon
Keyspaces or ScyllaDB.** `VectorCapabilities.from(ClusterCapabilities)` derives both gates and
`explain(...)` produces the tooltip text naming the detected flavour and version.
