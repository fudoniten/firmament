A small project the loader tests run from the store: a .clj source, a .cljc
source with reader conditionals, a namespace with both .clj and .cljc
sources (the JVM must pick .clj), a resource, and a :local/root jar holding
a namespace (greeting.core) and a resource of its own.

lib/greeting.jar is built by make_jar.py, with fixed timestamps so it is
byte-for-byte reproducible.
