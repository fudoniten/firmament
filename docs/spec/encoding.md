# Canonical Encoding

Format version: `firmament-0` (draft — not frozen until golden vectors land).

Every structured object is encoded as restricted, canonical EDN text. For any
value there is exactly one valid encoding; a decoder must reject any input
whose re-encoding would differ.

## Allowed values

| Type | Canonical form | Notes |
| --- | --- | --- |
| Map | `{k1 v1 k2 v2}` | Keys sorted (see below). No duplicate keys. Empty map is `{}`. |
| Vector | `[v1 v2]` | Order preserved. Empty vector is `[]`. |
| String | `"..."` | UTF-8, Unicode NFC, escaping below. |
| Integer | `-42`, `0`, `17` | Signed 64-bit. Decimal, no `+`, no leading zeros, no `-0`, no `N`/`M` suffix. |
| Boolean | `true`, `false` | |
| Keyword | `:name`, `:ns/name` | Character set below. |
| Link | `#h "sha256:<hex>"` | Exactly 64 lowercase hex digits. |
| Byte string | `#b64 "<base64>"` | RFC 4648 §4 standard alphabet, with `=` padding. Empty is `#b64 ""`. |

Not allowed: `nil`, floats, ratios, big integers, symbols, characters, lists,
sets, metadata, comments, and any tag other than `#h` and `#b64`.

`nil` is excluded so that "absent" has one encoding: a key with no value is
omitted, never written as `nil`.

## Separators

There is no optional whitespace. Where EDN requires a separator, exactly one
ASCII space (U+0020) is used:

- between consecutive vector elements,
- between a map key and its value, and between one map entry and the next,
- between a tag (`#h`, `#b64`) and its string.

No space follows `[` or `{`, or precedes `]` or `}`. Commas are never used. The
encoding has no leading or trailing whitespace and no trailing newline.

## Map key order

Map entries are sorted by the **canonical encoding of their keys**, compared
as unsigned bytes (lexicographically, shorter prefix first). This gives a total
order across key types without a separate ranking rule. For example, `"a"`
(`0x22 0x61 0x22`) sorts before `:a` (`0x3a 0x61`).

Clojure's `compare` is not used: it throws on mixed types and doesn't match
byte order for strings outside the BMP.

## Strings

Strings must be in Unicode NFC. Encoders normalize; decoders reject
non-NFC input. Escapes:

| Character | Encoding |
| --- | --- |
| `"` (U+0022) | `\"` |
| `\` (U+005C) | `\\` |
| newline (U+000A) | `\n` |
| tab (U+0009) | `\t` |
| carriage return (U+000D) | `\r` |
| other U+0000–U+001F, and U+007F | `\u` + 4 lowercase hex digits, e.g. `\u001b` |

Every other character, including all non-ASCII, is written as raw UTF-8.
Unpaired surrogates are invalid.

## Keywords

A keyword is `:` followed by either `name` or `ns/name`:

- `name`: first character in `[A-Za-z*+!_?<>=-]`, then any of
  `[A-Za-z0-9*+!_?<>=-]`.
- `ns`: one or more `name`-style segments joined by `.`, e.g. `clj`,
  `mime`, `my.app`.
- At most one `/`. ASCII only.

This is deliberately narrower than what the Clojure reader accepts.

## Decoder requirements

- Reject anything not produced by the rules above (wrong order, extra
  whitespace, non-NFC strings, out-of-range integers, unknown tags, uppercase
  hex, non-canonical base64 such as missing padding or nonzero trailing bits).
- Reject nesting deeper than 256 levels. Depth counts vectors and maps: a
  top-level `[]` has depth 1, so 256 nested vectors are valid and 257 are not.
  Encoders enforce the same limit.
- Equivalently: decode, re-encode, and require byte equality with the input.

## Examples

```
{:a 1 :b [true "x"]}
{"z" 1 :a 2}
{:type :tree :entries {}}
#h "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
```

Golden vectors (value, canonical bytes, hash, and inputs a decoder must
reject) are in [`test-resources/vectors/encoding.edn`](../../test-resources/vectors/encoding.edn)
and are the final authority over this text.
