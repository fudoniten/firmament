import unittest

from firmament_ref import gen
from firmament_ref.chunking import (
    GEAR, INDEX_CAP, MAX, MIN, build_index, cut_points, is_boundary, store_file)
from firmament_ref.edn import Link, decode, kw

MiB = 1 << 20


class Gear(unittest.TestCase):
    def test_against_sha256sum(self):
        # printf 'firmament-gear-v0\x00' | sha256sum | cut -c1-16
        self.assertEqual(GEAR[0], 0x23cdf8823f291c5a)
        self.assertEqual(GEAR[255], 0x2731e35e4584a4c6)
        self.assertEqual(len(set(GEAR)), 256)


class Cuts(unittest.TestCase):
    def test_chunk_sizes(self):
        data = gen.sha256_ctr("sizes", 0, 3 * MiB + 77)
        cuts = cut_points(data)
        self.assertEqual(cuts[-1], len(data))
        sizes = [b - a for a, b in zip([0] + cuts, cuts)]
        for s in sizes[:-1]:
            self.assertTrue(MIN <= s <= MAX)
        self.assertTrue(0 < sizes[-1] <= MAX)

    def test_zeros_cut_at_max(self):
        self.assertEqual(cut_points(bytes(3 * MAX)), [MAX, 2 * MAX, 3 * MAX])

    def test_insert_is_local(self):
        a = gen.sha256_ctr("local", 0, 4 * MiB)
        b = a[:2 * MiB] + b"inserted" + a[2 * MiB:]
        fa, fb = store_file(a), store_file(b)
        changed = set(fb.blobs) - set(fa.blobs)
        self.assertLessEqual(len(changed), 2)
        self.assertNotEqual(fa.root, fb.root)


class Files(unittest.TestCase):
    def test_blob_or_chunked(self):
        self.assertFalse(store_file(bytes(MAX)).chunked)
        f = store_file(gen.sha256_ctr("x", 0, MAX + 1))
        self.assertTrue(f.chunked)
        self.assertGreaterEqual(len(f.cuts), 2)

    def test_root_node(self):
        data = gen.sha256_ctr("root", 0, MiB)
        f = store_file(data)
        node = decode(f.index.objects[f.root.ref])
        self.assertEqual(node[kw("type")], kw("chunked"))
        self.assertEqual(node[kw("level")], 0)
        self.assertEqual(node[kw("size")], len(data))
        joined = b"".join(f.blobs[e[kw("link")].ref] for e in node[kw("chunks")])
        self.assertEqual(joined, data)


class Index(unittest.TestCase):
    def entries(self, seed, n):
        return [(Link("sha256:" + gen.ctr_block(seed, i).hex()), 1) for i in range(n)]

    def test_groups(self):
        entries = self.entries("groups", 5000)
        idx = build_index(entries)
        self.assertEqual(sum(idx.levels[0]), 5000)
        ends = [sum(idx.levels[0][:k + 1]) - 1 for k in range(len(idx.levels[0]))]
        for e in ends[:-1]:
            self.assertTrue(is_boundary(entries[e][0]) or idx.levels[0][ends.index(e)] == INDEX_CAP)
        root = decode(idx.objects[idx.root.ref])
        self.assertEqual(root[kw("size")], 5000)
        self.assertEqual(root[kw("level")], len(idx.levels) - 1)

    def test_cap(self):
        non_boundary = [e for e in self.entries("cap", 6000) if not is_boundary(e[0])]
        idx = build_index(non_boundary[:INDEX_CAP + 1])
        self.assertEqual(idx.levels, [[INDEX_CAP, 1], [2]])


if __name__ == "__main__":
    unittest.main()
