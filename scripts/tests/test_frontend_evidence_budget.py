"""Run the actual collector against synthetic local files; no CI/network/upload."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import zipfile

COLLECTOR = Path(__file__).resolve().parents[1] / 'collect-frontend-evidence.py'

class EvidenceBudget(unittest.TestCase):
    def fixture(self, directory):
        root=Path(directory);(root/'scripts').mkdir()
        script=root/'scripts/collect-frontend-evidence.py';shutil.copyfile(COLLECTOR,script)
        frames=root/'app/src/screenshotTestDebug/reference';frames.mkdir(parents=True)
        return root,script,frames
    def test_only_current_frames_and_selected_xml_enter_archive(self):
        with tempfile.TemporaryDirectory() as tmp:
            root,script,frames=self.fixture(tmp)
            (frames/'FrontendDayOne.png').write_bytes(b'synthetic image')
            apk=root/'app/build/outputs/apk/debug';apk.mkdir(parents=True);(apk/'app-debug.apk').write_bytes(b'excluded')
            baseline=root/'build/frontend-day/before';baseline.mkdir(parents=True);(baseline/'FrontendDayOld.png').write_bytes(b'excluded')
            subprocess.run(['python3',str(script)],check=True,capture_output=True)
            with zipfile.ZipFile(root/'build/frontend-day-minimal.zip') as z:
                self.assertEqual({'build/frontend-day/after/FrontendDayOne.png','scope.json'},set(z.namelist()))
    def test_over_count_size_and_empty_input_refuse_and_remove_stale_zip(self):
        for case in ('count','size','empty'):
            with self.subTest(case=case),tempfile.TemporaryDirectory() as tmp:
                root,script,frames=self.fixture(tmp)
                (root/'build').mkdir();archive=root/'build/frontend-day-minimal.zip';archive.write_bytes(b'old')
                if case=='count':
                    for i in range(7):(frames/f'FrontendDay{i}.png').write_bytes(b'x')
                elif case=='size':(frames/'FrontendDayLarge.png').write_bytes(b'x'*(6*1024*1024+1))
                result=subprocess.run(['python3',str(script)],capture_output=True)
                self.assertNotEqual(0,result.returncode);self.assertFalse(archive.exists())
