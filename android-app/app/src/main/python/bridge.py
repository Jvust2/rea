"""In-process yt-dlp bridge for FlowSave Android.

The build workflow copies the yt_dlp Python package from Jvust2/yt-dlp
into this Python source directory before packaging the APK.
"""
import json
import os
from pathlib import Path

from yt_dlp import YoutubeDL


def _settings():
    return {
        'quiet': True,
        'no_warnings': True,
        'noplaylist': True,
        'cachedir': False,
        'ignoreerrors': False,
    }


def probe(url):
    try:
        with YoutubeDL({**_settings(), 'skip_download': True}) as ydl:
            data = ydl.extract_info(url, download=False)
        if not data:
            raise ValueError('视频信息无法读取')
        return json.dumps({
            'title': str(data.get('title') or '未命名视频'),
            'uploader': str(data.get('uploader') or data.get('channel') or ''),
            'duration': int(data.get('duration') or 0),
            'ext': str(data.get('ext') or ''),
        }, ensure_ascii=False)
    except Exception as exc:
        return json.dumps({'error': str(exc)}, ensure_ascii=False)


def download(url, mode, output_dir, observer):
    """Download one source stream (no ffmpeg merge/transcode)."""
    try:
        os.makedirs(output_dir, exist_ok=True)
        final_file = [None]
        def report(progress, message):
            try:
                observer.onProgress(int(progress), str(message))
            except Exception:
                pass

        def on_progress(data):
            state = data.get('status')
            if state == 'downloading':
                total = data.get('total_bytes') or data.get('total_bytes_estimate') or 0
                current = data.get('downloaded_bytes') or 0
                percent = max(0, min(99, round(current * 100 / total))) if total else 0
                report(percent, f'正在下载 · {percent}%' if total else '正在下载…')
            elif state == 'finished':
                final_file[0] = data.get('filename')
                report(99, '下载完成，正在保存到手机…')

        # A single media file is selected deliberately: Android FFmpeg isn't bundled.
        format_value = (
            'bestaudio[ext=m4a]/bestaudio'
            if mode == 'audio' else
            'best[height<=720][ext=mp4][vcodec!=none][acodec!=none]/'
            'best[height<=720][vcodec!=none][acodec!=none]/'
            'best[ext=mp4][vcodec!=none][acodec!=none]/'
            'best[vcodec!=none][acodec!=none]'
        )
        options = {
            **_settings(),
            'format': format_value,
            'outtmpl': {'default': '%(title).100B [%(id)s].%(ext)s'},
            'paths': {'home': output_dir},
            'progress_hooks': [on_progress],
            'overwrites': False,
            'windowsfilenames': True,
        }
        report(0, '正在连接媒体站点…')
        with YoutubeDL(options) as ydl:
            info = ydl.extract_info(url, download=True)
        if not info:
            raise ValueError('下载未返回媒体信息')
        paths = [
            entry.get('filepath') for entry in info.get('requested_downloads', [])
            if isinstance(entry, dict)
        ]
        paths += [final_file[0], info.get('_filename')]
        for item in paths:
            if item and os.path.isfile(item) and not item.endswith('.part'):
                return json.dumps({'path': str(item)}, ensure_ascii=False)
        # yt-dlp may rename the download during postprocessing.
        files = [p for p in Path(output_dir).iterdir() if p.is_file() and not p.name.endswith(('.part', '.ytdl'))]
        if files:
            best = max(files, key=lambda p: p.stat().st_mtime)
            return json.dumps({'path': str(best)}, ensure_ascii=False)
        raise ValueError('未找到下载文件，站点可能要求 FFmpeg、登录或额外验证')
    except Exception as exc:
        return json.dumps({'error': str(exc)}, ensure_ascii=False)
