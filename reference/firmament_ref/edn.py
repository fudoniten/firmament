"""Canonical EDN encoder and strict decoder (docs/spec/encoding.md).

Python values used for EDN values:

  map          dict, or EdnMap (what the decoder returns)
  vector       list or tuple (the decoder returns tuple)
  string       str
  integer      int (signed 64-bit)
  boolean      bool
  keyword      Keyword
  link         Link
  byte string  bytes

Python treats True == 1, so a dict cannot hold both `true` and `1` as keys.
EdnMap keys its entries by their canonical encoding and has no such problem.
"""

import base64
import re
import unicodedata
from collections.abc import Mapping

MAX_DEPTH = 256
INT_MIN = -(2**63)
INT_MAX = 2**63 - 1

_NAME = r"[A-Za-z*+!_?<>=\-][A-Za-z0-9*+!_?<>=\-]*"
_NAME_RE = re.compile(_NAME + r"\Z")
_NS_RE = re.compile(_NAME + r"(?:\." + _NAME + r")*\Z")
_LINK_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
_INT_RE = re.compile(r"-?(?:0|[1-9][0-9]*)\Z")
_SURROGATE_RE = re.compile("[\ud800-\udfff]")


class EncodeError(ValueError):
    pass


class DecodeError(ValueError):
    pass


class Keyword:
    """A keyword `:name` or `:ns/name`."""

    __slots__ = ("ns", "name")

    def __init__(self, a, b=None):
        if b is None:
            if "/" in a:
                ns, _, name = a.rpartition("/")
                if not ns:
                    raise EncodeError(f"invalid keyword: {a!r}")
            else:
                ns, name = None, a
        else:
            ns, name = a, b
        if ns is not None and not _NS_RE.match(ns):
            raise EncodeError(f"invalid keyword namespace: {ns!r}")
        if not _NAME_RE.match(name):
            raise EncodeError(f"invalid keyword name: {name!r}")
        self.ns = ns
        self.name = name

    def __str__(self):
        return f":{self.ns}/{self.name}" if self.ns else f":{self.name}"

    __repr__ = __str__

    def __eq__(self, other):
        return isinstance(other, Keyword) and (self.ns, self.name) == (other.ns, other.name)

    def __hash__(self):
        return hash(("kw", self.ns, self.name))


def kw(s):
    """Keyword from text, with or without the leading colon."""
    return Keyword(s[1:] if s.startswith(":") else s)


class Link:
    """A link `#h "sha256:<hex>"`. Holds the full "sha256:<hex>" string."""

    __slots__ = ("ref",)

    def __init__(self, ref):
        if not isinstance(ref, str) or not _LINK_RE.match(ref):
            raise EncodeError(f"invalid link: {ref!r}")
        self.ref = ref

    @property
    def digest(self):
        return bytes.fromhex(self.ref[len("sha256:"):])

    def __str__(self):
        return f'#h "{self.ref}"'

    __repr__ = __str__

    def __eq__(self, other):
        return isinstance(other, Link) and self.ref == other.ref

    def __hash__(self):
        return hash(("link", self.ref))


class EdnMap(Mapping):
    """An immutable map whose keys are compared by canonical encoding."""

    def __init__(self, pairs=()):
        self._items = {}
        for k, v in pairs:
            ek = encode(k)
            if ek in self._items:
                raise EncodeError(f"duplicate map key: {ek!r}")
            self._items[ek] = (k, v)

    def __getitem__(self, key):
        return self._items[encode(key)][1]

    def __iter__(self):
        return (k for k, _ in self._items.values())

    def __len__(self):
        return len(self._items)

    def items(self):
        return list(self._items.values())

    def __eq__(self, other):
        return isinstance(other, Mapping) and edn_equal(self, other)

    def __hash__(self):
        return hash(encode(self))

    def __repr__(self):
        return f"EdnMap({self.items()!r})"


def edn_equal(a, b):
    """Equality as EDN values (so `true` and `1` differ)."""
    return encode(a) == encode(b)


# ---------------------------------------------------------------- encoding

_ESCAPES = {'"': '\\"', "\\": "\\\\", "\n": "\\n", "\t": "\\t", "\r": "\\r"}


def _escape_char(c):
    if c in _ESCAPES:
        return _ESCAPES[c]
    o = ord(c)
    if o < 0x20 or o == 0x7F:
        return f"\\u{o:04x}"
    return c


def _encode_string(s):
    if _SURROGATE_RE.search(s):
        raise EncodeError("string contains a surrogate code point")
    s = unicodedata.normalize("NFC", s)
    return '"' + "".join(_escape_char(c) for c in s) + '"'


def _encode(v, depth, out):
    # bool before int: in Python, bool is a subclass of int.
    if v is True:
        out.append("true")
    elif v is False:
        out.append("false")
    elif isinstance(v, int):
        if not INT_MIN <= v <= INT_MAX:
            raise EncodeError(f"integer out of signed 64-bit range: {v}")
        out.append(str(v))
    elif isinstance(v, str):
        out.append(_encode_string(v))
    elif isinstance(v, Keyword):
        out.append(str(v))
    elif isinstance(v, Link):
        out.append(str(v))
    elif isinstance(v, (bytes, bytearray)):
        out.append('#b64 "' + base64.b64encode(bytes(v)).decode("ascii") + '"')
    elif isinstance(v, (list, tuple)):
        if depth >= MAX_DEPTH:
            raise EncodeError(f"nesting deeper than {MAX_DEPTH}")
        out.append("[" + " ".join(_encode_str(x, depth + 1) for x in v) + "]")
    elif isinstance(v, Mapping):
        if depth >= MAX_DEPTH:
            raise EncodeError(f"nesting deeper than {MAX_DEPTH}")
        entries = {}
        for k, x in v.items():
            ek = _encode_str(k, depth + 1)
            if ek in entries:
                raise EncodeError(f"duplicate map key: {ek}")
            entries[ek] = _encode_str(x, depth + 1)
        keys = sorted(entries, key=lambda e: e.encode("utf-8"))
        out.append("{" + " ".join(k + " " + entries[k] for k in keys) + "}")
    else:
        raise EncodeError(f"value not allowed in canonical EDN: {v!r}")


def _encode_str(v, depth):
    out = []
    _encode(v, depth, out)
    return out[0]


def encode(v):
    """Canonical EDN bytes for v. Strings are normalized to NFC."""
    return _encode_str(v, 0).encode("utf-8")


# ---------------------------------------------------------------- decoding

_DELIMS = frozenset(' []{}"')


class _Parser:
    def __init__(self, text):
        self.s = text
        self.i = 0

    def fail(self, msg):
        raise DecodeError(f"{msg} at offset {self.i}")

    def peek(self):
        return self.s[self.i] if self.i < len(self.s) else ""

    def expect(self, c):
        if self.peek() != c:
            self.fail(f"expected {c!r}")
        self.i += 1

    def token(self):
        j = self.i
        while j < len(self.s) and self.s[j] not in _DELIMS:
            j += 1
        tok = self.s[self.i:j]
        self.i = j
        return tok

    def value(self, depth):
        c = self.peek()
        if c == "[":
            return self.vector(depth + 1)
        if c == "{":
            return self.map(depth + 1)
        if c == '"':
            return self.string()
        if c == "#":
            return self.tagged()
        if c == ":":
            return self.keyword()
        if c == "-" or c.isdigit():
            return self.integer()
        if c in ("t", "f"):
            tok = self.token()
            if tok == "true":
                return True
            if tok == "false":
                return False
            self.fail(f"invalid token {tok!r}")
        self.fail(f"unexpected {c!r}" if c else "unexpected end of input")

    def check_depth(self, depth):
        if depth > MAX_DEPTH:
            self.fail(f"nesting deeper than {MAX_DEPTH}")

    def vector(self, depth):
        self.check_depth(depth)
        self.expect("[")
        items = []
        if self.peek() == "]":
            self.i += 1
            return ()
        while True:
            items.append(self.value(depth))
            if self.peek() == "]":
                self.i += 1
                return tuple(items)
            self.expect(" ")

    def map(self, depth):
        self.check_depth(depth)
        self.expect("{")
        pairs = []
        last = None
        if self.peek() == "}":
            self.i += 1
            return EdnMap()
        while True:
            at = self.i
            k = self.value(depth)
            ek = encode(k)
            if last is not None and ek <= last:
                self.i = at
                self.fail("duplicate map key" if ek == last else "map keys out of order")
            last = ek
            self.expect(" ")
            pairs.append((k, self.value(depth)))
            if self.peek() == "}":
                self.i += 1
                return EdnMap(pairs)
            self.expect(" ")

    def string(self):
        self.expect('"')
        chars = []
        while True:
            c = self.peek()
            if c == "":
                self.fail("unterminated string")
            self.i += 1
            if c == '"':
                break
            if c == "\\":
                e = self.peek()
                self.i += 1
                if e in ('"', "\\"):
                    chars.append(e)
                elif e == "n":
                    chars.append("\n")
                elif e == "t":
                    chars.append("\t")
                elif e == "r":
                    chars.append("\r")
                elif e == "u":
                    hexs = self.s[self.i:self.i + 4]
                    if not re.fullmatch(r"[0-9a-f]{4}", hexs):
                        self.fail("\\u escape needs 4 lowercase hex digits")
                    o = int(hexs, 16)
                    if not (o < 0x20 or o == 0x7F) or chr(o) in _ESCAPES:
                        self.fail(f"\\u{hexs} is not the canonical form of that character")
                    self.i += 4
                    chars.append(chr(o))
                else:
                    self.fail(f"invalid escape \\{e}")
            elif ord(c) < 0x20 or ord(c) == 0x7F:
                self.fail(f"unescaped control character U+{ord(c):04X}")
            else:
                chars.append(c)
        s = "".join(chars)
        if unicodedata.normalize("NFC", s) != s:
            self.fail("string is not in NFC")
        return s

    def tagged(self):
        self.expect("#")
        tag = self.token()
        if tag not in ("h", "b64"):
            self.fail(f"unknown tag #{tag}")
        self.expect(" ")
        s = self.string()
        if tag == "h":
            if not _LINK_RE.match(s):
                self.fail(f"invalid link {s!r}")
            return Link(s)
        try:
            data = base64.b64decode(s, validate=True)
        except ValueError:
            self.fail("invalid base64")
        if base64.b64encode(data).decode("ascii") != s:
            self.fail("non-canonical base64")
        return data

    def keyword(self):
        tok = self.token()
        try:
            return Keyword(tok[1:])
        except EncodeError:
            self.fail(f"invalid keyword {tok!r}")

    def integer(self):
        tok = self.token()
        if not _INT_RE.match(tok) or tok == "-0":
            self.fail(f"invalid integer {tok!r}")
        n = int(tok)
        if not INT_MIN <= n <= INT_MAX:
            self.fail(f"integer out of signed 64-bit range: {tok}")
        return n


def decode(data):
    """Decode canonical EDN bytes, rejecting anything non-canonical."""
    if not isinstance(data, (bytes, bytearray)):
        raise TypeError("decode takes bytes")
    try:
        text = bytes(data).decode("utf-8", errors="strict")
    except UnicodeDecodeError as e:
        raise DecodeError(f"invalid UTF-8: {e}") from None
    p = _Parser(text)
    v = p.value(0)
    if p.i != len(text):
        p.fail("trailing input")
    # Belt and braces: the parser above should already reject everything
    # whose re-encoding differs.
    if encode(v) != bytes(data):
        raise DecodeError("input is not the canonical encoding of its value")
    return v
