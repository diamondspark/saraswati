import unittest

from version import version_code


class VersionCodeTest(unittest.TestCase):
    def test_returns_commit_count(self) -> None:
        self.assertEqual(version_code(4100, False), 4100)

    def test_rejects_shallow_clone(self) -> None:
        with self.assertRaises(ValueError):
            version_code(1, True)

    def test_rejects_zero_commits(self) -> None:
        with self.assertRaises(ValueError):
            version_code(0, False)


if __name__ == "__main__":
    unittest.main()
