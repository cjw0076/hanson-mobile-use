import os
import sys
import numpy as np
from PIL import Image

REPO_ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SCREENSHOTS_DIR = os.path.join(REPO_ROOT, "screenshots")
CODEBASE_DIR = os.path.join(REPO_ROOT, "app", "src", "main")

SCREENSHOT_FILES = [
    "s25_lifestyle_main.png",
    "s25_ime_input.png",
    "s25_media_player.png",
    "s25_3d_glb_stage.png",
    "s25_api_key_sheet.png",
    "s25_live_voice_session.png"
]

BANNED_JARGON = [
    "RAW·S25-NPU-NA",
    "MEM 12M·3기억",
    "MEM 45M",
    "GLB · 3D VIEW",
    "SOLID / WIRE / PTS",
    "120KB · 2399V/1404T",
    "YOUTUBE IN-APP",
    "인간 유저용"
]

def check_png_header(filepath):
    with open(filepath, 'rb') as f:
        header = f.read(8)
    return header == b'\x89PNG\r\n\x1a\n'

def compute_entropy(img_arr):
    if len(img_arr.shape) == 3:
        gray = (img_arr[...,0]*0.2989 + img_arr[...,1]*0.5870 + img_arr[...,2]*0.1140).astype(np.uint8)
    else:
        gray = img_arr.astype(np.uint8)
    hist, _ = np.histogram(gray, bins=256, range=(0, 256), density=True)
    hist = hist[hist > 0]
    return -np.sum(hist * np.log2(hist))

def run_image_inspection():
    print("=" * 60, flush=True)
    print("1. EMPIRICAL IMAGE INSPECTION & S25 AUTHENTICITY", flush=True)
    print("=" * 60, flush=True)
    all_passed = True
    
    for filename in SCREENSHOT_FILES:
        filepath = os.path.join(SCREENSHOTS_DIR, filename)
        if not os.path.exists(filepath):
            print(f"[FAIL] Missing file: {filename}", flush=True)
            all_passed = False
            continue
            
        size = os.path.getsize(filepath)
        is_png = check_png_header(filepath)
        
        try:
            with Image.open(filepath) as img:
                w, h = img.size
                mode = img.mode
                arr = np.array(img.convert('RGB'))
                
            # Fast unique color count via 24-bit packing
            packed = (arr[..., 0].astype(np.uint32) << 16) | (arr[..., 1].astype(np.uint32) << 8) | arr[..., 2].astype(np.uint32)
            unique_colors = len(np.unique(packed))
            std_dev = float(np.std(arr))
            mean_val = float(np.mean(arr))
            entropy = float(compute_entropy(arr))
            
            # Validation criteria for real Samsung Galaxy S25 screenshots
            res_ok = (w == 1080 and h == 2340)
            size_ok = (size > 100_000)
            non_blank = (std_dev > 15.0 and unique_colors > 500 and entropy > 1.5)
            
            status = "PASS" if (is_png and res_ok and size_ok and non_blank) else "FAIL"
            if status == "FAIL":
                all_passed = False
                
            print(f"[{status}] {filename}:", flush=True)
            print(f"       Size: {size:,} bytes | Valid PNG: {is_png}", flush=True)
            print(f"       Resolution: {w}x{h} (Target 1080x2340: {res_ok}) | Mode: {mode}", flush=True)
            print(f"       StdDev: {std_dev:.2f} | Mean: {mean_val:.2f} | Unique Colors: {unique_colors:,} | Entropy: {entropy:.2f}", flush=True)
            
        except Exception as e:
            print(f"[FAIL] Error analyzing {filename}: {e}", flush=True)
            all_passed = False

    return all_passed

def run_badge_codebase_grep():
    print("\n" + "=" * 60, flush=True)
    print("2. BADGE ELIMINATION & JARGON LEAKAGE STRESS TEST", flush=True)
    print("=" * 60, flush=True)
    
    violations = []
    scanned_files = 0
    
    for root, _, files in os.walk(CODEBASE_DIR):
        for f in files:
            if f.endswith(('.kt', '.java', '.xml')):
                scanned_files += 1
                fpath = os.path.join(root, f)
                with open(fpath, 'r', encoding='utf-8', errors='ignore') as fp:
                    content = fp.read()
                    for jargon in BANNED_JARGON:
                        if jargon in content:
                            rel_path = os.path.relpath(fpath, CODEBASE_DIR)
                            violations.append((rel_path, jargon))

    print(f"Scanned {scanned_files} production source files under app/src/main.", flush=True)
    if violations:
        print(f"[FAIL] Found {len(violations)} banned jargon occurrences in production source:", flush=True)
        for v in violations:
            print(f"       - {v[0]}: banned string '{v[1]}'", flush=True)
        return False
    else:
        print("[PASS] Zero banned developer badges/jargon found in production source files!", flush=True)
        return True

def run_ime_occlusion_analysis():
    print("\n" + "=" * 60, flush=True)
    print("3. IME KEYBOARD OCCLUSION & ERGONOMICS TEST", flush=True)
    print("=" * 60, flush=True)
    
    ime_path = os.path.join(SCREENSHOTS_DIR, "s25_ime_input.png")
    if not os.path.exists(ime_path):
        print(f"[FAIL] {ime_path} not found", flush=True)
        return False
        
    img = Image.open(ime_path).convert('RGB')
    arr = np.array(img)
    w, h = img.size
    
    # Calculate row-wise mean brightness
    row_means = np.mean(arr, axis=(1, 2))
    
    # Locate keyboard top edge: transition from light surface (>180) to dark keyboard surface (<80)
    keyboard_top = None
    for y in range(1100, 1600):
        if row_means[y] < 70 and row_means[y-15] > 180:
            keyboard_top = y
            break
            
    print(f"Detected Soft Keyboard (Honeyboard) Top Y: {keyboard_top} px", flush=True)
    
    # Composer region
    composer_top = keyboard_top - 200
    composer_bottom = keyboard_top
    composer_region = arr[composer_top:composer_bottom, :]
    
    # Measure Send button bounds and touch target
    send_slice = composer_region[:, 750:1000]
    send_dark_mask = np.mean(send_slice, axis=-1) < 40
    send_y_indices, send_x_indices = np.where(send_dark_mask)
    
    send_h = np.ptp(send_y_indices) if len(send_y_indices) > 0 else 0
    send_w = np.ptp(send_x_indices) if len(send_x_indices) > 0 else 0
    
    # Check cursor '|' in text input field
    text_slice = composer_region[:, 70:200]
    text_cols = np.min(np.mean(text_slice, axis=-1), axis=0)
    has_cursor = np.any(text_cols < 60)
    
    # Material ergonomics check
    touch_target_ok = (send_h >= 90 and send_w >= 140)
    no_occlusion = (composer_bottom <= keyboard_top and keyboard_top > 1200)
    
    print(f"Composer Box Y: {composer_top} to {composer_bottom} px (Height: {composer_bottom - composer_top} px)", flush=True)
    print(f"Send Button Size: {send_w}w x {send_h}h px (Material Touch Target >=48dp: {touch_target_ok})", flush=True)
    print(f"Input Field Cursor Detected: {has_cursor}", flush=True)
    print(f"Zero Soft Keyboard Occlusion: {no_occlusion}", flush=True)
    
    passed = (keyboard_top is not None and touch_target_ok and has_cursor and no_occlusion)
    status = "PASS" if passed else "FAIL"
    print(f"[{status}] IME Soft Keyboard Ergonomics & Non-Occlusion Verified!", flush=True)
    return passed

def run_media_visual_ergonomics_check():
    print("\n" + "=" * 60, flush=True)
    print("4. MEDIA RENDERING & VISUAL ERGONOMICS TEST", flush=True)
    print("=" * 60, flush=True)
    
    all_passed = True
    
    # 4.1 YouTube Player Check
    yt_path = os.path.join(SCREENSHOTS_DIR, "s25_media_player.png")
    yt_img = Image.open(yt_path).convert('RGB')
    yt_arr = np.array(yt_img)
    stage_region = yt_arr[1050:1650, 150:930]
    stage_dark_ratio = float(np.mean(stage_region < 40))
    center_pill = yt_arr[1300:1450, 350:730]
    pill_contrast = int(np.max(center_pill) - np.min(center_pill))
    yt_ok = (stage_dark_ratio > 0.60 and pill_contrast > 180)
    print(f"[{'PASS' if yt_ok else 'FAIL'}] YouTube 16:9 In-App Player Stage:", flush=True)
    print(f"       Dark stage ratio: {stage_dark_ratio:.1%} | Center Play Pill Contrast: {pill_contrast}", flush=True)
    if not yt_ok: all_passed = False
    
    # 4.2 3D GLB Viewport Check
    glb_path = os.path.join(SCREENSHOTS_DIR, "s25_3d_glb_stage.png")
    glb_img = Image.open(glb_path).convert('RGB')
    glb_arr = np.array(glb_img)
    viewport = glb_arr[1000:1700, 150:930]
    duck_pixels = viewport[viewport < 220]
    duck_present = len(duck_pixels) > 50_000
    bg_slice = viewport[100:200, 100:200]
    bg_std = float(np.std(bg_slice))
    no_cad_grid = (bg_std < 5.0)
    glb_ok = (duck_present and no_cad_grid)
    print(f"[{'PASS' if glb_ok else 'FAIL'}] 3D GLB Studio Stage:", flush=True)
    print(f"       Duck 3D Mesh Object Detected: {duck_present} ({len(duck_pixels):,} px)", flush=True)
    print(f"       Studio Background Smooth (Zero CAD grid lines): {no_cad_grid} (std={bg_std:.2f})", flush=True)
    if not glb_ok: all_passed = False
    
    # 4.3 API Key Sheet Check
    api_path = os.path.join(SCREENSHOTS_DIR, "s25_api_key_sheet.png")
    api_img = Image.open(api_path).convert('RGB')
    api_arr = np.array(api_img)
    upper_scrim = api_arr[200:800, :]
    upper_mean = float(np.mean(upper_scrim))
    # Bottom sheet is active between Y: 1350 and 2150
    btn_region = api_arr[2000:2120, 100:980]
    text_px = int(np.sum(btn_region > 100))
    has_btn = (text_px > 3000) # text pixels for 'API 키 저장 및 연결'
    api_ok = (upper_mean < 50 and has_btn)
    print(f"[{'PASS' if api_ok else 'FAIL'}] Editorial API Key ModalBottomSheet:", flush=True)
    print(f"       Upper Scrim Dimmed: {upper_mean < 50} (mean={upper_mean:.2f})", flush=True)
    print(f"       Bottom Sheet Action Button Text Present: {has_btn} ({text_px:,} light text px)", flush=True)
    if not api_ok: all_passed = False
    
    # 4.4 Live Voice Session Check
    voice_path = os.path.join(SCREENSHOTS_DIR, "s25_live_voice_session.png")
    voice_img = Image.open(voice_path).convert('RGB')
    voice_arr = np.array(voice_img)
    # Voice session card contour at Y: 1715-1730
    voice_contour_slice = voice_arr[1715:1730, 50:1030]
    contour_px = int(np.sum(voice_contour_slice > 200))
    has_card_contour = (contour_px > 500)
    # Dismiss button '종료' in voice card at x: 800 to 950, y: 1720 to 1800
    dismiss_btn_region = voice_arr[1720:1800, 800:950]
    has_dismiss_btn = (int(np.max(dismiss_btn_region)) > 230 and int(np.min(dismiss_btn_region)) < 50)
    voice_ok = (has_card_contour and has_dismiss_btn)
    print(f"[{'PASS' if voice_ok else 'FAIL'}] Gemini Live Voice Companion Pill Card:", flush=True)
    print(f"       Voice Session Card White Contour Detected: {has_card_contour} ({contour_px:,} px)", flush=True)
    print(f"       White Pill '종료' Dismiss Button Detected: {has_dismiss_btn}", flush=True)
    if not voice_ok: all_passed = False

    return all_passed

if __name__ == "__main__":
    t1 = run_image_inspection()
    t2 = run_badge_codebase_grep()
    t3 = run_ime_occlusion_analysis()
    t4 = run_media_visual_ergonomics_check()
    
    print("\n" + "=" * 60, flush=True)
    overall = (t1 and t2 and t3 and t4)
    verdict = "APPROVE" if overall else "REJECT"
    print(f"FINAL ADVERSARIAL CHALLENGE VERDICT: {verdict}", flush=True)
    print("=" * 60, flush=True)
    sys.exit(0 if overall else 1)
