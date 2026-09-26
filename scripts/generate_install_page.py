#!/usr/bin/env python3
# Copyright (c) 2026 Vern McGeorge. All rights reserved.
# Updated 2026-09-26, after version v0.2.0-29 main 2026-09-26
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
    parser.add_argument(
        "--template-url",
        default="https://docs.google.com/spreadsheets/d/1YZNQxZlzj8Xj4Bya2v8YEiq01n6jseD3JZSr7BlThJo/edit?usp=sharing",
        help="Google Sheet template URL (the same template MicroTasking's page links to)",
    )
    parser.add_argument(
        "--microtasking-page-url",
        default="https://vemcg.github.io/MicroTasking/",
        help="MicroTasking's onboarding page, linked from this one (it links back here)",
    )
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
  .companion-link {{
    margin-top: 1rem;
    text-align: left;
    background: var(--card-bg);
    padding: 0.9rem 1.25rem;
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
  .qr-container img, .qr-container canvas {{
    background: #fff;
    padding: 10px;
    border-radius: 8px;
    max-width: 220px;
    height: auto;
  }}
  /* qrcodejs drops a <canvas> (or a <table> fallback) straight into #sheetQr / #webAppQr; center whichever. */
  #sheetQr, #webAppQr {{
    display: flex;
    justify-content: center;
  }}
  #sheetQr table, #webAppQr table {{
    margin: 0 auto;
  }}
  .setup-part {{
    margin-top: 1.5rem;
    padding-top: 1rem;
    border-top: 1px solid var(--border, rgba(127, 127, 127, 0.3));
  }}
  .setup-part h3 {{
    margin: 0 0 0.5rem;
    font-size: 1.05rem;
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
  .qr-meta {{
    margin-top: 0.5rem;
    font-size: 0.8rem;
    color: var(--text-muted);
    word-break: break-all;
  }}
  .input-group {{
    margin: 1rem 0;
  }}
  input[type="url"] {{
    width: 100%;
    padding: 0.75rem;
    border-radius: 6px;
    border: 1px solid var(--border);
    background: #090d16;
    color: #fff;
    font-size: 1rem;
  }}
  input[type="url"]:focus {{
    outline: none;
    border-color: var(--accent);
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
<script src="https://cdnjs.cloudflare.com/ajax/libs/qrcodejs/1.0.0/qrcode.min.js"></script>
</head>
<body>
  <header>
    <h1>ActiveTasks</h1>
    <div class="tagline">A to-do list, prioritized the Eisenhower way.</div>
    <div class="intro">
      <strong>ActiveTasks</strong> is a traditional to-do list for the tasks you have decided to work on. Every task sits on a priority you set by touching an importance &times; urgency matrix, and each list is a tab of your own Google Sheet. It works on its own: you find tasks in your Sheet and activate the ones you want, right in the app. It is also a peer of <a href="{args.microtasking_page_url}" target="_blank" rel="noopener"><strong>MicroTasking</strong></a>, which prompts you with bite-sized tasks from the same Sheet &mdash; use either app, or both together. <a href="{args.microtasking_page_url}" target="_blank" rel="noopener">MicroTasking's setup page &rarr;</a>
    </div>
  </header>

  <main>
    <!-- STEP 1 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">1</div>
        <div class="step-title">Create & Customize Your Task Pool</div>
      </div>
      <p>Your tasks are managed directly in a Google Sheet. <strong>Already set one up for MicroTasking?</strong> It is the very same Sheet &mdash; skip to Step 2.</p>
      <ul>
        <li><strong>Open the Template:</strong> Click <a href="{args.template_url}" target="_blank" rel="noopener">Google Sheet Template</a> to open it in your browser.</li>
        <li><strong>Make Your Copy:</strong> In Google Sheets, tap <strong>File &rarr; Make a copy</strong> to save an editable copy to your Google Drive.</li>
        <li><strong>Lists = Tabs:</strong> Each tab at the bottom is a list (e.g. <em>Cleaning</em>, <em>Decluttering</em>, <em>Admin</em>). Add, rename, or remove tabs as you wish.</li>
        <li><strong>Tasks & Checkboxes:</strong> Add your tasks into the spreadsheet. Use the checkbox in <strong>Column A</strong> to enable or disable individual tasks &mdash; ActiveTasks's <strong>Find Task</strong> only offers enabled ones. <em>(Which tabs Find Task searches can be chosen in the app's Settings &rarr; Task Categories.)</em></li>
      </ul>
    </section>

    <!-- STEP 2 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">2</div>
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
        <li>
          <strong>Updating from a build before September 24, 2026? Uninstall it first, once.</strong>
          ActiveTasks is now signed with the same key as MicroTasking, so the two apps can update each
          other instantly, and Android won't install it over an older copy signed with the old key
          ("App not installed"). Uninstall ActiveTasks, then install this one. Your lists come back
          from your Sheet on the first sync; only progress percentages are lost.
        </li>
        <li>
          <strong>Launch:</strong> Open ActiveTasks from your home screen. It never sends notifications or wakes your screen; the only permission it asks for is the <strong>Camera</strong>, when you scan a setup QR code in Step 4.
        </li>
      </ol>
    </section>

    <!-- STEP 3 -->
    <section class="step" id="setup">
      <div class="step-header">
        <div class="step-num">3</div>
        <div class="step-title">Generate Your Setup QR Codes</div>
      </div>
      <p>Instead of typing long URLs on your phone keyboard, generate QR codes right here. There are <strong>two separate codes</strong> &mdash; one for your Sheet, one for the Web App URL &mdash; and the same two work in <strong>both</strong> ActiveTasks and <a href="{args.microtasking_page_url}#setup" target="_blank" rel="noopener">MicroTasking</a>. You scan each one on its own; scanning one never replaces what the other one set. (This page and MicroTasking's page remember what you type, so you only enter each URL once.)</p>

      <div class="setup-part">
        <h3>A. Sheet QR code</h3>
        <p>In your copied Google Sheet, make sure sharing is set to <strong>"Anyone with the link can view"</strong> (tap <strong>Share &rarr; Anyone with the link</strong>). Then copy the Sheet URL from your browser bar and paste the whole thing below &mdash; <code>#gid=</code> and <code>?usp=</code> junk and all. It's trimmed down to just the sheet ID before the QR code is made.</p>
        <div class="input-group">
          <input type="url" id="sheetUrl" placeholder="Google Sheet URL: https://docs.google.com/spreadsheets/d/your-sheet-id/edit..." oninput="generateSheetQr()" onfocus="this.select()">
        </div>
        <div class="qr-container" id="sheetQrContainer" style="display:none;">
          <p style="margin-bottom: 0.5rem; font-weight: 600; color: var(--accent);">Your Sheet QR Code:</p>
          <div id="sheetQr"></div>
          <div class="qr-meta" id="sheetQrMeta"></div>
        </div>
      </div>

      <div class="setup-part">
        <h3>B. Web App QR code</h3>
        <p>This is the address that lets the app write back to your Sheet &mdash; activating a task, marking it complete, and changing its priority. It is <em>not</em> the address of the Apps Script editor tab. Get it by deploying the script from your Sheet, once, on a computer:</p>
        <ol>
          <li>In your Sheet, open <strong>Extensions &rarr; Apps Script</strong>.</li>
          <li>Click <strong>Deploy &rarr; New deployment</strong> (or <strong>Manage deployments</strong> if you already deployed it).</li>
          <li>Click the gear next to "Select type" and choose <strong>Web app</strong>. Set <strong>Execute as: Me</strong> and <strong>Who has access: Anyone</strong>, then click <strong>Deploy</strong> (approve the permissions prompt the first time).</li>
          <li>Copy the <strong>Web app URL</strong> it shows &mdash; it starts with <code>https://script.google.com/macros/s/</code> and ends in <code>/exec</code> &mdash; and paste it below.</li>
        </ol>
        <div class="input-group">
          <input type="url" id="webAppUrl" placeholder="Apps Script Web App URL: https://script.google.com/macros/s/.../exec" oninput="generateWebAppQr()" onfocus="this.select()">
          <div class="qr-meta" id="webAppUrlProblem" style="color: #fca5a5;"></div>
        </div>
        <div class="qr-container" id="webAppQrContainer" style="display:none;">
          <p style="margin-bottom: 0.5rem; font-weight: 600; color: var(--accent);">Your Web App QR Code:</p>
          <div id="webAppQr"></div>
          <div class="qr-meta" id="webAppQrMeta"></div>
        </div>
      </div>
    </section>

    <!-- STEP 4 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">4</div>
        <div class="step-title">Scan & Sync in ActiveTasks</div>
      </div>
      <ol>
        <li>Open the <strong>ActiveTasks</strong> app on your Android phone.</li>
        <li>Tap the <strong>Settings (gear icon)</strong> in the top corner.</li>
        <li>Open the <strong>Google Sheet Connection</strong> section.</li>
        <li>Tap <strong>Scan Sheet QR Code</strong> and scan the <strong>Sheet QR code</strong> from Step 3. A scan only fills in the Sheet URL box &mdash; nothing is saved yet.</li>
        <li>Tap <strong>Scan Web App QR Code</strong> and scan that one. It fills in the Web App URL box the same way. Under each box you'll see a link to what you scanned, or a note if it doesn't look right.</li>
        <li>Tap <strong>Save Settings</strong>. That saves both and syncs your lists and tasks; the screen says <em>Syncing&hellip;</em> and returns to your lists when it's done (or stays open and tells you what went wrong).</li>
      </ol>
    </section>

    <!-- STEP 5 -->
    <section class="step">
      <div class="step-header">
        <div class="step-num">5</div>
        <div class="step-title">Find Your First Tasks</div>
      </div>
      <p>ActiveTasks starts empty on purpose &mdash; a task only appears once you <strong>activate</strong> it by giving it a priority:</p>
      <ol>
        <li>Tap the <strong>circle-with-a-plus</strong> button in the top bar to find tasks across every category chosen in <strong>Settings &rarr; Task Categories</strong>, or the same button at the bottom right of a list to find tasks in just that list.</li>
        <li>Tap <strong>Activate</strong> on a task, then touch the importance &times; urgency matrix to set its priority (and, if you like, its progress) and tap <strong>Activate</strong> again.</li>
        <li>Swipe between your lists to work through them. Mark a task <strong>Complete (for now)</strong> to put it back in the pool, or <strong>Fully complete</strong> to remove it for good.</li>
      </ol>
      <p style="color: var(--accent); font-weight: 600; margin-top: 0.5rem;">You're all set! Want random, bite-sized prompts from the same Sheet too? <a href="{args.microtasking_page_url}" target="_blank" rel="noopener">Set up MicroTasking</a> &mdash; scan the same two codes from Step 3 there. Tasks you refer from MicroTasking show up here automatically.</p>
    </section>
  </main>

  <script>
    // Same keys as MicroTasking's page on purpose: both pages are served from the same origin
    // (vemcg.github.io), so URLs entered on either one are already filled in on the other.
    const SHEET_URL_STORAGE_KEY = 'microtaskingSheetUrl';
    const WEBAPP_URL_STORAGE_KEY = 'microtaskingWebAppUrl';

    // The spreadsheet id out of whatever the user pasted (full URL, /edit#gid= link, or a bare id).
    function sheetIdFrom(raw) {{
      const str = String(raw || '').trim();
      const m = str.match(/\\/spreadsheets\\/d\\/([a-zA-Z0-9_-]+)/) || str.match(/^([a-zA-Z0-9_-]{{20,}})$/);
      return m ? m[1] : null;
    }}

    // The apps' QR scanners just read the raw scanned text - there's no custom URI scheme. There
    // are two separate codes, each a single line: the sheet URL, or the Web App URL. Each app tells
    // them apart by what the line looks like (script.google.com = Web App; see parseSetupQr in
    // SheetApiClient.kt) and only updates the setting that line belongs to, so scanning one code
    // never overwrites what the other set. (Older two-line codes still parse the same way.)
    function drawQr(container, qrDiv, text) {{
      qrDiv.innerHTML = '';
      container.style.display = 'block';
      new QRCode(qrDiv, {{
        text: text,
        width: 200,
        height: 200,
        colorDark : "#000000",
        colorLight : "#ffffff",
        correctLevel : QRCode.CorrectLevel.M
      }});
    }}

    // A Web App URL is https://script.google.com/macros/s/<id>/exec. The commonest mix-up is
    // pasting the Apps Script *editor's* address instead, so name that case explicitly.
    // Returns null when the URL is empty or fine, else a message saying what's wrong.
    function webAppUrlProblem(raw) {{
      const str = String(raw || '').trim();
      if (!str) return null;
      if (str.indexOf('https://script.google.com/') !== 0) {{
        return "That doesn't look like a Web App URL - it should start with https://script.google.com/macros/s/";
      }}
      if (str.indexOf('/home/') !== -1 || /\\/edit([?#]|$)/.test(str)) {{
        return "That's the address of the Apps Script editor, not the Web App URL. In the editor, click Deploy > New deployment (or Manage deployments) and copy the Web app URL it shows.";
      }}
      if (!/\\/exec([?#]|$)/.test(str)) {{
        return "A Web App URL ends in /exec (a URL ending in /dev is the test deployment and won't work from your phone).";
      }}
      return null;
    }}

    function generateSheetQr() {{
      const container = document.getElementById('sheetQrContainer');
      const meta = document.getElementById('sheetQrMeta');
      const id = sheetIdFrom(document.getElementById('sheetUrl').value);
      const sheetUrl = id ? 'https://docs.google.com/spreadsheets/d/' + id : '';

      if (sheetUrl) {{
        localStorage.setItem(SHEET_URL_STORAGE_KEY, sheetUrl);
        drawQr(container, document.getElementById('sheetQr'), sheetUrl);
        meta.textContent = 'Generated from sheet ' + id + ' at ' + new Date().toLocaleString();
      }} else {{
        localStorage.removeItem(SHEET_URL_STORAGE_KEY);
        document.getElementById('sheetQr').innerHTML = '';
        container.style.display = 'none';
        meta.textContent = '';
      }}
    }}

    function generateWebAppQr() {{
      const container = document.getElementById('webAppQrContainer');
      const meta = document.getElementById('webAppQrMeta');
      const problemDiv = document.getElementById('webAppUrlProblem');
      const webAppUrl = document.getElementById('webAppUrl').value.trim();
      const problem = webAppUrlProblem(webAppUrl);

      problemDiv.textContent = problem || '';
      if (webAppUrl) {{
        localStorage.setItem(WEBAPP_URL_STORAGE_KEY, webAppUrl);
      }} else {{
        localStorage.removeItem(WEBAPP_URL_STORAGE_KEY);
      }}

      if (webAppUrl && !problem) {{
        drawQr(container, document.getElementById('webAppQr'), webAppUrl);
        meta.textContent = 'Generated from the Web App URL at ' + new Date().toLocaleString();
      }} else {{
        document.getElementById('webAppQr').innerHTML = '';
        container.style.display = 'none';
        meta.textContent = '';
      }}
    }}

    (function restoreSavedUrls() {{
      const saved = localStorage.getItem(SHEET_URL_STORAGE_KEY);
      const savedWebApp = localStorage.getItem(WEBAPP_URL_STORAGE_KEY);
      if (saved) {{
        document.getElementById('sheetUrl').value = saved;
        generateSheetQr();
      }}
      if (savedWebApp) {{
        document.getElementById('webAppUrl').value = savedWebApp;
        generateWebAppQr();
      }}
    }})();
  </script>
</body>
</html>
"""
    (out_dir / "index.html").write_text(html, encoding="utf-8")


if __name__ == "__main__":
    main()
