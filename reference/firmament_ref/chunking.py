"""Chunker and chunk index (docs/spec/chunking.md)."""

import hashlib

from .edn import Keyword, Link, encode
from .hashing import blob_hash, edn_hash_bytes

MIN = 16384
AVG = 65536
MAX = 262144
MASK_S = 0xFFFFC00000000000
MASK_L = 0xFFFC000000000000
U64 = 0xFFFFFFFFFFFFFFFF

INDEX_BOUNDARY_MASK = 0x3FF
INDEX_CAP = 4096

GEAR_SEED = b"firmament-gear-v0"
GEAR = tuple(
    int.from_bytes(hashlib.sha256(GEAR_SEED + bytes([i])).digest()[:8], "big")
    for i in range(256)
)

_TYPE = Keyword("type")
_CHUNKED = Keyword("chunked")
_LEVEL = Keyword("level")
_SIZE = Keyword("size")
_CHUNKS = Keyword("chunks")
_LINK = Keyword("link")


def cut(data, start):
    """End offset of the chunk that starts at `start`."""
    n = len(data) - start
    if n <= MIN:
        return start + n
    end = start + min(n, MAX)
    normal = start + min(n, AVG)
    gear = GEAR
    fp = 0
    i = start + MIN
    while i < normal:
        fp = ((fp << 1) + gear[data[i]]) & U64
        i += 1
        if fp & MASK_S == 0:
            return i
    while i < end:
        fp = ((fp << 1) + gear[data[i]]) & U64
        i += 1
        if fp & MASK_L == 0:
            return i
    return end


def cut_points(data):
    """End offsets of every chunk of `data`, in order."""
    points = []
    start = 0
    while start < len(data):
        start = cut(data, start)
        points.append(start)
    return points


def is_boundary(link):
    """Whether an index node ends after an entry linking to `link`."""
    d = link.digest
    return (int.from_bytes(d[:2], "big") & INDEX_BOUNDARY_MASK) == 0


def chunked_node(level, entries):
    """The :chunked object for one index node; entries are (Link, size)."""
    return {
        _TYPE: _CHUNKED,
        _LEVEL: level,
        _SIZE: sum(size for _, size in entries),
        _CHUNKS: [{_LINK: link, _SIZE: size} for link, size in entries],
    }


def group_entries(entries):
    """Split one level's entries into the lists that become its nodes."""
    groups = []
    group = []
    for n, (link, size) in enumerate(entries):
        group.append((link, size))
        if is_boundary(link) or len(group) == INDEX_CAP or n == len(entries) - 1:
            groups.append(group)
            group = []
    return groups


class Index:
    """A built chunk index.

    root     Link to the root :chunked node
    levels   per level, the number of entries in each node
    objects  {hash: canonical bytes} for every index node
    """

    def __init__(self, root, levels, objects):
        self.root = root
        self.levels = levels
        self.objects = objects


def build_index(entries):
    """Build the chunk index over level-0 entries [(Link, size), ...]."""
    if not entries:
        raise ValueError("a chunk index needs at least one entry")
    level = 0
    levels = []
    objects = {}
    while True:
        groups = group_entries(entries)
        levels.append([len(g) for g in groups])
        next_entries = []
        for g in groups:
            canonical = encode(chunked_node(level, g))
            h = edn_hash_bytes(canonical)
            objects[h] = canonical
            next_entries.append((Link(h), sum(size for _, size in g)))
        if len(groups) == 1:
            return Index(next_entries[0][0], levels, objects)
        entries = next_entries
        level += 1


class StoredFile:
    """A file as stored.

    root     Link to the file: its blob, or its root :chunked node
    chunked  whether it was chunked
    cuts     chunk end offsets (empty for a single blob)
    index    the Index, when chunked
    blobs    {hash: bytes} for the file's blobs
    """

    def __init__(self, root, chunked, cuts, index, blobs):
        self.root = root
        self.chunked = chunked
        self.cuts = cuts
        self.index = index
        self.blobs = blobs


def store_file(data):
    """Turn file bytes into a single blob or a chunked file."""
    data = bytes(data)
    if len(data) <= MAX:
        h = blob_hash(data)
        return StoredFile(Link(h), False, [], None, {h: data})
    cuts = cut_points(data)
    blobs = {}
    entries = []
    start = 0
    for end in cuts:
        chunk = data[start:end]
        h = blob_hash(chunk)
        blobs[h] = chunk
        entries.append((Link(h), len(chunk)))
        start = end
    index = build_index(entries)
    return StoredFile(index.root, True, cuts, index, blobs)
