# Objects and Hashing

Format version: `firmament-0`, frozen 2026-10-26 (provisional; see
[Format versions](#format-versions)).

## Hashing

Every object is identified by `sha256:<hex>`, the SHA-256 of a type prefix plus
a payload, written as 64 lowercase hex digits.

| Kind | Hash input |
| --- | --- |
| Blob | `"blob\n"` + raw bytes |
| Structured object | `"edn\n"` + canonical EDN bytes ([encoding.md](encoding.md)) |

There are only two prefixes. A structured object says what it is through a
`:type` key inside its content, so new kinds of object never need a new prefix.

A link is written `#h "sha256:<hex>"`. Links are the only way objects refer to
each other, so a walker can collect every reference in any structured object
without knowing its schema.

## Format versions

A format version fixes every input to a hash: the canonical encoding, the hash
prefixes, the object shapes in this document and the chunker. Versions are
named `firmament-N`.

- **`firmament-0` is frozen.** Its spec text and golden vectors no longer
  change; anything they leave ambiguous is settled by the vectors. It is
  provisional: a later version is expected once real use shows what to
  change.
- **A change to any hash input is a new version**, never an edit to an
  existing one. Each version keeps its own spec text and golden vectors.
- **Versions do not share hashes.** A version that hashes anything
  differently also changes the hash prefixes (for example `"blob1\n"` and
  `"edn1\n"`), so the same hash can never mean two different things. The
  exact scheme is chosen with that version.
- **A store holds one version** and records which. Moving data between
  versions is a re-import that produces new hashes.

## Trust model

The core has no signatures and no names. Something outside the system — a DNS
record, a web service, a note on paper — hands you a root hash. Everything
reachable from that root is verified by hashing alone.

Signed names, attestations and similar are not part of the core. They can be
built on top as ordinary objects.

## Core node types

### 1. Blob

Raw bytes. Never wrapped in EDN. Files up to 256 KiB are always a single blob
([chunking.md](chunking.md)).

### 2. Chunked file

A file larger than 256 KiB, split into chunks.

```clojure
{:type   :chunked
 :level  0
 :size   10737418240
 :chunks [{:link #h "sha256:..." :size 65811} ...]}
```

Large files use several levels of these nodes; see [chunking.md](chunking.md).

### 3. Tree

A map from names to entries. Directories, namespace trees and other listings
are all trees.

```clojure
{:type    :tree
 :entries {"notes.md" {:link #h "sha256:..."
                       :tags {:mime/type "text/markdown"}}
           "app"      {:children #h "sha256:..."}}}
```

**Entry names** are strings: non-empty, NFC, no `/`, and not `.` or `..`.

**Entries** are maps:

- `:tags` (optional) is a map of namespaced keywords to values describing the
  entry. It must be omitted rather than written empty.
- Every other key maps to a link. Common keys are `:link` (the entry's
  content) and `:children` (a subtree). Layers may define more, such as the
  Clojure layer's `:source/clj` ([clojure.md](clojure.md)).
- An entry must have at least one link.

Tools must preserve tags and link keys they don't understand.

POSIX metadata is not part of the core; `:posix/mode` can be an optional tag.

## Other structured objects

Any canonical EDN value is a valid structured object. Maps carrying a `:type`
other than the core types are left to higher layers. In particular,
**meta-objects** (statements about other objects, such as "H compiles to H¹" or
the analysis of a namespace) are ordinary objects that link to their subject.
Their schemas are defined outside the core, by Firmament layers or by anyone
else.

## Local store state

A node may keep metadata per hash, such as `{:pinned? true :expires ... :source ...}`.
This is local, never hashed, and never part of an object.
