# Decisions

A dated log of format and design decisions, including where they depart from
[design.md](design.md). The specs in [spec/](spec/) are the source of truth;
this file records why.

## 2026-10-25 — Phase 0 decisions

**Encoding stays canonical EDN text.** Considered deterministic CBOR and a
custom binary format. EDN is readable, native to Clojure, and good enough for a
prototype.

**Booleans allowed; nil forbidden.** Booleans have one canonical form each.
`nil` would give `{:a nil}` and `{}` different hashes for the same meaning, so
absence is the only way to say "no value".

**Map keys sorted by the bytes of their canonical encoding.** A total order
across key types with no extra ranking rule.

**Separators: exactly one space.** The design's "no whitespace" means no
optional whitespace; EDN still needs one separator between elements.

**Signed names and claims removed from the core.** *(Departs from design.md.)*
A root hash reaches you by any means — DNS, a web service, paper — and
everything under it is trusted by hashing. Meta-objects such as `compiled-to`
statements are ordinary objects that link to their subject, with schemas left
to higher layers or third parties. Signing and attestation may be added later,
outside the core. The core now has three node types: blob, chunked, tree.

**Two hash prefixes: `blob` and `edn`.** *(Departs from design.md, which used a
prefix per type.)* Per-type prefixes mainly served to keep signatures for one
type from verifying as another; with signatures out of the core, a `:type`
key inside the object is enough, and new object kinds need no new prefix.

**Chunking: FastCDC-style, fully specified in our spec.** Our own gear table
(derived from SHA-256) and masks, so nothing depends on another library's
constants. Not compatible with other FastCDC implementations, which doesn't
matter. Cross-checked by two independent implementations.

**Chunk lists split into levels with content-defined boundaries.** Avoids
oversized index nodes for big files, and keeps edits local, without a later
format change.

**Platform-specific source links.** A namespace entry has `:source/clj`,
`:source/cljs` and `:source/cljc` rather than one `:source` with a tag.

**Paths only at the edges.** The store is pure namespaces. The importer trusts
the `ns` form over the file path; the loader maps classloader path requests
back to namespaces.

**`data_readers` deferred.**

## 2026-10-26 — Reference implementation and vectors

**Nesting depth defined.** Vectors and maps count; a top-level `[]` has
depth 1, so 256 levels are valid and 257 are not.

**Vectors describe large inputs instead of storing them.** Chunking inputs
are generated from SHA-256 in counter mode (or zeros), so the vector files
stay small and any implementation can rebuild the bytes. Index building is
tested on its own with synthetic links, which is the only practical way to
reach the 4096-entry cap and a three-level index.

**Vector files are plain EDN, pure ASCII.** Non-ASCII characters are
`\u` escapes, so a value reads the same in any EDN reader; canonical bytes
are given as hex. The vector files are not themselves canonical EDN.

## 2026-10-26 — `firmament-0` frozen, provisionally

**`firmament-0` is frozen.** The Python reference and the Clojure
implementation agree on every golden vector, which meets the Phase 0 exit
criteria. The spec text and vectors for `firmament-0` no longer change.

**Frozen does not mean final.** A later version is expected once the store,
trees and loader are in real use. Changes go into a new version
(`firmament-1`, …) with its own spec text and vectors, rather than into
`firmament-0`, so anything hashed under `firmament-0` keeps its meaning.

**Versions must not share hashes.** A later version that changes any hash
input also changes the hash prefixes, so a hash identifies its version and
two versions cannot disagree about what a hash means. The exact scheme waits
for that version. Until then each store holds a single version and records
which; data moves between versions only by re-import.

## 2026-10-27 — Directory trees

**Plain directories import without tags.** `put -r` maps files to
`{:link …}` and directories to `{:children …}`, and records no modes,
owners or times, so a directory's hash depends only on names and contents.
If executable bits turn out to matter, they can be added later as an
optional `:posix/mode` tag, which changes those hashes. See
[spec/directories.md](spec/directories.md).

**Symbolic links are refused** rather than followed or stored, until there
is a use for them.

## 2026-10-27 — Clojure import

**Analysis lives in a sibling tree** (`:analyses` in the manifest, the same
layout as `:namespaces`), not in the namespace entry, and not in a local
index. Keeping it out of the namespace tree makes that tree's hash mean
"this code", whatever analyzer ran: the hash a live REPL state would be
shared by shouldn't move when the analyzer does. Keeping it in the manifest
means consumers never analyze; a local index would only push the work
downstream. Cost: a save rewrites two paths, and loaders check that the two
trees match.

**`:requires` is per platform.** A `.cljc` file is read once for `:clj` and
once for `:cljs`, and their requires differ, so `:requires` is a map from
platform to a sorted list.

**The importer doesn't resolve dependencies.** Jars come from a classpath
(`clojure -Spath`) or `:local/root` jar deps. Resolving Maven coordinates
ourselves would mean reimplementing tools.deps and needing the network.

**Files without an `ns` form are resources**, including `data_readers.clj`,
so nothing under `:paths` is dropped.

## 2026-10-27 — Loader

**One classloader per manifest**, installed as `Compiler/LOADER` and the
context classloader for a `with-root` session, rather than a classloader
that reads `*ns-root*`. Classloaders aren't aware of dynamic bindings, and
Clojure caches the loader in compiled code.

**The manifest comes first for resources, and the parent comes first for
classes.** A program's own namespaces and resources win over anything the
host JVM has on its classpath. Clojure itself and the JDK always come from
the host, so there is one `clojure.lang.RT`.

**Paths are mapped forward, not parsed.** Munging loses information
(`my_app` could be `my-app` or `my_app`), so the loader builds a table from
each namespace's projected path to its source. It doesn't try to demunge
the path it's asked for.

**Jars are written to disk by hash.** Opening jars from memory would need a
custom jar reader, and jars rarely change, so each one is written out once
per store.

## 2026-10-27 — Runner

**Parameters replace the arguments.** With a parameters hash P, `-main` gets
P's value as its only argument; without one, it gets command-line strings as
usual. This keeps ordinary `-main` functions runnable, and a program written
for Firmament takes a map.

**Prefetch follows `:clj` requires only.** It loads what the JVM will
load. Resources and jars are fetched whole, since nothing records which of
them a program uses.

**The cache checks before it stores.** An object copied from another store
is hashed first, and a mismatch is refused, so a bad remote can't put
anything into the local store.

**`firmament` exits through `System/exit` only on failure.** On success it
returns, like `clojure -M -m`, so a program that started threads, such as a
server, keeps running.

## 2026-10-27 — Serving

**The URL layout is the store's directory layout** (`objects/ab/cdef…`,
`format`), not `GET /<hash>`. Then a static file server, or a bucket
synced from a store, is a remote with no Firmament code on it, and the
built-in server is a small file server plus authenticated PUT.

**Servers don't check what they serve; clients check what they get.** The
client hashes every object, so the server's integrity doesn't matter, and
a corrupted or tampered file is caught where it would do harm.

**Writes need a bearer token, and only writes.** Reading content-addressed
objects needs no secret, because you have to know a hash to ask for it.
Private content should be served only from your own host (see Deferred in
PLAN.md).

**No dependencies.** The client is `java.net.http` and the server is the
JDK's `com.sun.net.httpserver`.

