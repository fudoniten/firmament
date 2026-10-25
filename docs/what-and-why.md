# Content-Addressed Computing: What and Why

Oct 24, 2026 · @Peter

What if your entire computing life happened inside a Git repository? Every program, build, system configuration and personal setting would be an immutable value named by its content, so a whole working environment becomes one hash you can share, diff, roll back, or run anywhere.

## The problem

Today's computing is built on places, not values. A file is a mutable location, and almost everything painful follows from that.

- **Drift.** Two machines "running the same thing" rarely are. Config management, containers and lockfiles exist to fight this, each partially.
- **Deployment is ceremony.** Getting code onto a machine means packaging, copying, configuring and restarting, with many places for state to diverge.
- **Dependency hell.** Names like `my-lib 2.1` are promises, not identities; the same name can mean different code on different machines.
- **History is optional.** Code has Git, but systems, settings and documents mostly don't. Rollback is a feature some tools add, not a property of the medium.
- **Sharing is copying.** Sending someone your setup means describing it, or shipping an image that immediately drifts.
- **Modifiability is theoretical.** Open source lets you change anything, but rebuilding and running a modified piece of a real system is hard enough that almost nobody does.

## The idea

Replace places with values. Store everything in a content-addressed graph, where an object's name is the hash of its content, and build computing on top of it.

- A **file** is a hash. A **directory** or **namespace** is a hash of a listing of names to hashes.
- **Code** refers to other code by name, but each name resolves to a specific hash, so a program means exactly one thing.
- A **build** is a cached function: `compile(source-hash, target) → binary-hash`. Anyone who has built it once has built it for everyone who trusts them.
- A **system** or distro is a higher-level hash that collects programs, configuration and builds. Your machine is that hash plus an overlay of your changes.
- **Your state** (settings, documents) is another tree, so `[system-hash, state-hash]` describes your whole working environment.
- A small number of **signed names** (`prod.my-service`, `master.my-distro.com`, `me.my-domain.com`) are the only mutable things. Each is an append-only chain, so even naming has history.

Git, rollback, reproducibility and sharing stop being separate tools. They fall out of the medium.

## What it makes possible

- **"Try my system."** Send someone a hash; they load it and run exactly what you run, modulo hardware.
- **Your desk, anywhere.** Point any machine at `me.my-domain.com` and get your system and settings as of your last save.
- **Every state, kept.** Each change produces a new root hash, so every previous state of your system is a snapshot you can return to. An upgrade that fails is a pointer moved back.
- **Deploy by naming.** "Node, run H." Upgrading is "now run H¹." No deployment scripts, no config management, no drift.
- **Live upgrades.** Diff H against H¹ and reload only what changed into the running process.
- **Change anything.** Find any file in the system, edit it, rebuild, and get a new top-level hash: your own variant of the distro, with only the changed parts rebuilt.
- **Diff anything.** Compare your system to mine and see every local change, from a kernel option to a desktop setting.
- **Sharing is a link.** A file, a project or a whole environment is shared by handing over its hash, verifiable by the recipient.

## Principles

1. **Values over places.** Identity is content. Mutation is confined to a few explicit, signed, historied names.
2. **Open top to bottom.** Every layer is source you can inspect, change and rebuild. A distro is a convenience, not a gatekeeper.
3. **Practical over pure.** Use central services, caches and existing trust models where they work. Decentralization is available, not mandatory.
4. **Local first, self-hostable.** Your drives are your cache, your servers can be your resolvers, and nothing requires a third party.
5. **Verify, don't trust, by default.** Content checks itself; signatures are needed only for names and claims a hash can't prove.
6. **Evolve, don't legislate.** Freeze the data formats carefully, because hashes make them permanent, and let policy (trust, merging, retention) grow from real use.

## Lineage

Every piece exists somewhere already. What's new is unifying them under one object model with a practical naming layer.

| Source | What it contributes |
| --- | --- |
| Clojure | Immutable persistent data structures and path copying; the original inspiration |
| Datomic | History as a first-class value; an append-only log with batch indexing |
| Nix / NixOS | Systems as hashed closures, binary caches, atomic rollback |
| Unison | Code identified by hash, names as a mutable map to hashes, cached compilation |
| Git | Merkle trees for history, diffing, cheap branching |
| IPFS / IPLD | Content-addressed storage and links resolvable from any source |
| Dolt | Prolly trees, so large mutable datasets can still be versioned values |

## Why Clojure first

A whole operating system can't be replaced at once, so the vision needs a small, real community where it pays off immediately.

- **Code is data.** Clojure source is EDN, so code can be stored, analyzed and hashed as structured values.
- **The culture already agrees.** Immutable values, Datomic, "accretion, not breakage," and git-SHA dependencies in `deps.edn` are all established.
- **Namespaces map onto trees.** A namespace becomes a path in a hashed tree, removing the file-vs-namespace mismatch entirely.
- **The runtime can live-reload.** Redefining vars in a running JVM makes hash-diffed live upgrades possible.
- **It has been tried.** An earlier prototype already loaded Clojure namespaces from a hashed tree.

## The path


Rather than slotting into the middle of today's stack, the system starts as one process among many and grows until it can replace its host.

## Non-goals

- **A perfect distributed system.** Fully decentralized designs have been tried and stalled on performance and practicality. Central services are fine where they work.
- **A global DHT as the foundation.** It is one optional resolver for the long tail, not the substrate.
- **Replacing Linux on day one.** The system starts as a guest and earns its way down the stack.
- **Storing hot state in the tree.** Databases, logs and live app state stay outside, checkpointed in, until native designs exist.
- **Multiple library versions per process.** Set aside for now; the data model doesn't rule it out later.
- **Solving trust and governance up front.** Start with today's model (trust your distro) and let policy evolve.
