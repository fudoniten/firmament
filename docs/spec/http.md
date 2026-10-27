# Stores over HTTP

A store can be read, and with a token written, over HTTP
(`firmament.http`). The URL layout is the local store's directory layout
([store.md](store.md)), so a static file server pointed at a store directory
(nginx, Caddy, an S3 bucket synced from one) is a read-only remote too. No
Firmament code is needed on the server.

## Reading

Below a base URL such as `https://example.com/store/`:

| Request | Response |
| --- | --- |
| `GET format` | `firmament-0\n`, the format version the store holds |
| `GET objects/ab/cdef…` | The object file for `sha256:abcdef…`: its full hash input, `"blob\n"` + bytes or `"edn\n"` + canonical EDN |
| `HEAD objects/ab/cdef…` | 200 if the store has the object, 404 if not |

A missing object is a 404. Clients check `format` before anything else and
refuse a store holding another version.

**Nothing served is trusted.** A client hashes every object it receives and
refuses one that doesn't hash to the name it asked for, so a server, a
cache or a man in the middle can make a fetch fail but can't substitute
content. HTTPS still matters for privacy, not for integrity.

## Writing

`PUT objects/ab/cdef…` with the full hash input as the body stores an
object. The server:

- requires `Authorization: Bearer <token>`, matching the token it was
  started with. A server started without a token is read-only, and every
  PUT gets 403;
- refuses a body that doesn't hash to the name in the path (400), and one
  over 16 MiB (413). Files over 256 KiB are chunked, so real objects are far
  smaller;
- answers 204 once the object is stored. Storing an object it already has
  is a no-op.

`firmament push STORE HASH` copies an object and everything it links to,
children before parents, so a store never holds an object whose links are
missing. It skips objects the store already has.

## The server

`firmament serve [--port N] [--bind ADDR]` serves the local store, by
default on `127.0.0.1:8080`, and prints its URL. It accepts writes when
`$FIRMAMENT_TOKEN` is set. It serves object files exactly as they are on
disk and doesn't check them, because clients do.

## Clients

`firmament run --from URL H` runs H with the local store as a cache in front
of the server ([clojure.md](clojure.md#running)). Every object fetched is
checked and kept, so the second run of H, or a run of a changed H¹, fetches
only objects the local store doesn't have.

Objects are fetched one request at a time for now. Batching and parallel
fetches are future work and don't change this layout.
