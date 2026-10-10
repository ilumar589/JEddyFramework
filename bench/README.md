# Jeddy benchmarks (JMH)

Run all benchmarks, or a subset by regex:

```
./gradlew :bench:jmh
./gradlew :bench:jmh -Pjmh.includes=MathBench
./gradlew :bench:jmh -Pjmh.includes=JobBench "-Pjmh.args=-f 1 -wi 2 -i 3"
```

Results are written to `bench/build/reports/jmh/results.json`. CI runs a short smoke pass on
every push and posts the numbers as a notice annotation on the commit ("JMH results").

| Benchmark | What it measures | Task |
|---|---|---|
| `BaselineBench` | Harness floor on this machine | M1-15 |
| `CollectionsBench` | `IntIntMap` vs `HashMap<Integer,Integer>` lookups; radix sort vs `Arrays.sort` | M1-03 |
| `MathBench` | TRS composition, hierarchy pass, sphere culling for 10k items; `Mat4` multiply | M1-12 |
| `JobBench` | 1M-element `parallelFor` vs sequential (scaling target ≥ 6× on 8 cores); empty dispatch cost | M1-07 |

Vectorization of the math kernels is checked with JMH's `-prof perfasm` (needs `hsdis`), not
`-XX:+PrintCompilation`.
