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
