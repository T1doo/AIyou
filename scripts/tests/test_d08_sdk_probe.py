"""Offline contracts; one real pinned-JDK synthetic platform test, no SDK/device/key operations."""
import ast
import importlib.util
import json
import os
from pathlib import Path
import struct
import subprocess
import tempfile
import types
import unittest
from unittest.mock import patch
import hashlib
import zlib

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('sdk_probe', ROOT/'scripts/d08_sdk_probe.py')
probe = importlib.util.module_from_spec(spec); spec.loader.exec_module(probe)


class SdkProbeTests(unittest.TestCase):
    def test_actual_source_manifest_retains_application_provider_instrumentation_queries(self):
        old = probe.ET.fromstring(probe.MANIFEST.read_bytes())
        new = probe.ET.fromstring(probe.generate_manifest(probe.MANIFEST.read_bytes()))
        self.assertEqual('true',new.find('application').attrib.pop(probe.ANDROID+'debuggable'))
        for tag in ['application', 'queries', 'instrumentation']:
            self.assertEqual(probe.ET.tostring(old.find(tag)), probe.ET.tostring(new.find(tag)))
        self.assertEqual(new.get('package'), probe.PACKAGE)
        self.assertEqual(new.get(probe.ANDROID+'versionName'), probe.VERSION)
        self.assertEqual(new.find('uses-sdk').attrib, {probe.ANDROID+'minSdkVersion':'26',probe.ANDROID+'targetSdkVersion':'37'})
        self.assertEqual([], new.findall('uses-permission'))
        with self.assertRaises(ValueError):
            probe.generate_manifest(probe.MANIFEST.read_bytes().replace(b'<queries>',b'<uses-permission android:name="android.permission.INTERNET"/><queries>'))

    def test_generated_probe_keeps_original_debug_build_run_as_capability(self):
        manifest=probe.ET.fromstring(probe.generate_manifest(probe.MANIFEST.read_bytes()))
        self.assertEqual('true',manifest.find('application').get(probe.ANDROID+'debuggable'),
                         'The existing device collector uses run-as; AGP debug formerly injected this bit')

    def test_exact_existing_sdk_jdk_and_acceptance_only_missing_or_changed_fail(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d); sdk=root/'sdk'; jdk=root/'jdk'
            for parent, revision in [(sdk/'platforms/android-37.0','2'),(sdk/'build-tools/36.0.0','36.0.0')]:
                parent.mkdir(parents=True);(parent/'source.properties').write_text('Pkg.Revision='+revision)
            (sdk/'licenses').mkdir();(sdk/'licenses/android-sdk-license').write_text(probe.LICENSE)
            jdk.mkdir();(jdk/'release').write_text('JAVA_RUNTIME_VERSION="17.0.17+10"\nIMPLEMENTOR="Eclipse Adoptium"\n')
            for f in [*[jdk/'bin'/n for n in ['javac','java','jmod','jlink']],jdk/'lib/modules',jdk/'lib/jrt-fs.jar',
                      sdk/'platforms/android-37.0/android.jar',sdk/'platforms/android-37.0/core-for-system-modules.jar',
                      *[sdk/'build-tools/36.0.0'/n for n in ['aapt2','zipalign','lib/d8.jar','lib/apksigner.jar']]]:
                f.parent.mkdir(parents=True,exist_ok=True);f.write_bytes(b'synthetic identity; never executed')
            files, identities=probe.sdk_inputs(sdk,jdk);self.assertEqual(16,len(files));self.assertEqual(16,len(identities))
            core=files['android_core'];original=core.read_bytes();core.unlink()
            with self.assertRaises(FileNotFoundError):probe.sdk_inputs(sdk,jdk)
            core.write_bytes(original)
            f=sdk/'licenses/android-sdk-license';before=f.read_bytes();f.write_text('different acceptance')
            with self.assertRaisesRegex(ValueError,'acceptance'):probe.sdk_inputs(sdk,jdk)
            self.assertEqual('different acceptance',f.read_text());f.write_bytes(before)
            f=sdk/'build-tools/36.0.0/lib/d8.jar';f.unlink()
            with self.assertRaises(FileNotFoundError):probe.sdk_inputs(sdk,jdk)
            self.assertFalse(f.exists())

    def test_workspace_signing_and_output_paths_never_follow_home_symlinks(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); outside=root/'other';outside.mkdir();(root/'build').symlink_to(outside,target_is_directory=True)
            with self.assertRaises(ValueError):probe.confined(root/'build/probe.apk',root)
            with self.assertRaises(ValueError):probe.confined(Path('/unrelated/debug.keystore'),root)

    def test_only_formal_public_hosted_linux_push_attempt_one_can_compile(self):
        with tempfile.TemporaryDirectory() as d:
            f=Path(d)/'event.json';f.write_text(json.dumps({'repository':{'full_name':'T1doo/AIyou','private':False}}))
            env={'GITHUB_ACTIONS':'true','GITHUB_REPOSITORY':'T1doo/AIyou','GITHUB_EVENT_NAME':'push',
                 'GITHUB_RUN_ATTEMPT':'1','GITHUB_JOB':'d01','RUNNER_ENVIRONMENT':'github-hosted',
                 'RUNNER_OS':'Linux','RUNNER_ARCH':'X64','GITHUB_REF':'refs/heads/codex/test',
                 'GITHUB_SHA':'a'*40,'GITHUB_EVENT_PATH':str(f)}
            probe.ci_admission(env)
            for key,value in [('GITHUB_EVENT_NAME','pull_request'),('GITHUB_RUN_ATTEMPT','2'),('GITHUB_JOB','recovery'),
                              ('RUNNER_ENVIRONMENT','self-hosted'),('GITHUB_REF','refs/heads/device/test')]:
                with self.subTest(key=key),self.assertRaises(ValueError):probe.ci_admission({**env,key:value})
            f.write_text(json.dumps({'repository':{'full_name':'T1doo/AIyou','private':True}}))
            with self.assertRaises(ValueError):probe.ci_admission(env)
        with patch.object(probe,'compile_only') as compile_only,patch('sys.argv',['sdk_probe']):
            with self.assertRaises(SystemExit):probe.main()
            compile_only.assert_not_called()

    def test_actual_class_definitions_required_not_only_string_occurrences(self):
        # Minimal DEX class_def table fixture with a real checksum/header and owned names.
        names=['Lcom/t1doo/aiyou/recoveryprobe/'+n+';' for n in probe.NAMES]
        def dex(definitions):
            string_at=112;type_at=124;class_at=136;payload_at=class_at+32*len(definitions)
            data=bytearray(payload_at);offsets=[]
            for name in names:
                offsets.append(len(data));data.extend(bytes([len(name)])+name.encode()+b'\0')
            data[:8]=b'dex\n035\0'
            for at,value in [(32,len(data)),(36,112),(40,0x12345678),(56,3),(60,string_at),(64,3),(68,type_at),(96,len(definitions)),(100,class_at)]:struct.pack_into('<I',data,at,value)
            for i,off in enumerate(offsets):struct.pack_into('<I',data,string_at+4*i,off);struct.pack_into('<I',data,type_at+4*i,i)
            for i,t in enumerate(definitions):struct.pack_into('<I',data,class_at+32*i,t)
            data[12:32]=hashlib.sha1(data[32:]).digest();struct.pack_into('<I',data,8,zlib.adler32(data[12:])&0xffffffff)
            return bytes(data)
        self.assertEqual(sorted(names),probe.dex_classes(dex([0,1,2])))
        for values in [[0,1],[0,1,1]]:
            with self.assertRaises(ValueError):probe.dex_classes(dex(values))
        bad=bytearray(dex([0,1,2]));bad[-4]^=1
        with self.assertRaises(ValueError):probe.dex_classes(bad)

    def test_single_debug_signer_fingerprint_only_not_key_material(self):
        cert='Signer #1 certificate SHA-256 digest: '+'a'*64+'\n'
        self.assertEqual('a'*64,probe.certificate(cert))
        for raw in ['',cert+cert,cert+'Signer #2 certificate DN: other']:
            with self.assertRaises(ValueError):probe.certificate(raw)

    def test_early_missing_sdk_retains_failure_but_failed_admission_writes_nothing(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); report=root/'build/report.json'
            env={'GITHUB_SHA':'a'*40,'GITHUB_RUN_ID':'123'}
            with patch.object(probe,'ROOT',root),patch.object(probe,'REPORT',report),patch.dict(probe.os.environ,env),patch.object(probe,'ci_admission'),patch.object(probe.subprocess,'check_output',return_value='a'*40),patch.object(probe,'_compile_with_sdk',side_effect=FileNotFoundError('synthetic SDK missing')):
                with self.assertRaises(FileNotFoundError):probe.compile_only()
                value=json.loads(report.read_text());self.assertEqual('FAIL',value['status']);self.assertEqual('FileNotFoundError',value['error_type']);self.assertEqual([],value['commands'])
            report.unlink();report.parent.rmdir()
            with patch.object(probe,'ROOT',root),patch.object(probe,'REPORT',report),patch.object(probe,'ci_admission',side_effect=ValueError('unapproved')):
                with self.assertRaises(ValueError):probe.compile_only()
                self.assertFalse(report.parent.exists())

    def test_shared_deadline_rejects_late_success_without_resetting_per_command(self):
        times=iter([10,11,15,16])
        called=[]
        def execute(args,**kwargs):
            called.append(kwargs['timeout']);return types.SimpleNamespace(returncode=0,stdout='',stderr='')
        probe.bounded_command(['synthetic-no-executable'],20,clock=lambda:next(times),execute=execute)
        probe.bounded_command(['synthetic-no-executable'],20,clock=lambda:next(times),execute=execute)
        self.assertEqual([10,5],called)
        times=iter([18,21])
        with self.assertRaises(TimeoutError):probe.bounded_command(['synthetic'],20,clock=lambda:next(times),execute=execute)
        with self.assertRaises(TimeoutError):probe.bounded_command(['synthetic'],20,clock=lambda:21,execute=lambda *a,**k:self.fail('Expired command ran'))

    def test_timeout_diagnostic_never_stringifies_arguments_or_signing_input(self):
        def timeout(*args,**kwargs):
            raise subprocess.TimeoutExpired(['synthetic','private-argument'],1,output='private-output')
        with self.assertRaises(TimeoutError) as caught:
            probe.bounded_command(['synthetic'],10,clock=lambda:1,execute=timeout,stdin='private-input')
        self.assertNotIn('private',str(caught.exception));self.assertTrue(caught.exception.__suppress_context__)

    def test_compile_source_never_fetches_generates_keys_or_invokes_device(self):
        s=(ROOT/'scripts/d08_sdk_probe.py').read_text()
        for forbidden in ['urllib','requests.']:
            self.assertNotIn(forbidden,s)
        strings={n.value for n in ast.walk(ast.parse(s)) if isinstance(n,ast.Constant) and isinstance(n.value,str)}
        self.assertFalse(strings & {'adb','emulator','sdkmanager','keytool','gradlew','setfacl'})
        self.assertIn("'--patch-module', 'java.base='+str(core)",s)
        self.assertIn("'-classpath', files['android_jar']",s)
        self.assertLess(s.index("run('zipalign'"),s.index("run('sign-existing-job-debug-key'"))
        self.assertLess(s.index("run('sign-existing-job-debug-key'"),s.index("run('verify-probe-signature'"))
        self.assertIn("key = confined(ROOT/'.toolchains/android-user-home/debug.keystore'",s)
        self.assertNotIn("Path.home",s)

    def test_real_pinned_jdk_android_shaped_system_image_excludes_host_api(self):
        # Deliberately tiny synthetic java.base, not Android SDK evidence. The same
        # production image builder and final compiler command must admit only it.
        jdk=Path(os.environ['JAVA_HOME']) if os.environ.get('JAVA_HOME') else ROOT/'.toolchains/jdk-17.0.17+10'
        self.assertTrue((jdk/'bin/javac').is_file(), 'Existing pinned JDK required; no downloads')
        release=probe.properties(jdk/'release')
        self.assertEqual('17.0.17+10',release['JAVA_RUNTIME_VERSION'])
        with tempfile.TemporaryDirectory() as d:
            root=Path(d); src=root/'src/java/lang';src.mkdir(parents=True)
            for name,body in [('Object','public Object() {}'),('String',''),('AndroidOnly','')]:
                (src/(name+'.java')).write_text('package java.lang; public class '+name+' {'+body+'}')
            # jlink's standard java.base plugin emits java.lang.invoke metadata.
            invoke=src/'invoke';invoke.mkdir()
            (invoke/'PlatformMarker.java').write_text('package java.lang.invoke; public class PlatformMarker {}')
            initial=root/'initial';initial.mkdir()
            def execute(label,args):
                result=subprocess.run([str(a) for a in args],capture_output=True,text=True,timeout=30)
                if result.returncode:raise AssertionError(label+': '+result.stderr+result.stdout)
                return result.stdout
            execute('fixture-classes',[jdk/'bin/javac','--system','none','--patch-module','java.base='+str(root/'src'),
                    '-d',initial,*sorted(src.rglob('*.java'))])
            core=root/'core-for-system-modules.jar'
            with probe.zipfile.ZipFile(core,'x') as z:
                for path in sorted(initial.rglob('*.class')):z.write(path,path.relative_to(initial))
            files={n:jdk/'bin'/n for n in ['javac','jmod','jlink']}
            files.update(android_core=core,jrt_fs=jdk/'lib/jrt-fs.jar',android_jar=root/'android.jar')
            with probe.zipfile.ZipFile(files['android_jar'],'x'):pass
            work=root/'work';work.mkdir();image=probe.android_system_image(files,work,execute)
            out=root/'out';out.mkdir();allowed=root/'Allowed.java'
            allowed.write_text('class Allowed { java.lang.AndroidOnly value; }')
            args=probe.probe_javac_args(files,image,out,[allowed])
            self.assertEqual(str(image),str(args[args.index('--system')+1]));self.assertNotIn('none',args)
            self.assertEqual('17',args[args.index('-target')+1]);self.assertEqual('17',args[args.index('-source')+1])
            execute('allowed',args);self.assertTrue((out/'Allowed.class').is_file())
            rejected=root/'Rejected.java';rejected.write_text('class Rejected { java.nio.file.Path hostOnly; }')
            bad=subprocess.run([str(a) for a in probe.probe_javac_args(files,image,out,[rejected])],capture_output=True,text=True,timeout=30)
            self.assertNotEqual(0,bad.returncode);self.assertIn('java.nio.file',bad.stderr)
            self.assertFalse((out/'Rejected.class').exists())
            # Broken/missing platform content must fail before invoking any tools.
            with probe.zipfile.ZipFile(core,'w') as z:z.writestr('java/lang/Object.class',b'not-enough')
            with self.assertRaisesRegex(ValueError,'core system-module'):
                probe.android_system_image(files,work,lambda *a:self.fail('Invalid core invoked tool'))


if __name__=='__main__':unittest.main()
