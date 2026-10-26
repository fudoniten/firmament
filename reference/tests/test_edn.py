import random
import unittest

from firmament_ref.edn import (
    MAX_DEPTH, DecodeError, EdnMap, EncodeError, Keyword, Link, decode, encode, kw)


def random_value(rng, depth=0):
    choice = rng.randrange(10 if depth < 4 else 7)
    if choice == 0:
        return rng.choice([True, False])
    if choice == 1:
        return rng.choice([0, -1, 2**63 - 1, -(2**63), rng.randrange(-(2**63), 2**63)])
    if choice == 2:
        alphabet = 'ab"\\\n\t\r\x00\x1f\x7f\x80é ￿\U0001F600 '
        return "".join(rng.choice(alphabet) for _ in range(rng.randrange(6)))
    if choice == 3:
        return rng.choice([kw("a"), kw("b/c"), kw("my.app/x-y?"), kw("-")])
    if choice == 4:
        return Link("sha256:" + "".join(rng.choice("0123456789abcdef") for _ in range(64)))
    if choice in (5, 6):
        return bytes(rng.randrange(256) for _ in range(rng.randrange(5)))
    if choice in (7, 8):
        return [random_value(rng, depth + 1) for _ in range(rng.randrange(4))]
    pairs = {}
    for _ in range(rng.randrange(4)):
        k = random_value(rng, depth + 1)
        pairs[encode(k)] = (k, random_value(rng, depth + 1))
    return EdnMap(pairs.values())


class RoundTrip(unittest.TestCase):
    def test_random_values(self):
        rng = random.Random(0)
        for _ in range(3000):
            v = random_value(rng)
            b = encode(v)
            d = decode(b)
            self.assertEqual(encode(d), b)
            self.assertEqual(encode(v), b, "encode is stable")


class Encoder(unittest.TestCase):
    def test_examples(self):
        self.assertEqual(encode({kw("a"): 1, kw("b"): [True, "x"]}), b'{:a 1 :b [true "x"]}')
        self.assertEqual(encode({kw("a"): 2, "z": 1}), b'{"z" 1 :a 2}')
        self.assertEqual(encode({kw("type"): kw("tree"), kw("entries"): {}}),
                         b"{:entries {} :type :tree}")

    def test_true_and_one_differ(self):
        self.assertEqual(encode(EdnMap([(True, 1), (1, 2)])), b"{1 2 true 1}")
        self.assertNotEqual(encode([True]), encode([1]))

    def test_normalizes_strings(self):
        self.assertEqual(encode("é"), '"é"'.encode())

    def test_rejects(self):
        bad = [None, 1.5, {1}, 2**63, -(2**63) - 1, "\ud800", object(),
               [None], {kw("a"): None}]
        for v in bad:
            with self.assertRaises((EncodeError, TypeError), msg=repr(v)):
                encode(v)

    def test_rejects_keys_equal_after_nfc(self):
        with self.assertRaises(EncodeError):
            encode({"é": 1, "é": 2})

    def test_bad_keywords(self):
        for s in ["", "1a", "a.b", "a/b/c", "/a", "a/", "a..b/c", "café", "a'"]:
            with self.assertRaises(EncodeError, msg=s):
                Keyword(s)

    def test_depth_limit(self):
        v = []
        for _ in range(MAX_DEPTH - 1):
            v = [v]
        encode(v)
        with self.assertRaises(EncodeError):
            encode([v])
        with self.assertRaises(EncodeError):
            encode({kw("a"): v})


class Decoder(unittest.TestCase):
    def test_depth_limit(self):
        decode(b"[" * MAX_DEPTH + b"]" * MAX_DEPTH)
        with self.assertRaises(DecodeError):
            decode(b"[" * (MAX_DEPTH + 1) + b"]" * (MAX_DEPTH + 1))

    def test_values(self):
        self.assertEqual(decode(b"[]"), ())
        self.assertIs(decode(b"true"), True)
        self.assertEqual(decode(b':a/b'), Keyword("a", "b"))
        self.assertEqual(decode(b'#b64 "AAE="'), b"\x00\x01")
        self.assertEqual(decode('"é"'.encode()), "é")


if __name__ == "__main__":
    unittest.main()
