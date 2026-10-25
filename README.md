# Firmament

**Content-addressed computing, starting with Clojure.**

Firmament stores everything — code, builds, systems, user state — as immutable
values named by the hash of their content. The only mutable things are a few
signed, append-only *name chains* that point at hashes. Version control,
rollback, reproducibility and sharing stop being separate tools and become
properties of the medium.

The first target is Clojure: namespaces are stored as hashed trees, so a node
can be told *"load H and run"*, and upgraded by diffing H against its
successor.

> **Status:** pre-alpha. Nothing runs yet. The data formats are being frozen
> first, because hashes make them permanent. See [PLAN.md](PLAN.md).

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
- A few **signed names** (`prod.my-service`, `me.my-domain.com`) are the only
  mutable things, and each keeps its own history.

That makes possible: deploy by naming ("run H", then "run H¹"), live upgrades
that reload only changed namespaces, "try my system" by sending a hash, and
diffing or rolling back anything.

The full motivation is in [docs/what-and-why.md](docs/what-and-why.md); the
technical design is in [docs/design.md](docs/design.md).

## Principles

1. **Values over places.** Identity is content. Mutation is confined to a few
   explicit, signed, historied names.
2. **Open top to bottom.** Every layer is source you can inspect, change and
   rebuild.
3. **Practical over pure.** Central services, caches and today's trust models
   are fine where they work. A global DHT is one optional resolver, not the
   foundation.
4. **Local first, self-hostable.** Your drives are your cache; your servers can
   be your resolvers.
5. **Verify, don't trust, by default.** Content checks itself; signatures are
   needed only for names and claims a hash can't prove.
6. **Evolve, don't legislate.** Freeze data formats carefully; let policy
   (trust, merging, retention) grow from real use.

## Core concepts

| Concept | What it is |
| --- | --- |
| **Blob** | Raw bytes, hashed as `"blob\n" + bytes`. |
| **Chunked file** | A large file split by FastCDC into chunk blobs, with sizes for range reads. |
| **Tree** | A map of names to entries; each entry has tags and named links. Directories, namespace trees, manifests and distros are all trees. |
| **Name record** | One entry in a signed, append-only chain mapping a name to a hash. |
| **Claim** | A signed statement about a hash (`:compiled-to`, `:retained-until`, …). |
| **Link** | A distinct type, `#h "sha256:…"`, so closures can be walked without a schema. |
| **Resolver stack** | RAM → disk → LAN → mirrors → private gateway → DHT. Any source may be untrusted, since content self-verifies. |

Objects are encoded as restricted, canonical EDN (sorted keys, no whitespace,
integers only), and hashes are written `sha256:<hex>`.

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
5. **Names and serving.** Signed name chains and an HTTP blob server, so a second machine can `run prod.my-service`.

Deliberately deferred: the DHT, trust policy, merging, GC, privacy gateways and
live upgrade. See [PLAN.md](PLAN.md) for phases, deliverables and exit
criteria.

## Repository layout

```
README.md            this file
PLAN.md              phased implementation plan
docs/
  what-and-why.md    motivation, principles, lineage, non-goals
  design.md          data model, namespaces, trust, prototype plan
```

Planned additions as phases land: `docs/spec/` (the frozen format spec),
`test-resources/vectors/` (golden vectors), `src/` and `test/`.

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
- Solving trust and governance up front.
