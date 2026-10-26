"""Deterministic test data, as described in test-resources/vectors/README.md.

Vectors describe their input data with a small spec instead of storing it:

  {:gen :zeros :length n}
  {:gen :sha256-ctr :seed s :offset o :length n}
  {:gen :concat :parts [spec ...]}

The sha256-ctr stream for seed s is the concatenation, for i = 0, 1, 2, ...,
of SHA-256(utf8(s) || uint64be(i)). The spec selects bytes [o, o + n).
"""

import hashlib


def ctr_block(seed, i):
    return hashlib.sha256(seed.encode("utf-8") + i.to_bytes(8, "big")).digest()


def sha256_ctr(seed, offset, length):
    first = offset // 32
    last = (offset + length + 31) // 32
    stream = b"".join(ctr_block(seed, i) for i in range(first, last))
    skip = offset - first * 32
    return stream[skip:skip + length]


def zeros(length):
    return bytes(length)


def concat(parts):
    return b"".join(parts)
