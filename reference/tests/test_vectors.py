import os
import unittest

import gen_vectors


class VectorsUpToDate(unittest.TestCase):
    """The fast vector files match the generator. Run
    `python3 reference/gen_vectors.py --check` to check all of them."""

    def check(self, name, text):
        with open(os.path.join(gen_vectors.OUT, name), encoding="ascii") as f:
            self.assertEqual(f.read(), text, f"{name} is stale; run reference/gen_vectors.py")

    def test_gear(self):
        self.check("gear.edn", gen_vectors.gear_file())

    def test_encoding(self):
        self.check("encoding.edn", gen_vectors.encoding_file())

    def test_blobs(self):
        self.check("blobs.edn", gen_vectors.blobs_file())


if __name__ == "__main__":
    unittest.main()
