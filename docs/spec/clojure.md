# Clojure Layer (draft)

This layer is built on the core objects and is **not frozen yet**. Phase 2
settled the import side (namespace trees, manifests, analysis, projection),
implemented in `firmament.clojure`; loading (Phase 3) may still change it.

## Namespace trees

The store holds namespaces, not paths. A namespace name splits on `.`, and
each segment is a tree entry, starting from the namespace root:
`my.app.server` → `"my"` → `"app"` → `"server"`.

Segments are the namespace's own names, unmunged: `my-app`, never `my_app`.

An entry may carry:

| Key | Points to |
| --- | --- |
| `:source/clj` | File: JVM source (raw bytes of the `.clj` file) |
| `:source/cljs` | File: ClojureScript source |
| `:source/cljc` | File: portable source |
| `:children` | Tree: child namespaces |

A file is a blob, or a chunked file for sources over 256 KiB.

A namespace tree holds code only. Each source's analysis lives at the same
path in a second tree, the manifest's `:analyses`, whose entries carry
`:analysis/clj`, `:analysis/cljs` or `:analysis/cljc` (and `:children`), one
for each source; see [Analysis meta-object](#analysis-meta-object).

So `my.app` and `my.app.server` coexist, and one namespace may have sources for
several platforms. The loader picks the source the way Clojure would: on the
JVM, `:source/clj` before `:source/cljc`.

## Manifest

A loadable program is a manifest object:

```clojure
{:type       :clj/manifest
 :namespaces #h "sha256:..."   ; namespace tree root: sources only
 :analyses   #h "sha256:..."   ; the same layout, with each source's analysis
 :resources  #h "sha256:..."   ; tree of non-source files
 :jars       #h "sha256:..."   ; tree of jars, each an opaque file
 :tags       {:clj/main "my.app"}}
```

`:namespaces`, `:analyses`, `:resources` and `:jars` are always present; an empty one is
the empty tree. `:tags` is optional (the importer doesn't set any yet).
Resources are a plain directory tree (`{:link …}` and `{:children …}`
entries) laid out by their path below a source root. Jars are entries named
by their file name, such as `"clojure-1.12.5.jar"`.

`*ns-root*` is bound to the manifest hash. This differs slightly from
[design.md](../design.md), where the manifest and namespace root are the same
tree: keeping them separate stops a namespace named `resources` from
colliding with the resources subtree.

## Import

`firmament import DIR` imports a `deps.edn` project:

- **Every file under the project's `:paths`** (default `["src"]`) is either a
  namespace source or a resource. A `.clj`, `.cljs` or `.cljc` file whose
  first form is an `ns` form is a source; anything else, including
  `data_readers.clj` and other `.clj` files without an `ns` form, is a
  resource.
- **Sources go under the namespace their `ns` form declares**, not their file
  path. This avoids ambiguity from munging (`my_app.clj` could be `my-app` or
  `my_app`) and settles what happens when path and `ns` disagree. Two files
  giving the same namespace the same platform are an error.
- **Source is stored as raw bytes**, never normalized.
- **The `ns` form is read, never evaluated**: `*read-eval*` is off, unknown
  tagged literals are kept as data, and reader conditionals are resolved
  separately for each platform the file is read for (`:clj` for `.clj`,
  `:cljs` for `.cljs`, both for `.cljc`; `:default` branches apply to both).
- **Resources** keep their path below whichever source root they're in. The
  same path under two roots is an error.
- **Jars** come from the classpath given with `--classpath` (the output of
  `clojure -Spath`) and from `:local/root` deps that point at a `.jar`.
  Classpath directories must be the project's own `:paths`; other
  directories (git or local-root projects) aren't supported yet. The importer
  never resolves dependencies itself.

Importing the same project always gives the same manifest hash.

## Projection

`firmament export MANIFEST DIR` writes a manifest back out as a project:

```
DIR/
  deps.edn       {:paths ["src" "resources"]
                  :deps {jars/<name> {:local/root "jars/<name>.jar"} ...}}
  src/           each source at the path Clojure looks for it
                 (my.app-server → src/my/app_server.clj)
  resources/     the resources tree
  jars/          the jars
```

Importing a projection gives the same manifest hash as the original. Two
namespaces whose sources would land on the same path (`my.a-b` and `my.a_b`)
can't be projected.

## Loading

Our own `require` can take namespace names and look them up in the tree
directly. Existing code and libraries call `clojure.core/require`, which asks
the classloader for a path such as `my/app_server.clj`. A compatibility
classloader translates that request straight back into a namespace
(`my.app-server`) and platform, and serves the matching source. Paths are
handled only at this edge and never reach the store.

## Analysis meta-object

```clojure
{:type     :clj/analysis
 :source   #h "sha256:..."   ; the source file analyzed
 :analyzer #h "sha256:..."   ; the analyzer that produced this
 :ns       "my.app"
 :requires {:clj  ["clojure.string" "my.app.util"]
            :cljs ["goog.string" "my.app.util"]}}
```

`:requires` has a sorted list for each platform the source is read for, of
every namespace (or, in ClojureScript, npm module string) named in the `ns`
form's `:require`, `:use`, `:require-macros` and `:use-macros` clauses,
prefix lists expanded. The runner uses it to prefetch a program's closure
without parsing source.

`:analyzer` links to an object naming the code that did the analysis,
`{:type :clj/analyzer :name "firmament.clojure" :version 1}`; its version
goes up whenever the same source would be analyzed differently.

**Analysis lives in its own tree**, the manifest's `:analyses`, laid out
like the namespace tree: the analysis of `my.app`'s `:source/clj` is the
`:analysis/clj` of entry `my` → `app`. So the namespace tree's hash depends
only on the code, and an analyzer upgrade changes `:analyses` and the
manifest but not `:namespaces`. Every source must have exactly one analysis
and every analysis a source; a manifest where they don't match is
rejected. Analyses travel with the manifest, so whoever fetches a program
can load it without analyzing anything; saving an edited source means
writing its new analysis too.

## Deferred

- **`data_readers.clj` / `data_readers.cljc`.** These classpath-root files map
  tagged-literal symbols to reader functions, and Clojure loads every copy it
  finds at startup. Until they're supported, programs that depend on a
  library's tagged literals won't load.
