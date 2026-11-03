# wordfreq: a first Firmament project

A small `deps.edn` project to try Firmament on. It counts the words in some
text and prints the most frequent:

```
src/wordfreq/core.clj      pure functions; reads a resource (stopwords.txt)
src/wordfreq/report.clj    text and JSON output, using org.clojure/data.json
src/wordfreq/main.clj      -main: wordfreq [--json] [--top N] [--all] [FILE...]
resources/wordfreq/        stopwords.txt, and sample.txt for when no FILE is given
test/wordfreq/             clojure.test tests, and test_runner.clj to run them all
deps.edn                   one Maven dep, and a :test alias adding test/
```

It has one Maven dependency, a resource read with `io/resource`, a `-main`
and tests, which is everything the importer handles today. It runs as
plain Clojure too: `clojure -M -m wordfreq.main`, and `clojure -M:test`
for the tests.

## Import and run

From the repository root, `nix develop` puts `firmament` and `clojure` on
the PATH. Then:

```
cd examples/wordfreq
H=$(firmament import . --main wordfreq.main)
echo $H                      # sha256:…, the whole program: code, resources and jars
firmament ls $H              # the manifest: namespaces, then the resources and jars trees
firmament ls $H wordfreq.core
firmament run $H             # counts the bundled sample.txt
firmament run $H -- --json --top 3 ../../README.md
```

`import` runs `clojure -Spath` to find data.json's jar and copies it into
the store with everything else, so from here on nothing reads the project
directory: `firmament run $H` works from any directory, and so does any
machine holding the store (`firmament push`, `firmament run --from`).

Importing the same files always gives the same hash. Edit a source, import
again, and only that namespace's entry and the trees above it change; the
rest of the program is shared with the old hash.

## Run the tests

Tests are imported as a second program: the `:test` alias adds `test/` to
the paths, and `--main` names the test runner.

```
T=$(firmament import . --alias test --main wordfreq.test-runner)
firmament run $T             # prints clojure.test's report; exits 1 if a test fails
```

`$T` holds the same sources, resources and jars as `$H` plus the test
namespaces, so the two hashes share almost all of their objects, and `$T`
names exactly the code the tests passed on.

For a project of your own, the recipe is the same. If it doesn't have a
test runner namespace, add one like
[test/wordfreq/test_runner.clj](test/wordfreq/test_runner.clj): require the
test namespaces, call `clojure.test/run-tests`, and `System/exit 1` on
failure. Or skip the runner and run the tests from the REPL, as below.

## Use the REPL

`firmament repl HASH` starts a Clojure REPL whose `require` loads from the
store: the source comes from the manifest at that hash, not from disk.

```
firmament repl $T
```

```clojure
;; firmament: loading from sha256:…
(require '[wordfreq.core :as core])
(core/top 3 (core/frequencies-of "a hash is a hash is a tree"))
;; => (["hash" 2] ["tree" 1])

(require 'clojure.java.io)
(clojure.java.io/resource "wordfreq/stopwords.txt")
;; => #object[java.net.URL "firmament:sha256:…!/wordfreq/stopwords.txt"]

(require 'clojure.test 'wordfreq.core-test)
(clojure.test/run-tests 'wordfreq.core-test)
```

Some things to know:

- **The REPL is pinned to its hash.** Editing a file on disk changes
  nothing here, and `(require 'wordfreq.core :reload)` reloads the stored
  version. That is the point: the hash says exactly what's running.
- **Experiment by evaluating forms.** `(in-ns 'wordfreq.core)`, redefine a
  function, call it. A `:reload` puts back the stored version.
- **To keep a change, import again.** Edit the file, `firmament import`,
  and start a REPL on the new hash. Comparing `firmament ls` of the two
  hashes shows which namespaces changed.
- **Use `$T` for testing at the REPL** and `$H` for the program alone; only
  `$T` has the test namespaces.
- **`firmament repl` with no hash** is plain Clojure on the empty program.
- It's `clojure.main`'s REPL on stdin: there's no nREPL server yet, so
  CIDER or Calva can't connect to it.

The REPL doesn't yet track what you load or define as a new hash. That's
where it's heading: a hash for the REPL's whole state, which someone else
could start from.

## Trying other projects

The importer takes Maven jars and `:local/root` jars only: no git deps, no
`:local/root` directories, and only string `:paths`. Two small libraries
whose `deps.edn` fits:

- [clojure/data.csv](https://github.com/clojure/data.csv): one namespace
  under `src/main/clojure`, no dependencies, clojure.test tests under
  `src/test/clojure`.
- [weavejester/medley](https://github.com/weavejester/medley): one `.cljc`
  namespace under `src`, tests under `test`, and only Clojure as a dep.

Neither `deps.edn` has a test alias, so add one before importing, such as
`:aliases {:test {:extra-paths ["src/test/clojure"]}}` for data.csv. Then,
without writing a runner:

```
C=$(firmament import . --alias test)
firmament repl $C
(require 'clojure.test 'clojure.data.csv-test)
(clojure.test/run-tests 'clojure.data.csv-test)
```

A `data_readers.clj` on the paths is imported as a resource, but Clojure
only reads it from the real classpath at startup, so tagged literals it
defines won't work yet.
