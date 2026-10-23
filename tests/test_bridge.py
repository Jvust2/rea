import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'android-app/app/src/main/python'))
import bridge


def fmt(fid, ext, v='none', a='none', height=0, tbr=0):
    return {'format_id': fid, 'url': 'https://example.org/media/' + fid,
            'ext': ext, 'vcodec': v, 'acodec': a, 'height': height, 'tbr': tbr}


class TestSiteLinks(unittest.TestCase):
    def test_support_six_hosts(self):
        cases = {
            'https://www.youtube.com/watch?v=BaW_jenozKc': 'YouTube',
            'https://youtu.be/BaW_jenozKc': 'YouTube',
            'https://twitter.com/user/status/1234567890123456789': 'X / Twitter',
            'https://x.com/user/status/1234567890123456789': 'X / Twitter',
            'https://vt.tiktok.com/ZS12345/': 'TikTok',
            'https://www.tiktok.com/@creator/video/1234567890123456789': 'TikTok',
            'https://www.instagram.com/reel/Cabcdef123/': 'Instagram',
            'https://v.douyin.com/abcdef/': '抖音',
            'https://www.douyin.com/video/7345678901234567890': '抖音',
            'https://b23.tv/AbCd123': 'Bilibili',
            'https://www.bilibili.com/video/BV1xx411c7mD/': 'Bilibili',
        }
        for url, wanted in cases.items():
            with self.subTest(url=url):
                self.assertEqual(bridge.detect_site(url), wanted)

    def test_extract_share_link(self):
        s = '7.21 打开抖音，看看【分享者】的作品：你好 https://v.douyin.com/abcde/ 复制此链接'
        self.assertEqual(bridge.clean_url(s), 'https://v.douyin.com/abcde/')
        self.assertEqual(bridge.detect_site(s), '抖音')
        self.assertEqual(bridge.clean_url('分享 https://x.com/i/status/123456?foo=a&bar=2！'),
                         'https://x.com/i/status/123456?foo=a&bar=2')

    def test_bad_urls(self):
        with self.assertRaises(ValueError):
            bridge.clean_url('not a url')
        with self.assertRaises(ValueError):
            bridge.clean_url('https://user:pass@example.com/secret')

    def test_host_does_not_fool_detection(self):
        self.assertEqual(bridge.detect_site('https://twitter.com.evil.example/video'), '其他站点')


class TestFormats(unittest.TestCase):
    def test_youtube_video_plus_audio(self):
        info = {'formats': [
            fmt('18', 'mp4', 'avc1.42001E', 'mp4a.40.2', 360, 600),
            fmt('137', 'mp4', 'avc1.640028', 'none', 1080, 4400),
            fmt('136', 'mp4', 'avc1.4D401F', 'none', 720, 1600),
            fmt('140', 'm4a', 'none', 'mp4a.40.2', 0, 128),
        ]}
        selected = bridge.choose_formats(info, '1080')
        self.assertEqual(selected['kind'], 'mux')
        self.assertEqual(selected['video']['format_id'], '137')
        self.assertEqual(selected['audio_format']['format_id'], '140')
        self.assertEqual(bridge.choose_formats(info, '720')['video']['format_id'], '136')
        self.assertEqual(bridge.choose_formats(info, 'audio')['format']['format_id'], '140')

    def test_social_single_mp4(self):
        info = {'formats': [fmt('hd', 'mp4', 'avc1', 'mp4a', 720, 1200)]}
        self.assertEqual(bridge.choose_formats(info, 'best')['kind'], 'single')
        self.assertTrue(bridge.choose_formats(info, 'audio')['extract_audio'])

    def test_reject_drm(self):
        drm = fmt('drm', 'mp4', 'avc1', 'mp4a', 1080)
        drm['has_drm'] = True
        with self.assertRaises(ValueError):
            bridge.choose_formats({'formats': [drm]})

    def test_bad_split_rejected(self):
        info = {'formats': [fmt('vp9', 'webm', 'vp9', 'none', 1080),
                            fmt('opus', 'webm', 'none', 'opus')]}
        with self.assertRaisesRegex(ValueError, '无法直接合并'):
            bridge.choose_formats(info)
        self.assertEqual(bridge.choose_formats(info, 'audio')['format']['format_id'], 'opus')

    def test_lowest_quality_fallback(self):
        info = {'formats': [fmt('hd', 'mp4', 'avc1', 'mp4a', 720)]}
        self.assertEqual(bridge.choose_formats(info, '480')['format']['format_id'], 'hd')

    def test_runtime_path_options(self):
        self.assertNotIn('js_runtimes', bridge._options(qjs_path='/no/such/file'))

    def test_engine_extractors_match_platform_urls(self):
        # This is a *recognition* test; it does not claim remote download succeeds.
        try:
            from yt_dlp.extractor import gen_extractor_classes
        except ImportError:
            self.skipTest('yt_dlp not installed outside CI')
        cases = [
            'https://www.youtube.com/watch?v=BaW_jenozKc',
            'https://x.com/test/status/1234567890123456789',
            'https://www.tiktok.com/@test/video/7345678901234567890',
            'https://www.instagram.com/reel/CabcDEF1234/',
            'https://www.douyin.com/video/7345678901234567890',
            'https://www.bilibili.com/video/BV1xx411c7mD',
        ]
        ies = list(gen_extractor_classes())
        for url in cases:
            with self.subTest(url=url):
                matches = [ie.IE_NAME for ie in ies if ie.IE_NAME != 'generic' and ie.suitable(url)]
                self.assertTrue(matches, f'No extractor for {url}')


if __name__ == '__main__':
    unittest.main()
