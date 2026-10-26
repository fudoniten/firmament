# Python reference implementation

An independent implementation of the `firmament-0` format, used to produce
the golden vectors in [test-resources/vectors/](../test-resources/vectors/)
and to cross-check the Clojure implementation. It favours being obviously
correct over being fast, and has no dependencies beyond the Python 3.10+
standard library.

| Module | Spec |
| --- | --- |
| `firmament_ref/edn.py` | Canonical EDN encoder and strict decoder ([encoding.md](../docs/spec/encoding.md)) |
| `firmament_ref/hashing.py` | Blob and structured-object hashes ([objects.md](../docs/spec/objects.md)) |
| `firmament_ref/chunking.py` | Gear table, cut points, chunk index ([chunking.md](../docs/spec/chunking.md)) |
| `firmament_ref/gen.py` | Generated test data |
| `gen_vectors.py` | Writes or checks the vector files |

It deliberately shares no code with the Clojure implementation; the point is
that two implementations written separately agree.

## Commands

From the repository root:

```
python3 reference/gen_vectors.py           # write test-resources/vectors/
python3 reference/gen_vectors.py --check   # fail if the files are stale (about 20 s)
cd reference && python3 -m unittest discover -s tests -t .
```
