# Firmament Implementation Plan

This plan turns the prototype plan in [docs/design.md](docs/design.md) into
phases with concrete deliverables and exit criteria. The first payoff is
Phase 4: `run H P` loads and runs an ordinary `deps.edn` project from a hash.

Items marked **(proposed)** are suggestions not settled by the design docs;
they need a decision before the phase that depends on them.

## Guiding rules

- **Formats before code.** Anything that affects a hash is specified and
  covered by golden vectors before an implementation depends on it.
- **Defer everything that isn't on the path to "load H and run".** Each phase
  should leave a working, testable tool behind.
- **Stub the future, don't build it.** Claims, signatures and resolvers get
  their record shapes and interfaces early, with minimal implementations.

## Phase 0 — Freeze the format

**Goal:** a written spec that fixes every input to a hash.

**Deliverables**

- `docs/spec/encoding.md` — the canonical encoding.
- `docs/spec/objects.md` — the five node types plus the `:clj/analysis` node.
- `docs/spec/chunking.md` — FastCDC variant and parameters.
- `test-resources/vectors/` — golden vectors: input value, expected canonical
  bytes, expected hash.
- A format version identifier recorded in the spec.

**Decisions to settle**

Already decided by the design:

- SHA-256, written `sha256:<hex>`.
- Type-tagged hash input: `"<type>\n" + payload`.
- Allowed types: maps, vectors, strings, integers, keywords, byte strings,
  links. Sorted keys, no whitespace, no floats, strings in Unicode NFC.
- Blobs are raw bytes; source is hashed as raw bytes, not normalized forms.
- FastCDC with pinned parameters (example: 16 / 64 / 256 KiB).

Still open:

- [ ] **Booleans and nil.** Not in the allowed type list. Exclude them
      (encode flags as keywords or by absence) or add them? **(proposed:
      exclude, and say so explicitly)**
- [ ] **Key ordering across types.** A total order is needed when a map mixes
      keywords and strings, e.g. type rank first, then UTF-8 byte order
      within a type. **(proposed)**
- [ ] **Byte strings and links in EDN text.** Pick reader tags, e.g. `#h` for
      links and a tag such as `#b64` for byte strings, plus exact
      base64/hex alphabets and letter case. **(proposed: lowercase hex for
      hashes)**
- [ ] **String escaping and integer range.** Which characters are escaped,
      and whether integers are arbitrary-precision or capped at 64 bits.
- [ ] **Keywords.** Namespaced keywords allowed (the design uses them for
      tags); define the allowed character set.
- [ ] **Type tags for every node.** `blob`, `tree`, and the tags for
      chunked, name, claim and analysis nodes.
- [ ] **Signing input.** Proposed: the canonical encoding of the record with
      `:sig` removed, and `:prev` is the hash of the full previous record
      (including its signature). **(proposed)**
- [ ] **FastCDC details.** Which variant (original or normalized chunking),
      the gear table, and the small-file threshold below which a file stays a
      single blob.
- [ ] **Tree entry keys.** Reserved link keys (`:link`, `:source`,
      `:children`) vs tag map (`:tags`), and how platform variants
      (`.clj`/`.cljs`/`.cljc`) are represented: tags or sibling links.

**Exit criteria:** spec reviewed, vectors committed, and every open item above
checked off or explicitly deferred with a reason.

## Phase 1 — Store and encoding

**Goal:** put values in, get hashes out, get values back.

**Deliverables**

- Project skeleton: `deps.edn`, test runner, and a Nix flake dev shell.
- Canonical encoder and a strict decoder that rejects non-canonical input.
- Hashing with type tags.
- FastCDC chunker producing chunked-file nodes.
- Local content store: `put`, `get`, `has?`, keyed by hash on disk, with the
  local metadata map (`:pinned?`, `:expires`, `:source`) stored separately and
  never hashed.
- Resolver protocol, with the local store as the first implementation.
- Link walker: collect every link in an object without a schema.
- A minimal CLI: `put <file>`, `get <hash>`, `cat <hash>`.

**Exit criteria**

- All golden vectors pass, in both encode and decode directions.
- Round-trip property tests: decode(encode(v)) = v, and encode is stable.
- A blob hash matches an independent check (`printf 'blob\n' | cat - file | sha256sum`).
- Chunking the same bytes always yields the same root hash; an insert in the
  middle of a large file changes only nearby chunks.

## Phase 2 — Trees and import

**Goal:** turn a real `deps.edn` project into a single root hash.

**Deliverables**

- Tree construction and path-copying update (change one entry, rewrite only
  the path to the root).
- Importer: walk a project's source paths and map files to namespace entries
  (`:source`, `:children`, platform tags); put dependency jars under `:jars`
  and non-source files under `:resources`.
- `:clj/analysis` nodes keyed by source hash plus analyzer hash, holding at
  least `:requires` (via `tools.namespace` or `tools.reader`).
- Projection: export a tree back to a filesystem layout.
- CLI: `import <project-dir>` prints the root hash; `ls <hash> [path]`.

**Exit criteria**

- Importing the same project twice gives the same hash.
- Import, project, and import again gives the same hash.
- Editing one namespace changes only the hashes on its path to the root.
- Works on at least two real projects, including one with `.cljc` files and
  resources.

## Phase 3 — Loader

**Goal:** `require` a namespace from a root hash with no filesystem checkout.

**Deliverables**

- Root binding: `*ns-root*` bound to a root hash for a load session.
- A classloader that serves `my/app.clj` (and resources) from the tree, so
  `require`, `io/resource` and existing tooling work unmodified.
- Jars from `:jars` made available to the classloader.

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

**Exit criteria:** a REPL started with nothing but the store and a root hash
can `(require 'my.app)` and call into it, including a dependency from a jar
and a resource lookup.

## Phase 4 — Runner (first payoff)

**Goal:** `run H P` — fetch, load and start a program from hashes.

**Deliverables**

- Entry point taken from an entry tag on the manifest.
- Closure prefetch driven by analysis `:requires` lists, so a cold start
  fetches everything it needs without parsing source.
- Per-node parameters P as their own (private) hash, passed to the entry point.
- CLI: `firmament run <H> [<P>]`.

**Exit criteria**

- On a clean machine with an empty store, `run H P` against a remote store
  starts the program.
- Changing one namespace and running H¹ transfers only the changed objects.

## Phase 5 — Names and serving

**Goal:** a second machine can `run prod.my-service`.

**Deliverables**

- HTTP blob server: `GET /<hash>`, plus `PUT` for authorized pushes.
- HTTP resolver, so the stack is local store → remote server.
- ed25519 keys, signed name records, and append-only chain verification
  (sequence, `:prev` link, signature).
- A simple hosted name service: get the latest record, append a record.
- Claim records with signature verification stubbed (shape only, no policy).
- CLI: `name set <name> <hash>`, `name get <name>`, `run <name>`.

**Exit criteria**

- Machine A imports a project and sets `prod.my-service`; machine B runs it by
  name.
- Updating the name and re-running on B picks up the new hash, and the full
  chain verifies.
- Tampering with any record or blob is detected.

## Deferred

These stay out of scope until the phases above are done:

| Item | Interim stand-in |
| --- | --- |
| Global DHT | Local store plus HTTP `GET /<hash>` |
| Trust policy | Claim shapes defined, verification stubbed |
| Live upgrade (diff H → H¹, reload) | Restart on upgrade |
| Data migration declarations | None |
| Name-chain merging | Single writer per name |
| GC and retention | Pins only, no collection |
| Privacy gateways | Private content served only from your own host |
| Normalized source forms | Raw source bytes |
| Per-definition granularity | Per-namespace objects |

Live upgrade is the natural Phase 6: diff H against H¹, reload changed
namespaces, and fall back to a restart for records, protocols and `defonce`
state.

## Open questions

Carried from the design and not blocking the phases above:

- How two devices that advanced the same name chain reconcile.
- Revocation of leaked secrets or unlawful content in immutable history.
- A checkpointing overlay filesystem for POSIX apps that write constantly.
- Where the reproducibility boundary sits (firmware, drivers, time, network).
- Keeping per-namespace granularity cheap via prefetch and bundles.
