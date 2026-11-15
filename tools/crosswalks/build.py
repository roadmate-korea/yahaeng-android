#!/usr/bin/env python3
"""지자체별 횡단보도 CSV에서 밤에 어두운 횡단보도만 추려 앱 데이터로 만든다.

    python3 -I tools/crosswalks/build.py <받은 폴더> app/src/main/assets/dark_crosswalks.csv

- 출처는 공공데이터포털 전국횡단보도표준데이터(15028201)의 지자체별 파일이다.
- 보행자신호등유무와 집중조명시설유무가 둘 다 N으로 적힌 줄만 쓴다. 빈칸은 어둡다고 보지 않는다.
  조명 항목이 통째로 빈 파일(예: 수원시)은 결과에 한 곳도 남지 않는다.
- 차로수가 2 이상인 곳만 쓴다. 1차로 골목길 횡단보도까지 말하면 안내가 너무 잦아진다.
- 한국 범위 밖 좌표는 빼고, 위도와 경도가 뒤바뀐 줄은 바로잡는다.
- 서로 20m 안에 있는 줄은 한 횡단보도로 합친다(같은 횡단보도가 여러 줄로 올라온 경우가 많다).
- 데이터 기준일이 2025년 이전인 파일은 오래되어 뺀다.
"""
import collections
import csv
import datetime
import glob
import hashlib
import importlib.util
import io
import os
import sys

# 파일 읽기, 열 찾기, 거리 계산은 신호등 도구와 같은 것을 쓴다
_spec = importlib.util.spec_from_file_location(
    "signals_build", os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "signals", "build.py"))
_signals = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_signals)
column, distance, inside, read = _signals.column, _signals.distance, _signals.inside, _signals.read

MERGE_METERS = 20.0
MIN_LANES = 2
OLDEST_BASE = "2025-01-01"


def lanes_of(text):
    try:
        return int(float(text))
    except ValueError:
        return None


def dark_rows(text, stats):
    """CSV 내용 하나에서 (region, base, [(lat, lon, lanes, road)])를 돌려준다. 쓸 수 없는 파일이면 None."""
    rows = list(csv.reader(io.StringIO(text)))
    if not rows:
        return None
    h = rows[0]
    c = {k: column(h, *v) for k, v in {
        "signal": ("보행자신호등유무",), "light": ("집중조명시설유무",), "lanes": ("차로수",),
        "lat": ("위도",), "lon": ("경도",), "road": ("도로명",),
        "sido": ("시도명",), "sigungu": ("시군구명",), "base": ("데이터기준일자",),
    }.items()}
    if any(c[k] is None for k in ("signal", "light", "lat", "lon", "lanes")):
        stats["files_without_columns"] += 1
        return None
    get = lambda r, k: r[c[k]].strip() if c[k] is not None and len(r) > c[k] else ""
    bases = collections.Counter(get(r, "base")[:10] for r in rows[1:] if get(r, "base"))
    base = bases.most_common(1)[0][0] if bases else ""
    if base and base < OLDEST_BASE:
        stats["files_too_old"] += 1
        return None
    region = ""
    out = []
    for r in rows[1:]:
        stats["rows"] += 1
        if get(r, "signal").upper() != "N" or get(r, "light").upper() != "N":
            continue
        lanes = lanes_of(get(r, "lanes"))
        if lanes is None or lanes < MIN_LANES:
            continue
        try:
            lat, lon = float(get(r, "lat")), float(get(r, "lon"))
        except ValueError:
            stats["bad_coords"] += 1
            continue
        if not inside(lat, lon) and inside(lon, lat):
            lat, lon = lon, lat
        if not inside(lat, lon):
            stats["bad_coords"] += 1
            continue
        region = region or " ".join(x for x in (get(r, "sido"), get(r, "sigungu")) if x)
        out.append((lat, lon, lanes, get(r, "road").replace(",", " ")[:30]))
    return region, base, out


def merge(points):
    """20m 안의 줄을 하나로 합친다. 차로수는 가장 큰 값, 도로명은 가장 많이 나온 값."""
    clusters = []
    for lat, lon, lanes, road in points:
        for cl in clusters:
            if distance(cl["center"], (lat, lon)) <= MERGE_METERS:
                cl["pts"].append((lat, lon))
                cl["lanes"] = max(cl["lanes"], lanes)
                cl["roads"][road] += 1
                n = len(cl["pts"])
                cl["center"] = (sum(p[0] for p in cl["pts"]) / n, sum(p[1] for p in cl["pts"]) / n)
                break
        else:
            clusters.append({"center": (lat, lon), "pts": [(lat, lon)], "lanes": lanes, "roads": collections.Counter({road: 1})})
    return [(cl["center"][0], cl["center"][1], cl["lanes"], next((r for r, _ in cl["roads"].most_common() if r), "")) for cl in clusters]


def main():
    src, out = sys.argv[1], sys.argv[2]
    stats = collections.Counter()
    crosswalks = []
    for path in sorted(glob.glob(os.path.join(src, "*.csv"))):
        text = read(path)
        if text is None:
            stats["bad_files"] += 1
            continue
        got = dark_rows(text, stats)
        if got is None:
            continue
        region, base, points = got
        stats["files"] += 1
        if not points:
            stats["files_without_dark"] += 1
            continue
        for lat, lon, lanes, road in merge(points):
            cid = hashlib.sha1(f"cw,{lat:.5f},{lon:.5f}".encode()).hexdigest()[:10]
            crosswalks.append((cid, lat, lon, lanes, road, region, base))
    crosswalks.sort(key=lambda x: (x[5], x[1], x[2]))
    seen = set()
    today = datetime.date.today().isoformat()
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 보행자 신호등과 집중 조명이 둘 다 없는 2차로 이상 횡단보도. 출처: 공공데이터포털 전국횡단보도표준데이터(15028201), 지자체별 파일\n")
        f.write(f"# 만든 날: {today}. tools/crosswalks/build.py로 만든다\n")
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["id", "lat", "lon", "lanes", "road", "region", "base"])
        for cid, lat, lon, lanes, road, region, base in crosswalks:
            if cid in seen:
                continue
            seen.add(cid)
            w.writerow([cid, f"{lat:.6f}", f"{lon:.6f}", lanes, road, region, base])
    stats["crosswalks"] = len(seen)
    stats["regions"] = len({x[5] for x in crosswalks})
    print(dict(stats))


if __name__ == "__main__":
    main()
