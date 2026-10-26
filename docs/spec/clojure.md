# Clojure Layer (draft)

This layer is built on the core objects and is **not frozen in Phase 0**. It
is settled during Phases 2–3; this document records the current direction.

## Namespace trees

The store holds namespaces, not paths. A namespace name splits on `.`, and
each segment is a tree entry, starting from the namespace root:
`my.app.server` → `"my"` → `"app"` → `"server"`.

Segments are the namespace's own names, unmunged: `my-app`, never `my_app`.

An entry may carry:

| Key | Points to |
| --- | --- |
| `:source/clj` | Blob: JVM source (raw bytes of the `.clj` file) |
| `:source/cljs` | Blob: ClojureScript source |
| `:source/cljc` | Blob: portable source |
| `:children` | Tree: child namespaces |

So `my.app` and `my.app.server` coexist, and one namespace may have sources for
several platforms. The loader picks the source the way Clojure would: on the
JVM, `:source/clj` before `:source/cljc`.

## Manifest (proposed)

A loadable program is a manifest object:

```clojure
{:type       :clj/manifest
 :namespaces #h "sha256:..."   ; namespace tree root
 :resources  #h "sha256:..."   ; tree of non-source files
 :jars       #h "sha256:..."   ; tree of jars, each an opaque blob
 :tags       {:clj/main "my.app"}}
```

`*ns-root*` is bound to the manifest hash. This differs slightly from
[design.md](../design.md), where the manifest and namespace root are the same
tree: keeping them separate stops a namespace named `resources` from
colliding with the resources subtree.

## Import

The importer reads each source file's `ns` form and uses the namespace name it
declares, not the file path. This avoids ambiguity from munging (`my_app.clj`
could be `my-app` or `my_app`) and settles what happens when path and `ns`
disagree.

Source is stored as raw bytes, never normalized.

## Loading

Our own `require` can take namespace names and look them up in the tree
directly. Existing code and libraries call `clojure.core/require`, which asks
the classloader for a path such as `my/app_server.clj`. A compatibility
classloader translates that request straight back into a namespace
(`my.app-server`) and platform, and serves the matching source. Paths are
handled only at this edge and never reach the store.

## Analysis meta-object (proposed)

```clojure
{:type     :clj/analysis
 :source   #h "sha256:..."   ; the source blob analyzed
 :analyzer #h "sha256:..."   ; the analyzer that produced this
 :ns       "my.app"
 :requires ["clojure.string" "my.app.util"]}
```

The runner uses `:requires` to prefetch a program's closure without parsing
source. How analysis objects are found from a source hash (a local index, or
a link in the manifest) is open.

## Deferred

- **`data_readers.clj` / `data_readers.cljc`.** These classpath-root files map
  tagged-literal symbols to reader functions, and Clojure loads every copy it
  finds at startup. Until they're supported, programs that depend on a
  library's tagged literals won't load.
