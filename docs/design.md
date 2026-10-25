# Content-Addressed Computing: Design Summary

Oct 24, 2026 · @Peter

Everything (code, builds, systems, user state) is named by the hash of its content, and the only mutable things are signed name chains that point at hashes. The first target is Clojure: namespaces stored as hashed trees, so a node can be told "load H and run," and upgraded by diffing H against its successor.

## Core principles

The design is practical rather than fully decentralized. The global DHT is one optional resolver, not the foundation.

1. **Universal content addressing.** A hash is the identity of every object. Immutable objects need no signatures: a trusted hash verifies its content and, through links, everything below it.
2. **Signed, append-only name chains** are the only mutable thing. Each update records the previous record, so name history is itself content-addressed and providers can be switched by carrying the chain. Names can start as a simple hosted service.
3. **A pluggable resolver stack.** RAM, local disk, LAN cache, distro mirrors, private gateways, then the DHT. Because content is self-verifying, any source can be untrusted.
4. **Hot vs. checkpointed state.** Fast-changing state (databases, logs, game saves) lives outside the tree and is folded in periodically or at app-chosen save points. Databases can go native using immutable designs (Datomic's log plus batch indexing, Dolt-style prolly trees).
5. **Eat the host.** Start as an environment running on Linux, become the most important process, and only later the only one.

## At a glance


A signed name points at a manifest tree; claims point at hashes from outside the tree, and everything under the tree is verified by hashing alone.

## Data model

Five node types in a restricted, canonical EDN, plus local store state that is never hashed.

**Rules**

- **Identity vs. policy.** Anything intrinsic to content goes inside the hash. Anything about who holds it, for how long, or why (expiry, pins, trust) goes outside, often as a signed claim.
- **Canonical encoding.** Sorted map keys, no whitespace, integers only (no floats), a fixed set of types: maps, vectors, strings, integers, keywords, byte strings, links. Names in Unicode NFC. Golden test vectors define the format.
- **Links are a distinct type** (`#h "sha256:..."`), so closure walks, GC and prefetch collect every link without a schema.
- **Type-tagged, self-describing hashes.** Hash `"blob\n" + bytes`, `"tree\n" + canonical-edn`, and so on; write hashes as `sha256:...`.
- **Blobs are raw bytes**, never wrapped in EDN.
- **Chunking is built in.** One fixed content-defined algorithm (FastCDC) with pinned parameters (for example 16 / 64 / 256 KiB min / average / max), so the same bytes always give the same hash. Small files stay single blobs; large files are chunk trees with per-entry sizes for range reads.
- **Tree entries** are a map of tags plus any number of named links. Tags live on the entry, not the blob, use namespaced keys, and tools must preserve tags and links they don't understand. No POSIX modes in the core; `:posix/mode` can be an optional tag.

**Node types**

```clojure
;; 1. Blob: raw bytes, hashed as "blob\n" + bytes.

;; 2. Chunked file: a tree of chunks with sizes.
{:type   :chunked
 :size   10737418240
 :chunks [{:link #h "sha256:..." :size 65811} ...]}

;; 3. Tree: directory, namespace tree, manifest, distro.
{:type    :tree
 :entries {"app"      {:source   #h "sha256:..."
                       :children #h "sha256:..."
                       :tags     {:clj/platform :cljc}}
           "notes.md" {:link #h "sha256:..."
                       :tags {:mime/type "text/markdown"}}}}

;; 4. Name record: one entry in a signed append-only chain.
{:type :name  :name "prod.my-service"  :seq 42
 :prev #h "sha256:..."  :value #h "sha256:..."
 :key "ed25519:..."  :sig "..."}

;; 5. Claim: a signed statement about a hash.
{:type :claim  :subject #h "sha256:..."
 :predicate :compiled-to   ; :retained-until, :closure, ...
 :object #h "sha256:..."
 :context {:target "jvm-21" :clojure "1.12"}
 :key "ed25519:..."  :sig "..."}
```

Local store state: `hash -> {:pinned? true, :expires ..., :source ...}`, per node and never hashed.

## Namespaces as trees

The namespace is fundamental; a filesystem layout is only a projection generated when a tool needs one.

- **Lookup.** A namespace name splits on `.`; each segment is a tree entry, starting from the namespace root.
- **Leaf plus children.** `my.app` and `my.app.server` can both exist, so an entry carries `:source` (the namespace itself) and `:children` (the subtree). This also ends file-name munging; `.clj`/`.cljs`/`.cljc` variants become tags or sibling links.
- **Root bound to a hash.** `*ns-root*` is a dynamic var bound to an immutable root hash for a whole load session: `(binding [*ns-root* H] (require 'my.app))`. The manifest H and the namespace root are the same thing.
- **Grafting.** A library is one entry pointing at its subtree. Changing one namespace rewrites only the path to the root (path copying, as in Clojure's persistent maps). A user overlay is a tree that replaces a few links in the distro root.
- **Resources and Java.** `:resources` and `:jars` live in their own subtrees beside the namespace tree; jars are opaque leaves hashed by `sha256`.

**Namespace metadata** has three homes:

| Kind | Example | Where it lives |
| --- | --- | --- |
| Written in the `ns` form | docstring, `:author`, `:deprecated` | The source blob; never copied into tags |
| Not in the source | entry point, "tests for my.app" | Entry tags |
| Extracted from source | requires, public vars, docs | A derived `:clj/analysis` node, keyed by source hash plus analyzer hash |

The analysis node's `:requires` list drives cold-start prefetch without parsing any source.

## Trust, storage and privacy

Signatures are needed only where a hash cannot verify the claim: names and derived claims.

- **Names** are signed chains; they are the trust root, and everything below is verified by hashing.
- **Derived claims** (`compiled-to`, closures, analysis, retention) are signed assertions. Start with today's model: trust what your distro releases. Leave room in the records for multiple signatures and attestations, so policy can later grow into transparency logs or "accept a binary only when two rebuilders agree" without a format change.
- **Storage** is layered: local drives cache everything that matters to you, distros host what they depend on, and the DHT serves the long tail.
- **Privacy** comes from private gateways. Private hashes are only ever requested from your own host or service, and public content can be fetched through a trusted cache so the open DHT rarely sees your access patterns.
- **Retention** works like Nix GC roots plus retention claims ("distro X keeps H until 2030"). Old objects can be marked deprecated with an expiry, after which holders keep them themselves. Shared links carry a location hint (`hash + "try my-domain.com"`).

## First use case: load H and run

Deploying becomes "node, run H with parameters P"; upgrading becomes "now run H¹." Nix closures and digest-pinned containers already give "run exactly this," so the value is in what goes beyond them:

- **Incremental transfer.** Fetch only namespaces whose hashes changed, not whole image layers.
- **Identical value in REPL and production.**
- **Live upgrade.** Diff H against H¹ and reload just the changed namespaces into the running JVM, with no restart. Fall back to a restart when the diff touches what live reload can't handle safely (record shapes, protocols, `defonce` state).

Two inputs stay outside H:

- **Per-node parameters P** (hostname, certificates, secrets) form a private hash fetched from the private resolver.
- **Data migrations.** Rolling code back is trivial; rolling data back isn't. H¹ should declare its migrations so the runner knows when a rollback is unsafe.

## Prototype plan

**Freeze before writing code**, since these shape every hash:

- The canonical encoding spec and golden vectors.
- Hash algorithm and prefix format (`sha256:`).
- Chunking parameters, pinned with a format version.
- Raw source vs. normalized forms: hash **raw source bytes** for now. Reading Clojure depends on namespace aliases (`::kw`), reader conditionals and tagged literals, so normalized forms become a derived index later.

**Defer:** the DHT itself (a local store plus HTTP `GET /<hash>` suffices), trust policy (define claim shapes, stub verification), merging, GC, privacy gateways, live upgrade.

**Build order:**

1. **Store and encoding.** Canonical EDN, hashing, blobs, chunking, golden vectors.
2. **Trees and import.** Turn a `deps.edn` project into a namespace tree plus `:jars` and `:resources`.
3. **Loader.** Bind `*ns-root*` to a hash; a custom classloader serves `my/app.clj` from the tree so `require` and existing tooling work unmodified.
4. **Runner.** `run H P`: prefetch the closure from analysis nodes, load, call the entry point. This is the first payoff.
5. **Names and serving.** Signed name chains and the HTTP blob server, so a second machine can `run prod.my-service`.

## Known limits and open questions

- **One version per JVM.** Clojure namespaces are global per process, so two versions of a library can coexist in storage but not at runtime.
- **Merging.** How two devices that advanced the same name chain independently reconcile, including what a conflict in user settings means.
- **Revocation.** Immutable history can't truly delete leaked secrets or unlawful content; at most your own resolvers refuse to serve it, and rewriting history changes every hash above it.
- **Hot state compatibility.** Existing POSIX apps write files constantly; a checkpointing overlay filesystem is likely needed for adoption.
- **Reproducibility boundary.** "Exactly my system" holds for pure software, not firmware, drivers, time or network.
- **Granularity cost.** Per-namespace (and later per-definition) objects mean more metadata and lookups; closure prefetch and bundles must keep that cheap.
