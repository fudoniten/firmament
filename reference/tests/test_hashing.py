import unittest

from firmament_ref.edn import kw
from firmament_ref.hashing import blob_hash, edn_hash

# Computed independently with sha256sum, e.g. { printf 'blob\n'; cat f; } | sha256sum.
EMPTY_BLOB = "sha256:bc103b4a84971ef6459b294a2b98568a2bfb72cded09d4acd1e16366a401f95b"
HELLO_BLOB = "sha256:1be8615f7fbda85e1ef029f02573bdfefe09fb01af39e6ce684104a45a360ae8"
MAP_A1 = "sha256:63fd2b150ed3e191a9a116641e44427e21d75379991e421ef7ddaec30fed59fd"


class Hashing(unittest.TestCase):
    def test_blob(self):
        self.assertEqual(blob_hash(b""), EMPTY_BLOB)
        self.assertEqual(blob_hash(b"hello\n"), HELLO_BLOB)

    def test_edn(self):
        self.assertEqual(edn_hash({kw("a"): 1}), MAP_A1)

    def test_prefixes_differ(self):
        self.assertNotEqual(blob_hash(b"{:a 1}"), edn_hash({kw("a"): 1}))


if __name__ == "__main__":
    unittest.main()
