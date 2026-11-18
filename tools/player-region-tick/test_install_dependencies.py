#!/usr/bin/env python3
"""An installer that rewrites dependency jars must never alter a running installation's inode."""
import contextlib,io,json,sys,tempfile,unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch
import install

class InstallationDependencyTest(unittest.TestCase):
 def run_install(self,link=True,fail=False):
  with tempfile.TemporaryDirectory(prefix='forgia-private-install-') as tmp:
   base=Path(tmp);seed=base/'seed';seed.mkdir();root=base/'new'
   paths=['org/example/stable.jar','org/example/rewritten.jar','net/minecraft/server/generated.jar','net/neoforged/neoforge/version/core.jar']
   for relative in paths:
    p=seed/relative;p.parent.mkdir(parents=True,exist_ok=True);p.write_bytes(b'original')
   eula=base/'eula.txt';eula.write_text('eula=true\n');installer=base/'installer.jar';installer.write_bytes(b'installer')
   def fake_processor(argv,**kwargs):
    for relative in paths:
     source=seed/relative;destination=root/'libraries'/relative
     self.assertNotEqual(source.stat().st_ino,destination.stat().st_ino,'Installer saw a shared live inode')
    # Simulate BUNDLER_EXTRACT/rename opening an existing jar with truncation and rewriting it.
    (root/'libraries/org/example/rewritten.jar').write_bytes(b'generated')
    self.assertEqual(b'original',(seed/'org/example/rewritten.jar').read_bytes())
    return SimpleNamespace(returncode=1 if fail else 0)
   argv=['install.py','--output',str(root),'--libraries',str(seed),'--eula',str(eula),'--installer',str(installer),'--java',str(base/'java'),'--version','isolated-test','--without-oracle']
   if link:argv+=['--link-library-dependencies']
   with patch.object(sys,'argv',argv),patch.object(install.subprocess,'run',side_effect=fake_processor),contextlib.redirect_stdout(io.StringIO()):
    if fail:
     with self.assertRaisesRegex(RuntimeError,'Installer failed'):install.main()
    else:install.main()
   for relative in paths:self.assertEqual(b'original',(seed/relative).read_bytes())
   for relative in paths:
    shared=(seed/relative).stat().st_ino==(root/'libraries'/relative).stat().st_ino
    self.assertEqual(link and not fail and relative=='org/example/stable.jar',shared,relative)
   if not fail:
    receipt=json.loads((root/'installation.json').read_text())
    self.assertTrue(receipt['installerInputsPrivate'])
    self.assertEqual(int(link),receipt['linkedLibraryDependencies'])

 def test_installer_rewrites_private_copy_before_identical_dependency_sharing(self):self.run_install()
 def test_default_keeps_all_dependencies_private(self):self.run_install(link=False)
 def test_failed_installer_never_publishes_shared_dependency_inodes(self):self.run_install(fail=True)

if __name__=='__main__':unittest.main()
