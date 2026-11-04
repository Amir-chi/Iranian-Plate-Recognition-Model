"""Download pinned native SDK/model assets; never put build dependencies in Git."""
from pathlib import Path, PurePosixPath
import hashlib, json, urllib.request, zipfile, shutil, unicodedata, sys
ROOT=Path(__file__).resolve().parents[1]
DEPS=ROOT/'.deps';DEPS.mkdir(exist_ok=True)
PACKAGES=[('models.zip','https://github.com/Amir-chi/Iranian-Plate-Recognition-Model/releases/download/v0.2.0/v0.2.0-android-ncnn-fp16.zip','7e250105e22b629e01d00b05bec847c2b01d193782e425dea5f648f243473e40'),('ncnn.zip','https://github.com/Tencent/ncnn/releases/download/20261020/ncnn-20261020-android.zip','62a9edb493ceb42a5c9cbf2d3a79b3f44bff06c625134ffb64d183dcc42c25fc')]
for name,url,digest in PACKAGES:
    path=DEPS/name
    if not path.exists():urllib.request.urlretrieve(url,path)
    assert hashlib.sha256(path.read_bytes()).hexdigest()==digest, f'Hash mismatch: {name}'
    destination=DEPS/('models' if name=='models.zip' else 'sdk')
    with zipfile.ZipFile(path) as archive:
        assert all(not PurePosixPath(n).is_absolute() and '..' not in PurePosixPath(n).parts for n in archive.namelist())
        archive.extractall(destination)
models=ROOT/'app/src/main/assets/models';models.mkdir(parents=True,exist_ok=True)
for kind in ('plate_detector','characters'):
    (models/kind).mkdir(exist_ok=True)
    for name in ('model.ncnn.param','model.ncnn.bin','classes.json','metadata.yaml'):
        shutil.copyfile(DEPS/'models'/kind/name,models/kind/name)
classes=json.loads((models/'characters/classes.json').read_text(encoding='utf-8'))
tokens=[]
for i in range(37):
    raw=classes[str(i)]
    token='ا' if raw=='الف' else 'ژ' if raw.startswith('ژ ') else unicodedata.normalize('NFKC',raw).replace('\u200d','').replace('\u200c','').replace('ي','ی').replace('ك','ک')
    tokens.append(token.translate(str.maketrans('0123456789','۰۱۲۳۴۵۶۷۸۹')))
generated=ROOT/'app/src/main/cpp/generated';generated.mkdir(exist_ok=True)
(generated/'model_labels.h').write_text('#pragma once\nstatic const char* CHARACTER_TOKENS[] = {'+','.join(json.dumps(t,ensure_ascii=False) for t in tokens)+'};\n',encoding='utf-8')
assert (DEPS/'sdk/ncnn-20261020-android/arm64-v8a/lib/cmake/ncnn/ncnnConfig.cmake').exists()
print('Verified model/SDK hashes and generated 37 class tokens')
if '--fixtures' in sys.argv:
    data=json.loads((ROOT.parent/'training_results/cropped-output-2026-11-04/results.json').read_text(encoding='utf-8'))
    target=ROOT/'app/src/androidTest/assets/fixtures';target.mkdir(parents=True,exist_ok=True)
    for row in data['images']:
        path=target/row['source']
        if not path.exists():urllib.request.urlretrieve(row['source_url'].replace('https://github.com/','https://raw.githubusercontent.com/').replace('/blob/','/'),path)
        assert hashlib.sha256(path.read_bytes()).hexdigest()==row['source_sha256']
    print('Verified five Android test fixture hashes')
