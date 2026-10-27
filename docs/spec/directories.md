# Directories

How `firmament put -r` turns a directory into a tree, and how `firmament
export` writes one back. This is a convention on top of the core tree type
([objects.md](objects.md)), not part of the format: other importers, such as
the Clojure one, build trees their own way.

## Import

- A regular file becomes `{:link <file>}`, where the file is a blob or a
  chunked file ([chunking.md](chunking.md)).
- A directory becomes `{:children <tree>}`. An empty directory is the empty
  tree, `{:type :tree :entries {}}`.
- Names are normalized to NFC. Two names in one directory that are equal
  after normalization are an error.
- Every entry is included, hidden files too. Nothing is ignored.
- No tags are recorded: no modes, owners or times, so the same contents give
  the same hash on any machine.
- Symbolic links and special files (devices, sockets, pipes) are refused.

Importing the same contents always gives the same hash, and importing,
exporting and importing again gives the same hash too.

## Export

The target directory must not exist or must be empty. Entries whose only
link is `:children` become directories and entries whose only link is
`:link` become files; tags are ignored. Any other entry, such as one with
`:source/clj`, has no plain-directory form, and export refuses the tree.
