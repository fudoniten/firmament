# Chunking

Format version: `firmament-0` (draft).

The same bytes must always produce the same hash, so the chunking algorithm and
every constant are part of the format. The algorithm is FastCDC-style (gear
hash with normalized chunking, after Xia et al., 2020), but it is **defined
entirely by this document**. Its cut points are not expected to match other
FastCDC libraries.

## Blob or chunked

- Files of **256 KiB (262144 bytes) or less** are a single blob.
- Larger files are always a chunked file. Since a chunk is at most 256 KiB, a
  chunked file always has at least two chunks.

## Parameters

| Name | Value |
| --- | --- |
| `MIN` | 16384 (16 KiB) |
| `AVG` | 65536 (64 KiB) |
| `MAX` | 262144 (256 KiB) |
| `MASK_S` | top 18 bits set: `0xFFFFC00000000000` |
| `MASK_L` | top 14 bits set: `0xFFFC000000000000` |

The masks use normalized chunking level 2 (16 ± 2 bits). They select the
**high** bits of the gear hash, because after the left shift these depend on
the last 64 bytes, while low bits depend only on the most recent few bytes.

## Gear table

`GEAR[i]`, for `i` in 0–255, is the first 8 bytes, read as a big-endian unsigned
64-bit integer, of:

```
SHA-256("firmament-gear-v0" ‖ byte(i))
```

where `byte(i)` is the single byte with value `i`. The full table is in
[`test-resources/vectors/gear.edn`](../../test-resources/vectors/gear.edn).

## Cut-point algorithm

All arithmetic is on unsigned 64-bit integers, wrapping on overflow.

```
cut(data, start):
  n = len(data) - start
  if n <= MIN: return start + n
  end    = start + min(n, MAX)
  normal = start + min(n, AVG)
  fp = 0
  i  = start + MIN              ; the first MIN bytes are not hashed
  while i < normal:
    fp = (fp << 1) + GEAR[data[i]]
    i += 1
    if fp & MASK_S == 0: return i
  while i < end:
    fp = (fp << 1) + GEAR[data[i]]
    i += 1
    if fp & MASK_L == 0: return i
  return end
```

Chunks are produced by calling `cut` repeatedly from offset 0. Each chunk is a
blob. The last chunk may be shorter than `MIN`.

## Chunk index levels

A file of 10 GiB has about 160,000 chunks, too many for one node, so the chunk
list is split into a tree of `:chunked` nodes. The boundaries are
content-defined, so an edit only rewrites index nodes near it.

```clojure
{:type   :chunked
 :level  0                                   ; 0: links point to chunk blobs
 :size   <total bytes covered by this node>
 :chunks [{:link #h "sha256:..." :size <bytes>} ...]}
```

At level `k > 0`, each link points to a `:chunked` node at level `k - 1`, and
its `:size` is the total bytes under that node. A node's `:size` is the sum of
its entries' sizes.

**Building the index.** Start with the list of level-0 entries (one per chunk).
Group entries into nodes in order. A node ends after an entry `e` when any of
these holds:

1. **Boundary:** the first two bytes of the SHA-256 digest `e` links to
   (the digest in its `#h` link), read as a
   big-endian 16-bit integer, have their low 10 bits all zero (about 1 in 1024).
2. **Cap:** the node has reached 4096 entries.
3. **End:** `e` is the last entry.

If this produces exactly one node, that node is the file's root. Otherwise,
make one entry per node (its link and `:size`) and repeat at the next level.

## Verification

Because there are no external reference vectors, the chunker is implemented
twice — in Clojure and in an independent Python reference
([`reference/`](../../reference/)) — and the golden vectors are accepted only
when both agree. Cut points and file roots are in
[`chunking.edn`](../../test-resources/vectors/chunking.edn); index building
on its own, including the 4096-entry cap and three-level indexes, is in
[`index.edn`](../../test-resources/vectors/index.edn).
