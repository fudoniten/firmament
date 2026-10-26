"""Object hashing (docs/spec/objects.md)."""

import hashlib

from .edn import Link, encode

BLOB_PREFIX = b"blob\n"
EDN_PREFIX = b"edn\n"


def _ref(digest):
    return "sha256:" + digest.hex()


def blob_hash(data):
    """The "sha256:<hex>" name of a blob."""
    return _ref(hashlib.sha256(BLOB_PREFIX + bytes(data)).digest())


def edn_hash_bytes(canonical):
    """The name of a structured object, given its canonical bytes."""
    return _ref(hashlib.sha256(EDN_PREFIX + canonical).digest())


def edn_hash(value):
    """The name of a structured object, given its value."""
    return edn_hash_bytes(encode(value))


def blob_link(data):
    return Link(blob_hash(data))


def edn_link(value):
    return Link(edn_hash(value))
