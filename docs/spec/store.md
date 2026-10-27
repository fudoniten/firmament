# Local Store

This is the on-disk layout of the local content store. It is local state, not
part of the format: nothing here is ever hashed, and another implementation
may store objects however it likes. It records what `firmament.store` does
so that stores stay readable across versions of this code.

## Layout

```
<store>/
  format              "firmament-0\n": the format version this store holds
  objects/ab/cdef…    one file per object
  meta/ab/cdef….edn   local metadata for an object, if any
  tmp/                files being written
```

**Where the store is.** Tools use the directory named by the
`FIRMAMENT_STORE` environment variable, or `~/.firmament/store` when it is
unset (`firmament.store/default-dir`). The CLI's `--store DIR` overrides both.

An object named `sha256:abcdef…` lives at `objects/ab/cdef…`: the first two
hex digits name the directory and the other 62 the file.

**An object file holds the full hash input**: `"blob\n"` + bytes for a blob,
or `"edn\n"` + canonical EDN for a structured object ([objects.md](objects.md)).
So the SHA-256 of the file is the object's hash, and `sha256sum` checks a
store with no Firmament code at all. The prefix also says which kind of
object the file is.

## Rules

- **A store holds one format version** and records it in `format`
  ([Format versions](objects.md#format-versions)). Opening a store of another
  version fails, as does opening a non-empty directory with no `format` file.
- **Objects are written atomically**: to a file in `tmp/`, then moved into
  place. A reader never sees a partial object.
- **Objects are checked on every read.** A file whose SHA-256 is not its name
  is reported as corrupt rather than returned.
- **Children before parents.** When a file is stored, its chunks are written
  before the index nodes that link to them, and lower index levels before
  higher ones.

## Metadata

`meta/ab/cdef….edn` is an ordinary EDN map, such as
`{:pinned? true :expires #inst "2030-01-01T00:00:00.000-00:00" :source …}`.
It is local, never hashed and never part of an object, so it is plain EDN
rather than canonical EDN and may use `#inst`. Metadata can only be set for an
object the store holds.
