# Firmament Implementation Plan

This plan turns the prototype plan in [docs/design.md](docs/design.md) into
phases with concrete deliverables and exit criteria. The first payoff is
Phase 4: `run H P` loads and runs an ordinary `deps.edn` project from a hash.

Decisions and their reasons are logged in [docs/decisions.md](docs/decisions.md).

## Guiding rules

- **Formats before code.** Anything that affects a hash is specified and
  covered by golden vectors before an implementation depends on it.
- **A small core.** The core is three node types and two hash prefixes.
  Naming, signing and meta-object schemas live outside it.
- **Defer everything that isn't on the path to "load H and run".** Each phase
  should leave a working, testable tool behind.

## Phase 0 — Freeze the format

**Goal:** a written spec that fixes every input to a hash.

**Deliverables**

- [x] [docs/spec/encoding.md](docs/spec/encoding.md) — canonical EDN.
- [x] [docs/spec/objects.md](docs/spec/objects.md) — hashing, trust model,
      blob / chunked / tree.
- [x] [docs/spec/chunking.md](docs/spec/chunking.md) — chunker, parameters,
      gear table, index levels.
- [x] [docs/spec/clojure.md](docs/spec/clojure.md) — Clojure layer direction
      (draft; frozen later, in Phases 2–3).
- [x] Python reference implementation of encoding, hashing and chunking
      (`reference/`), independent of the Clojure one.
- [x] Golden vectors in `test-resources/vectors/`: values with their canonical
      bytes and hashes, invalid inputs a decoder must reject, the gear table,
      and chunking cases (cut points and index roots for generated data).

**Decided**

- Canonical EDN text; booleans allowed, nil forbidden; signed 64-bit integers.
- Map keys sorted by the bytes of their canonical encoding.
- Exactly one space as separator; no other whitespace.
- Fixed string escape table; strings in NFC.
- Conservative ASCII keyword character set.
- `#h "sha256:<lowercase hex>"` links; `#b64` byte strings.
- Two hash prefixes, `blob` and `edn`; object kind given by `:type`.
- No signatures or names in the core.
- FastCDC-style chunker fully defined in the spec; files ≤ 256 KiB are blobs;
  multi-level chunk index with content-defined boundaries.
- Tree entries: optional `:tags`, every other key a link.

**Exit criteria:** the Python reference produces the vectors, the spec text and
vectors agree, and the format version `firmament-0` is declared frozen.
*Met 2026-10-26: `firmament-0` is frozen as a provisional version (see
[Format versions](docs/spec/objects.md#format-versions)).*

## Phase 1 — Store and encoding

**Goal:** put values in, get hashes out, get values back.

**Deliverables**

- [x] Project skeleton: `deps.edn`, test runner, and a Nix flake dev shell.
- [x] Canonical encoder and a strict decoder that rejects non-canonical input.
- [x] Hashing with the two prefixes.
- [x] Chunker producing multi-level chunked nodes.
- [x] Local content store: `put`, `get`, `has?`, keyed by hash on disk, with the
      local metadata map (`:pinned?`, `:expires`, `:source`) stored separately and
      never hashed.
- [x] Resolver protocol, with the local store as the first implementation.
- [x] Link walker: collect every link in an object without a schema.
- [x] A minimal CLI: `put <file>`, `get <hash>`, `cat <hash>`.

**Exit criteria**

- All golden vectors pass, in both encode and decode directions, and the
  Clojure and Python implementations agree.
- Round-trip property tests: decode(encode(v)) = v, and encode is stable.
- A blob hash matches an independent check
  (`{ printf 'blob\n'; cat file; } | sha256sum`).
- An insert in the middle of a large file changes only nearby chunks and
  index nodes.

## Phase 2 — Trees and import

**Goal:** turn a real `deps.edn` project into a single manifest hash.

**Deliverables**

- [x] Tree construction and path-copying update (change one entry, rewrite only
  the path to the root).
- [x] Importer: read each source file's `ns` form, place its source under the
  namespace's entry as `:source/clj`, `:source/cljs` or `:source/cljc`; put
  dependency jars under `:jars` and non-source files under `:resources`; emit a
  `:clj/manifest`.
- [x] `:clj/analysis` meta-objects with at least `:requires`, and a way to find
      them from a source hash.
- [x] Projection: export a manifest back to a filesystem layout.
- [x] CLI: `import <project-dir>` prints the manifest hash; `ls <hash> [ns]`.

**Exit criteria**

- Importing the same project twice gives the same hash.
- Import, project, and import again gives the same hash.
- Editing one namespace changes only the hashes on its path to the root.
- Works on at least two real projects, including one with `.cljc` files and
  resources.
- The Clojure layer in [docs/spec/clojure.md](docs/spec/clojure.md) is frozen.

## Phase 3 — Loader

**Goal:** `require` a namespace from a manifest hash with no filesystem
checkout.

**Deliverables**

- [x] Root binding: `*ns-root*` bound to a manifest hash for a load session.
- [x] A namespace-native `require` for our own code.
- [x] A compatibility classloader that maps path requests (`my/app_server.clj`)
      back to namespaces and serves the right platform source, so
      `clojure.core/require`, `io/resource` and existing tooling work unmodified.
- [x] Jars from `:jars` made available to the classloader.

**Technical risks to resolve early**

- Clojure resolves source through classloader resource lookups, which return
  URLs. Serving from a store needs a custom URL scheme or in-memory URL
  handler.
- A classloader isn't aware of dynamic bindings, so the practical form of
  `*ns-root*` may be one classloader per root hash, installed as the context
  loader (or `Compiler/LOADER`) for the session.
- `*loaded-libs*` is global, so a second root in the same JVM won't reload
  namespaces already loaded. This is the expected one-version-per-JVM limit;
  document it rather than work around it.

**Exit criteria:** a REPL started with nothing but the store and a manifest
hash can `(require 'my.app)` and call into it, including a dependency from a
jar and a resource lookup.

## Phase 4 — Runner (first payoff)

**Goal:** `run H P` — fetch, load and start a program from hashes.

**Deliverables**

- Entry point taken from the manifest's `:clj/main` tag.
- Closure prefetch driven by analysis `:requires` lists, so a cold start
  fetches everything it needs without parsing source.
- Per-node parameters P as their own (private) hash, passed to the entry point.
- CLI: `firmament run <H> [<P>]`.

**Exit criteria**

- On a clean machine with an empty store, `run H P` against a remote store
  starts the program.
- Changing one namespace and running H¹ transfers only the changed objects.

## Phase 5 — Serving

**Goal:** a second machine can run a hash published by the first.

**Deliverables**

- HTTP blob server: `GET /<hash>`, plus `PUT` for authorized pushes.
- HTTP resolver, so the stack is local store → remote server.
- Every fetched object verified against its hash.

**Exit criteria**

- Machine A imports a project and serves it; machine B runs it by hash.
- Tampering with any served object is detected.

## Deferred

| Item | Interim stand-in |
| --- | --- |
| Signed names | A root hash handed over by any means (DNS, web service, paper) |
| Signing and attestation of meta-objects | Unsigned meta-objects; trust whoever gave you the root |
| Global DHT | Local store plus HTTP `GET /<hash>` |
| `data_readers.clj(c)` | Unsupported; programs relying on library tagged literals won't load |
| Live upgrade (diff H → H¹, reload) | Restart on upgrade |
| Data migration declarations | None |
| GC and retention | Pins only, no collection |
| Privacy gateways | Private content served only from your own host |
| Normalized source forms | Raw source bytes |
| Per-definition granularity | Per-namespace objects |

Live upgrade is the natural Phase 6: diff H against H¹, reload changed
namespaces, and fall back to a restart for records, protocols and `defonce`
state.

## Open questions

Carried from the design and not blocking the phases above:

- How two devices that advanced the same state independently reconcile.
- Revocation of leaked secrets or unlawful content in immutable history.
- A checkpointing overlay filesystem for POSIX apps that write constantly.
- Where the reproducibility boundary sits (firmware, drivers, time, network).
- Keeping per-namespace granularity cheap via prefetch and bundles.
