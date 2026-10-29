"""Compile the actual installer PIN validation with valid and invalid settings."""
from pathlib import Path
import subprocess,re
root=Path(__file__).resolve().parents[1]
sketch=root/'PLC_Scanner_v3'
if not sketch.exists():sketch=root/'deliverables/PLC_Scanner_v3'
s=(sketch/'PLC_Scanner_v3.ino').read_text()
a=s.index('static constexpr char PLC_MAINTENANCE_PIN[]')
b=s.index('// ============================================================================',a)
block=s[a:b]
for pin,valid in [('123456',True),('001234',True),('12345',False),('1234567',False),('12ab56',False)]:
 code=re.sub(r'(PLC_MAINTENANCE_PIN\[\] = )"[0-9]+"',lambda m:m[1]+'"'+pin+'"',block, count=1)
 r=subprocess.run(['g++','-std=c++17','-x','c++','-fsyntax-only','-'],input=code,text=True,capture_output=True)
 assert (r.returncode==0)==valid, (pin,r.stderr)
print('PASS: 5 compilation checks for configurable PIN (including leading zeros and invalid settings)')
