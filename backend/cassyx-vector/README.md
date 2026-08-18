# cassyx-vector

`vector<float, N>` columns, SAI index lifecycle and ANN query generation. **Plain Java — no Spring.**

Entry point: `io.cassyx.vector.api.VectorFactory`.

## Usage

```java
import io.cassyx.vector.api.*;
import java.util.*;

VectorColumn column = new VectorColumn("demo", "docs", "embedding", 3);
System.out.println(column.cqlType());   // vector<float, 3>

String ddl = VectorFactory.saiIndexManager().createIndexCql(
    "demo", "docs", "embedding", "docs_embedding_idx", SimilarityFunction.COSINE, Map.of());
// CREATE CUSTOM INDEX IF NOT EXISTS docs_embedding_idx ON demo.docs (embedding)
//   USING 'StorageAttachedIndex' WITH OPTIONS = {'similarity_function': 'cosine'}

String ann = VectorFactory.annQueryBuilder().build(new AnnQuery(
    column, List.of(0.1f, 0.2f, 0.3f), 5,
    List.of("similarity_cosine(embedding, [0.1, 0.2, 0.3]) AS score"),
    Map.of("lang", "= 'en'")));
// SELECT *, similarity_cosine(...) AS score FROM demo.docs
//   WHERE lang = 'en' ORDER BY embedding ANN OF [0.1, 0.2, 0.3] LIMIT 5
```

Generated CQL is always shown in the "Preview CQL" pane and is editable before execution — DDL is
never executed silently.

## Version constraints

`java-driver-core` **4.19.0** is required: 4.16 introduced `CqlVector`, and only 4.19.0 emits correct
vector DDL from `describe` (CASSJAVA-2) and supports vectors in QueryBuilder (JAVA-3118). Guard
CASSANDRA-19333 (a `VectorCodec` corruption bug) with a 1536-dimension round-trip fidelity test.
Vector/ANN needs Cassandra 5.x or Astra — gate the feature on `Capability.VECTOR_ANN`.
