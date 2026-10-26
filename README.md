# Firmament

**Content-addressed computing, starting with Clojure.**

Firmament stores everything — code, builds, systems, user state — as immutable
values named by the hash of their content. Naming lives outside the core:
anything that hands you a root hash (a DNS record, a web service, a note on
paper) is enough, and everything under it is verified by hashing. Version control,
rollback, reproducibility and sharing stop being separate tools and become
properties of the medium.

The first target is Clojure: namespaces are stored as hashed trees, so a node
can be told *"load H and run"*, and upgraded by diffing H against its
successor.

> **Status:** pre-alpha. The data formats are frozen as the provisional
> version `firmament-0`, with encoding, hashing and chunking implemented; the
> store and CLI come next. See [PLAN.md](PLAN.md).

## Why

Today's computing is built on *places* — mutable files at paths — and most of
its pain follows from that: drift between machines, deployment ceremony,
dependency names that are promises rather than identities, history that only
some things have, and sharing that means copying.

Firmament replaces places with values:

- A **file** is a hash. A **directory** or **namespace** is a hash of a
  listing of names to hashes.
- A **build** is a cached function: `compile(source-hash, target) → binary-hash`.
- A **system** is a higher-level hash; your machine is that hash plus an
  overlay of your changes.
- **Names** (`prod.my-service`, `me.my-domain.com`) are just pointers to root
  hashes, kept outside the core; signed, historied names can be layered on
  later.

That makes possible: deploy by naming ("run H", then "run H¹"), live upgrades
that reload only changed namespaces, "try my system" by sending a hash, and
diffing or rolling back anything.

The full motivation is in [docs/what-and-why.md](docs/what-and-why.md); the
technical design is in [docs/design.md](docs/design.md).

## Principles

1. **Values over places.** Identity is content. Mutation is confined to the
   pointers that name root hashes.
2. **Open top to bottom.** Every layer is source you can inspect, change and
   rebuild.
3. **Practical over pure.** Central services, caches and today's trust models
   are fine where they work. A global DHT is one optional resolver, not the
   foundation.
4. **Local first, self-hostable.** Your drives are your cache; your servers can
   be your resolvers.
5. **Verify, don't trust, by default.** Content checks itself. Trust comes
   from whoever gave you the root hash; signing and attestation can be built on
   top.
6. **Evolve, don't legislate.** Freeze data formats carefully; let policy
   (trust, merging, retention) grow from real use.

## Core concepts

| Concept | What it is |
| --- | --- |
| **Blob** | Raw bytes, hashed as `"blob\n" + bytes`. |
| **Chunked file** | A file over 256 KiB, split by a FastCDC-style chunker into chunk blobs, with sizes for range reads. |
| **Tree** | A map of names to entries; each entry has optional tags and named links. Directories and namespace trees are trees. |
| **Link** | A distinct type, `#h "sha256:…"`, so closures can be walked without a schema. |
| **Meta-object** | An ordinary object that links to what it describes (`compiled-to`, namespace analysis, …). Its schema is outside the core. |
| **Resolver stack** | RAM → disk → LAN → mirrors → private gateway → DHT. Any source may be untrusted, since content self-verifies. |

Structured objects are restricted, canonical EDN, hashed as
`"edn\n" + bytes`; hashes are written `sha256:<hex>`. The spec is in
[docs/spec/](docs/spec/).

### Namespaces as trees

A namespace name splits on `.`, and each segment is a tree entry. An entry can
carry both `:source` (the namespace itself) and `:children` (the subtree), so
`my.app` and `my.app.server` coexist with no file-name munging. A load session
is bound to an immutable root hash:

```clojure
(binding [*ns-root* H]
  (require 'my.app))
```

A filesystem layout is just a projection, generated when a tool needs one.

## Roadmap

The first milestone is *"load H and run"* for an ordinary `deps.edn` project:

1. **Store and encoding.** Canonical EDN, hashing, blobs, chunking, golden test vectors.
2. **Trees and import.** Turn a `deps.edn` project into a namespace tree plus `:jars` and `:resources`.
3. **Loader.** Serve namespaces from a root hash so `require` and existing tooling work unmodified.
4. **Runner.** `run H P`: prefetch the closure, load, call the entry point.
5. **Serving.** An HTTP blob server, so a second machine can run a hash published by the first.

Deliberately deferred: signed names, attestation, the DHT, merging, GC,
privacy gateways and live upgrade. See [PLAN.md](PLAN.md) for phases, deliverables and exit
criteria.

## Repository layout

```
README.md            this file
PLAN.md              phased implementation plan
docs/
  what-and-why.md    motivation, principles, lineage, non-goals
  design.md          original design summary
  decisions.md       dated decision log, incl. departures from design.md
  spec/
    encoding.md      canonical EDN
    objects.md       hashing, trust model, core node types
    chunking.md      chunker and chunk index
    clojure.md       Clojure layer (draft)
deps.edn             Clojure project
deps-lock.json       pinned Clojure dependencies, for Nix builds
flake.nix            Nix build, checks and dev shell
nix/                 Nix derivations used by flake.nix
src/firmament/
  edn.clj            canonical EDN encoder and strict decoder
  hash.clj           blob and structured-object hashes
  chunk.clj          chunker and chunk index
test/                Clojure tests, including the golden vectors
reference/           Python reference implementation and vector generator
test-resources/
  vectors/           golden vectors
```

## Development

Enter the dev shell with `nix develop`, or install a JDK (21+), the Clojure
CLI and Python 3 yourself. Then:

```
clojure -M:test                                         # Clojure tests
python3 reference/gen_vectors.py --check                # vectors are current
(cd reference && python3 -m unittest discover -s tests -t .)
```

With Nix:

```
nix build                 # the library jar
nix flake check           # all of the above, offline, in the Nix sandbox
nix run .#update-deps     # regenerate deps-lock.json after editing deps.edn
```

The Nix builds use [fudo-nix-helpers](https://github.com/fudoniten/fudo-nix-helpers),
which builds from the artifacts pinned in `deps-lock.json`. CI runs every
check above on each pull request, and fails if `deps-lock.json` is stale.

## Lineage

Firmament borrows from Clojure (persistent data, path copying), Datomic
(history as a value), Nix (hashed closures, atomic rollback), Unison (code
identified by hash), Git (Merkle history), IPFS/IPLD (content-addressed links)
and Dolt (prolly trees). What's new is unifying them under one object model
with a practical naming layer.

## Non-goals (for now)

- A perfect, fully decentralized system.
- A global DHT as the foundation.
- Replacing Linux on day one — Firmament starts as a guest and earns its way
  down the stack.
- Storing hot state (databases, logs) in the tree.
- Multiple versions of a library in one JVM.
- Solving trust and governance up front, or building signing into the core.
