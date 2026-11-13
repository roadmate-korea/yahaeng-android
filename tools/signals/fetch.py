#!/usr/bin/env python3
"""공공데이터포털 '전국신호등표준데이터'(15028198)의 지자체별 파일을 내려받는다.

전국 통합 파일은 로그인이 필요해서, 표준데이터 페이지 아래의 지자체별 파일 데이터를 받는다.
포털이 보안문자(다운로드 제한)를 요구하면 그 자리에서 멈춘다(우회하지 않는다). 요청 사이에 쉬어 간다.

    python3 -I tools/signals/fetch.py <받을 폴더>

다시 실행하면 이전 실행과 상세 키·등록일이 같은 파일만 건너뛰고, 바뀐 파일은 다시 받는다.
index.json에는 실제로 받은 파일만 적는다. 받은 원본은 저장소에 올리지 않는다(build.py로 추린 결과만 올린다).
"""
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

BASE = "https://www.data.go.kr"
PK = "15028198"
PAUSE = 1.5
UA = {"User-Agent": "yahaeng-data-build/1.0"}


def get(path, data=None):
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    req = urllib.request.Request(BASE + path, data=body, headers=UA)
    with urllib.request.urlopen(req, timeout=60) as r:
        return r.read()


def list_files():
    """(상세 키, 데이터명, 등록일) 목록. 같은 데이터명이 여러 번 나오면 등록일이 가장 늦은 것만."""
    files = {}
    page = 1
    last = None
    while True:
        html = get(f"/tcs/dss/stdFileList.do?publicDataPk={PK}&pageIndex={page}").decode("utf-8")
        if last is None:
            nums = [int(n) for n in re.findall(r"fn_page\((\d+)\)", html)]
            last = max(nums) if nums else 1
        text = re.sub(r"<[^>]+>", " ", html)
        keys = re.findall(r'data-public-pk="([^"]+)"', html)
        rows = re.findall(r"파일데이터\s+(\S+)\s+.*?(\d{4}-\d{2}-\d{2})", text, re.S)
        for key, (name, date) in zip(keys, rows):
            if name not in files or files[name][1] < date:
                files[name] = (key, date)
        print(f"page {page}/{last}: {len(files)} files", flush=True)
        if page >= last:
            break
        page += 1
        time.sleep(PAUSE)
    return files


def main():
    out = sys.argv[1]
    os.makedirs(out, exist_ok=True)
    files = list_files()
    index_path = os.path.join(out, "index.json")
    # 이전에 실제로 받은 파일의 [상세 키, 등록일]
    done = {}
    if os.path.exists(index_path):
        with open(index_path, encoding="utf-8") as f:
            done = {k: tuple(v) for k, v in json.load(f).items()}

    def save_index():
        with open(index_path, "w", encoding="utf-8") as f:
            json.dump(done, f, ensure_ascii=False, indent=1)

    # 포털 목록에서 빠진 파일은 목록에서도 뺀다(build.py가 그 파일을 쓰지 않게)
    done = {k: v for k, v in done.items() if k in files}
    save_index()
    for name, (key, date) in sorted(files.items()):
        safe = re.sub(r"[^\w가-힣_.-]", "_", name)
        target = os.path.join(out, f"{safe}.csv")
        if os.path.exists(target) and done.get(name) == (key, date):
            continue
        time.sleep(PAUSE)
        detail = get("/tcs/dss/selectDpkDetailInfo.do", {"publicDataDetailPk": key}).decode("utf-8")
        m = re.search(r"fn_fileDataDown\('[^']*', '[^']*', '([^']+)', '([^']+)', '([^']+)'\)", detail)
        if not m:
            print(f"skip {name}: no download link", flush=True)
            continue
        atch, sn, ext = m.groups()
        if ext.lower().lstrip(".") != "csv":
            print(f"skip {name}: {ext}", flush=True)
            continue
        limit = json.loads(get("/cmm/cmm/check-limit.json", {"atchFileId": atch, "fileDetailSn": sn}))
        if limit.get("needCaptcha"):
            print(f"stop: portal asks for a captcha at {name}. Run again later.", flush=True)
            sys.exit(2)
        data = get(f"/cmm/cmm/fileDownload.do?atchFileId={atch}&fileDetailSn={sn}&dataNm=" + urllib.parse.quote(name))
        if data.lstrip()[:15].lower().startswith(b"<!doctype") or data.lstrip().startswith(b"<html"):
            print(f"stop: got an HTML page instead of CSV at {name}", flush=True)
            sys.exit(3)
        with open(target + ".part", "wb") as f:
            f.write(data)
        os.replace(target + ".part", target)
        done[name] = (key, date)
        save_index()
        print(f"ok {name} {date} {len(data)}B", flush=True)


if __name__ == "__main__":
    main()
