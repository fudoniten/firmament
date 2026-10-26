#!/usr/bin/env python3
"""Generate the golden vectors in test-resources/vectors/.

  python3 reference/gen_vectors.py           write the vector files
  python3 reference/gen_vectors.py --check   fail if they differ from the files on disk
"""

import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from firmament_ref import FORMAT_VERSION, gen  # noqa: E402
from firmament_ref.chunking import (  # noqa: E402
    GEAR, INDEX_CAP, MAX, MIN, build_index, is_boundary, store_file)
from firmament_ref.edn import (  # noqa: E402
    MAX_DEPTH, DecodeError, EdnMap, Keyword, Link, decode, encode, kw)
from firmament_ref.hashing import blob_hash, edn_hash_bytes  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "test-resources", "vectors")

EMPTY = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
H1 = Link("sha256:" + "0123456789abcdef" * 4)
H2 = Link("sha256:" + "f" * 64)


# --------------------------------------------------------- vector-file EDN
#
# Vector files are EDN for a Clojure reader, not canonical EDN. They are
# pure ASCII: every non-ASCII character in a string is a \uXXXX escape of
# its UTF-16 code units, so the values survive any EDN reader unchanged.

def _ascii_string(s):
    out = ['"']
    for c in s:
        o = ord(c)
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c == "\n":
            out.append("\\n")
        elif c == "\t":
            out.append("\\t")
        elif c == "\r":
            out.append("\\r")
        elif 0x20 <= o < 0x7F:
            out.append(c)
        elif o > 0xFFFF:
            o -= 0x10000
            out.append(f"\\u{0xD800 + (o >> 10):04x}\\u{0xDC00 + (o & 0x3FF):04x}")
        else:
            out.append(f"\\u{o:04x}")
    out.append('"')
    return "".join(out)


def w(v):
    """Vector-file EDN for a value (canonical key order, ASCII only)."""
    if v is True:
        return "true"
    if v is False:
        return "false"
    if isinstance(v, int):
        return str(v)
    if isinstance(v, str):
        return _ascii_string(v)
    if isinstance(v, (Keyword, Link)):
        return str(v)
    if isinstance(v, bytes):
        return encode(v).decode("ascii")
    if isinstance(v, (list, tuple)):
        return "[" + " ".join(w(x) for x in v) + "]"
    items = [(encode(k), k, x) for k, x in v.items()]
    items.sort(key=lambda t: t[0])
    return "{" + " ".join(w(k) + " " + w(x) for _, k, x in items) + "}"


def record(*fields):
    """One vector as a single-line map; fields are (key, edn-text) pairs."""
    return "{" + " ".join(f":{k} {text}" for k, text in fields) + "}"


def edn_file(header, sections):
    lines = [f";; {line}" if line else ";;" for line in header]
    lines.append("{:format " + w(FORMAT_VERSION))
    for name, records in sections:
        lines.append(f" :{name}")
        if not records:
            lines.append(" []")
            continue
        for n, r in enumerate(records):
            lines.append((" [" if n == 0 else "  ") + r)
        lines[-1] += "]"
    lines[-1] += "}"
    return "\n".join(lines) + "\n"


# ------------------------------------------------------------- gear table

def gear_file():
    lines = [
        ";; GEAR[i] for i in 0..255, as 16 lowercase hex digits (unsigned 64-bit,",
        ";; big-endian). GEAR[i] is the first 8 bytes of",
        ';; SHA-256("firmament-gear-v0" || byte(i)). See docs/spec/chunking.md.',
        "{:format " + w(FORMAT_VERSION),
        " :gear",
    ]
    for i in range(0, 256, 4):
        row = " ".join(f'"{g:016x}"' for g in GEAR[i:i + 4])
        lines.append((" [" if i == 0 else "  ") + row)
    lines[-1] += "]}"
    return "\n".join(lines) + "\n"


# --------------------------------------------------------------- encoding

def nested(depth):
    v = []
    for _ in range(depth - 1):
        v = [v]
    return v


def valid_values():
    k = kw
    return [
        ("true", True),
        ("false", False),
        ("zero", 0),
        ("positive integer", 17),
        ("negative integer", -42),
        ("int64 max", 2**63 - 1),
        ("int64 min", -(2**63)),
        ("empty string", ""),
        ("ascii string", "hello, world"),
        ("string escapes", 'quote " backslash \\ newline \n tab \t return \r'),
        ("string control escapes",
         "".join(chr(i) for i in range(0x20) if chr(i) not in "\n\t\r") + "\x7f"),
        ("string slash and apostrophe are raw", "a/b 'c' #d ;e"),
        ("string C1 controls are raw", "\x80\x85\x9f"),
        ("string line separators are raw", "\u2028\u2029"),
        ("string non-ASCII", "caf\u00e9 \u65e5\u672c \u00fc"),
        ("string non-BMP", "\U0001F600 \U00010348"),
        ("string NUL", "\x00"),
        ("keyword", k("a")),
        ("keyword namespaced", k("mime/type")),
        ("keyword dotted namespace", k("my.app/main")),
        ("keyword special characters", k("*+!_?<>=-")),
        ("keyword digits after first character", k("a1/b2")),
        ("keyword starting with minus", k("-1")),
        ("keyword starting with plus", k("+")),
        ("keyword named like literals", [k("true"), k("nil"), k("false")]),
        ("link", H1),
        ("link to empty", Link(EMPTY)),
        ("bytes empty", b""),
        ("bytes one (==)", b"\x00"),
        ("bytes two (=)", b"\xff\xfe"),
        ("bytes three (no padding)", b"\x00\x01\x02"),
        ("bytes all values", bytes(range(256))),
        ("empty vector", []),
        ("empty map", {}),
        ("vector of scalars", [1, "two", k("three"), True, H2, b"4"]),
        ("vector nested", [[], [[]], [1, [2, [3]]]]),
        ("map simple", {k("a"): 1, k("b"): [True, "x"]}),
        ("map keys sorted by bytes", {k("b"): 2, k("a"): 1, k("aa"): 3, k("a/b"): 4}),
        ("map string before keyword", {k("a"): 2, "z": 1}),
        ("map mixed key types", EdnMap([
            (k("k"), 1), ("s", 2), (7, 3), (-7, 4), (True, 5), (False, 6),
            ([1], 7), ({}, 8), (H1, 9), (b"x", 10)])),
        ("map true and 1 are different keys", EdnMap([(True, "t"), (1, "one")])),
        ("map integer keys sort as text", EdnMap([(10, "a"), (9, "b"), (-1, "c"), (100, "d")])),
        ("map keys in UTF-8 order, not UTF-16", {"\uff61": 1, "\U0001F600": 2}),
        ("map nested", {k("outer"): {k("inner"): {}}}),
        ("nesting at the limit", nested(MAX_DEPTH)),
        ("tree", {k("type"): k("tree"), k("entries"): {
            "notes.md": {k("link"): H1, k("tags"): {k("mime/type"): "text/markdown"}},
            "app": {k("children"): H2}}}),
        ("chunked node", {k("type"): k("chunked"), k("level"): 0, k("size"): 300000,
                          k("chunks"): [{k("link"): H1, k("size"): 200000},
                                        {k("link"): H2, k("size"): 100000}]}),
        ("clojure manifest", {k("type"): k("clj/manifest"), k("namespaces"): H1,
                              k("resources"): H2, k("jars"): Link(EMPTY),
                              k("tags"): {k("clj/main"): "my.app"}}),
    ]


def normalize_values():
    return [
        ("decomposed e-acute", "e\u0301", "\u00e9"),
        ("decomposed Hangul", "\u1100\u1161", "\uac00"),
        ("combining mark reorder", "a\u0323\u0302", "\u1ead"),
        ("singleton (Angstrom sign)", "\u212b", "\u00c5"),
    ]


def invalid_inputs():
    big = "9223372036854775808"
    return [
        # whitespace and layout
        ("empty input", b""),
        ("leading space", b" 1"),
        ("trailing space", b"1 "),
        ("trailing newline", b"1\n"),
        ("two top-level values", b"1 2"),
        ("double space in vector", b"[1  2]"),
        ("space after [", b"[ 1]"),
        ("space before ]", b"[1 ]"),
        ("space inside empty vector", b"[ ]"),
        ("space after {", b"{ :a 1}"),
        ("comma separator", b"[1,2]"),
        ("comma and space", b"[1, 2]"),
        ("tab separator", b"[1\t2]"),
        ("newline separator", b"[1\n2]"),
        ("no separator", b"[\"a\"\"b\"]"),
        ("no separator after vector", b"[[][]]"),
        ("byte order mark", b"\xef\xbb\xbf1"),
        # maps
        ("keys out of order", b"{:b 1 :a 2}"),
        ("keyword before string", b"{:a 1 \"z\" 2}"),
        ("integer keys in numeric order", b"{9 1 10 2}"),
        ("duplicate key", b"{:a 1 :a 2}"),
        ("odd number of forms in map", b"{:a}"),
        ("map with nil value", b"{:a nil}"),
        # forbidden types
        ("nil", b"nil"),
        ("float", b"1.5"),
        ("float exponent", b"1e3"),
        ("ratio", b"1/2"),
        ("bigint suffix", b"1N"),
        ("bigdec suffix", b"1M"),
        ("symbol", b"foo"),
        ("character", b"\\a"),
        ("list", b"(1 2)"),
        ("set", b"#{1}"),
        ("comment", b"1 ;x"),
        ("discard", b"#_1 2"),
        ("metadata", b"^:a [1]"),
        ("unknown tag", b"#inst \"2026-10-26T00:00:00Z\""),
        ("namespaced map", b"#:a{:b 1}"),
        ("True is not a boolean", b"True"),
        # integers
        ("plus sign", b"+1"),
        ("leading zero", b"01"),
        ("negative zero", b"-0"),
        ("negative leading zero", b"-01"),
        ("lone minus", b"-"),
        ("hex integer", b"0x10"),
        ("octal-looking integer", b"010"),
        ("int64 max + 1", big.encode()),
        ("int64 min - 1", b"-9223372036854775809"),
        ("huge integer", b"1" * 40),
        # strings
        ("unterminated string", b"\"abc"),
        ("raw newline in string", b"\"a\nb\""),
        ("raw tab in string", b"\"a\tb\""),
        ("raw NUL in string", b"\"\x00\""),
        ("raw DEL in string", b"\"\x7f\""),
        ("uppercase \\u escape", b"\"\\u001B\""),
        ("\\u escape of printable ASCII", b"\"\\u0041\""),
        ("\\u escape of non-ASCII", b"\"\\u00e9\""),
        ("\\u escape of newline", b"\"\\u000a\""),
        ("\\u escape of quote", b"\"\\u0022\""),
        ("\\u escape of a surrogate", b"\"\\ud83d\\ude00\""),
        ("short \\u escape", b"\"\\u41\""),
        ("\\b escape", b"\"\\b\""),
        ("\\f escape", b"\"\\f\""),
        ("\\/ escape", b"\"\\/\""),
        ("\\' escape", b"\"\\'\""),
        ("not NFC (decomposed e-acute)", "\"e\u0301\"".encode()),
        ("not NFC (Angstrom sign)", "\"\u212b\"".encode()),
        ("invalid UTF-8", b"\"\xff\""),
        ("truncated UTF-8", b"\"\xc3\""),
        ("overlong UTF-8", b"\"\xc0\xaf\""),
        ("UTF-8 encoded surrogate", b"\"\xed\xa0\xbd\xed\xb8\x80\""),
        ("code point above U+10FFFF", b"\"\xf4\x90\x80\x80\""),
        # keywords
        ("bare colon", b":"),
        ("auto-resolved keyword", b"::a"),
        ("keyword starting with digit", b":1a"),
        ("keyword with dot in name", b":a.b"),
        ("keyword with two slashes", b":a/b/c"),
        ("keyword with empty namespace", b":/a"),
        ("keyword with empty name", b":a/"),
        ("keyword slash only", b":/"),
        ("keyword with empty namespace segment", b":a..b/c"),
        ("keyword namespace ending in dot", b":a./c"),
        ("keyword with non-ASCII", ":caf\u00e9".encode()),
        ("keyword with apostrophe", b":a'"),
        ("keyword with hash", b":a#"),
        ("keyword with colon inside", b":a:b"),
        ("keyword with ampersand", b":a&b"),
        ("keyword with percent", b":a%"),
        ("keyword with dollar", b":a$"),
        # links
        ("link without space", b"#h\"sha256:" + b"0" * 64 + b"\""),
        ("link with two spaces", b"#h  \"sha256:" + b"0" * 64 + b"\""),
        ("link uppercase hex", b"#h \"sha256:" + b"A" * 64 + b"\""),
        ("link short", b"#h \"sha256:" + b"0" * 63 + b"\""),
        ("link long", b"#h \"sha256:" + b"0" * 65 + b"\""),
        ("link other algorithm", b"#h \"sha512:" + b"0" * 64 + b"\""),
        ("link without algorithm", b"#h \"" + b"0" * 64 + b"\""),
        ("link to a non-string", b"#h 1"),
        ("link tag alone", b"#h"),
        # byte strings
        ("base64 missing padding", b"#b64 \"AA\""),
        ("base64 extra padding", b"#b64 \"AAE==\""),
        ("base64 nonzero trailing bits (2)", b"#b64 \"AB==\""),
        ("base64 nonzero trailing bits (1)", b"#b64 \"AAF=\""),
        ("base64 url alphabet", b"#b64 \"-_8=\""),
        ("base64 with whitespace", b"#b64 \"AA AA\""),
        ("base64 with newline escape", b"#b64 \"AAAA\\nAAAA\""),
        ("base64 padding only", b"#b64 \"====\""),
        ("base64 bad length", b"#b64 \"A\""),
        ("b64 tag without space", b"#b64\"\""),
        # nesting
        ("nesting over the limit", b"[" * (MAX_DEPTH + 1) + b"]" * (MAX_DEPTH + 1)),
        ("unclosed vector", b"[1"),
        ("unclosed map", b"{:a 1"),
        ("unbalanced close", b"]"),
    ]


def encoding_file():
    valid = []
    for name, v in valid_values():
        canonical = encode(v)
        assert encode(decode(canonical)) == canonical, name
        valid.append(record(("name", w(name)), ("value", w(v)),
                            ("hex", w(canonical.hex())), ("hash", w(edn_hash_bytes(canonical)))))
    normalize = []
    for name, raw, nfc in normalize_values():
        v = {kw("s"): raw}
        canonical = encode(v)
        assert canonical == encode({kw("s"): nfc}), name
        normalize.append(record(("name", w(name)), ("value", w(v)),
                                ("hex", w(canonical.hex())), ("hash", w(edn_hash_bytes(canonical)))))
    invalid = []
    for name, data in invalid_inputs():
        try:
            decode(data)
        except DecodeError:
            pass
        else:
            raise AssertionError(f"invalid vector decodes: {name}")
        invalid.append(record(("name", w(name)), ("hex", w(data.hex()))))
    return edn_file(
        [
            "Canonical EDN vectors (docs/spec/encoding.md). Generated by",
            "reference/gen_vectors.py; do not edit by hand.",
            "",
            ":valid      encode(value) has bytes :hex, and decoding :hex gives value.",
            "            :hash is SHA-256(\"edn\\n\" + bytes).",
            ":normalize  values whose strings are not NFC: encoders normalize, so",
            "            encode(value) is :hex, but decoding :hex gives the NFC value.",
            ":invalid    bytes a decoder must reject. :name says why.",
        ],
        [("valid", valid), ("normalize", normalize), ("invalid", invalid)],
    )


# ------------------------------------------------------------------ blobs

def blobs_file():
    cases = [
        ("empty", b""),
        ("hello newline", b"hello\n"),
        ("all byte values", bytes(range(256))),
        ("looks like EDN", b"{:a 1}"),
    ]
    records = [record(("name", w(n)), ("hex", w(d.hex())), ("hash", w(blob_hash(d))))
               for n, d in cases]
    return edn_file(
        [
            "Blob hashes (docs/spec/objects.md): :hash is SHA-256(\"blob\\n\" + bytes),",
            "where bytes are :hex. Generated by reference/gen_vectors.py.",
        ],
        [("blobs", records)],
    )


# --------------------------------------------------------------- chunking

def data_from(spec):
    g = spec[kw("gen")]
    if g == kw("zeros"):
        return gen.zeros(spec[kw("length")])
    if g == kw("sha256-ctr"):
        return gen.sha256_ctr(spec[kw("seed")], spec[kw("offset")], spec[kw("length")])
    if g == kw("concat"):
        return gen.concat([data_from(p) for p in spec[kw("parts")]])
    raise ValueError(g)


def ctr(seed, length, offset=0):
    return {kw("gen"): kw("sha256-ctr"), kw("seed"): seed, kw("offset"): offset,
            kw("length"): length}


def zeros(length):
    return {kw("gen"): kw("zeros"), kw("length"): length}


MiB = 1 << 20


def chunking_cases():
    return [
        ("empty file is a blob", zeros(0)),
        ("one byte is a blob", ctr("one", 1)),
        ("MIN bytes is a blob", ctr("min", MIN)),
        ("MAX bytes is a blob", ctr("max", MAX)),
        ("MAX + 1 bytes is chunked", ctr("max+1", MAX + 1)),
        ("zeros never cut early", zeros(MiB + 1000)),
        ("random 1 MiB", ctr("random-a", MiB)),
        ("random 4 MiB with short tail", ctr("random-b", 4 * MiB + 1234)),
        ("random 4 MiB, 100 bytes inserted at 2 MiB",
         {kw("gen"): kw("concat"), kw("parts"): [
             ctr("random-b", 2 * MiB), ctr("insert", 100),
             ctr("random-b", 2 * MiB + 1234, offset=2 * MiB)]}),
        ("random then zeros",
         {kw("gen"): kw("concat"), kw("parts"): [ctr("mixed", MiB), zeros(MiB)]}),
    ]


def chunking_file():
    records = []
    for name, spec in chunking_cases():
        f = store_file(data_from(spec))
        fields = [("name", w(name)), ("data", w(spec)), ("chunked", w(f.chunked))]
        if f.chunked:
            fields.append(("cuts", w(f.cuts)))
        fields.append(("root", w(f.root.ref)))
        records.append(record(*fields))
    return edn_file(
        [
            "Chunking vectors (docs/spec/chunking.md). Generated by",
            "reference/gen_vectors.py.",
            "",
            ":data     generated input; see README.md for the generators.",
            ":chunked  false for a single blob, true for a chunked file.",
            ":cuts     end offset of each chunk, in order (chunked files only).",
            ":root     hash of the file: its blob, or its root :chunked node.",
        ],
        [("files", records)],
    )


# ------------------------------------------------------------ chunk index

def index_entries(seed, count):
    return [(Link("sha256:" + gen.ctr_block(seed, i).hex()), 65536 + i) for i in range(count)]


def find_seed(prefix, count, ok):
    for n in range(10000):
        seed = f"{prefix}-{n}"
        if ok(seed, count):
            return seed
    raise RuntimeError(f"no seed found for {prefix}")


def has_cap(seed, count):
    run = 0
    for link, _ in index_entries(seed, count):
        run = 0 if is_boundary(link) else run + 1
        if run >= INDEX_CAP:
            return True
    return False


def three_levels(seed, count):
    return len(build_index(index_entries(seed, count)).levels) >= 3


def index_cases():
    return [
        ("two entries, one node", "index-small", 2),
        ("a few level-0 boundaries", "index-boundary", 1500),
        ("several level-0 nodes", "index-two-levels", 6000),
        ("cap of 4096 entries", find_seed("index-cap", 9000, has_cap), 9000),
        ("three levels", find_seed("index-three", 250000, three_levels), 250000),
    ]


def index_file():
    records = []
    for name, seed, count in index_cases():
        idx = build_index(index_entries(seed, count))
        spec = {kw("seed"): seed, kw("count"): count}
        records.append(record(("name", w(name)), ("entries", w(spec)),
                              ("levels", w(idx.levels)), ("root", w(idx.root.ref))))
    return edn_file(
        [
            "Chunk index vectors (docs/spec/chunking.md, \"Building the index\").",
            "Generated by reference/gen_vectors.py.",
            "",
            ":entries  level-0 entries, :count of them. Entry i (from 0) links to",
            "          \"sha256:\" + hex(SHA-256(utf8(:seed) || uint64be(i))) and has",
            "          :size 65536 + i. The links are arbitrary hashes, not real chunks.",
            ":levels   for each level from 0, the number of entries in each node.",
            ":root     hash of the root :chunked node.",
        ],
        [("indexes", records)],
    )


# ------------------------------------------------------------------- main

def files():
    return {
        "gear.edn": gear_file(),
        "encoding.edn": encoding_file(),
        "blobs.edn": blobs_file(),
        "chunking.edn": chunking_file(),
        "index.edn": index_file(),
    }


def main(argv):
    check = "--check" in argv
    out = files()
    stale = []
    for name, text in out.items():
        path = os.path.join(OUT, name)
        if check:
            try:
                with open(path, encoding="ascii") as f:
                    if f.read() != text:
                        stale.append(name)
            except FileNotFoundError:
                stale.append(name)
        else:
            os.makedirs(OUT, exist_ok=True)
            with open(path, "w", encoding="ascii", newline="\n") as f:
                f.write(text)
    if stale:
        print("vectors out of date: " + ", ".join(stale), file=sys.stderr)
        return 1
    print(("checked " if check else "wrote ") + ", ".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
