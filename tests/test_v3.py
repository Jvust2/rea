import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'android-app/app/src/main/python'))
import engine_v3 as e
import remote_client


def fmt(fid, v, a='none', w=1920, h=1080, ext='mp4', rate=1000):
    return dict(format_id=fid, url='https://media.example/'+fid,vcodec=v,acodec=a,
                width=w,height=h,ext=ext,tbr=rate,fps=30)

class Selection(unittest.TestCase):
    def setUp(self):
        self.formats=[fmt('h264','avc1','mp4a.40.2',1280,720),
                      fmt('4k','vp9',w=3840,h=2160,ext='webm',rate=8000),
                      fmt('av1','av01',w=3840,h=2160,rate=10000),
                      fmt('opus','none','opus',0,0,'webm',160),
                      fmt('aac','none','mp4a.40.2',0,0,'m4a',128)]
    def test_highest_not_limited_to_h264(self):
        self.assertEqual(e.select_media({'formats':self.formats})['tracks'][0]['format_id'],'av1')
    def test_vp9_opus_supported(self):
        chosen=e.select_media({'formats':[self.formats[1],self.formats[3]]})
        self.assertEqual(len(chosen['tracks']),2)
        self.assertEqual(e.output_extension(chosen['tracks']),'mkv')
    def test_explicit_mp4_compat(self):
        self.assertEqual(e.select_media({'formats':self.formats},'compat')['tracks'][0]['format_id'],'h264')
    def test_720_cap(self):
        self.assertEqual(e.select_media({'formats':self.formats},'720')['tracks'][0]['format_id'],'h264')
    def test_cap_is_not_silently_exceeded(self):
        with self.assertRaises(ValueError):e.select_media({'formats':[fmt('hi','avc1','aac')]},'720')
    def test_portrait_resolution(self):self.assertEqual(e.resolution({'width':1080,'height':1920}),1080)
    def test_drm_is_excluded(self):
        f=fmt('drm','avc1','aac');f['has_drm']=True
        with self.assertRaises(ValueError):e.select_media({'formats':[f]})
    def test_audio_without_transcode(self):
        self.assertEqual(e.select_media({'formats':self.formats},'audio')['tracks'][0]['acodec'],'opus')
    def test_audio_extraction(self):
        self.assertTrue(e.select_media({'formats':[self.formats[0]]},'audio')['extract_audio'])

class ImagesAndNetwork(unittest.TestCase):
    def test_image_signatures(self):
        self.assertEqual(e.image_extension(b'\xff\xd8\xff123'),'jpg')
        self.assertEqual(e.image_extension(b'RIFF0000WEBP000'),'webp')
        with self.assertRaises(ValueError):e.image_extension(b'<html>login</html>')
    def test_only_target_douyin_images(self):
        page='window._ROUTER_DATA = '+json.dumps({'r':[
            {'aweme_id':'123','images':[{'url_list':['https://example.org/main.jpg']}]},
            {'aweme_id':'456','images':[{'url_list':['https://example.org/unrelated.jpg']}]}]})
        self.assertEqual(e.douyin_images(page,'123'),['https://example.org/main.jpg'])
    def test_cover_does_not_fake_gallery(self):
        page='window._ROUTER_DATA = '+json.dumps({'aweme_id':'123','cover':'x.jpg'})
        with self.assertRaises(ValueError):e.douyin_images(page,'123')
    def test_profile_is_not_a_post(self):
        self.assertFalse(e.is_single_post('https://instagram.com/test/'))
        self.assertTrue(e.is_single_post('https://instagram.com/p/ABC/'))
        self.assertTrue(e.is_single_post('https://www.tiktok.com/@test/photo/123'))
    def test_valid_proxy(self):
        self.assertEqual(e.config_value({'network':'proxy','proxy':'socks5://127.0.0.1:1080'})['network'],'proxy')
    def test_invalid_proxy(self):
        for p in ('file:///tmp/a','https://example.org','http://host:80/path'):
            with self.assertRaises(ValueError):e.config_value({'network':'proxy','proxy':p})
    def test_https_server_required(self):
        with self.assertRaises(ValueError):e.config_value({'network':'server','server':'http://host','token':'x'*32})
    def test_server_credentials_not_in_url(self):
        with self.assertRaises(ValueError):e.config_value({'network':'server','server':'https://user:secret@host','token':'x'*32})
    def test_direct_ignores_stored_proxy(self):
        self.assertEqual(e.options({'network':'direct','proxy':'http://unused:80'})['proxy'],'')
    def test_redaction(self):
        s=e.safe_error(Exception('bad https://user:secret@proxy.test/path?token=hidden Bearer secret'))
        self.assertNotIn('secret',s);self.assertNotIn('hidden',s)
    def test_filename_safety(self):self.assertNotIn('/',e.safe_name('../../hello/test'))
    def test_gallery_engine_available(self):
        try:from gallery_dl import extractor
        except ImportError:self.skipTest('gallery-dl installed in CI')
        for url in ('https://www.instagram.com/p/CabcDEF1234/',
                    'https://www.tiktok.com/@test/photo/7345678901234567890',
                    'https://x.com/test/status/1234567890123456789'):
            self.assertIsNotNone(extractor.find(url),url)
    def test_remote_request_does_not_upload_cookies(self):
        class Response:
            status_code=200
            def __init__(self,data):self.data=data
            def __enter__(self):return self
            def __exit__(self,*a):pass
            def iter_content(self,*a):yield json.dumps(self.data).encode()
            def raise_for_status(self):pass
        class Session:
            def __init__(self):self.headers={};self.sent=[]
            def request(self,method,url,**kwargs):
                self.sent.append((method,url,kwargs))
                return Response({'id':'a'*32} if method=='POST' else {'state':'done','result':{},'files':[]})
            def delete(self,*a,**k):pass
            def close(self):pass
        session=Session()
        with patch('requests.Session',return_value=session):
            remote_client.run_remote('probe','https://youtube.com/watch?v=x','video','best','',
                {'server':'https://my.example','token':'a'*32,'cookies':'secret','proxy':'secret'})
        self.assertEqual(set(session.sent[0][2]['json']),{'action','url','media','quality'})
        self.assertFalse(session.sent[0][2]['allow_redirects'])

class NativeStreamCopy(unittest.TestCase):
    @unittest.skipUnless(shutil.which('ffmpeg') and shutil.which('ffprobe'),'host FFmpeg required')
    def test_vp9_opus_packet_hashes_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            root=Path(directory)
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','color=c=blue:s=128x72:r=10','-t','0.5','-c:v','libvpx-vp9','-an',str(root/'v.webm')],check=True)
            subprocess.run(['ffmpeg','-v','error','-f','lavfi','-i','sine=frequency=440:sample_rate=48000','-t','0.5','-c:a','libopus','-vn',str(root/'a.webm')],check=True)
            e.stream_copy(shutil.which('ffmpeg'),[root/'v.webm',root/'a.webm'],root/'merged.mkv')
            def hashes(file,stream):
                data=json.loads(subprocess.check_output(['ffprobe','-v','error','-select_streams',stream,'-show_packets','-show_data_hash','sha256','-of','json',str(file)]))
                return [p['data_hash'] for p in data['packets']]
            self.assertEqual(hashes(root/'v.webm','v'),hashes(root/'merged.mkv','v'))
            self.assertEqual(hashes(root/'a.webm','a'),hashes(root/'merged.mkv','a'))

class BackendGuard(unittest.TestCase):
    def test_private_ranges_blocked(self):
        sys.path.insert(0,str(ROOT/'server'))
        from guard import public_address
        for address in ('127.0.0.1','10.0.0.1','192.168.0.1','169.254.169.254','::1','fc00::1','224.0.0.1'):
            self.assertFalse(public_address(address),address)
        self.assertTrue(public_address('8.8.8.8'))
    def test_url_domain_allowlist(self):
        sys.path.insert(0,str(ROOT/'server'))
        from app import validate
        base=dict(action='probe',media='video',quality='best')
        with self.assertRaises(ValueError):validate(dict(base,url='https://youtube.com.evil.test/'))
        with self.assertRaises(ValueError):validate(dict(base,url='https://127.0.0.1/'))
        with self.assertRaises(ValueError):validate(dict(base,url='https://youtube.com/',cookies='secret'))
        self.assertEqual(validate(dict(base,url='https://youtu.be/test'))['action'],'probe')

if __name__=='__main__':unittest.main()
