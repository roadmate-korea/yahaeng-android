"""python3 -I -m unittest discover -s tools/crosswalks"""
import collections
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from build import dark_rows, merge  # noqa: E402

HEADER = "시도명,시군구명,도로명,위도,경도,차로수,보행자신호등유무,집중조명시설유무,데이터기준일자\n"


def rows(*lines):
    return HEADER + "".join(line + "\n" for line in lines)


class DarkRowsTest(unittest.TestCase):
    def test_keeps_only_rows_without_signal_and_light(self):
        text = rows(
            "경기도,구리시,아차산로,37.56,127.11,4,N,N,2026-07-01",
            "경기도,구리시,아차산로,37.57,127.11,4,Y,N,2026-07-01",
            "경기도,구리시,아차산로,37.58,127.11,4,N,Y,2026-07-01",
            "경기도,구리시,아차산로,37.59,127.11,4,N,,2026-07-01",
        )
        region, base, points = dark_rows(text, collections.Counter())
        self.assertEqual("경기도 구리시", region)
        self.assertEqual("2026-07-01", base)
        self.assertEqual([(37.56, 127.11, 4, "아차산로")], points)

    def test_skips_single_lane_and_unknown_lanes(self):
        text = rows(
            "경기도,구리시,골목,37.56,127.11,1,N,N,2026-07-01",
            "경기도,구리시,골목,37.57,127.11,,N,N,2026-07-01",
            "경기도,구리시,큰길,37.58,127.11,2,N,N,2026-07-01",
        )
        self.assertEqual(1, len(dark_rows(text, collections.Counter())[2]))

    def test_fixes_swapped_coordinates_and_drops_outside_korea(self):
        text = rows(
            "경기도,구리시,큰길,127.11,37.56,2,N,N,2026-07-01",
            "경기도,구리시,큰길,10.0,10.0,2,N,N,2026-07-01",
        )
        self.assertEqual([(37.56, 127.11, 2, "큰길")], dark_rows(text, collections.Counter())[2])

    def test_drops_old_files(self):
        text = rows("경기도,구리시,큰길,37.56,127.11,2,N,N,2024-03-01")
        self.assertIsNone(dark_rows(text, collections.Counter()))

    def test_file_without_light_column_is_skipped(self):
        text = "위도,경도,차로수,보행자신호등유무\n37.56,127.11,2,N\n"
        self.assertIsNone(dark_rows(text, collections.Counter()))


class MergeTest(unittest.TestCase):
    def test_rows_within_20m_become_one_crosswalk(self):
        merged = merge([(37.5600, 127.1100, 2, "큰길"), (37.56005, 127.1100, 4, "큰길"), (37.5610, 127.1100, 2, "옆길")])
        self.assertEqual(2, len(merged))
        self.assertEqual(4, merged[0][2])


if __name__ == "__main__":
    unittest.main()
