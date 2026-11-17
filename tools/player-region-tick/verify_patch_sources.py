#!/usr/bin/env python3
"""Compare persisted Minecraft patches' applied source archive with every generated Java source."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive',type=Path,default=Path('projects/neoforge/build/neodev/artifacts/patched-sources.jar'))
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args()
    if args.output.exists():raise FileExistsError('preserve existing patch proof')
    changed=subprocess.check_output(['git','diff','--name-only','--','patches'],text=True).splitlines()
    untracked=subprocess.check_output(['git','ls-files','--others','--exclude-standard','patches'],text=True).splitlines()
    patches=sorted(set(path for path in changed+untracked if path.endswith('.java.patch')))
    proof=[]
    with zipfile.ZipFile(args.archive) as archive:
        for path in patches:
            source=Path(path).relative_to('patches').as_posix().removesuffix('.patch')
            generated=Path('projects/neoforge/src/main/java')/source
            applied=archive.read(source);actual=generated.read_bytes()
            if applied!=actual:raise AssertionError('persistent patch/generated mismatch: '+source)
            proof.append({'patch':path,'patchSha256':hashlib.sha256(Path(path).read_bytes()).hexdigest(),
                          'generated':str(generated),'sourceSha256':hashlib.sha256(applied).hexdigest()})
    result={'status':'passed','archive':str(args.archive),'archiveSha256':hashlib.sha256(args.archive.read_bytes()).hexdigest(),
            'comparison':'byte-for-byte applied persisted source vs generated source, changed and untracked patches',
            'files':proof,'fileCount':len(proof)}
    args.output.write_text(json.dumps(result,indent=2)+'\n');print(json.dumps({'status':'passed','fileCount':len(proof)}))


if __name__=='__main__':main()
