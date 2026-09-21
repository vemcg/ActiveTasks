#!/usr/bin/env python3
# Copyright (c) 2026 Vern McGeorge. All rights reserved.
"""Generates the GitHub Pages install & onboarding page + QR code pointing at a release APK."""
import argparse
import pathlib
import re

import qrcode
from qrcode.constants import ERROR_CORRECT_H
from PIL import Image, ImageDraw, ImageFont

# App icon colors, matching app/src/main/res/drawable/ic_launcher_foreground.xml.
_LOGO_OUTER = "#81C784"
_LOGO_INNER = "#43A047"
_LOGO_CHECK = "#2E7D32"


def _load_font(size: int) -> ImageFont.FreeTypeFont:
    for candidate in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "C:/Windows/Fonts/segoeuib.ttf",
    ):
        try:
            return ImageFont.truetype(candidate, size)
        except OSError:
            continue
    return ImageFont.load_default()


def make_qr_with_logo(url: str, version_label: str, out_path: pathlib.Path) -> None:
    """Renders a QR code with a center carve-out holding the app mark + short version.

    Uses error-correction level H (tolerates ~30% damage) and keeps the carve-out to a
    small fraction of the total area so the code stays reliably scannable.
    """
    qr = qrcode.QRCode(error_correction=ERROR_CORRECT_H, box_size=10, border=4)
    qr.add_data(url)
    qr.make(fit=True)
    img = qr.make_image(fill_color="black", back_color="white").convert("RGB")
    draw = ImageDraw.Draw(img)

    width, height = img.size
    label = version_label.lstrip("v")

    box_side = int(min(width, height) * 0.30)
    left, top = (width - box_side) // 2, (height - box_side) // 2
    right, bottom = left + box_side, top + box_side
    draw.rounded_rectangle([left, top, right, bottom], radius=box_side // 8, fill="white", outline="#cbd5e1", width=2)

    pad = int(box_side * 0.10)
    mark_size = int(box_side * 0.44)
    mark_left = left + (box_side - mark_size) // 2
    mark_top = top + pad
    mark_right, mark_bottom = mark_left + mark_size, mark_top + mark_size
    draw.rectangle([mark_left, mark_top, mark_right, mark_bottom], outline=_LOGO_OUTER, width=max(1, mark_size // 16))
    inset = max(2, mark_size // 6)
    draw.rectangle(
        [mark_left + inset, mark_top + inset, mark_right - inset, mark_bottom - inset],
        outline=_LOGO_INNER,
        width=max(1, mark_size // 14),
    )
    check_w = max(2, mark_size // 10)
    cx0, cy0 = mark_left + mark_size * 0.28, mark_top + mark_size * 0.52
    cx1, cy1 = mark_left + mark_size * 0.44, mark_top + mark_size * 0.70
    cx2, cy2 = mark_left + mark_size * 0.74, mark_top + mark_size * 0.32
    draw.line([cx0, cy0, cx1, cy1, cx2, cy2], fill=_LOGO_CHECK, width=check_w, joint="curve")

    max_text_width = box_side - 2 * pad
    font_size = max(10, int(box_side * 0.16))
    while font_size > 8:
        font = _load_font(font_size)
        text_bbox = draw.textbbox((0, 0), label, font=font)
        text_w = text_bbox[2] - text_bbox[0]
        if text_w <= max_text_width:
            break
        font_size -= 1
    text_h = text_bbox[3] - text_bbox[1]
    text_x = left + (box_side - text_w) // 2 - text_bbox[0]
    text_y = mark_bottom + pad - text_bbox[1]
    draw.text((text_x, text_y), label, fill=_LOGO_CHECK, font=font)

    img.save(out_path)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True, help="Direct APK download URL")
    parser.add_argument("--version", required=True, help="Release tag/version label")
    parser.add_argument("--branch", default="main", help="Branch this build was made from")
    parser.add_argument("--main-url", default="", help="Direct APK download URL for the latest main release")
    parser.add_argument("--main-version", default="", help="Release tag/version label for the latest main release")
    parser.add_argument("--out", default="docs", help="Output directory")
    args = parser.parse_args()

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    def normalize(version: str) -> str:
        return version if version.startswith("v") else f"v{version}"

    def short_display_version(version: str) -> str:
        match = re.match(r"^v?\d+\.\d+\.\d+-\d+", version)
        return normalize(match.group(0)) if match else normalize(version)

    display_version = short_display_version(args.version)

    def qr_filename(prefix: str, version: str) -> str:
        slug = re.sub(r"[^A-Za-z0-9._-]+", "-", version.lstrip("v")).strip("-") or "build"
        return f"{prefix}-{slug}.png"

    qr_name = qr_filename("qr", display_version)
    make_qr_with_logo(args.url, display_version, out_dir / qr_name)

    show_main_qr = bool(args.main_url) and args.branch != "main"
    main_display_version = short_display_version(args.main_version) if args.main_version else ""
    main_qr_name = ""
    if show_main_qr:
        main_qr_name = qr_filename("qr-main", main_display_version)
        make_qr_with_logo(args.main_url, main_display_version, out_dir / main_qr_name)

    def qr_block(url: str, version: str, img_name: str, label: str = "") -> str:
        label_html = f'<div class="qr-label">{label}</div>' if label else ""
        return f"""<div class="qr-container">
            {label_html}
            <img src="{img_name}" alt="ActiveTasks APK QR Code ({version})">
            <br>
            <a href="{url}" class="btn" target="_blank" rel="noopener">Download ActiveTasks APK ({version})</a>
            <br>
            <a href="{url}" target="_blank" rel="noopener">Open the download again</a>
          </div>"""

    if show_main_qr:
        qr_section_html = f"""<div class="qr-row">
            {qr_block(args.main_url, main_display_version, main_qr_name, "Stable Release")}
            {qr_block(args.url, display_version, qr_name, args.branch)}
          </div>"""
        scan_target = "the <strong>Stable Release</strong> QR code (on the left) below"
        qr_guidance_html = (
            '<p class="qr-guidance"><strong>Which one to use:</strong> Install from the '
            '<strong>Stable Release</strong> on the left &mdash; that is the build you should '
            f'download and run. The <strong>{args.branch}</strong> development build on the right '
            'is an in-progress branch, published for testing only. You are welcome to try it, but '
            'it may be unstable, may fail to install or launch, and carries no guarantee that it '
            'works at all.</p>'
        )
    else:
        qr_section_html = qr_block(args.url, display_version, qr_name)
        scan_target = "the QR code below"
        qr_guidance_html = ""

    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>ActiveTasks &mdash; Quick Start & Onboarding</title>
<style>
  :root {{
    --bg: #0f172a;
    --card-bg: #1e293b;
    --text: #f8fafc;
    --text-muted: #94a3b8;
    --accent: #38bdf8;
    --accent-hover: #0284c7;
    --border: #334155;
    --badge-bg: #0369a1;
  }}
  * {{ box-sizing: border-box; margin: 0; padding: 0; }}
  body {{
    font-family: system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
    background-color: var(--bg);
    color: var(--text);
    line-height: 1.6;
    padding: 2rem 1rem;
    max-width: 800px;
    margin: 0 auto;
  }}
  header {{
    text-align: center;
    margin-bottom: 2.5rem;
    padding-bottom: 1.5rem;
    border-bottom: 1px solid var(--border);
  }}
  h1 {{
    font-size: 2.25rem;
    color: var(--accent);
    margin-bottom: 0.5rem;
  }}
  .tagline {{
    font-size: 1.1rem;
    font-weight: 600;
    color: var(--text-muted);
    margin-bottom: 1rem;
  }}
  .intro {{
    font-size: 1.05rem;
    color: #cbd5e1;
    text-align: left;
    background: var(--card-bg);
    padding: 1.25rem;
    border-radius: 8px;
    border: 1px solid var(--border);
  }}
  .step {{
    background: var(--card-bg);
    border: 1px solid var(--border);
    border-radius: 10px;
    padding: 1.5rem;
    margin-bottom: 2rem;
  }}
  .step-header {{
    display: flex;
    align-items: center;
    gap: 0.75rem;
    margin-bottom: 1rem;
  }}
  .step-num {{
    background: var(--badge-bg);
    color: #fff;
    font-weight: bold;
    width: 32px;
    height: 32px;
    border-radius: 50%;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
  }}
  .step-title {{
    font-size: 1.3rem;
    font-weight: 600;
    color: var(--text);
  }}
  ul, ol {{
    margin-left: 1.5rem;
    margin-bottom: 1rem;
  }}
  li {{
    margin-bottom: 0.5rem;
  }}
  a {{
    color: var(--accent);
    text-decoration: none;
  }}
  a:hover {{
    text-decoration: underline;
  }}
  .btn {{
    display: inline-block;
    background: var(--accent);
    color: #0f172a;
    font-weight: bold;
    padding: 0.6rem 1.2rem;
    border-radius: 6px;
    margin-top: 0.5rem;
  }}
  .btn:hover {{
    background: var(--accent-hover);
    color: #fff;
    text-decoration: none;
  }}
  .qr-container {{
    text-align: center;
    margin: 1.25rem 0;
  }}
  .qr-container img {{
    background: #fff;
    padding: 10px;
    border-radius: 8px;
    max-width: 220px;
    height: auto;
  }}
  .qr-row {{
    display: flex;
    flex-wrap: wrap;
    justify-content: center;
    gap: 1.5rem;
  }}
  .qr-row .qr-container {{
    flex: 1 1 220px;
    max-width: 260px;
  }}
  .qr-label {{
    font-weight: 600;
    color: var(--accent);
    margin-bottom: 0.4rem;
  }}
  .badge-android {{
    display: inline-block;
    background: #15803d;
    color: #fff;
    font-size: 0.8rem;
    padding: 0.2rem 0.5rem;
    border-radius: 4px;
    vertical-align: middle;
    margin-left: 0.5rem;
  }}
  .qr-guidance {{
    margin-top: 1rem;
    padding: 0.9rem 1rem;
    background: #0b1220;
    border: 1px solid var(--border);
    border-left: 3px solid var(--accent);
    border-radius: 6px;
    font-size: 0.95rem;
    color: #cbd5e1;
  }}
</style>
</head>
<body>
  <header>
    <h1>ActiveTasks</h1>
    <div class="tagline">A to-do list, prioritized the Eisenhower way.</div>
    <div class="intro">
      <strong>ActiveTasks</strong> is a companion to MicroTasking: a traditional to-do list that reads
      the same Google Sheet as MicroTasking. Items don't just appear here from every checked row -
      you <strong>refer</strong> a task to ActiveTasks from MicroTasking's own task queue, dragging a
      finger across an importance/urgency matrix to set its priority as you send it over.
    </div>
  </header>

  <main>
    <!-- STEP 1 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">1</div>
        <div class="step-title">Install & Launch the App <span class="badge-android">Android Only</span></div>
      </div>
      <ol>
        <li>
          <strong>Scan or Download:</strong> Point your phone's camera at {scan_target}, or tap its download link if you are viewing this page on your phone:
          {qr_section_html}
          {qr_guidance_html}
        </li>
        <li>
          <strong>Allow Sideloading (First Time Only):</strong>
          <ul>
            <li>After tapping <strong>Download anyway</strong>, open your browser's <strong>Downloads</strong> list or the Android <strong>Files</strong> app and tap the downloaded <code>.apk</code> file. The browser may not open the installer automatically.</li>
            <li>If Android displays <em>"For your security, your phone is not allowed to install unknown apps from this source"</em>, tap <strong>Settings</strong>, toggle <strong>Allow from this source</strong> to ON, then go back and tap <strong>Install</strong>.</li>
            <li>If Google Play Protect displays a warning, tap <strong>More details &rarr; Install anyway</strong>.</li>
          </ul>
        </li>
      </ol>
    </section>

    <!-- STEP 2 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">2</div>
        <div class="step-title">Point It at Your MicroTasking Google Sheet</div>
      </div>
      <p>
        ActiveTasks doesn't need a spreadsheet of its own &mdash; it reads the same one MicroTasking
        already uses. If you already have MicroTasking set up:
      </p>
      <ol>
        <li>
          Get your two setup QR codes (Sheet URL and Apps Script Web App URL) from
          <a href="https://vemcg.github.io/MicroTasking/#setup" target="_blank" rel="noopener">Step 3 of the MicroTasking onboarding page</a>.
          That section also explains how to deploy the Web App and find its URL &mdash; it's the
          same two codes for both apps, so if you already made them for MicroTasking, reuse them.
        </li>
        <li>Open <strong>ActiveTasks</strong> on your Android phone, open <strong>Settings</strong> and tap <strong>Scan Sheet QR Code</strong> and <strong>Scan Web App QR Code</strong> to scan the two codes (each is filed by what it contains, so a mix-up can't overwrite the other box). Or paste the two URLs into the boxes by hand.</li>
        <li>Tap <strong>Sync Lists</strong>.</li>
      </ol>
    </section>

    <!-- STEP 3 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">3</div>
        <div class="step-title">Refer a Task from MicroTasking</div>
      </div>
      <p>
        ActiveTasks starts empty on purpose &mdash; a task only shows up here once you send it over
        from MicroTasking:
      </p>
      <ol>
        <li>In <strong>MicroTasking</strong>, open the task queue.</li>
        <li>Tap <strong>Refer to ActiveTasks</strong> on any task, any time &mdash; it doesn't matter whether you've started it yet.</li>
        <li>Drag/tap the importance &times; urgency matrix that pops up to set its priority. That task leaves MicroTasking's queue and lands in ActiveTasks.</li>
        <li>Back in <strong>ActiveTasks</strong>, swipe between your lists (one per Sheet tab) to find it, set a progress %, and mark it <strong>Complete (for now)</strong> to hand it back to MicroTasking, or <strong>Fully complete</strong> to remove it for good.</li>
      </ol>
    </section>
  </main>
</body>
</html>
"""
    (out_dir / "index.html").write_text(html, encoding="utf-8")


if __name__ == "__main__":
    main()
