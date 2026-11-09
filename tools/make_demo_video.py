# -*- coding: utf-8 -*-
"""record_demo.ps1이 남긴 두 녹화본과 표식(JSON)으로 자막을 붙인 시연 영상 한 편을 만든다.

녹화 화면은 자르거나 빨리 감지 않는다. 화면 아래에 설명 띠(120px)를 덧붙이고,
앞뒤에 제목 카드와 마무리 카드를 넣는다.
usage: py -3 tools/make_demo_video.py   (저장소 루트에서)
출력: demo/gilbeot_demo.mp4
"""
import json
import os
import shutil
import subprocess
import sys

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
VID = os.path.join(REPO, "captures", "video")
OUT_DIR = os.path.join(REPO, "demo")
OUT = os.path.join(OUT_DIR, "gilbeot_demo.mp4")
FONT = "C\\:/Windows/Fonts/malgunbd.ttf"
W, H, BAND = 1920, 1080, 120

TITLE = [
    "길벗 — 시연 영상",
    "Pleos Connect Emulator v2.1.0 · release APK 녹화 (자르기·빨리 감기 없음, 아래 설명 띠만 추가)",
    "차속은 Virtual Console로 조작합니다.",
    "에뮬레이터 내비 위치가 고정이라 회전까지 남은 거리는 차속을 적분해 계산합니다.",
    "에뮬레이터에서 Pleos TTS가 완료되지 않아 안내는 큰 자막으로 나옵니다(음성 없음).",
    "화면의 '시각 주입' 배지는 일몰 판단을 재현하려고 넣은 데모 전용 값입니다.",
]
ENDING = [
    "구현: ① 미리, 차분히 안내 · ③ 나의 운전 약속",
    "소스 · APK · README: github.com/dtone-gilbeot/gilbeot",
]


def load_marks(name):
    with open(os.path.join(VID, f"{name}_marks.json"), encoding="utf-8-sig") as f:
        return json.load(f)


def captions_before(m):
    """첫 녹화본의 (시작, 끝, 설명) 목록."""
    return [
        (0, m["sunset"], "앱 실행 → 서울시청 경로 요청 (시각 주입 15:30, 데모용)"),
        (m["sunset"], m["sunsetShown"], "TTS 두 단계가 시간 초과되면 같은 안내를 큰 자막으로 띄웁니다 (에뮬레이터 한계)"),
        (m["sunsetShown"], m["drive"], "③ 약속 · 도착 예정이 일몰 전이라 그대로 알려 줍니다"),
        (m["drive"], m["turn500"], "Virtual Console: 기어 D · 50 km/h → 차속을 적분해 회전까지 남은 거리 계산"),
        (m["turn500"], m["turn300"], "① 대상 회전(대왕판교IC 나들목) 500m 전 예고 · 그 전 우회전은 카드 아래 한 줄"),
        (m["turn300"], m["headlamp"], "① 300m 전 다시 안내 (각 지점 한 번씩만)"),
        (m["headlamp"], m["stop"], "③ 주행 중 전조등 ON → 편안한 경로로 바꾸도록 요청 (에뮬레이터에선 경로가 바뀌지 않음)"),
        (m["stop"], m["end"] + 5, "정차(1 m/s 이하)하면 [약속 설정] · [쉬어 갈 곳 보기] 버튼이 나타납니다"),
    ]


def captions_after(m):
    """두 번째 녹화본의 (시작, 끝, 설명) 목록."""
    return [
        (0, m["sunset"], "같은 경로를 시각 주입 17:00(데모용)으로 다시 실행"),
        (m["sunset"], m["end"] + 5, "③ 약속 · 도착이 일몰 뒤 → 알리고 편안한 경로로 바꾸도록 요청"),
    ]


def esc(text):
    """drawtext text= 값에 쓸 수 있게 특수문자를 이스케이프한다."""
    return text.replace("\\", "\\\\").replace("'", "’").replace(":", "\\:").replace("%", "\\%").replace(",", "\\,")


def band_filters(caps):
    """설명 띠 위에 시간 구간별 자막을 그리는 drawtext 체인."""
    parts = []
    for start, end, text in caps:
        parts.append(
            f"drawtext=fontfile='{FONT}':text='{esc(text)}':fontsize=40:fontcolor=white:"
            f"x=(w-text_w)/2:y={H}+({BAND}-text_h)/2:enable='between(t\\,{start:.2f}\\,{end:.2f})'"
        )
    return ",".join(parts)


def card(label, lines, dur, big):
    """검은 배경 카드. 첫 줄은 크게."""
    chain = [f"color=c=0x101820:s={W}x{H + BAND}:d={dur}:r=30"]
    y0 = 330 if big else 470
    for i, line in enumerate(lines):
        size = 76 if (i == 0 and big) else 38
        y = y0 if i == 0 else y0 + 120 + (i - 1) * 64
        color = "0x7FDCC9" if i == 0 else "white"
        chain.append(f"drawtext=fontfile='{FONT}':text='{esc(line)}':fontsize={size}:fontcolor={color}:x=(w-text_w)/2:y={y}")
    return ",".join(chain) + f",format=yuv420p,setsar=1[{label}]"


def clip(idx, label, caps):
    """녹화본을 1920×1080 30fps로 맞추고 아래 띠와 자막을 붙인다."""
    return (
        f"[{idx}:v]fps=30,scale={W}:{H},pad={W}:{H + BAND}:0:0:color=0x101820,"
        f"{band_filters(caps)},format=yuv420p,setsar=1[{label}]"
    )


def main():
    ffmpeg = shutil.which("ffmpeg")
    if not ffmpeg:
        sys.exit("ffmpeg가 PATH에 없습니다")
    mb, ma = load_marks("demo_before"), load_marks("demo_after")
    graph = ";".join([
        card("t", TITLE, 9, True),
        clip(0, "b", captions_before(mb)),
        clip(1, "a", captions_after(ma)),
        card("e", ENDING, 5, False),
        "[t][b][a][e]concat=n=4:v=1:a=0[v]",
    ])
    os.makedirs(OUT_DIR, exist_ok=True)
    script = os.path.join(VID, "_filter.txt")
    with open(script, "w", encoding="utf-8") as f:
        f.write(graph)
    cmd = [
        ffmpeg, "-v", "error", "-y",
        "-i", os.path.join(VID, "demo_before.mp4"),
        "-i", os.path.join(VID, "demo_after.mp4"),
        "-/filter_complex", script, "-map", "[v]",
        "-c:v", "libx264", "-preset", "slow", "-crf", "26", "-movflags", "+faststart", OUT,
    ]
    subprocess.run(cmd, check=True)
    os.remove(script)
    print(f"wrote {OUT} ({os.path.getsize(OUT) / 1e6:.1f} MB)")


if __name__ == "__main__":
    main()
