"""在后端根目录执行：.venv/bin/python ~/hello-word-logout-update/install_logout.py"""
from pathlib import Path
from datetime import datetime
import shutil

root = Path.cwd().resolve()
source = Path(__file__).resolve().parent
main = root / 'app/main.py'
if not main.is_file() or not (root / 'app/auth.py').is_file():
    raise SystemExit('请先 cd /home/ubuntu/hello-word，再运行此脚本')
text = main.read_text(encoding='utf-8-sig')
if 'from app.logout import router as logout_router, initialize_logout_storage' not in text:
    text = 'from app.logout import router as logout_router, initialize_logout_storage\n' + text
if 'await run_in_threadpool(initialize_logout_storage)' not in text:
    anchor = '    await run_in_threadpool(initialize_database)'
    if text.count(anchor) != 1: raise SystemExit('启动代码与预期不同，未修改任何文件，请提供 app/main.py 进行检查')
    text = text.replace(anchor, anchor + '\n    await run_in_threadpool(initialize_logout_storage)')
if 'app.include_router(logout_router)' not in text:
    text += '\napp.include_router(logout_router)\n'
start = text.index('def create_access_token(')
end = text.index('return jwt.encode', start)
if '"jti"' not in text[start:end] and "'jti'" not in text[start:end]:
    anchor = '    token_data = {'
    if text[start:end].count(anchor) != 1: raise SystemExit('签发代码与预期不同，未修改任何文件')
    pos = text.index(anchor, start)
    text = text[:pos] + text[pos:].replace(anchor, anchor + '\n        "jti": __import__("uuid").uuid4().hex,', 1)
files = {'main.py': text, 'auth.py': (source / 'auth.py').read_text(encoding='utf-8-sig'),
         'logout.py': (source / 'logout.py').read_text(encoding='utf-8-sig')}
for name, content in files.items(): compile(content, name, 'exec')
backup = root / 'backups' / ('logout-' + datetime.now().strftime('%Y%m%d-%H%M%S-%f'))
backup.mkdir(parents=True)
for name in files:
    target = root / 'app' / name
    if target.exists(): shutil.copy2(target, backup / name)
try:
    for name, content in files.items():
        (root / 'app' / name).write_text(content, encoding='utf-8')
except Exception:
    for name in files:
        if (backup / name).exists(): shutil.copy2(backup / name, root / 'app' / name)
    raise
print('补丁已安装，原文件备份在：', backup)
print('接下来初始化撤销表，成功后再重启 hello-word 服务。')
