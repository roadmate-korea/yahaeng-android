#!/usr/bin/env python3
"""fetch.py로 받은 지자체별 신호등 CSV에서 밤에만 점멸하는 교차로를 추려 앱 데이터로 만든다.

    python3 -I tools/signals/build.py <받은 폴더> app/src/main/assets/flashing_signals.csv

- 점멸등운영여부 열이 있으면 Y인 줄만 쓴다.
- 하루 종일 점멸(23시간 이상)은 뺀다. "밤에 바뀌는 규칙"이 아니기 때문이다.
- 시작이 17시~03시, 끝이 03시~10시, 길이가 3~14시간인 것만 밤 점멸로 본다. 시각을 읽을 수 없는 줄은 뺀다.
- 한국 범위 밖 좌표는 빼고, 위도와 경도가 뒤바뀐 줄은 바로잡는다.
- 같은 운영 시간이고 서로 60m 안에 있는 신호등은 한 교차로로 합친다.
- 데이터 기준일이 2025년 이전인 파일은 오래되어 뺀다.
- 받은 폴더에 index.json(fetch.py)이 있으면 그 목록의 파일만 쓴다.
"""
import collections
import csv
import datetime
import glob
import hashlib
import io
import json
import math
import os
import re
import sys

LAT = (33.0, 39.0)
LON = (124.0, 132.0)
MERGE_METERS = 60.0
OLDEST_BASE = "2025-01-01"


def read(path):
    raw = open(path, "rb").read()
    for enc in ("utf-8-sig", "cp949"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    return None


def column(header, *names):
    for i, h in enumerate(header):
        if h.strip().replace(" ", "") in names:
            return i
    return None


def minutes(text):
    m = re.match(r"^\s*(\d{1,2}):(\d{2})", text or "")
    if not m:
        return None
    h, mi = int(m.group(1)), int(m.group(2))
    if h == 24 and mi == 0:
        h = 0
    if h > 23 or mi > 59:
        return None
    return h * 60 + mi


def night_window(start, end):
    if start is None or end is None or start == end:
        return None
    length = (end - start) % 1440
    if length >= 23 * 60:
        return None
    if not (start >= 17 * 60 or start <= 3 * 60):
        return None
    if not (3 * 60 <= end <= 10 * 60):
        return None
    if not (3 * 60 <= length <= 14 * 60):
        return None
    return start, end


def inside(lat, lon):
    return LAT[0] <= lat <= LAT[1] and LON[0] <= lon <= LON[1]


def distance(a, b):
    r = 6371000.0
    p1, p2 = math.radians(a[0]), math.radians(b[0])
    dp, dl = p2 - p1, math.radians(b[1] - a[1])
    h = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(h))


def clean_name(place, road):
    name = (place or "").strip() or (road or "").strip()
    # 앞의 시도와 시군구 이름은 뺀다(예: "경기도 구리시 장자대로 89" → "장자대로 89")
    for _ in range(3):
        name = re.sub(r"^\S+(특별자치도|특별자치시|특별시|광역시|도|시|군|구)\s+", "", name)
    return re.sub(r"[,\n\r]+", " ", name)[:40]


def main():
    src, out = sys.argv[1], sys.argv[2]
    paths = sorted(glob.glob(os.path.join(src, "*.csv")))
    index_path = os.path.join(src, "index.json")
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            names = {re.sub(r"[^\w가-힣_.-]", "_", n) + ".csv" for n in json.load(f)}
        paths = [p for p in paths if os.path.basename(p) in names]
    stats = collections.Counter()
    intersections = []
    for path in paths:
        text = read(path)
        if text is None:
            stats["bad_files"] += 1
            continue
        rows = list(csv.reader(io.StringIO(text)))
        if not rows:
            continue
        h = rows[0]
        c = {k: column(h, *v) for k, v in {
            "yn": ("점멸등운영여부",), "start": ("점멸등운영시작시각",), "end": ("점멸등운영종료시각",),
            "lat": ("위도",), "lon": ("경도",), "place": ("설치장소",), "road": ("소재지도로명주소",),
            "sido": ("시도명",), "sigungu": ("시군구명",), "base": ("데이터기준일자",),
        }.items()}
        if c["start"] is None or c["end"] is None or c["lat"] is None or c["lon"] is None:
            stats["files_without_columns"] += 1
            continue
        get = lambda r, k: r[c[k]].strip() if c[k] is not None and len(r) > c[k] else ""
        bases = collections.Counter(get(r, "base")[:10] for r in rows[1:] if get(r, "base"))
        base = bases.most_common(1)[0][0] if bases else ""
        if base and base < OLDEST_BASE:
            stats["files_too_old"] += 1
            continue
        stats["files"] += 1
        points = collections.defaultdict(list)
        region = ""
        for r in rows[1:]:
            stats["rows"] += 1
            if c["yn"] is not None and get(r, "yn").upper() != "Y":
                continue
            window = night_window(minutes(get(r, "start")), minutes(get(r, "end")))
            if window is None:
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
            points[window].append(((lat, lon), clean_name(get(r, "place"), get(r, "road"))))
        if not region:
            region = os.path.basename(path).rsplit("_", 1)[0].replace("_", " ")
        for window, items in points.items():
            clusters = []
            for (pt, name) in items:
                for cl in clusters:
                    if distance(cl["center"], pt) <= MERGE_METERS:
                        cl["pts"].append(pt)
                        cl["names"][name] += 1
                        n = len(cl["pts"])
                        cl["center"] = (sum(p[0] for p in cl["pts"]) / n, sum(p[1] for p in cl["pts"]) / n)
                        break
                else:
                    clusters.append({"center": pt, "pts": [pt], "names": collections.Counter({name: 1})})
            for cl in clusters:
                lat, lon = cl["center"]
                name = next((n for n, _ in cl["names"].most_common() if n), "")
                key = f"{lat:.4f},{lon:.4f},{window[0]},{window[1]}"
                iid = hashlib.sha1(key.encode()).hexdigest()[:10]
                intersections.append((iid, lat, lon, window[0], window[1], name, region, base))
    intersections.sort(key=lambda x: (x[6], x[1], x[2]))
    seen = set()
    today = datetime.date.today().isoformat()
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("# 밤에만 점멸하는 교차로. 출처: 공공데이터포털 전국신호등표준데이터(15028198), 지자체별 파일\n")
        f.write(f"# 받은 날: {today}. tools/signals/build.py로 만든다\n")
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["id", "lat", "lon", "start", "end", "name", "region", "base"])
        for iid, lat, lon, s, e, name, region, base in intersections:
            if iid in seen:
                continue
            seen.add(iid)
            w.writerow([iid, f"{lat:.5f}", f"{lon:.5f}", s, e, name, region, base])
    stats["intersections"] = len(seen)
    stats["regions"] = len({x[6] for x in intersections})
    print(dict(stats))


if __name__ == "__main__":
    main()
